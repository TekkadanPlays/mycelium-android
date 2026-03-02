package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import social.mycelium.android.data.Author
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory cache for NIP-01 kind-0 profile metadata. Fetches from indexer relays via
 * [RelayConnectionStateMachine.requestTemporarySubscription] so connections are managed by the
 * shared NostrClient relay pool — no duplicate WebSocket management, proper lifecycle, and
 * automatic connection reuse. Repositories resolve Author from this cache and request fetches
 * on miss; when kind-0 arrives we emit so UI can refresh.
 * Bounded with LRU eviction; supports trim for memory pressure.
 *
 * Disk persistence: call [init] from MainActivity.onCreate so profiles survive process death.
 * On cold start, profiles are loaded from SharedPreferences before relay fetches begin, so
 * cached notes render with display names and avatars immediately.
 */
class ProfileMetadataCache {

    companion object {
        @Volatile
        private var instance: ProfileMetadataCache? = null
        fun getInstance(): ProfileMetadataCache =
            instance ?: synchronized(this) { instance ?: ProfileMetadataCache().also { instance = it } }
        internal const val TAG = "ProfileMetadataCache"
        internal const val KIND0_FETCH_TIMEOUT_MS = 5000L
        /** Longer timeout for bulk follow-list profile fetches so slow relays can respond. */
        private const val KIND0_BULK_FETCH_TIMEOUT_MS = 12_000L
        /** Above this many pubkeys we use the bulk timeout. */
        private const val BULK_THRESHOLD = 50
        private const val MAX_ENTRIES = 2000

        /** When over this size, we evict even pinned (follow-list) profiles to avoid unbounded growth. */
        private const val HARD_CAP = 3000

        /** Size to trim to when app is in background. */
        const val TRIM_SIZE_BACKGROUND = 800

        private const val PROFILE_CACHE_PREFS = "profile_metadata_cache"
        private const val PROFILE_CACHE_KEY = "profiles_json"
        private const val PROFILE_CREATED_AT_KEY = "profiles_created_at_json"
        private const val OUTBOX_RELAYS_KEY = "outbox_relays_json"
        private const val PROFILE_FETCHED_AT_KEY = "profiles_fetched_at_json"
        /** Max profiles saved to disk. */
        private const val DISK_CACHE_MAX = 1500
        /** Debounce delay before writing profile cache to disk after an update. */
        private const val DISK_SAVE_DEBOUNCE_MS = 2000L
        /** Profiles older than this are considered stale and will be re-fetched when encountered. */
        private const val PROFILE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        /** How long to wait before checking if any profiles arrived (early-out for disconnected relays).
         *  Increased from 2s — relays often need 1-2s just to connect, leaving no time for kind-0 to return. */
        private const val EARLY_PROBE_MS = 3500L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    // ── Internal batching: accumulate pubkeys from all callers into one batch ──
    private val internalPendingPubkeys = Collections.synchronizedSet(HashSet<String>())
    private var internalPendingRelays = listOf<String>()

    /** Returns the relay URLs currently configured for profile fetching. */
    fun getConfiguredRelayUrls(): List<String> = internalPendingRelays
    private var internalBatchScheduleJob: Job? = null
    private var internalBatchFetchJob: Job? = null
    private val INTERNAL_BATCH_DELAY_MS = 400L
    private val INTERNAL_BATCH_SIZE = 30
    /** Shared counter for profiles received across all pool connections. */
    private val poolReceived = AtomicInteger(0)
    /** Consecutive early-out failures — drives exponential backoff to avoid retry spam. */
    private var consecutiveEarlyOuts = 0

    /** Application context for SharedPreferences persistence. Set via [init]. */
    @Volatile private var appContext: Context? = null
    /** Debounced disk save job. */
    private var diskSaveJob: Job? = null
    /** Prevents concurrent disk saves; if a save is in progress, new requests just mark dirty. */
    private val diskSaveMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var diskDirtyAfterSave = false

    /** Pubkeys (lowercase) that should not be evicted by LRU when under HARD_CAP (e.g. follow list). */
    private val pinnedPubkeys = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Set pubkeys to protect from eviction (e.g. people the user follows). Pass null or empty to clear.
     * Pinned entries are still evicted when cache size exceeds [HARD_CAP].
     */
    fun setPinnedPubkeys(pubkeys: Set<String>?) {
        val normalized = pubkeys?.map { it.lowercase() }?.toSet() ?: emptySet()
        pinnedPubkeys.clear()
        pinnedPubkeys.addAll(normalized)
    }

    // LRU order for eviction; actual data in cache + profileCreatedAt.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Author>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Author>): Boolean {
                if (size <= MAX_ENTRIES) return false
                if (eldest.key in pinnedPubkeys && size <= HARD_CAP) return false
                profileCreatedAt.remove(eldest.key)
                return true
            }
        }
    )
    /** Store createdAt per pubkey so we only keep the latest kind-0 when multiple relays send profiles. */
    private val profileCreatedAt = ConcurrentHashMap<String, Long>()
    /** Wall-clock time (System.currentTimeMillis) when each profile was last fetched from network.
     *  Used for TTL-based stale refresh: profiles older than PROFILE_TTL_MS are re-requested. */
    private val profileFetchedAt = ConcurrentHashMap<String, Long>()
    /** Per-user outbox relay URLs (NIP-65 write relays). Persisted alongside profiles. */
    private val outboxRelayCache = ConcurrentHashMap<String, List<String>>()
    /** Large buffer so bulk loads (e.g. debug "Fetch all") don't drop emissions before coalescer can apply to feed. */
    private val _profileUpdated = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 2048)
    val profileUpdated: SharedFlow<String> = _profileUpdated.asSharedFlow()

    /** Flips to true once the disk cache has been restored. UI components can key on this to rebuild
     *  content that was rendered with placeholder authors before the disk cache was ready. */
    private val _diskCacheRestored = MutableStateFlow(false)
    val diskCacheRestored: StateFlow<Boolean> = _diskCacheRestored.asStateFlow()

    /** Monotonically increasing counter that ticks whenever any profile is updated.
     *  Composables use this as a `remember` key so AnnotatedStrings with @mentions
     *  rebuild when display names resolve from kind-0 fetches. */
    private val _profileVersion = MutableStateFlow(0L)
    val profileVersion: StateFlow<Long> = _profileVersion.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private fun normalizeKey(pubkey: String): String = pubkey.lowercase()

    /**
     * Sanitize kind-0 string: trim, strip control/non-printable chars, collapse whitespace.
     * Returns null if result is blank or only "null" literal.
     */
    private fun sanitizeKind0String(s: String?, maxLen: Int = Int.MAX_VALUE): String? {
        if (s == null) return null
        val trimmed = s.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        val noControl = trimmed
            .filter { c -> c.code >= 32 && c.code != 0xFFFD }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noControl.isBlank()) return null
        return noControl.take(maxLen)
    }

    fun getAuthor(pubkey: String): Author? = cache[normalizeKey(pubkey)]

    /** Return a snapshot of all cached profiles (pubkey → Author). */
    fun getAllCached(): Map<String, Author> = synchronized(cache) { HashMap(cache) }

    /** Check if a cached profile is stale (older than PROFILE_TTL_MS). Returns true if missing or stale. */
    fun isProfileStale(pubkey: String): Boolean {
        val key = normalizeKey(pubkey)
        if (cache[key] == null) return true
        val fetchedAt = profileFetchedAt[key] ?: return true
        return (System.currentTimeMillis() - fetchedAt) > PROFILE_TTL_MS
    }

    /** Store outbox relay URLs for a user (NIP-65 write relays). */
    fun setOutboxRelays(pubkey: String, relayUrls: List<String>) {
        outboxRelayCache[normalizeKey(pubkey)] = relayUrls
        scheduleDiskSave()
    }

    /** Get cached outbox relay URLs for a user. Returns empty list if not cached. */
    fun getOutboxRelays(pubkey: String): List<String> =
        outboxRelayCache[normalizeKey(pubkey)] ?: emptyList()

    /**
     * Resolve author: cached or placeholder. Call requestProfiles(listOf(pubkey)) to fetch if missing.
     * Uses lowercase pubkey for cache key so feed updates match when kind-0 arrives (relays may send different casing).
     */
    fun resolveAuthor(pubkey: String): Author {
        val key = normalizeKey(pubkey)
        return cache[key] ?: Author(
            id = key,
            username = pubkey.take(8) + "...",
            displayName = pubkey.take(8) + "...",
            avatarUrl = null,
            isVerified = false
        )
    }

    /**
     * Put profile only if we don't have one or this event is newer (by createdAt).
     * Prioritizes the latest kind-0 when multiple relays send profile events.
     * Stores and emits under lowercase pubkey so feed update matches all notes for this user.
     */
    fun putProfileIfNewer(pubkey: String, author: Author?, createdAt: Long): Boolean {
        if (author == null) return false
        val key = normalizeKey(pubkey)
        val existingAt = profileCreatedAt[key] ?: 0L
        if (createdAt < existingAt) return false
        val existing = cache[key]
        val dataChanged = existing == null || existing != author || createdAt != existingAt
        cache[key] = author
        profileCreatedAt[key] = createdAt
        profileFetchedAt[key] = System.currentTimeMillis()
        // Always emit so notes get their authors applied (even if data came from disk cache)
        scope.launch {
            _profileUpdated.emit(key)
        }
        if (dataChanged) _profileVersion.value++
        // Only write to disk when the profile data actually changed
        if (dataChanged) scheduleDiskSave()
        return true
    }

    fun putProfile(pubkey: String, author: Author) {
        val key = normalizeKey(pubkey)
        cache[key] = author
        profileCreatedAt[key] = Long.MAX_VALUE
        profileFetchedAt[key] = System.currentTimeMillis()
        _profileVersion.value++
        scope.launch {
            _profileUpdated.emit(key)
        }
        scheduleDiskSave()
    }

    /**
     * Enhanced profile request that merges relay hints (note source relay, NIP-65 outbox relays)
     * with the standard indexer relays. This improves discovery for profiles that aren't indexed
     * by the user's configured relays — like Amethyst's UserFinderFilterAssemblerSubscription
     * which subscribes to the relay where the event was seen.
     *
     * @param pubkeys Pubkeys to fetch.
     * @param cacheRelayUrls Standard indexer/cache relay URLs.
     * @param hintRelayUrls Additional relay hints (e.g. note source relay, author's outbox relays).
     */
    suspend fun requestProfileWithHints(pubkeys: List<String>, cacheRelayUrls: List<String>, hintRelayUrls: List<String>) {
        val mergedRelays = (hintRelayUrls + cacheRelayUrls).distinct()
        requestProfiles(pubkeys, mergedRelays)
    }

    /**
     * Public API: accumulate pubkeys into an internal batch and schedule a debounced fetch.
     * All callers (NotesRepository, Kind1RepliesRepository, NotificationsRepository, etc.)
     * funnel through here. The actual fetch fires once after the debounce settles via
     * [RelayConnectionStateMachine.requestTemporarySubscription], reusing the shared relay pool.
     */
    suspend fun requestProfiles(pubkeys: List<String>, cacheRelayUrls: List<String>) {
        if (pubkeys.isEmpty() || cacheRelayUrls.isEmpty()) return
        // Fetch profiles that are missing OR stale (older than TTL)
        val needed = pubkeys.filter { pk ->
            val key = normalizeKey(pk)
            cache[key] == null || isProfileStale(pk)
        }
        if (needed.isEmpty()) return

        internalPendingPubkeys.addAll(needed.map { normalizeKey(it) })
        if (cacheRelayUrls.isNotEmpty()) internalPendingRelays = cacheRelayUrls

        scheduleInternalBatch()
    }

    /**
     * Schedule the internal batch fetch. If no fetch is in-flight, resets the debounce timer.
     * If a fetch IS in-flight, just let pubkeys accumulate — the while loop will pick them up.
     */
    private fun scheduleInternalBatch() {
        // If a fetch is already running, pubkeys will be picked up by its while loop — no need to schedule
        if (internalBatchFetchJob != null) return
        internalBatchScheduleJob?.cancel()
        internalBatchScheduleJob = scope.launch {
            delay(INTERNAL_BATCH_DELAY_MS)
            internalBatchFetchJob = scope.launch {
                while (internalPendingPubkeys.isNotEmpty() && consecutiveEarlyOuts < 5) {
                    val batch = synchronized(internalPendingPubkeys) {
                        internalPendingPubkeys.take(INTERNAL_BATCH_SIZE).also { internalPendingPubkeys.removeAll(it.toSet()) }
                    }
                    if (batch.isEmpty()) break
                    val relays = internalPendingRelays
                    if (relays.isEmpty()) {
                        internalPendingPubkeys.addAll(batch)
                        break
                    }
                    fetchProfilesBatch(batch, relays)
                    if (internalPendingPubkeys.isNotEmpty()) delay(300)
                }
                internalBatchFetchJob = null
            }
            internalBatchScheduleJob = null
        }
    }

    /**
     * Fetch kind-0 profiles for a batch of pubkeys via [RelayConnectionStateMachine].
     * Uses a temporary subscription through the shared NostrClient relay pool so connections
     * are properly managed, reused, and cleaned up. No raw WebSockets.
     */
    private suspend fun fetchProfilesBatch(pubkeys: List<String>, cacheRelayUrls: List<String>) {
        val uncached = pubkeys.filter { cache[normalizeKey(it)] == null }
        if (uncached.isEmpty()) return

        val allRelays = cacheRelayUrls.distinct()
        if (allRelays.isEmpty()) return

        Log.d(TAG, "Fetching kind-0 for ${uncached.size} pubkeys from ${allRelays.size} relays (state machine)")
        val beforeCount = poolReceived.get()

        val filter = Filter(
            kinds = listOf(0),
            authors = uncached,
            limit = uncached.size
        )

        val batchReceived = AtomicInteger(0)
        val handle = RelayConnectionStateMachine.getInstance()
            .requestTemporarySubscription(allRelays, filter, priority = SubscriptionPriority.LOW) { event: Event ->
                if (event.kind == 0) {
                    val pubkey = event.pubKey
                    val content = event.content
                    val createdAt = event.createdAt
                    if (pubkey.isNotBlank() && content.isNotBlank()) {
                        val author = parseKind0Content(pubkey, content)
                        if (author != null && putProfileIfNewer(pubkey, author, createdAt)) {
                            batchReceived.incrementAndGet()
                            poolReceived.incrementAndGet()
                        }
                    }
                }
            }

        // Progressive early-out: poll every 500ms during the probe window so we catch relays
        // that connect mid-probe (e.g. at 1.5s) instead of waiting the full window.
        // Backoff only adds a delay BEFORE the next retry cycle, not during the probe.
        val probeIntervalMs = 500L
        val probeChecks = (EARLY_PROBE_MS / probeIntervalMs).toInt().coerceAtLeast(1)
        var probeElapsed = 0L
        for (i in 0 until probeChecks) {
            delay(probeIntervalMs)
            probeElapsed += probeIntervalMs
            if (batchReceived.get() > 0) break
        }
        if (batchReceived.get() == 0) {
            handle.cancel()
            consecutiveEarlyOuts++
            // Re-queue so next batch picks them up (relays may be connected by then)
            synchronized(internalPendingPubkeys) { internalPendingPubkeys.addAll(uncached) }
            // Hard cap: after 5 consecutive failures, stop retrying entirely.
            // Pubkeys stay in the queue and will be picked up when new notes trigger a fresh batch.
            if (consecutiveEarlyOuts >= 5) {
                Log.d(TAG, "Kind-0 early-out: 0/${uncached.size} after ${probeElapsed}ms (attempt $consecutiveEarlyOuts) — giving up, relays appear offline")
                return
            }
            // Backoff delay before the while-loop retries: 0s, 1s, 2s, 4s, 8s cap
            val backoffMs = if (consecutiveEarlyOuts <= 1) 0L
                else (1000L * (1L shl (consecutiveEarlyOuts - 2).coerceAtMost(3))).coerceAtMost(8_000L)
            Log.d(TAG, "Kind-0 early-out: 0/${uncached.size} after ${probeElapsed}ms (attempt $consecutiveEarlyOuts, next backoff ${backoffMs}ms), re-queued")
            if (backoffMs > 0) delay(backoffMs)
            return
        }
        consecutiveEarlyOuts = 0 // Reset on success

        // Relays are responding — wait for the full timeout
        val remainingMs = ((if (uncached.size > BULK_THRESHOLD) KIND0_BULK_FETCH_TIMEOUT_MS else KIND0_FETCH_TIMEOUT_MS) - probeElapsed).coerceAtLeast(1000L)
        delay(remainingMs)

        handle.cancel()
        Log.d(TAG, "Kind-0 fetch done: ${batchReceived.get()}/${uncached.size} profiles from ${allRelays.size} relays")
    }

    /**
     * Parse kind-0 content JSON into an Author, without needing a Quartz Event object.
     */
    private fun parseKind0Content(pubkey: String, content: String): Author? {
        return try {
            val parsed = json.decodeFromString<Kind0Content>(content)
            val key = normalizeKey(pubkey)
            val fallbackShort = pubkey.take(8) + "..."
            val displayName = sanitizeKind0String(parsed.display_name, 64)
                ?: sanitizeKind0String(parsed.name, 64)
                ?: fallbackShort
            val username = sanitizeKind0String(parsed.name, 16)
                ?: sanitizeKind0String(parsed.display_name, 16)
                ?: fallbackShort
            val picture = sanitizeKind0String(parsed.picture, 512)
            val about = sanitizeKind0String(parsed.about, 500)
            val nip05 = sanitizeKind0String(parsed.nip05, 128)
            val website = sanitizeKind0String(parsed.website, 256)
            val lud16 = sanitizeKind0String(parsed.lud16, 128)
            val banner = sanitizeKind0String(parsed.banner, 512)
            val pronouns = sanitizeKind0String(parsed.pronouns, 32)
            Author(
                id = key,
                username = username,
                displayName = displayName,
                avatarUrl = picture?.takeIf { it.isNotBlank() },
                isVerified = false,
                about = about?.takeIf { it.isNotBlank() },
                nip05 = nip05?.takeIf { it.isNotBlank() },
                website = website?.takeIf { it.isNotBlank() },
                lud16 = lud16?.takeIf { it.isNotBlank() },
                banner = banner?.takeIf { it.isNotBlank() },
                pronouns = pronouns?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Parse kind-0 content failed: ${e.message}")
            null
        }
    }

    /**
     * Reduce cache to at most maxEntries (LRU eviction). Pinned pubkeys are evicted last.
     * Thread-safe.
     */
    fun trimToSize(maxEntries: Int) {
        synchronized(cache) {
            val keysByAge = cache.keys.toList()
            for (key in keysByAge) {
                if (cache.size <= maxEntries) break
                if (key in pinnedPubkeys) continue
                cache.remove(key)
                profileCreatedAt.remove(key)
            }
        }
    }

    // ── Disk persistence ────────────────────────────────────────────────────

    /**
     * Initialize disk persistence. Call once from MainActivity.onCreate so profiles survive
     * process death. Loads any previously saved profiles into the in-memory cache immediately.
     */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { loadProfileCacheFromDisk() }
    }

    /** Schedule a debounced save after a profile update. */
    private fun scheduleDiskSave() {
        if (appContext == null) return
        // If a save is already in progress, mark dirty so it does one more pass after finishing
        if (diskSaveMutex.isLocked) {
            diskDirtyAfterSave = true
            return
        }
        diskSaveJob?.cancel()
        diskSaveJob = scope.launch {
            delay(DISK_SAVE_DEBOUNCE_MS)
            if (diskSaveMutex.tryLock()) {
                try {
                    do {
                        diskDirtyAfterSave = false
                        saveProfileCacheToDisk()
                    } while (diskDirtyAfterSave)
                } finally {
                    diskSaveMutex.unlock()
                }
            }
        }
    }

    private suspend fun loadProfileCacheFromDisk() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                val prefs = ctx.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE)
                val profilesJson = prefs.getString(PROFILE_CACHE_KEY, null) ?: return@withContext
                val createdAtJson = prefs.getString(PROFILE_CREATED_AT_KEY, null)
                val fetchedAtJson = prefs.getString(PROFILE_FETCHED_AT_KEY, null)
                val outboxJson = prefs.getString(OUTBOX_RELAYS_KEY, null)

                val profiles: Map<String, Author> = json.decodeFromString(profilesJson)
                val createdAts: Map<String, Long> = if (createdAtJson != null) {
                    json.decodeFromString(createdAtJson)
                } else {
                    emptyMap()
                }
                val fetchedAts: Map<String, Long> = if (fetchedAtJson != null) {
                    try { json.decodeFromString(fetchedAtJson) } catch (_: Exception) { emptyMap() }
                } else {
                    emptyMap()
                }
                val outboxRelays: Map<String, List<String>> = if (outboxJson != null) {
                    try { json.decodeFromString(outboxJson) } catch (_: Exception) { emptyMap() }
                } else {
                    emptyMap()
                }

                if (profiles.isEmpty()) return@withContext

                val restoredKeys = mutableListOf<String>()
                synchronized(cache) {
                    for ((key, author) in profiles) {
                        val normalized = normalizeKey(key)
                        if (cache[normalized] == null) {
                            cache[normalized] = author
                            createdAts[normalized]?.let { profileCreatedAt[normalized] = it }
                            fetchedAts[normalized]?.let { profileFetchedAt[normalized] = it }
                            restoredKeys.add(normalized)
                        }
                    }
                }
                // Restore outbox relay cache
                for ((key, relays) in outboxRelays) {
                    val normalized = normalizeKey(key)
                    if (outboxRelayCache[normalized] == null) {
                        outboxRelayCache[normalized] = relays
                    }
                }
                Log.d(TAG, "Restored ${profiles.size} profiles, ${outboxRelays.size} outbox relay sets from disk cache (${restoredKeys.size} new)")
                // Signal UI components to rebuild with real display names.
                // We use diskCacheRestored (StateFlow) instead of emitting profileUpdated
                // for every key, which would flood reply ViewModels and cause race conditions.
                _diskCacheRestored.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Load profile cache from disk failed: ${e.message}", e)
            }
        }
    }

    private suspend fun saveProfileCacheToDisk() {
        val ctx = appContext ?: return
        withContext(Dispatchers.IO) {
            try {
                // Take a snapshot of the most recent entries (pinned first, then LRU order)
                val snapshot: Map<String, Author>
                val createdAtSnapshot: Map<String, Long>
                val fetchedAtSnapshot: Map<String, Long>
                val outboxSnapshot: Map<String, List<String>>
                synchronized(cache) {
                    val pinned = cache.entries.filter { it.key in pinnedPubkeys }
                    val rest = cache.entries.filter { it.key !in pinnedPubkeys }
                    val combined = (pinned + rest).takeLast(DISK_CACHE_MAX)
                    val keys = combined.map { it.key }.toSet()
                    snapshot = combined.associate { it.key to it.value }
                    createdAtSnapshot = combined.mapNotNull { entry ->
                        profileCreatedAt[entry.key]?.let { entry.key to it }
                    }.toMap()
                    fetchedAtSnapshot = combined.mapNotNull { entry ->
                        profileFetchedAt[entry.key]?.let { entry.key to it }
                    }.toMap()
                    outboxSnapshot = outboxRelayCache.filter { it.key in keys }
                }

                val profilesJson = json.encodeToString(snapshot)
                val createdAtJson = json.encodeToString(createdAtSnapshot)
                val fetchedAtJson = json.encodeToString(fetchedAtSnapshot)
                val outboxJson = json.encodeToString(outboxSnapshot)
                ctx.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PROFILE_CACHE_KEY, profilesJson)
                    .putString(PROFILE_CREATED_AT_KEY, createdAtJson)
                    .putString(PROFILE_FETCHED_AT_KEY, fetchedAtJson)
                    .putString(OUTBOX_RELAYS_KEY, outboxJson)
                    .apply()
                Log.d(TAG, "Saved ${snapshot.size} profiles, ${outboxSnapshot.size} outbox relay sets to disk cache")
            } catch (e: Exception) {
                Log.e(TAG, "Save profile cache to disk failed: ${e.message}", e)
            }
        }
    }

    @Serializable
    private data class Kind0Content(
        val name: String? = null,
        val display_name: String? = null,
        val picture: String? = null,
        val about: String? = null,
        val nip05: String? = null,
        val website: String? = null,
        val lud16: String? = null,
        val banner: String? = null,
        val pronouns: String? = null
    )
}
