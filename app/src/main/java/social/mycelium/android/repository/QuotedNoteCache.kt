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

/**
 * Fetches quoted kind-1 events by id from relays and returns minimal metadata.
 * Uses the user's subscription relays (set via [setRelayUrls]) plus fallback indexer relays.
 * Returns as soon as the event is found (early-return) with a ceiling timeout.
 * Bounded with LRU eviction; supports trim for memory pressure.
 */
object QuotedNoteCache {

    private const val TAG = "QuotedNoteCache"
    private const val FETCH_TIMEOUT_MS = 6000L
    private const val SNIPPET_MAX_LEN = 150
    private const val MAX_ENTRIES = 200

    /** Size to trim to when UI is hidden. */
    const val TRIM_SIZE_UI_HIDDEN = 100

    /** Size to trim to when app is in background. */
    const val TRIM_SIZE_BACKGROUND = 50

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

    /** Set the user's subscription relay URLs so quoted note fetches use real note relays. */
    fun setRelayUrls(urls: List<String>) {
        userRelayUrls = urls
    }

    /**
     * Get quoted note metadata from memory cache only (no network fetch).
     * Returns null if not cached. Used for synchronous lookups like outbox preloading.
     */
    fun getCached(eventId: String): QuotedNoteMeta? = memoryCache[eventId]

    /**
     * Get quoted note metadata by event id (hex). Returns from memory cache or fetches from relays.
     */
    suspend fun get(eventId: String): QuotedNoteMeta? {
        if (eventId.isBlank() || eventId.length != 64) return null
        memoryCache[eventId]?.let { return it }
        return fetchAndCache(eventId)
    }

    private suspend fun fetchAndCache(eventId: String): QuotedNoteMeta? {
        return try {
            val relays = (userRelayUrls + fallbackRelays).distinct().filter { it.isNotBlank() }.take(8)
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
                val snippet = e.content.take(SNIPPET_MAX_LEN).let { if (e.content.length > SNIPPET_MAX_LEN) "$it…" else it }
                val meta = QuotedNoteMeta(
                    eventId = e.id,
                    authorId = e.pubKey,
                    contentSnippet = snippet,
                    fullContent = e.content,
                    createdAt = e.createdAt,
                    relayUrl = sourceRelay.ifBlank { relays.firstOrNull() }
                )
                memoryCache[eventId] = meta
                meta
            }
        } catch (e: Exception) {
            Log.e(TAG, "Quoted note fetch failed for ${eventId.take(8)}: ${e.message}")
            null
        }
    }

    fun clear() {
        memoryCache.clear()
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
    }
}
