package social.mycelium.android.repository.feed

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.data.Note
import social.mycelium.android.db.CachedEventEntity
import social.mycelium.android.db.EventDao
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Room-backed windowed feed manager. Provides unbounded scroll depth by
 * loading only a sliding window of notes from Room instead of keeping
 * everything in memory.
 *
 * Architecture:
 * ```
 * Relay -> flushKind1Events -> Room (all events persisted)
 *                                |
 *         FeedWindowManager loads window from Room
 *                                |
 *         _windowNotes (80 notes in heap) -> displayed in LazyColumn
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

    /** Follow filter pubkeys -- set by NotesRepository when follow mode changes. */
    @Volatile var followPubkeys: List<String> = emptyList()
    
    /** Whether follow filter is active. */
    @Volatile var followFilterEnabled: Boolean = false

    /** True when a Room page load is in progress. */
    private val _isLoadingPage = MutableStateFlow(false)
    val isLoadingPage: StateFlow<Boolean> = _isLoadingPage.asStateFlow()

    /** Dirty flag: set when Room receives new events, consumed by NotesRepository. */
    private val _roomDirty = AtomicBoolean(false)

    // -- Core operations --------------------------------------------------

    /**
     * Load a page of notes from Room older than [olderThanSec].
     * [floorSec]: lower timestamp bound — events older than this are excluded.
     *  Pass 0 to disable (e.g., initial page load). This prevents ancient events
     *  from sparse relays (like pickle's 11/30 events) contaminating the feed.
     * Returns the converted notes (empty if Room is exhausted).
     *
     * This does NOT manage any window state -- it's a pure Room query.
     * NotesRepository decides what to do with the results (append to
     * _displayedNotes, merge with in-memory data, etc.).
     */
    suspend fun loadPage(olderThanSec: Long, floorSec: Long = 0, limit: Int = pageSize): List<Note> {
        _isLoadingPage.value = true
        try {
            val entities = queryWindow(olderThanSec, floorSec, limit)
            if (entities.isEmpty()) {
                _hasOlderInRoom.value = false
                return emptyList()
            }
            val notes = entities.mapNotNull { entity ->
                try {
                    entityToNote(entity)
                } catch (e: Exception) {
                    MLog.w(TAG, "Skip bad entity ${entity.eventId.take(8)}: ${e.message}")
                    null
                }
            }
            MLog.d(TAG, "loadPage(older than ${fmtSec(olderThanSec)}, floor=${fmtSec(floorSec)}): ${entities.size} entities -> ${notes.size} notes")
            return notes
        } catch (e: Exception) {
            MLog.e(TAG, "loadPage failed: ${e.message}", e)
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
        return loadPage(Long.MAX_VALUE, limit = limit)
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
            MLog.w(TAG, "Count query failed: ${e.message}")
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
        _roomDirty.set(false)
    }

    // ── Room-first freshness ─────────────────────────────────────────────────

    /** Called by NotesRepository after flushEventStore() persists events to Room.
     *  Marks the Room window as stale so the next display update can refresh. */
    fun onRoomDataChanged() {
        _roomDirty.set(true)
        resetExhaustion()
    }

    /** Consume the dirty flag, returning true if Room data changed since last check. */
    fun consumeRoomDirty(): Boolean = _roomDirty.getAndSet(false)

    /**
     * Refresh the newest page from Room. Used after Room data changes to ensure
     * the pagination tail reflects the latest persisted events. Returns notes
     * newer than [newerThanSec] (or all if Long.MIN_VALUE).
     *
     * This is the Room-first "freshness" path: when new events arrive and are
     * persisted, calling this ensures the feed includes them even if they
     * weren't in the original in-memory window.
     */
    suspend fun refreshNewestPage(newerThanSec: Long = Long.MIN_VALUE, limit: Int = pageSize): List<Note> {
        return loadPage(Long.MAX_VALUE, limit = limit)
    }

    /**
     * Week-at-a-time backward reveal: load one week of events older than [olderThanSec].
     * Returns all matching notes in that 7-day window. Used for explicit "load more history"
     * actions rather than continuous pagination.
     *
     * The 7-day window size matches FEED_SINCE_DAYS in NotesRepository so each
     * backward expansion reveals a consistent amount of content.
     */
    suspend fun weekExpand(olderThanSec: Long): List<Note> {
        val weekAgoSec = olderThanSec - (7L * 86400)
        _isLoadingPage.value = true
        try {
            val pubkeys = if (followFilterEnabled && followPubkeys.isNotEmpty()) followPubkeys else null
            // Query events in the [weekAgoSec, olderThanSec) range
            val entities = if (pubkeys != null) {
                eventDao.getFeedWindow(pubkeys, olderThanSec, 200)
            } else {
                eventDao.getFeedWindowAll(olderThanSec, 200)
            }
            // Filter to only events within the week window
            val weekEntities = entities.filter { it.createdAt >= weekAgoSec }
            if (weekEntities.isEmpty()) {
                _hasOlderInRoom.value = entities.isEmpty()
                return emptyList()
            }
            val notes = weekEntities.mapNotNull { entity ->
                try { entityToNote(entity) } catch (e: Exception) {
                    MLog.w(TAG, "Skip bad entity ${entity.eventId.take(8)}: ${e.message}")
                    null
                }
            }
            MLog.d(TAG, "weekExpand(older than ${fmtSec(olderThanSec)}): ${weekEntities.size} entities → ${notes.size} notes (week floor=${fmtSec(weekAgoSec)})")
            return notes
        } catch (e: Exception) {
            MLog.e(TAG, "weekExpand failed: ${e.message}", e)
            return emptyList()
        } finally {
            _isLoadingPage.value = false
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    private suspend fun queryWindow(olderThanSec: Long, floorSec: Long = 0, limit: Int): List<CachedEventEntity> {
        val pubkeys = if (followFilterEnabled && followPubkeys.isNotEmpty()) followPubkeys else null
        return if (pubkeys != null) {
            eventDao.getFeedWindow(pubkeys, olderThanSec, floorSec, limit)
        } else {
            eventDao.getFeedWindowAll(olderThanSec, floorSec, limit)
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
