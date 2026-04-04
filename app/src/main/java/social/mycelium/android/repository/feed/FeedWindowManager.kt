package social.mycelium.android.repository.feed

import android.util.Log
import com.example.cybin.core.Event
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.data.Note
import social.mycelium.android.db.CachedEventEntity
import social.mycelium.android.db.EventDao

/**
 * Room-backed windowed feed manager. Provides unbounded scroll depth by
 * loading only a sliding window of notes from Room instead of keeping
 * everything in memory.
 *
 * Architecture:
 * ```
 * Relay → flushKind1Events → Room (all events persisted)
 *                                ↓
 *         FeedWindowManager loads window from Room
 *                                ↓
 *         _windowNotes (80 notes in heap) → displayed in LazyColumn
 * ```
 *
 * The manager is cursor-based: it tracks the oldest timestamp in the
 * current window. Scrolling down loads older events from Room; the top
 * of the feed stays driven by live relay data via NotesRepository.
 */
class FeedWindowManager(
    private val eventDao: EventDao,
    /** Converts a CachedEventEntity to a Note. Delegates to NotesRepository.convertEventToNote. */
    private val entityToNote: (CachedEventEntity) -> Note?,
    /** Number of notes per page loaded from Room. */
    private val pageSize: Int = 50,
) {
    companion object {
        private const val TAG = "FeedWindowManager"
    }

    /** Total root feed events in Room (for UI indicators). */
    private val _totalRoomEvents = MutableStateFlow(0)
    val totalRoomEvents: StateFlow<Int> = _totalRoomEvents.asStateFlow()

    /** Whether Room has more events older than the current window cursor. */
    private val _hasOlderInRoom = MutableStateFlow(true)
    val hasOlderInRoom: StateFlow<Boolean> = _hasOlderInRoom.asStateFlow()

    /** Follow filter pubkeys — set by NotesRepository when follow mode changes. */
    @Volatile var followPubkeys: List<String> = emptyList()
    
    /** Whether follow filter is active. */
    @Volatile var followFilterEnabled: Boolean = false

    /** True when a Room page load is in progress. */
    private val _isLoadingPage = MutableStateFlow(false)
    val isLoadingPage: StateFlow<Boolean> = _isLoadingPage.asStateFlow()

    // ── Core operations ────────────────────────────────────────────────────

    /**
     * Load a page of notes from Room older than [olderThanSec].
     * Returns the converted notes (empty if Room is exhausted).
     *
     * This does NOT manage any window state — it's a pure Room query.
     * NotesRepository decides what to do with the results (append to
     * _displayedNotes, merge with in-memory data, etc.).
     */
    suspend fun loadPage(olderThanSec: Long, limit: Int = pageSize): List<Note> {
        _isLoadingPage.value = true
        try {
            val entities = queryWindow(olderThanSec, limit)
            if (entities.isEmpty()) {
                _hasOlderInRoom.value = false
                return emptyList()
            }
            val notes = entities.mapNotNull { entity ->
                try {
                    entityToNote(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Skip bad entity ${entity.eventId.take(8)}: ${e.message}")
                    null
                }
            }
            Log.d(TAG, "loadPage(older than ${fmtSec(olderThanSec)}): ${entities.size} entities → ${notes.size} notes")
            return notes
        } catch (e: Exception) {
            Log.e(TAG, "loadPage failed: ${e.message}", e)
            return emptyList()
        } finally {
            _isLoadingPage.value = false
        }
    }

    /**
     * Load the initial window (newest events from Room). Convenience
     * for cold-start restoration.
     */
    suspend fun loadInitialPage(limit: Int = pageSize): List<Note> {
        _hasOlderInRoom.value = true // reset exhaustion flag
        return loadPage(Long.MAX_VALUE, limit)
    }

    /**
     * Count total root feed events in Room. Updates [totalRoomEvents] state.
     */
    suspend fun updateTotalCount() {
        try {
            val pubkeys = if (followFilterEnabled && followPubkeys.isNotEmpty()) followPubkeys else null
            _totalRoomEvents.value = if (pubkeys != null) {
                eventDao.countRootFeedEvents(pubkeys)
            } else {
                eventDao.countRootFeedEventsAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Count query failed: ${e.message}")
        }
    }

    /** Reset exhaustion flag (e.g., after new events are ingested to Room). */
    fun resetExhaustion() {
        _hasOlderInRoom.value = true
    }

    /** Clear state (feed mode switch, sign out, etc.). */
    fun clear() {
        _hasOlderInRoom.value = true
        _totalRoomEvents.value = 0
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private suspend fun queryWindow(olderThanSec: Long, limit: Int): List<CachedEventEntity> {
        val pubkeys = if (followFilterEnabled && followPubkeys.isNotEmpty()) followPubkeys else null
        return if (pubkeys != null) {
            eventDao.getFeedWindow(pubkeys, olderThanSec, limit)
        } else {
            eventDao.getFeedWindowAll(olderThanSec, limit)
        }
    }

    private fun fmtSec(sec: Long): String {
        if (sec == Long.MAX_VALUE) return "MAX"
        if (sec <= 0) return "0"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = sec * 1000 }
        return String.format("%02d/%02d %02d:%02d",
            cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }
}
