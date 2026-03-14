package social.mycelium.android.utils

import android.util.LruCache

/**
 * In-memory LRU cache of measured heights (in pixels) for rendered quoted notes.
 * Keyed by quoted event ID. When a quoted note scrolls back into view, its
 * container applies Modifier.defaultMinSize(minHeight = cachedHeight) so the
 * LazyColumn reserves the correct space immediately — preventing layout jumps
 * when async content (text, media) loads.
 */
object QuotedNoteHeightCache {
    private val cache = LruCache<String, Int>(500)

    fun get(eventId: String): Int? = cache.get(eventId)

    fun put(eventId: String, heightPx: Int) {
        if (heightPx > 0) {
            cache.put(eventId, heightPx)
        }
    }
}
