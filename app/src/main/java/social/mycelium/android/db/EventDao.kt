package social.mycelium.android.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<CachedEventEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: CachedEventEntity)

    /** Update the stored merged relay URLs for an event. Used when relay merges happen
     *  in-memory so cold-start restoration preserves all relay orbs. */
    @Query("UPDATE cached_events SET relayUrls = :relayUrls WHERE eventId = :eventId")
    suspend fun updateRelayUrls(eventId: String, relayUrls: String)

    /** Feed events (kind-1, kind-6, kind-30023) ordered newest-first. */
    @Query("SELECT * FROM cached_events WHERE kind IN (1, 6, 30023) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getFeedEvents(limit: Int = 500): List<CachedEventEntity>

    /** Oldest cached event timestamp (for DeepHistoryFetcher cursor initialization). */
    @Query("SELECT MIN(createdAt) FROM cached_events WHERE kind IN (1, 6, 30023)")
    suspend fun getOldestFeedEventTimestamp(): Long?

    /** Feed events for specific authors (Following mode). */
    @Query("SELECT * FROM cached_events WHERE kind IN (1, 6, 30023) AND pubkey IN (:pubkeys) ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getFeedEventsForAuthors(pubkeys: List<String>, limit: Int = 500): List<CachedEventEntity>

    /** Paginated feed events older than [untilSec] for specific authors. Used by loadOlderNotes Room-first path. */
    @Query("SELECT * FROM cached_events WHERE kind = 1 AND pubkey IN (:pubkeys) AND createdAt < :untilSec ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOlderFeedEvents(pubkeys: List<String>, untilSec: Long, limit: Int = 500): List<CachedEventEntity>

    /** Paginated feed events older than [untilSec] (all authors). */
    @Query("SELECT * FROM cached_events WHERE kind = 1 AND createdAt < :untilSec ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOlderFeedEventsAll(untilSec: Long, limit: Int = 500): List<CachedEventEntity>

    // ── Windowed feed queries (Room-backed paging) ─────────────────────────

    /** Windowed feed: root posts only for followed authors, ordered newest-first.
     *  Uses the composite (isReply, kind, createdAt) index for efficient filtering.
     *  [olderThanSec]: cursor timestamp — pass Long.MAX_VALUE for initial load.
     *  Returns at most [limit] events older than the cursor. */
    @Query("""
        SELECT * FROM cached_events 
        WHERE isReply = 0 AND kind IN (1, 6, 30023) 
        AND pubkey IN (:pubkeys) AND createdAt < :olderThanSec 
        ORDER BY createdAt DESC LIMIT :limit
    """)
    suspend fun getFeedWindow(pubkeys: List<String>, olderThanSec: Long, limit: Int = 80): List<CachedEventEntity>

    /** Windowed feed: root posts only, all authors (global mode). */
    @Query("""
        SELECT * FROM cached_events 
        WHERE isReply = 0 AND kind IN (1, 6, 30023) 
        AND createdAt < :olderThanSec 
        ORDER BY createdAt DESC LIMIT :limit
    """)
    suspend fun getFeedWindowAll(olderThanSec: Long, limit: Int = 80): List<CachedEventEntity>

    /** Load events NEWER than a timestamp (for scroll-up / prepend). */
    @Query("""
        SELECT * FROM cached_events 
        WHERE isReply = 0 AND kind IN (1, 6, 30023) 
        AND pubkey IN (:pubkeys) AND createdAt > :newerThanSec 
        ORDER BY createdAt ASC LIMIT :limit
    """)
    suspend fun getNewerFeedEvents(pubkeys: List<String>, newerThanSec: Long, limit: Int = 40): List<CachedEventEntity>

    /** Count total root feed events for followed authors (for UI progress indicator). */
    @Query("SELECT COUNT(*) FROM cached_events WHERE isReply = 0 AND kind IN (1, 6, 30023) AND pubkey IN (:pubkeys)")
    suspend fun countRootFeedEvents(pubkeys: List<String>): Int

    /** Count all root feed events (global mode). */
    @Query("SELECT COUNT(*) FROM cached_events WHERE isReply = 0 AND kind IN (1, 6, 30023)")
    suspend fun countRootFeedEventsAll(): Int

    /** Topic events (kind-11). */
    @Query("SELECT * FROM cached_events WHERE kind = 11 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getTopicEvents(limit: Int = 500): List<CachedEventEntity>

    /** Topic comment events (kind-1111) for reply count hydration. */
    @Query("SELECT * FROM cached_events WHERE kind = 1111 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getCommentEvents(limit: Int = 5000): List<CachedEventEntity>

    /** Paginated topic events older than [untilSec]. */
    @Query("SELECT * FROM cached_events WHERE kind = 11 AND createdAt < :untilSec ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getOlderTopicEvents(untilSec: Long, limit: Int = 200): List<CachedEventEntity>

    /** Events by specific IDs. */
    @Query("SELECT * FROM cached_events WHERE eventId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<CachedEventEntity>

    /** Check if an event exists. */
    @Query("SELECT COUNT(*) FROM cached_events WHERE eventId = :eventId")
    suspend fun exists(eventId: String): Int

    /** Delete all events (account switch). */
    @Query("DELETE FROM cached_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_events")
    suspend fun count(): Int

    /** Estimated total size in bytes (length of all stored JSON). */
    @Query("SELECT COALESCE(SUM(LENGTH(eventJson)), 0) FROM cached_events")
    suspend fun totalJsonBytes(): Long

    /** Delete events by kind (e.g. clear only feed events). */
    @Query("DELETE FROM cached_events WHERE kind IN (:kinds)")
    suspend fun deleteByKinds(kinds: List<Int>)

    /** Poll/zap-poll response events referencing a specific poll event. */
    @Query("SELECT * FROM cached_events WHERE kind = :kind AND referencedEventId = :pollId ORDER BY createdAt ASC")
    suspend fun getByKindAndReference(kind: Int, pollId: String): List<CachedEventEntity>

    /** User's own events of a specific kind (e.g. own poll votes). */
    @Query("SELECT * FROM cached_events WHERE kind = :kind AND pubkey = :pubkey ORDER BY createdAt DESC")
    suspend fun getByKindAndPubkey(kind: Int, pubkey: String): List<CachedEventEntity>

    /**
     * Size-based pruning: delete the oldest events beyond [keepCount].
     * Keeps the most recent events by createdAt. Call periodically to
     * cap storage when the device starts running low.
     */
    @Query("DELETE FROM cached_events WHERE eventId NOT IN (SELECT eventId FROM cached_events ORDER BY createdAt DESC LIMIT :keepCount)")
    suspend fun trimToNewest(keepCount: Int)
}
