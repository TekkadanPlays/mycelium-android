package social.mycelium.android.utils

import android.util.LruCache

/**
 * In-memory LRU cache of measured heights (in pixels) for rendered quoted notes.
 * Keyed by composite "parentNoteId:eventId" so the same quoted event in different
 * cards caches independently and expansion state changes track correctly.
 *
 * When a quoted note scrolls back into view, its container applies
 * Modifier.defaultMinSize(minHeight = cachedHeight) so the LazyColumn reserves the
 * correct space immediately — preventing layout jumps when async content loads.
 *
 * The cache updates on EVERY size change (including shrinks from "show less")
 * so it always reflects the current rendered height, never a stale expanded value.
 */
object QuotedNoteHeightCache {
    private val cache = LruCache<String, Int>(500)

    private fun key(parentNoteId: String, eventId: String) = "$parentNoteId:$eventId"

    fun get(parentNoteId: String, eventId: String): Int? = cache.get(key(parentNoteId, eventId))

    /** Always update — including shrinks — so cached height tracks current state. */
    fun put(parentNoteId: String, eventId: String, heightPx: Int) {
        if (heightPx > 0) {
            cache.put(key(parentNoteId, eventId), heightPx)
        }
    }
}
