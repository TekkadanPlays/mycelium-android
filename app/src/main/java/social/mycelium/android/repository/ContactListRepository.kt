package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.core.eventTemplate
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fetches kind-3 (contact list) for a user and returns the set of followed pubkeys (p-tags).
 * Uses in-memory cache with TTL to avoid refetching on every screen/effect run.
 * Ensures we use the newest kind-3 across all relays: collect all kind-3 events in the timeout
 * window, then pick the single latest by createdAt (so the user's "write" relay's newer list wins).
 *
 * Also supports follow/unfollow by building a new kind-3 event, signing via Amber, and publishing.
 */
object ContactListRepository {

    private const val TAG = "ContactListRepository"
    /** Max wait for kind-3 responses; early-exit fires sooner when first event arrives.
     *  Must be long enough for relays to connect on cold start (DNS + TLS can take 5-10s). */
    private const val KIND3_FETCH_TIMEOUT_MS = 8000L
    /** After first kind-3 event arrives, wait this long for more relays before returning.
     *  Raised from 500→1500ms so slower relays have time to deliver the newest kind-3. */
    private const val KIND3_SETTLE_MS = 1500L
    /** No hardcoded priority relays — user's configured relays are used exclusively. */
    private val KIND3_PRIORITY_RELAYS = emptyList<String>()
    /** Cache TTL: 2 min so we don't rely on stale follow lists; forceRefresh on Following pull. */
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    /** Emits updated follow list whenever the cache changes (follow/unfollow/fetch). */
    private val _followListUpdates = MutableSharedFlow<Set<String>>(replay = 1, extraBufferCapacity = 1)
    val followListUpdates: SharedFlow<Set<String>> = _followListUpdates.asSharedFlow()

    private data class CacheEntry(val pubkey: String, val followSet: Set<String>, val timestampMs: Long)
    @Volatile
    private var cacheEntry: CacheEntry? = null

    /** The raw kind-3 Event most recently fetched for the current user, used to build follow/unfollow updates. */
    @Volatile
    private var latestKind3Event: Event? = null

    // ─── Persistence: survive app restarts so stale relay data can't overwrite local changes ───
    private const val PREFS_NAME = "Mycelium_contact_list"
    private const val KEY_KIND3_JSON = "latest_kind3_json"
    private const val KEY_KIND3_CREATED_AT = "latest_kind3_created_at"
    private const val KEY_KIND3_PUBKEY = "latest_kind3_pubkey"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var db: social.mycelium.android.db.AppDatabase? = null

    /** Call once from MainActivity.onCreate() to enable persistence. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        db = social.mycelium.android.db.AppDatabase.getInstance(context.applicationContext)
    }

    /** Restore persisted kind-3 into memory. Called after account restore so latestKind3Event
     *  is populated before any relay fetch can overwrite it with stale data. */
    fun restorePersistedKind3(pubkey: String) {
        // Try Room first
        val database = db
        if (database != null) {
            try {
                val entity = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    database.followListDao().get(pubkey)
                }
                if (entity != null) {
                    val event = eventFromJson(entity.eventJson)
                    if (event != null) {
                        latestKind3Event = event
                        val pubkeys = extractPubkeys(event)
                        cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
                        _followListUpdates.tryEmit(pubkeys)
                        Log.d(TAG, "Restored kind-3 from Room for ${pubkey.take(8)}: ${pubkeys.size} follows, createdAt=${event.createdAt}")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Room restore failed: ${e.message}")
            }
        }
        // Fallback to SharedPreferences (migration path)
        val p = prefs ?: return
        val savedPubkey = p.getString(KEY_KIND3_PUBKEY, null)
        if (savedPubkey != pubkey) return
        val json = p.getString(KEY_KIND3_JSON, null) ?: return
        val event = eventFromJson(json)
        if (event != null) {
            latestKind3Event = event
            val pubkeys = extractPubkeys(event)
            cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
            _followListUpdates.tryEmit(pubkeys)
            Log.d(TAG, "Restored persisted kind-3 from SP for ${pubkey.take(8)}: ${pubkeys.size} follows, createdAt=${event.createdAt}")
            // Migrate to Room
            persistKind3ToRoom(pubkey, event, pubkeys)
        }
    }

    /** Persist the latest kind-3 event to SharedPreferences + Room. */
    private fun persistKind3(pubkey: String, event: Event) {
        val p = prefs ?: return
        p.edit()
            .putString(KEY_KIND3_PUBKEY, pubkey)
            .putString(KEY_KIND3_JSON, eventToJson(event))
            .putLong(KEY_KIND3_CREATED_AT, event.createdAt)
            .apply()
        persistKind3ToRoom(pubkey, event, extractPubkeys(event))
    }

    /** Persist kind-3 to Room DB (fire-and-forget on IO). */
    private fun persistKind3ToRoom(pubkey: String, event: Event, pubkeys: Set<String>) {
        val database = db ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.followListDao().upsert(
                    social.mycelium.android.db.CachedFollowListEntity(
                        pubkey = pubkey,
                        eventJson = eventToJson(event),
                        eventCreatedAt = event.createdAt,
                        followPubkeys = pubkeys.joinToString(",")
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Room persist kind-3 failed: ${e.message}")
            }
        }
    }

    /** Get the persisted createdAt for a pubkey to reject stale relay data. */
    private fun getPersistedCreatedAt(pubkey: String): Long {
        // Check Room first
        val database = db
        if (database != null) {
            try {
                val entity = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    database.followListDao().get(pubkey)
                }
                if (entity != null) return entity.eventCreatedAt
            } catch (_: Exception) { }
        }
        // Fallback to SharedPreferences
        val p = prefs ?: return 0L
        val savedPubkey = p.getString(KEY_KIND3_PUBKEY, null)
        return if (savedPubkey == pubkey) p.getLong(KEY_KIND3_CREATED_AT, 0L) else 0L
    }

    /** In-flight deduplication: only one network fetch per pubkey at a time. */
    private val inFlightFetches = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Set<String>>>()

    /**
     * Return cached follow list if present and not stale. Lock-free read.
     */
    fun getCachedFollowList(pubkey: String): Set<String>? {
        val entry = cacheEntry ?: return null
        if (entry.pubkey != pubkey) return null
        if (System.currentTimeMillis() - entry.timestampMs >= CACHE_TTL_MS) return null
        return entry.followSet
    }

    /**
     * Invalidate cache (e.g. after user follows/unfollows). Call when follow list may have changed.
     */
    fun invalidateCache(pubkey: String?) {
        if (pubkey == null) { cacheEntry = null; latestKind3Event = null }
        else if (cacheEntry?.pubkey == pubkey) { cacheEntry = null; latestKind3Event = null }
    }

    // ─── Helpers: extract pubkeys, serialize/deserialize kind-3 events ───

    private fun extractPubkeys(event: Event): Set<String> {
        return event.tags
            .map { it.toList() }
            .filter { it.isNotEmpty() && it[0] == "p" }
            .mapNotNull { list ->
                val pk = list.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (pk.length == 64 && pk.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) pk.lowercase() else null
            }
            .toSet()
    }

    private fun eventToJson(event: Event): String {
        val obj = JSONObject()
        obj.put("id", event.id)
        obj.put("pubKey", event.pubKey)
        obj.put("createdAt", event.createdAt)
        obj.put("kind", event.kind)
        obj.put("content", event.content)
        obj.put("sig", event.sig)
        val tagsArr = JSONArray()
        for (tag in event.tags) {
            val tagArr = JSONArray()
            for (s in tag) tagArr.put(s)
            tagsArr.put(tagArr)
        }
        obj.put("tags", tagsArr)
        return obj.toString()
    }

    private fun eventFromJson(json: String): Event? {
        return try {
            val obj = JSONObject(json)
            val tagsArr = obj.getJSONArray("tags")
            val tags = Array(tagsArr.length()) { i ->
                val inner = tagsArr.getJSONArray(i)
                Array(inner.length()) { j -> inner.getString(j) }
            }
            Event(
                id = obj.getString("id"),
                pubKey = obj.getString("pubKey"),
                createdAt = obj.getLong("createdAt"),
                kind = obj.getInt("kind"),
                content = obj.optString("content", ""),
                sig = obj.getString("sig"),
                tags = tags
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse persisted kind-3: ${e.message}")
            null
        }
    }

    /**
     * Fetch kind-3 for the given pubkey from the given relays (e.g. cache + outbox); return set of p-tag pubkeys (hex 64).
     * Uses in-memory cache: if a valid cache exists for this pubkey, returns it without network. Otherwise fetches and caches.
     * @param forceRefresh if true, skip cache and refetch (e.g. pull-to-refresh on Following tab).
     */
    suspend fun fetchFollowList(pubkey: String, relayUrls: List<String>, forceRefresh: Boolean = false): Set<String> {
        if (relayUrls.isEmpty()) return emptySet()
        if (!forceRefresh) {
            getCachedFollowList(pubkey)?.let { cached ->
                Log.d(TAG, "Kind-3 cache hit for ${pubkey.take(8)}... (${cached.size} follows)")
                return cached
            }
        }

        // Deduplicate concurrent fetches: if another coroutine is already fetching, wait for it
        val existing = inFlightFetches[pubkey]
        if (existing != null) {
            Log.d(TAG, "Kind-3 fetch already in-flight for ${pubkey.take(8)}..., waiting")
            return try { existing.await() } catch (_: Exception) { emptySet() }
        }

        val deferred = kotlinx.coroutines.CompletableDeferred<Set<String>>()
        val prev = inFlightFetches.putIfAbsent(pubkey, deferred)
        if (prev != null) {
            // Another coroutine won the race
            return try { prev.await() } catch (_: Exception) { emptySet() }
        }
        val distinctUrls = (KIND3_PRIORITY_RELAYS + relayUrls).distinct()
        Log.d(TAG, "Fetching kind-3 for ${pubkey.take(8)}... from ${distinctUrls.size} relay(s)")
        return try {
            val filter = Filter(
                kinds = listOf(3),
                authors = listOf(pubkey),
                limit = 20
            )
            val collected = CopyOnWriteArrayList<Event>()
            val firstEventAt = java.util.concurrent.atomic.AtomicLong(0)
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(distinctUrls, filter, priority = SubscriptionPriority.HIGH) { event ->
                if (event.kind == 3) {
                    collected.add(event)
                    firstEventAt.compareAndSet(0, System.currentTimeMillis())
                }
            }
            // Early-exit: poll every 100ms; once first event arrives, wait settle window then return
            val deadline = System.currentTimeMillis() + KIND3_FETCH_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(100)
                val firstAt = firstEventAt.get()
                if (firstAt > 0 && System.currentTimeMillis() - firstAt >= KIND3_SETTLE_MS) break
            }
            handle.cancel()
            val event = collected.maxByOrNull { it.createdAt } ?: run {
                // No relay responded — fall back to persisted event if available
                val persisted = latestKind3Event
                if (persisted != null) {
                    val pubkeys = extractPubkeys(persisted)
                    cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
                    _followListUpdates.tryEmit(pubkeys)
                    deferred.complete(pubkeys)
                    Log.d(TAG, "No relay kind-3 — using persisted (${pubkeys.size} follows)")
                    return pubkeys
                }
                return emptySet()
            }
            // Only accept relay event if it's at least as new as our persisted version.
            // This prevents stale relay data from overwriting a local follow/unfollow.
            val persistedTs = getPersistedCreatedAt(pubkey)
            if (event.createdAt < persistedTs && latestKind3Event != null) {
                Log.w(TAG, "Relay kind-3 is STALE (relay=${event.createdAt}, persisted=$persistedTs) — keeping local version")
                val persisted = latestKind3Event!!
                val pubkeys = extractPubkeys(persisted)
                cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
                _followListUpdates.tryEmit(pubkeys)
                deferred.complete(pubkeys)
                return pubkeys
            }
            latestKind3Event = event
            persistKind3(pubkey, event)
            val pubkeys = extractPubkeys(event)
            cacheEntry = CacheEntry(pubkey, pubkeys, System.currentTimeMillis())
            _followListUpdates.tryEmit(pubkeys)
            Log.d(TAG, "Kind-3 parsed ${pubkeys.size} follows for ${pubkey.take(8)}..., contains cff1720e77bb: ${pubkeys.any { it.startsWith("cff1720e77bb") }}")
            deferred.complete(pubkeys)
            pubkeys
        } catch (e: Exception) {
            Log.e(TAG, "Kind-3 fetch failed: ${e.message}", e)
            val fallback = getCachedFollowList(pubkey) ?: emptySet()
            deferred.complete(fallback)
            fallback
        } finally {
            inFlightFetches.remove(pubkey)
        }
    }

    /**
     * Follow a user by building a new kind-3 event with the p-tag added, signing, and publishing.
     * Returns null on success, or an error message.
     */
    suspend fun follow(
        myPubkey: String,
        targetPubkey: String,
        signer: NostrSigner,
        outboxRelays: Set<String>,
        cacheRelayUrls: List<String>
    ): String? {
        return try {
            // Ensure we have the latest kind-3 event
            if (latestKind3Event == null || cacheEntry?.pubkey != myPubkey) {
                fetchFollowList(myPubkey, cacheRelayUrls + outboxRelays.toList(), forceRefresh = true)
            }

            val existing = latestKind3Event
            // Build new tags: existing p-tags + new follow (deduplicated)
            val existingTags = existing?.tags?.toList() ?: emptyList()
            val alreadyFollowed = existingTags.any { it.size >= 2 && it[0] == "p" && it[1] == targetPubkey.lowercase() }
            val newTags = if (alreadyFollowed) {
                existingTags.toTypedArray()
            } else {
                (existingTags + listOf(arrayOf("p", targetPubkey.lowercase()))).toTypedArray()
            }
            val template = eventTemplate(3, existing?.content ?: "", nowUnixSeconds()) {
                newTags.forEach { add(it) }
            }
            val signed = signer.sign(template)

            // Publish to outbox relays
            Log.d(TAG, "Publishing follow kind-3 (createdAt=${signed.createdAt}) to ${outboxRelays.size} relays, tags=${signed.tags.size}")
            RelayConnectionStateMachine.getInstance().send(signed, outboxRelays)

            // Update local cache + persist so it survives app restart
            latestKind3Event = signed
            persistKind3(myPubkey, signed)
            val currentFollows = cacheEntry?.followSet?.toMutableSet() ?: mutableSetOf()
            currentFollows.add(targetPubkey.lowercase())
            cacheEntry = CacheEntry(myPubkey, currentFollows, System.currentTimeMillis())
            _followListUpdates.tryEmit(currentFollows.toSet())
            Log.d(TAG, "Followed ${targetPubkey.take(8)}... — now following ${currentFollows.size}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Follow failed: ${e.message}", e)
            "Follow failed: ${e.message}"
        }
    }

    /**
     * Unfollow a user by building a new kind-3 event with the p-tag removed, signing, and publishing.
     * Returns null on success, or an error message.
     */
    suspend fun unfollow(
        myPubkey: String,
        targetPubkey: String,
        signer: NostrSigner,
        outboxRelays: Set<String>,
        cacheRelayUrls: List<String>
    ): String? {
        return try {
            // Ensure we have the latest kind-3 event
            if (latestKind3Event == null || cacheEntry?.pubkey != myPubkey) {
                fetchFollowList(myPubkey, cacheRelayUrls + outboxRelays.toList(), forceRefresh = true)
            }

            val existing = latestKind3Event
                ?: return "Cannot unfollow — no existing contact list found"

            // Build new tags: existing tags minus the target p-tag
            val newTags = existing.tags
                .filter { !(it.size >= 2 && it[0] == "p" && it[1] == targetPubkey.lowercase()) }
                .toTypedArray()
            val template = eventTemplate(3, existing.content, nowUnixSeconds()) {
                newTags.forEach { add(it) }
            }
            val signed = signer.sign(template)

            // Publish to outbox relays
            Log.d(TAG, "Publishing unfollow kind-3 (createdAt=${signed.createdAt}) to ${outboxRelays.size} relays, tags=${signed.tags.size}")
            RelayConnectionStateMachine.getInstance().send(signed, outboxRelays)

            // Update local cache + persist so it survives app restart
            latestKind3Event = signed
            persistKind3(myPubkey, signed)
            val currentFollows = cacheEntry?.followSet?.toMutableSet() ?: mutableSetOf()
            currentFollows.remove(targetPubkey.lowercase())
            cacheEntry = CacheEntry(myPubkey, currentFollows, System.currentTimeMillis())
            _followListUpdates.tryEmit(currentFollows.toSet())
            Log.d(TAG, "Unfollowed ${targetPubkey.take(8)}... — now following ${currentFollows.size}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unfollow failed: ${e.message}", e)
            "Unfollow failed: ${e.message}"
        }
    }
}
