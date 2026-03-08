package social.mycelium.android.repository

import android.util.Log
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.core.eventTemplate
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** After first kind-3 event arrives, wait this long for more relays before returning. */
    private const val KIND3_SETTLE_MS = 500L
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
            val event = collected.maxByOrNull { it.createdAt } ?: return emptySet()
            latestKind3Event = event
            val pubkeys = event.tags
                .map { it.toList() }
                .filter { it.isNotEmpty() && it[0] == "p" }
                .mapNotNull { list ->
                    val pk = list.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    if (pk.length == 64 && pk.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) pk.lowercase() else null
                }
                .toSet()
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
            Log.d(TAG, "Publishing follow kind-3 to ${outboxRelays.size} relays")
            RelayConnectionStateMachine.getInstance().send(signed, outboxRelays)

            // Update local cache
            latestKind3Event = signed
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
            Log.d(TAG, "Publishing unfollow kind-3 to ${outboxRelays.size} relays")
            RelayConnectionStateMachine.getInstance().send(signed, outboxRelays)

            // Update local cache
            latestKind3Event = signed
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
