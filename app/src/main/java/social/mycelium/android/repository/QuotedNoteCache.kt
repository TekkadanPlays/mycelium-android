package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import social.mycelium.android.data.QuotedNoteMeta
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches quoted kind-1 events by id from relays and returns minimal metadata.
 * Uses the user's subscription relays (set via [setRelayUrls]) plus fallback indexer relays.
 * Returns as soon as the event is found (early-return) with a ceiling timeout.
 * Bounded with LRU eviction; supports trim for memory pressure.
 */
object QuotedNoteCache {

    private const val TAG = "QuotedNoteCache"
    private const val FETCH_TIMEOUT_MS = 6000L
    private const val BATCH_DEBOUNCE_MS = 300L
    private const val MAX_BATCH_SIZE = 40
    private const val SNIPPET_MAX_LEN = 500
    private const val MAX_ENTRIES = 200
    private const val DISK_CACHE_MAX = 150
    private const val PREFS_NAME = "quoted_note_cache"
    private const val PREFS_KEY = "entries"

    /** Size to trim to when UI is hidden. */
    const val TRIM_SIZE_UI_HIDDEN = 100

    /** Size to trim to when app is in background. */
    const val TRIM_SIZE_BACKGROUND = 50

    private val diskScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val diskJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var diskDirty = false

    /** IDs currently being fetched — prevents duplicate concurrent requests. */
    private val inFlightIds = Collections.synchronizedSet(HashSet<String>())

    /** IDs that failed to fetch (relay didn't have them). Cleared periodically so retries can happen. */
    private val failedIds = ConcurrentHashMap<String, Long>()
    private const val FAILED_TTL_MS = 60_000L

    // LRU map: access order, eldest evicted when over MAX_ENTRIES.
    private val memoryCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, QuotedNoteMeta>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QuotedNoteMeta>): Boolean =
                size > MAX_ENTRIES
        }
    )

    private const val PREFS_VERSION_KEY = "cache_version"
    private const val CURRENT_CACHE_VERSION = 3 // v3: fix NIP-10 mention e-tags falsely setting rootNoteId

    /** Call once from Application/Activity to load disk cache into memory. */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs?.getInt(PREFS_VERSION_KEY, 1) ?: 1
        if (storedVersion < CURRENT_CACHE_VERSION) {
            Log.d(TAG, "Cache version $storedVersion < $CURRENT_CACHE_VERSION — clearing stale entries")
            prefs?.edit()?.remove(PREFS_KEY)?.putInt(PREFS_VERSION_KEY, CURRENT_CACHE_VERSION)?.apply()
        }
        loadFromDisk()
    }

    /** No hardcoded fallback relays — user's configured relays are used exclusively. */
    private val fallbackRelays = emptyList<String>()

    /** User's active subscription relays — set from DashboardScreen when account loads. */
    @Volatile
    private var userRelayUrls: List<String> = emptyList()

    /** Indexer relays for broader quoted note coverage. */
    @Volatile
    private var indexerRelayUrls: List<String> = emptyList()

    /** Relay hints extracted from nevent1 TLV, keyed by event ID.
     *  Populated at note parse time so fetchAndCache can use them without changing the Note data class. */
    private val relayHintsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
                size > MAX_ENTRIES
        }
    )

    /** Register relay hints for a quoted event ID (extracted from nevent1 TLV at parse time). */
    fun putRelayHints(eventId: String, hints: List<String>) {
        if (hints.isNotEmpty()) relayHintsCache[eventId] = hints
    }

    /** Set the user's subscription relay URLs so quoted note fetches use real note relays. */
    fun setRelayUrls(urls: List<String>) {
        userRelayUrls = urls
    }

    /** Set indexer relay URLs for fallback quoted note fetches. */
    fun setIndexerRelayUrls(urls: List<String>) {
        indexerRelayUrls = urls
    }

    /**
     * Get quoted note metadata from memory cache only (no network fetch).
     * Returns null if not cached. Used for synchronous lookups like outbox preloading.
     */
    fun getCached(eventId: String): QuotedNoteMeta? = memoryCache[eventId]

    /**
     * Get quoted note metadata by event id (hex). Returns from memory cache or fetches from relays.
     * Relay hints registered via [putRelayHints] are used automatically; explicit hints take priority.
     *
     * **Batched**: IDs are collected over a [BATCH_DEBOUNCE_MS] window and fetched in a single
     * relay subscription with `ids=[all pending]`, collapsing N subscriptions into 1.
     */
    suspend fun get(eventId: String, relayHints: List<String> = emptyList()): QuotedNoteMeta? {
        if (eventId.isBlank() || eventId.length != 64) return null
        memoryCache[eventId]?.let { return it }
        // Skip if already in-flight or recently failed
        if (eventId in inFlightIds) return null
        val failedAt = failedIds[eventId]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_TTL_MS) return null
        if (!inFlightIds.add(eventId)) return null
        // Merge explicit hints with any previously registered hints from nevent1 TLV
        val allHints = (relayHints + (relayHintsCache[eventId] ?: emptyList())).distinct()
        // Enqueue into batch and wait for the result
        val deferred = CompletableDeferred<QuotedNoteMeta?>()
        synchronized(pendingBatch) {
            pendingBatch[eventId] = PendingQuoteFetch(eventId, allHints, deferred)
        }
        scheduleBatchFlush()
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            Log.d(TAG, "Quoted note fetch cancelled for ${eventId.take(8)} (scrolled away)")
            synchronized(pendingBatch) { pendingBatch.remove(eventId) }
            null
        } finally {
            inFlightIds.remove(eventId)
        }
    }

    // ── Batched fetch infrastructure ─────────────────────────────────────────

    private data class PendingQuoteFetch(
        val eventId: String,
        val relayHints: List<String>,
        val deferred: CompletableDeferred<QuotedNoteMeta?>,
    )

    private val pendingBatch = LinkedHashMap<String, PendingQuoteFetch>()
    private val batchScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var batchFlushJob: kotlinx.coroutines.Job? = null

    private fun scheduleBatchFlush() {
        // If the batch is full, flush immediately; otherwise debounce.
        val shouldFlushNow = synchronized(pendingBatch) { pendingBatch.size >= MAX_BATCH_SIZE }
        if (shouldFlushNow) {
            batchFlushJob?.cancel()
            batchScope.launch { flushBatch() }
            return
        }
        if (batchFlushJob?.isActive == true) return // Already scheduled
        batchFlushJob = batchScope.launch {
            kotlinx.coroutines.delay(BATCH_DEBOUNCE_MS)
            flushBatch()
        }
    }

    private suspend fun flushBatch() {
        // Snapshot and clear the pending batch
        val batch: Map<String, PendingQuoteFetch>
        synchronized(pendingBatch) {
            if (pendingBatch.isEmpty()) return
            batch = LinkedHashMap(pendingBatch)
            pendingBatch.clear()
        }
        Log.d(TAG, "Flushing batch of ${batch.size} quoted note IDs")

        // Collect all relay hints + user relays into one set
        val allRelayHints = batch.values.flatMap { it.relayHints }.distinct()
        val relays = (allRelayHints + userRelayUrls).distinct().filter { it.isNotBlank() }.take(10)
        if (relays.isEmpty()) {
            Log.w(TAG, "No relays available for batch fetch")
            for ((_, entry) in batch) {
                entry.deferred.complete(null)
                inFlightIds.remove(entry.eventId)
            }
            return
        }

        // Single subscription with all IDs
        val ids = batch.keys.toList()
        val filter = Filter(
            kinds = listOf(1, 11, 1111),
            ids = ids,
            limit = ids.size
        )

        val fulfilled = ConcurrentHashMap<String, Boolean>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        try {
            stateMachine.awaitOneShotSubscription(
                relays, filter, priority = SubscriptionPriority.LOW,
                settleMs = 500L, maxWaitMs = FETCH_TIMEOUT_MS
            ) { event ->
                val entry = batch[event.id] ?: return@awaitOneShotSubscription
                if (fulfilled.putIfAbsent(event.id, true) != null) return@awaitOneShotSubscription
                val snippet = buildSmartSnippet(event.content, SNIPPET_MAX_LEN)
                val rootId = social.mycelium.android.utils.Nip10ReplyDetector.getRootId(event)
                val meta = QuotedNoteMeta(
                    eventId = event.id,
                    authorId = event.pubKey,
                    contentSnippet = snippet,
                    fullContent = event.content,
                    createdAt = event.createdAt,
                    relayUrl = relays.firstOrNull(),
                    rootNoteId = rootId,
                    kind = event.kind
                )
                memoryCache[event.id] = meta
                failedIds.remove(event.id)
                entry.deferred.complete(meta)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Batch fetch error: ${e.message}")
        }

        // Complete any unfulfilled deferreds as null (not found on any relay)
        var savedAny = false
        for ((id, entry) in batch) {
            if (!fulfilled.containsKey(id)) {
                failedIds[id] = System.currentTimeMillis()
                entry.deferred.complete(null)
            } else {
                savedAny = true
            }
            inFlightIds.remove(id)
        }
        if (savedAny) scheduleDiskSave()
        Log.d(TAG, "Batch complete: ${fulfilled.size}/${batch.size} found")
    }

    /**
     * Build a snippet that never truncates inside a `nostr:` URI.
     * If the naive cut falls mid-URI, back up to before the URI so the
     * annotated string builder can fully resolve all NIP-19 identifiers.
     */
    private fun buildSmartSnippet(content: String, maxLen: Int): String {
        if (content.length <= maxLen) return content
        var cutAt = maxLen
        // Check if the cut point falls inside a nostr: URI
        val nostrPattern = Regex("nostr:[a-z0-9]+", RegexOption.IGNORE_CASE)
        for (match in nostrPattern.findAll(content)) {
            if (match.range.first < cutAt && match.range.last >= cutAt) {
                // Cut falls inside this URI — back up to before it
                cutAt = match.range.first
                break
            }
        }
        // Also avoid cutting mid-word: find the last whitespace before cutAt
        val lastSpace = content.lastIndexOf(' ', cutAt - 1)
        if (lastSpace > cutAt / 2) cutAt = lastSpace
        return content.take(cutAt).trimEnd() + "…"
    }

    fun clear() {
        memoryCache.clear()
        relayHintsCache.clear()
        inFlightIds.clear()
        failedIds.clear()
        prefs?.edit()?.remove(PREFS_KEY)?.apply()
    }

    /**
     * Reduce cache to at most maxEntries (LRU eviction).
     * Thread-safe.
     */
    fun trimToSize(maxEntries: Int) {
        synchronized(memoryCache) {
            while (memoryCache.size > maxEntries && memoryCache.isNotEmpty()) {
                val eldest = memoryCache.keys.iterator().next()
                memoryCache.remove(eldest)
            }
        }
        synchronized(relayHintsCache) {
            while (relayHintsCache.size > maxEntries && relayHintsCache.isNotEmpty()) {
                val eldest = relayHintsCache.keys.iterator().next()
                relayHintsCache.remove(eldest)
            }
        }
    }

    // ── Disk persistence ─────────────────────────────────────────────────

    private fun loadFromDisk() {
        try {
            val json = prefs?.getString(PREFS_KEY, null) ?: return
            val list = diskJson.decodeFromString<List<QuotedNoteMeta>>(json)
            synchronized(memoryCache) {
                for (meta in list) memoryCache[meta.eventId] = meta
            }
            Log.d(TAG, "Loaded ${list.size} quoted notes from disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache: ${e.message}")
        }
    }

    private var diskSaveJob: kotlinx.coroutines.Job? = null

    private fun scheduleDiskSave() {
        diskSaveJob?.cancel()
        diskSaveJob = diskScope.launch {
            kotlinx.coroutines.delay(2000)
            saveToDisk()
        }
    }

    private fun saveToDisk() {
        try {
            val entries = synchronized(memoryCache) {
                memoryCache.values.toList().takeLast(DISK_CACHE_MAX)
            }
            val json = diskJson.encodeToString(entries)
            prefs?.edit()?.putString(PREFS_KEY, json)?.apply()
            Log.d(TAG, "Saved ${entries.size} quoted notes to disk cache")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save disk cache: ${e.message}")
        }
    }
}
