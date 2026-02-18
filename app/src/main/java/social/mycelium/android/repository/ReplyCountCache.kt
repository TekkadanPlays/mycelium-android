package social.mycelium.android.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory cache of reply counts per note ID.
 * Updated when a thread is loaded (Kind1RepliesViewModel / ThreadRepliesViewModel)
 * and proactively when kind-1 reply events are received for feed note IDs (incrementForReply).
 * Feed and profile screens use this to show "X replies" on cards.
 */
object ReplyCountCache {
    private val _replyCountByNoteId = MutableStateFlow<Map<String, Int>>(emptyMap())
    val replyCountByNoteId: StateFlow<Map<String, Int>> = _replyCountByNoteId.asStateFlow()

    /** Event IDs we have already counted (dedupe across relays). Bounded to avoid unbounded growth. */
    private val countedReplyEventIds = mutableSetOf<String>()
    private const val MAX_COUNTED_IDS = 5000

    fun set(noteId: String, count: Int) {
        if (count <= 0) return
        _replyCountByNoteId.value = _replyCountByNoteId.value + (noteId to count)
    }

    /**
     * Increment reply count for rootNoteId when we see a reply event (kind-1 with e-tags).
     * Dedupes by eventId so the same reply from multiple relays is only counted once.
     * Call from relay onEvent when kind==1 and root is in noteIdsOfInterest.
     */
    fun incrementForReply(eventId: String, rootNoteId: String) {
        if (rootNoteId.isBlank()) return
        synchronized(countedReplyEventIds) {
            if (eventId in countedReplyEventIds) return
            if (countedReplyEventIds.size >= MAX_COUNTED_IDS) {
                countedReplyEventIds.clear()
            }
            countedReplyEventIds.add(eventId)
        }
        val current = _replyCountByNoteId.value[rootNoteId] ?: 0
        _replyCountByNoteId.value = _replyCountByNoteId.value + (rootNoteId to (current + 1))
    }

    fun get(noteId: String): Int? = _replyCountByNoteId.value[noteId]
}
