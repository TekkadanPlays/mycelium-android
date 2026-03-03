package social.mycelium.android.repository

import android.util.Log
import social.mycelium.android.data.QuotedNoteMeta
import social.mycelium.android.relay.RelayConnectionStateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
    private const val SNIPPET_MAX_LEN = 500
    private const val MAX_ENTRIES = 200

    /** Size to trim to when UI is hidden. */
    const val TRIM_SIZE_UI_HIDDEN = 100

    /** Size to trim to when app is in background. */
    const val TRIM_SIZE_BACKGROUND = 50

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
     */
    suspend fun get(eventId: String, relayHints: List<String> = emptyList()): QuotedNoteMeta? {
        if (eventId.isBlank() || eventId.length != 64) return null
        memoryCache[eventId]?.let { return it }
        // Skip if already in-flight or recently failed
        if (eventId in inFlightIds) return null
        val failedAt = failedIds[eventId]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_TTL_MS) return null
        // Merge explicit hints with any previously registered hints from nevent1 TLV
        val allHints = (relayHints + (relayHintsCache[eventId] ?: emptyList())).distinct()
        return fetchAndCache(eventId, allHints)
    }

    private suspend fun fetchAndCache(eventId: String, relayHints: List<String> = emptyList()): QuotedNoteMeta? {
        if (!inFlightIds.add(eventId)) return null // Another coroutine is already fetching this
        return try {
            // Relay hints from nevent1 TLV first (most likely to have the event), then user relays as fallback
            val relays = (relayHints + userRelayUrls).distinct().filter { it.isNotBlank() }.take(8)
            if (relays.isEmpty()) {
                Log.w(TAG, "No relays available to fetch quoted note ${eventId.take(8)}")
                return null
            }
            val filter = Filter(
                kinds = listOf(1, 11, 1111),
                ids = listOf(eventId),
                limit = 1
            )
            val deferred = CompletableDeferred<Pair<Event, String>>()
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscriptionWithRelay(relays, filter, priority = SubscriptionPriority.LOW) { e, relayUrl ->
                if (e.id == eventId) deferred.complete(e to relayUrl)
            }
            // Wait until event arrives or timeout — whichever comes first
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
            handle.cancel()
            result?.let { (e, sourceRelay) ->
                val snippet = buildSmartSnippet(e.content, SNIPPET_MAX_LEN)
                // Extract NIP-10 root id so navigation can open the full thread for kind-1 replies
                val rootId = social.mycelium.android.utils.Nip10ReplyDetector.getRootId(e)
                val meta = QuotedNoteMeta(
                    eventId = e.id,
                    authorId = e.pubKey,
                    contentSnippet = snippet,
                    fullContent = e.content,
                    createdAt = e.createdAt,
                    relayUrl = sourceRelay.ifBlank { relays.firstOrNull() },
                    rootNoteId = rootId,
                    kind = e.kind
                )
                memoryCache[eventId] = meta
                failedIds.remove(eventId)
                meta
            } ?: run {
                failedIds[eventId] = System.currentTimeMillis()
                null
            }
        } catch (e: CancellationException) {
            // Normal: composable left composition while fetch was in-flight. Don't mark as failed.
            Log.d(TAG, "Quoted note fetch cancelled for ${eventId.take(8)} (scrolled away)")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Quoted note fetch failed for ${eventId.take(8)}: ${e.message}")
            failedIds[eventId] = System.currentTimeMillis()
            null
        } finally {
            inFlightIds.remove(eventId)
        }
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
}
