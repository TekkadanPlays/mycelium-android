package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.db.AppDatabase
import social.mycelium.android.db.CachedEventEntity
import social.mycelium.android.relay.RelayConnectionStateMachine

/**
 * Background fetcher that retrieves the user's complete relay history in batched
 * time windows. Runs at LOW priority so it never competes with the live feed,
 * enrichment, or pagination.
 *
 * ## Design
 *
 * - Walks backward from the oldest note in the current feed in **30-day windows**.
 * - Each window is a single `awaitOneShotSubscription` with a generous timeout.
 * - Events are persisted directly to Room (`cached_events`) so they survive restarts.
 * - Events are NOT injected into the live in-memory feed (would thrash the UI).
 *   They become available via Room-backed pagination (`loadOlderNotes`).
 * - The cursor (`deepFetchCursorSec`) is persisted to SharedPreferences so the
 *   fetch resumes across app restarts without re-fetching already-stored windows.
 * - A **2-second pause** between batches yields CPU/network to the live feed.
 * - Automatically stops when a window returns 0 events (relay history exhausted)
 *   or when we've reached the 5-year floor.
 *
 * ## Usage
 *
 * Called by `StartupOrchestrator.runPhase5DeepHistory` soon after Phase 1 (staggered in nav):
 * ```
 * DeepHistoryFetcher.start(context, pubkey, followList, relayUrls)
 * ```
 */
object DeepHistoryFetcher {

    private const val TAG = "DeepHistoryFetcher"
    private const val PREFS_NAME = "deep_history_prefs"
    private const val KEY_CURSOR_PREFIX = "cursor_sec_"
    private const val KEY_EXHAUSTED_PREFIX = "exhausted_"
    private const val KEY_RELAY_HASH_PREFIX = "relay_hash_"

    /** How far back to fetch (5 years). */
    private const val HISTORY_FLOOR_YEARS = 5
    /** Time window per batch (30 days in seconds). */
    private const val WINDOW_SECONDS = 30L * 86_400L
    /** Max events per batch request. Raised to 1000 for efficiency. */
    private const val BATCH_LIMIT = 1000
    /** Pause between batches to yield CPU/network to the live feed. */
    private const val INTER_BATCH_DELAY_MS = 2_000L
    /** Max time to wait for a single batch to complete. */
    private const val BATCH_TIMEOUT_MS = 15_000L
    /** Settle time: if no new events arrive for this long, close the batch. */
    private const val BATCH_SETTLE_MS = 2_000L

    /** Event kinds that should be replayed to NotificationsRepository after deep fetch.
     *  Only applies to events from p-tag groups (useUserAsPTag=true).
     *  Feed content groups (which fetch kind-1/6 by followed authors) are gated
     *  out at the group level, so kind-1/6 here only covers p-tag results
     *  (replies TO us, reposts OF us). */
    private val NOTIFICATION_KINDS = setOf(1, 6, 7, 8, 16, 1068, 1018, 1111, 1984, 9735, 9802)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True while the fetcher is actively running batches. */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Progress: number of batches completed this session. */
    private val _batchesCompleted = MutableStateFlow(0)
    val batchesCompleted: StateFlow<Int> = _batchesCompleted.asStateFlow()

    /** Total events persisted this session. */
    private val _totalEventsPersisted = MutableStateFlow(0)
    val totalEventsPersisted: StateFlow<Int> = _totalEventsPersisted.asStateFlow()

    /** True when the relay history is exhausted (0 events returned or 5-year floor reached). */
    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    @Volatile private var fetchJob: Job? = null

    /**
     * Start the deep history fetch. Idempotent — calling again while running is a no-op.
     *
     * @param context Application context for Room and SharedPreferences.
     * @param userPubkey The user's hex pubkey.
     * @param followedPubkeys The user's follow list (fetch only followed authors' notes).
     * @param relayUrls Relay URLs to fetch from (user's subscription relays).
     */
    fun start(
        context: Context,
        userPubkey: String,
        followedPubkeys: Set<String>,
        relayUrls: List<String>,
    ) {
        if (_isRunning.value) return
        if (_exhausted.value) {
            Log.d(TAG, "Already exhausted for $userPubkey — skipping")
            return
        }
        if (relayUrls.isEmpty() || followedPubkeys.isEmpty()) {
            Log.w(TAG, "No relays or follows — skipping deep fetch")
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shortKey = userPubkey.take(8)

        // Detect relay set changes — if user added new relays, clear exhaustion
        // so the deep fetch re-runs against the expanded relay set.
        val relayHash = relayUrls.sorted().joinToString(",").hashCode()
        val storedHash = prefs.getInt(KEY_RELAY_HASH_PREFIX + shortKey, 0)
        if (storedHash != 0 && storedHash != relayHash) {
            Log.d(TAG, "Relay set changed (hash $storedHash → $relayHash) — clearing exhaustion")
            prefs.edit()
                .remove(KEY_EXHAUSTED_PREFIX + shortKey)
                .putInt(KEY_RELAY_HASH_PREFIX + shortKey, relayHash)
                .apply()
            _exhausted.value = false
        } else if (storedHash == 0) {
            prefs.edit().putInt(KEY_RELAY_HASH_PREFIX + shortKey, relayHash).apply()
        }

        // Check persisted exhaustion flag
        if (prefs.getBoolean(KEY_EXHAUSTED_PREFIX + shortKey, false)) {
            _exhausted.value = true
            Log.d(TAG, "Persisted exhaustion flag set for $shortKey — skipping")
            return
        }

        _isRunning.value = true
        _batchesCompleted.value = 0
        _totalEventsPersisted.value = 0

        fetchJob = scope.launch {
            try {
                runFetchLoop(context, userPubkey, followedPubkeys, relayUrls)
            } catch (e: Exception) {
                Log.e(TAG, "Deep fetch failed: ${e.message}", e)
            } finally {
                _isRunning.value = false
                Log.d(TAG, "Session ended: ${_batchesCompleted.value} batches, ${_totalEventsPersisted.value} events")
            }
        }
    }

    /** Stop the current fetch session. Safe to call from any thread. */
    fun stop() {
        fetchJob?.cancel()
        fetchJob = null
        _isRunning.value = false
        Log.d(TAG, "Stopped by caller")
    }

    /** Reset the cursor and exhaustion flag for an account (e.g. account switch). */
    fun reset(context: Context, userPubkey: String) {
        stop()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shortKey = userPubkey.take(8)
        prefs.edit()
            .remove(KEY_CURSOR_PREFIX + shortKey)
            .remove(KEY_EXHAUSTED_PREFIX + shortKey)
            .apply()
        _exhausted.value = false
        _batchesCompleted.value = 0
        _totalEventsPersisted.value = 0
        Log.d(TAG, "Reset cursor for $shortKey")
    }

    /**
     * Kind groups fetched per 30-day window. Each group is a separate subscription
     * to avoid overwhelming relays with a single massive filter.
     *
     * @param label Human-readable label for logging.
     * @param kinds Nostr event kinds to fetch.
     * @param useFollowedAuthors If true, filter by followedPubkeys. If false, filter by userPubkey only (e.g. DMs, own events).
     * @param useUserAsPTag If true, use userPubkey in a p-tag filter (e.g. zaps to me, mentions of me).
     */
    private data class KindGroup(
        val label: String,
        val kinds: List<Int>,
        val useFollowedAuthors: Boolean = true,
        val useUserAsPTag: Boolean = false,
    )

    /** All kind groups to fetch per window. Order matters — most important first. */
    private val KIND_GROUPS = listOf(
        // ── Content groups (by followed authors → feed) ─────────────────
        KindGroup("feed",       listOf(1, 6, 30023)),           // Notes, reposts, long-form articles
        KindGroup("topics",     listOf(11)),                     // NIP-22 topic roots
        KindGroup("comments",   listOf(1111)),                   // NIP-22 thread comments
        KindGroup("polls",      listOf(1068, 6969)),             // NIP-88 polls + zap polls

        // ── Notification groups (p-tag = user → notifications) ─────────
        KindGroup("reactions",  listOf(7),                       // NIP-25 reactions/likes TO the user
            useFollowedAuthors = false, useUserAsPTag = true),
        KindGroup("zaps",       listOf(9735),                    // NIP-57 zap receipts TO the user
            useFollowedAuthors = false, useUserAsPTag = true),
        KindGroup("reposts-of-me", listOf(6, 16),               // Reposts OF the user's notes
            useFollowedAuthors = false, useUserAsPTag = true),
        KindGroup("replies-mentions", listOf(1),                 // Replies/mentions/quotes TO the user
            useFollowedAuthors = false, useUserAsPTag = true),
        KindGroup("badge-awards", listOf(8),                     // NIP-58 badge awards TO the user
            useFollowedAuthors = false, useUserAsPTag = true),
        KindGroup("highlights", listOf(9802),                    // NIP-84 highlights referencing the user
            useFollowedAuthors = false, useUserAsPTag = true),

        // ── Metadata groups (by followed authors) ──────────────────────
        KindGroup("badges-defs", listOf(30009)),                 // NIP-58 badge definitions
    )

    private suspend fun runFetchLoop(
        context: Context,
        userPubkey: String,
        followedPubkeys: Set<String>,
        relayUrls: List<String>,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shortKey = userPubkey.take(8)
        val eventDao = AppDatabase.getInstance(context.applicationContext).eventDao()
        val stateMachine = RelayConnectionStateMachine.getInstance()

        // 5-year floor in unix seconds
        val floorSec = (System.currentTimeMillis() / 1000) - (HISTORY_FLOOR_YEARS * 365L * 86_400L)

        // Resume from persisted cursor, or start from the oldest note in the feed
        var cursorSec = prefs.getLong(KEY_CURSOR_PREFIX + shortKey, 0L)
        if (cursorSec == 0L) {
            val oldestInRoom = eventDao.getOldestFeedEventTimestamp()
            cursorSec = oldestInRoom ?: (System.currentTimeMillis() / 1000)
            Log.d(TAG, "Starting deep fetch from ${formatSec(cursorSec)} (oldest in Room)")
        } else {
            Log.d(TAG, "Resuming deep fetch from ${formatSec(cursorSec)} (persisted cursor)")
        }

        val followedAuthors = followedPubkeys.toList()
        var batchCount = 0
        var consecutiveEmptyWindows = 0

        while (cursorSec > floorSec) {
            val windowStart = maxOf(cursorSec - WINDOW_SECONDS, floorSec)
            var windowTotalEvents = 0
            var oldestEventInBatch = cursorSec

            Log.d(TAG, "Window ${formatSec(windowStart)} → ${formatSec(cursorSec)} — fetching ${KIND_GROUPS.size} kind groups")

            // Fetch each kind group for this time window
            for (group in KIND_GROUPS) {

                val filter = when {
                    group.useUserAsPTag -> Filter(
                        kinds = group.kinds,
                        since = windowStart,
                        until = cursorSec,
                        limit = BATCH_LIMIT,
                        tags = mapOf("p" to listOf(userPubkey))
                    )
                    group.useFollowedAuthors -> Filter(
                        kinds = group.kinds,
                        authors = followedAuthors,
                        since = windowStart,
                        until = cursorSec,
                        limit = BATCH_LIMIT
                    )
                    else -> Filter(
                        kinds = group.kinds,
                        authors = listOf(userPubkey),
                        since = windowStart,
                        until = cursorSec,
                        limit = BATCH_LIMIT
                    )
                }

                val events = mutableListOf<Pair<Event, String>>()
                try {
                    stateMachine.awaitOneShotSubscription(
                        relayUrls = relayUrls,
                        filter = filter,
                        priority = SubscriptionPriority.LOW,
                        settleMs = BATCH_SETTLE_MS,
                        maxWaitMs = BATCH_TIMEOUT_MS,
                    ) { event ->
                        events.add(event to "")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "${group.label}: subscription failed: ${e.message}")
                }

                if (events.isNotEmpty()) {
                    val entities = events.map { (event, relayUrl) ->
                        CachedEventEntity(
                            eventId = event.id,
                            kind = event.kind,
                            pubkey = event.pubKey,
                            createdAt = event.createdAt,
                            eventJson = event.toJson(),
                            relayUrl = relayUrl.ifEmpty { null },
                        )
                    }
                    try {
                        eventDao.insertAll(entities)
                    } catch (e: Exception) {
                        Log.e(TAG, "${group.label}: Room insert failed: ${e.message}")
                    }

                    // Replay notification-relevant events so they populate the notifications tab.
                    // Use ingestEventAsHistorical() so events are auto-marked as seen — they
                    // populate the list for browsing but do NOT increment the badge or trigger
                    // system notifications. This prevents badge flicker during deep fetch.
                    //
                    // ONLY replay events from p-tag groups (useUserAsPTag=true).
                    // Feed content groups fetch by followed-author — those events will never
                    // pass the p-tag relevance check in NotificationsRepository, so replaying
                    // them is pure CPU waste (hundreds of events checked and immediately dropped).
                    if (group.useUserAsPTag) {
                        var notifCount = 0
                        for ((event, _) in events) {
                            if (event.kind in NOTIFICATION_KINDS) {
                                try {
                                    NotificationsRepository.ingestEventAsHistorical(event)
                                    notifCount++
                                } catch (e: Exception) {
                                    // Don't let a single bad event kill the whole batch
                                }
                            }
                        }
                        if (notifCount > 0) {
                            Log.d(TAG, "  ${group.label}: replayed $notifCount events to notifications")
                        }
                    }

                    windowTotalEvents += events.size
                    _totalEventsPersisted.value += events.size
                    
                    if (events.size >= BATCH_LIMIT) {
                        val oldestInGroup = events.minOfOrNull { it.first.createdAt } ?: oldestEventInBatch
                        oldestEventInBatch = minOf(oldestEventInBatch, oldestInGroup)
                        Log.d(TAG, "  ${group.label}: hit limit ${events.size}, oldest seen is ${formatSec(oldestInGroup)}")
                    } else {
                        Log.d(TAG, "  ${group.label}: ${events.size} events")
                    }
                }

                batchCount++
                _batchesCompleted.value = batchCount

                // Short yield between kind groups within the same window
                delay(500L)
            }

            Log.d(TAG, "Window complete: ${windowTotalEvents} events persisted (total: ${_totalEventsPersisted.value})")

            if (windowTotalEvents == 0) {
                consecutiveEmptyWindows++
                if (cursorSec - WINDOW_SECONDS <= floorSec) {
                    Log.d(TAG, "Reached 5-year floor with 0 events — exhausted")
                    markExhausted(prefs, shortKey)
                    break
                }
                if (consecutiveEmptyWindows >= 6) {
                    Log.d(TAG, "6 consecutive empty windows (6 months) with 0 events — exhausted")
                    markExhausted(prefs, shortKey)
                    break
                }
            } else {
                consecutiveEmptyWindows = 0
            }

            // Advance cursor
            // If we hit the overflow limit in any group, oldestEventInBatch will be < cursorSec but > windowStart.
            // We step the cursor precisely to the oldest event retrieved to ensure perfectly continuous coverage
            // without dropping events in dense periods.
            cursorSec = if (oldestEventInBatch < cursorSec && oldestEventInBatch > windowStart) {
                oldestEventInBatch
            } else {
                windowStart
            }
            saveCursor(prefs, shortKey, cursorSec)

            if (cursorSec <= floorSec) {
                Log.d(TAG, "Reached 5-year floor — exhausted")
                markExhausted(prefs, shortKey)
                break
            }

            // Longer yield between windows to keep the app responsive
            delay(INTER_BATCH_DELAY_MS)
        }
    }

    private fun saveCursor(prefs: android.content.SharedPreferences, shortKey: String, cursorSec: Long) {
        prefs.edit().putLong(KEY_CURSOR_PREFIX + shortKey, cursorSec).apply()
    }

    private fun markExhausted(prefs: android.content.SharedPreferences, shortKey: String) {
        _exhausted.value = true
        prefs.edit().putBoolean(KEY_EXHAUSTED_PREFIX + shortKey, true).apply()
    }

    private fun formatSec(sec: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date(sec * 1000))
    }
}
