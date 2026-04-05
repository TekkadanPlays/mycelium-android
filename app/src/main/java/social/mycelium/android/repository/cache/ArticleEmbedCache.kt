package social.mycelium.android.repository.cache

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches kind-30023 (NIP-23 long-form article) events by author+dTag from relays
 * and returns a [Note] suitable for rendering an embedded article preview.
 * Uses the same relay strategy as [QuotedNoteCache].
 */
object ArticleEmbedCache {

    private const val TAG = "ArticleEmbedCache"
    private const val FETCH_TIMEOUT_MS = 6000L
    private const val MAX_ENTRIES = 100

    /** Cache key = "author:dTag" */
    private val memoryCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Note>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Note>): Boolean =
                size > MAX_ENTRIES
        }
    )

    private val inFlightKeys = Collections.synchronizedSet(HashSet<String>())
    private val failedKeys = ConcurrentHashMap<String, Long>()
    private const val FAILED_TTL_MS = 60_000L

    @Volatile
    private var userRelayUrls: List<String> = emptyList()

    fun setRelayUrls(urls: List<String>) {
        userRelayUrls = urls
    }

    private fun cacheKey(author: String, dTag: String) = "$author:$dTag"

    fun getCached(author: String, dTag: String): Note? = memoryCache[cacheKey(author, dTag)]

    suspend fun get(author: String, dTag: String, relayHints: List<String> = emptyList()): Note? {
        if (author.isBlank() || dTag.isBlank()) return null
        val key = cacheKey(author, dTag)
        memoryCache[key]?.let { return it }
        if (key in inFlightKeys) return null
        val failedAt = failedKeys[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILED_TTL_MS) return null
        return fetchAndCache(author, dTag, relayHints)
    }

    private suspend fun fetchAndCache(author: String, dTag: String, relayHints: List<String>): Note? {
        val key = cacheKey(author, dTag)
        if (!inFlightKeys.add(key)) return null
        return try {
            val relays = (relayHints + userRelayUrls).distinct().filter { it.isNotBlank() }.take(8)
            if (relays.isEmpty()) {
                MLog.w(TAG, "No relays available to fetch article ${author.take(8)}:$dTag")
                return null
            }
            val filter = Filter(
                kinds = listOf(30023),
                authors = listOf(author),
                tags = mapOf("d" to listOf(dTag)),
                limit = 1
            )
            val deferred = CompletableDeferred<com.example.cybin.core.Event>()
            val stateMachine = RelayConnectionStateMachine.getInstance()
            val handle = stateMachine.requestTemporarySubscription(relays, filter, priority = SubscriptionPriority.LOW) { e ->
                if (e.pubKey.equals(author, ignoreCase = true) && e.kind == 30023) {
                    deferred.complete(e)
                }
            }
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) { deferred.await() }
            handle.cancel()
            result?.let { e ->
                val note = eventToNote(e)
                memoryCache[key] = note
                failedKeys.remove(key)
                note
            } ?: run {
                failedKeys[key] = System.currentTimeMillis()
                null
            }
        } catch (_: CancellationException) {
            MLog.d(TAG, "Article fetch cancelled for ${author.take(8)}:$dTag")
            null
        } catch (ex: Exception) {
            MLog.e(TAG, "Article fetch failed for ${author.take(8)}:$dTag: ${ex.message}")
            failedKeys[key] = System.currentTimeMillis()
            null
        } finally {
            inFlightKeys.remove(key)
        }
    }

    private fun eventToNote(e: com.example.cybin.core.Event): Note {
        val tags = e.tags.map { it.toList() }
        val title = tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        val summary = tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1)
        val image = tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        val dTag = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
        val hashtags = tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
        val publishedAt = tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull()
        return Note(
            id = e.id,
            author = Author(id = e.pubKey, username = "", displayName = ""),
            content = e.content,
            timestamp = (publishedAt ?: e.createdAt) * 1000L,
            kind = 30023,
            topicTitle = title,
            summary = summary,
            imageUrl = image,
            dTag = dTag,
            hashtags = hashtags,
            tags = tags
        )
    }

    fun clear() {
        memoryCache.clear()
        inFlightKeys.clear()
        failedKeys.clear()
    }
}
