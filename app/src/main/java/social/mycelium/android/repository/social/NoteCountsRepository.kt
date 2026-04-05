package social.mycelium.android.repository.social

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import social.mycelium.android.repository.cache.ReplyCountCache
import social.mycelium.android.repository.NotificationsRepository

/**
 * Aggregated counts per note: zap count and NIP-25 reactions (kind-7, kind-9735).
 *
 * Uses the **outbox model**: for each visible note, sends kind-7 and kind-9735 filters
 * to the relays where that note was actually seen (+ the note author's NIP-65 read relays
 * when available). This mirrors Amethyst's `filterRepliesAndReactionsToNotes` approach.
 *
 * Reactions and zap receipts are typically stored on the relays the note was published to
 * (the author's write/outbox relays) and the relays where reactors send them (the author's
 * read/inbox relays). By targeting those specific relays, we get accurate counts.
 */
data class NoteCounts(
    /** Total sats zapped to this note (sum of all bolt11 invoices). */
    val zapTotalSats: Long = 0,
    /** Number of distinct zap receipts. */
    val zapCount: Int = 0,
    /** Number of kind-1 replies referencing this note. */
    val replyCount: Int = 0,
    /** Distinct reaction emojis (e.g. ["❤️", "🔥"]); NIP-25 content or "+" as "❤️". */
    val reactions: List<String> = emptyList(),
    /** Pubkeys of authors who reacted, keyed by emoji. */
    val reactionAuthors: Map<String, List<String>> = emptyMap(),
    /** Pubkeys of authors who zapped this note, with their zap amount in sats. */
    val zapAmountByAuthor: Map<String, Long> = emptyMap(),
    /** Pubkeys of authors who zapped this note (ordered by receipt time). */
    val zapAuthors: List<String> = emptyList(),
    /** NIP-30 custom emoji URLs: maps ":shortcode:" to image URL for custom emoji reactions. */
    val customEmojiUrls: Map<String, String> = emptyMap(),
    /** Number of kind-6 reposts (boosts). */
    val repostCount: Int = 0,
    /** Pubkeys of authors who reposted this note (kind-6). */
    val repostAuthors: List<String> = emptyList()
)

object NoteCountsRepository {

    private const val TAG = "NoteCountsRepository"
    private const val DEBOUNCE_MS = 200L

    /** Current user's hex pubkey. Set on login/account switch so own reactions can be detected. */
    @Volatile
    var currentUserPubkey: String? = null

    /**
     * Active engagement filter from the feed UI: null, "replies", "likes", or "zaps".
     * When set, the counts subscription boosts the limit for the corresponding kind
     * and places it first in the filter list so relays return relevant data faster.
     */
    @Volatile
    var activeEngagementFilter: String? = null
        set(value) {
            if (field != value) {
                field = value
                MLog.d(TAG, "Engagement filter changed to: ${value ?: "none"} — retriggering counts")
                retrigger()
            }
        }

    /** Note IDs the current user has boosted (detected from incoming kind-6 events). */
    private val _ownBoostedNoteIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Note IDs the current user has zapped (detected from incoming kind-9735 events). */
    private val _ownZappedNoteIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * Own reaction event IDs: noteId → list of (reactionEventId, emoji).
     * Populated from incoming kind-7 events where author matches [currentUserPubkey],
     * AND from optimistic injection after publishing. Needed for kind-5 deletion.
     */
    private val ownReactionEvents = java.util.concurrent.ConcurrentHashMap<String, MutableList<Pair<String, String>>>()

    /** Check if the current user has boosted a note (from relay data). */
    fun isOwnBoost(noteId: String): Boolean = _ownBoostedNoteIds.contains(noteId)
    /** Register an own boost detected outside the counts pipeline (e.g. main feed kind-6). */
    fun trackOwnBoost(noteId: String) { _ownBoostedNoteIds.add(noteId) }
    /** Check if the current user has zapped a note (from relay data). */
    fun isOwnZap(noteId: String): Boolean = _ownZappedNoteIds.contains(noteId)

    /** Get all own reactions for a note: list of (reactionEventId, emoji). */
    fun getOwnReactions(noteId: String): List<Pair<String, String>> =
        ownReactionEvents[noteId]?.toList() ?: emptyList()

    /** Track an own reaction event (from relay kind-7 or optimistic injection). */
    fun trackOwnReactionEvent(noteId: String, reactionEventId: String, emoji: String) {
        val list = ownReactionEvents.getOrPut(noteId) { java.util.Collections.synchronizedList(mutableListOf()) }
        if (list.none { it.first == reactionEventId }) {
            list.add(reactionEventId to emoji)
        }
    }

    /**
     * Remove an own reaction from counts (optimistic removal after kind-5 deletion).
     * Removes the reaction event from tracking and decrements the reaction author count.
     */
    fun removeOwnReaction(noteId: String, reactionEventId: String, emoji: String) {
        // Remove from own tracking
        ownReactionEvents[noteId]?.removeAll { it.first == reactionEventId }
        // Remove from ReactionsRepository last-reaction
        val remaining = ownReactionEvents[noteId]
        if (remaining.isNullOrEmpty()) {
            ReactionsRepository.clearLastReaction(noteId)
        } else {
            // Set last reaction to most recent remaining
            ReactionsRepository.setLastReaction(noteId, remaining.last().second)
        }
        // Remove from counts
        val snapshot = _countsByNoteId.value.toMutableMap()
        val counts = snapshot[noteId] ?: return
        val authors = counts.reactionAuthors.toMutableMap()
        val emojiAuthors = (authors[emoji] ?: emptyList()).toMutableList()
        currentUserPubkey?.let { emojiAuthors.remove(it) }
        if (emojiAuthors.isEmpty()) {
            authors.remove(emoji)
        } else {
            authors[emoji] = emojiAuthors
        }
        val updatedReactions = authors.keys.toList()
        snapshot[noteId] = counts.copy(reactions = updatedReactions, reactionAuthors = authors)
        _countsByNoteId.value = snapshot
        MLog.d(TAG, "Removed own reaction $emoji on ${noteId.take(8)} (event ${reactionEventId.take(8)})")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) })

    private val _countsByNoteId = MutableStateFlow<Map<String, NoteCounts>>(emptyMap())
    val countsByNoteId: StateFlow<Map<String, NoteCounts>> = _countsByNoteId.asStateFlow()

    /** Feed: noteId → list of relay URLs where that note was seen. */
    @Volatile
    private var feedNoteRelays: Map<String, List<String>> = emptyMap()

    /** Topic: noteId → relay URLs. */
    @Volatile
    private var topicNoteRelays: Map<String, List<String>> = emptyMap()

    /** Thread: noteId → relay URLs. */
    @Volatile
    private var threadNoteRelays: Map<String, List<String>> = emptyMap()

    @Volatile
    private var lastSubscribedNoteIds: Set<String> = emptySet()

    /** Active CybinRelayPool subscription handle for background counts (LOW priority). */
    @Volatile
    private var countsSubscriptionHandle: TemporarySubscriptionHandle? = null

    /** Viewport: top N notes visible in the feed.
     *  These get a dedicated NORMAL-priority subscription that fires immediately
     *  (no debounce) so counts for visible content resolve before off-screen data. */
    @Volatile
    private var viewportNoteRelays: Map<String, List<String>> = emptyMap()
    @Volatile
    private var viewportCountsHandle: TemporarySubscriptionHandle? = null

    /** Debounce job so rapid note-ID changes don't thrash subscriptions. */
    private var debounceJob: Job? = null

    /** Dedup: event IDs we've already processed so relay overlap doesn't double-count. */
    private val processedEventIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** Pending events waiting to be flushed into counts in a single batch. */
    private val pendingCountEvents = ConcurrentLinkedQueue<Event>()
    /** Debounce job for flushing pending count events. */
    private var countsFlushJob: Job? = null
    /** Debounce window: accumulate events for this long before flushing to StateFlow. */
    private const val COUNTS_FLUSH_DEBOUNCE_MS = 80L
    /** Max delay: force flush even if events keep arriving. */
    private const val COUNTS_FLUSH_MAX_DELAY_MS = 300L
    /** Timestamp of first un-flushed event (for max delay enforcement). */
    @Volatile private var firstPendingEventTs = 0L

    /**
     * Merge note IDs + their relay URLs into the feed interest set.
     * Called at ingestion time and display time; accumulates entries.
     * Capped at [MAX_INTEREST_IDS] entries — oldest-inserted evicted first.
     * @param noteRelays map of noteId → list of relay URLs where that note was seen
     * @param replace if true, replaces the entire feed set (use sparingly)
     */
    fun setNoteIdsOfInterest(noteRelays: Map<String, List<String>>, replace: Boolean = false) {
        val merged = if (replace) noteRelays else {
            val m = LinkedHashMap<String, List<String>>(feedNoteRelays)
            m.putAll(noteRelays)
            // Cap to MAX_INTEREST_IDS — evict oldest (first-inserted) entries
            if (m.size > MAX_INTEREST_IDS) {
                val excess = m.size - MAX_INTEREST_IDS
                val iter = m.iterator()
                repeat(excess) { iter.next(); iter.remove() }
            }
            m
        }
        // Skip if the note ID set is unchanged
        if (merged.size == feedNoteRelays.size && merged.keys == feedNoteRelays.keys) return
        MLog.d(TAG, "setNoteIdsOfInterest: ${merged.size} feed notes (${if (replace) "replace" else "+${noteRelays.size} merged"})")
        feedNoteRelays = merged
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the current thread view (replies).
     */
    fun setThreadNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        threadNoteRelays = noteRelays
        scheduleSubscriptionUpdate()
    }

    /**
     * Set note IDs from the topic feed.
     */
    fun setTopicNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        if (noteRelays.size == topicNoteRelays.size && noteRelays.keys == topicNoteRelays.keys) return
        MLog.d(TAG, "setTopicNoteIdsOfInterest: ${noteRelays.size} topic notes")
        topicNoteRelays = noteRelays
        scheduleSubscriptionUpdate()
    }

    private fun scheduleSubscriptionUpdate() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            val snapshot = feedNoteRelays + topicNoteRelays + threadNoteRelays
            if (snapshot.isEmpty()) {
                cancelCountsSubscription()
                lastSubscribedNoteIds = emptySet()
                return@launch
            }
            // Single all-kinds subscription: replies, reactions, zaps, reposts, votes
            // all in one shot — no Phase 1/Phase 2 split, no extra delays.
            updateCountsSubscription(overrideMerged = snapshot)
        }
    }

    /**
     * Set note IDs for the viewport (top of feed, visible to user).
     * Creates a dedicated NORMAL-priority subscription that fires **immediately**
     * without debounce. This ensures counts for visible notes resolve before the
     * background LOW-priority subscription for off-screen content.
     *
     * Called by [NotesRepository.fireEnrichmentForNotes] with the top N notes
     * sorted by timestamp descending.
     */
    fun setViewportNoteIds(noteRelays: Map<String, List<String>>) {
        if (noteRelays.isEmpty()) return
        // Skip if exact same viewport IDs
        if (noteRelays.keys == viewportNoteRelays.keys) return
        viewportNoteRelays = noteRelays
        MLog.d(TAG, "setViewportNoteIds: ${noteRelays.size} viewport notes (NORMAL priority, immediate)")
        updateViewportSubscription()
    }

    /**
     * Create/replace the viewport counts subscription at NORMAL priority.
     * Runs the same filter logic as [updateCountsSubscription] but:
     * - NORMAL priority (vs LOW for background)
     * - No debounce (fires immediately)
     * - Smaller filter set (only viewport IDs)
     */
    private fun updateViewportSubscription() {
        viewportCountsHandle?.cancel()
        val validMerged = viewportNoteRelays.filterKeys { HEX_ID_REGEX.matches(it) }
        if (validMerged.isEmpty()) return

        // Build per-relay note ID groups
        val perRelayNoteIds = mutableMapOf<String, MutableSet<String>>()
        for ((noteId, relayUrls) in validMerged) {
            for (url in relayUrls.ifEmpty { FALLBACK_RELAYS }) {
                perRelayNoteIds.getOrPut(url) { mutableSetOf() }.add(noteId)
            }
        }

        val cappedPerRelay = if (perRelayNoteIds.size > MAX_COUNTS_RELAYS) {
            perRelayNoteIds.entries
                .sortedByDescending { it.value.size }
                .take(MAX_COUNTS_RELAYS)
                .associate { it.key to it.value }
        } else perRelayNoteIds

        // Build per-relay filters — proportional limits for the small viewport set
        val relayFilters = mutableMapOf<String, List<Filter>>()
        for ((relayUrl, noteIds) in cappedPerRelay) {
            val noteIdList = noteIds.toList()
            if (noteIdList.isEmpty()) continue
            val eTags = mapOf("e" to noteIdList)
            relayFilters[relayUrl] = listOf(
                Filter(kinds = listOf(1), tags = eTags, limit = 200),
                Filter(kinds = listOf(6), tags = eTags, limit = 100),
                Filter(kinds = listOf(7), tags = eTags, limit = 200),
                Filter(kinds = listOf(9735), tags = eTags, limit = 100),
                Filter(kinds = listOf(30011), tags = eTags, limit = 200)
            )
        }

        val rsm = RelayConnectionStateMachine.getInstance()
        viewportCountsHandle = rsm.requestTemporarySubscriptionPerRelay(
            relayFilters = relayFilters,
            onEvent = { event ->
                onCountsEvent(event)
                // Cross-pollinate to notifications (same as background subscription)
                if (event.kind == 7 || event.kind == 9735 || event.kind == 6) {
                    NotificationsRepository.ingestEvent(event)
                }
            },
            priority = SubscriptionPriority.NORMAL,
        )
    }

    /**
     * Force re-trigger the counts subscription even if note IDs haven't changed.
     */
    fun retrigger() {
        MLog.d(TAG, "retrigger: forcing counts subscription update")
        lastSubscribedNoteIds = emptySet()
        scheduleSubscriptionUpdate()
    }

    /** Maximum note IDs in the feed interest set; oldest evicted first. */
    private const val MAX_INTEREST_IDS = 300

    /** Pre-compiled regex for validating 64-char hex Nostr event IDs. */
    private val HEX_ID_REGEX = Regex("^[0-9a-f]{64}$")

    /**
     * Update counts subscription via CybinRelayPool (through RelayConnectionStateMachine).
     * Cancels any previous subscription and creates a new one with per-relay filters.
     * All event kinds (replies, reactions, zaps, reposts, votes) in a single subscription.
     *
     * Note: no minimum-new-ID threshold — the batched accumulator
     * ([enqueueNoteIdOfInterest]) already debounces by 500ms, so every flush
     * that reaches here represents a legitimate batch of new IDs.
     */
    private fun updateCountsSubscription(overrideMerged: Map<String, List<String>>? = null) {
        val merged = overrideMerged ?: (feedNoteRelays + topicNoteRelays + threadNoteRelays)
        if (merged.isEmpty()) {
            cancelCountsSubscription()
            lastSubscribedNoteIds = emptySet()
            return
        }
        val mergedIds = merged.keys
        if (mergedIds == lastSubscribedNoteIds) return
        lastSubscribedNoteIds = mergedIds

        // Validate note IDs: must be exactly 64 hex chars (Nostr event ID)
        val validMerged = merged.filterKeys { HEX_ID_REGEX.matches(it) }
        if (validMerged.isEmpty()) return

        // Build per-relay note ID groups
        val perRelayNoteIds = mutableMapOf<String, MutableSet<String>>()
        for ((noteId, relayUrls) in validMerged) {
            for (url in relayUrls.ifEmpty { FALLBACK_RELAYS }) {
                perRelayNoteIds.getOrPut(url) { mutableSetOf() }.add(noteId)
            }
        }
        val allNoteIds = validMerged.keys.take(MAX_INTEREST_IDS).toList()
        for (fallback in FALLBACK_RELAYS) {
            perRelayNoteIds.getOrPut(fallback) { mutableSetOf() }.addAll(allNoteIds)
        }

        // Cap relay fan-out to avoid subscription pressure on too many relays
        val cappedPerRelay = if (perRelayNoteIds.size > MAX_COUNTS_RELAYS) {
            perRelayNoteIds.entries
                .sortedByDescending { it.value.size }
                .take(MAX_COUNTS_RELAYS)
                .associate { it.key to it.value }
        } else perRelayNoteIds

        MLog.d(TAG, "Updating counts sub: ${mergedIds.size} notes across ${cappedPerRelay.size} relays (${perRelayNoteIds.size} total)")

        // Build per-relay Cybin Filter maps.
        // When an engagement filter is active, boost the limit for the relevant kind
        // and place it first so relays prioritize returning that data.
        val engFilter = activeEngagementFilter
        val relayFilters = mutableMapOf<String, List<Filter>>()
        for ((relayUrl, noteIds) in cappedPerRelay) {
            val noteIdList = noteIds.take(MAX_INTEREST_IDS).toList()
            if (noteIdList.isEmpty()) continue
            val eTags = mapOf("e" to noteIdList)
            val filters = when (engFilter) {
                "replies" -> listOf(
                    Filter(kinds = listOf(1), tags = eTags, limit = 800),   // boosted
                    Filter(kinds = listOf(7), tags = eTags, limit = 300),
                    Filter(kinds = listOf(9735), tags = eTags, limit = 150),
                    Filter(kinds = listOf(6), tags = eTags, limit = 150),
                    Filter(kinds = listOf(30011), tags = eTags, limit = 300)
                )
                "likes" -> listOf(
                    Filter(kinds = listOf(7), tags = eTags, limit = 800),   // boosted
                    Filter(kinds = listOf(1), tags = eTags, limit = 300),
                    Filter(kinds = listOf(9735), tags = eTags, limit = 150),
                    Filter(kinds = listOf(6), tags = eTags, limit = 150),
                    Filter(kinds = listOf(30011), tags = eTags, limit = 300)
                )
                "zaps" -> listOf(
                    Filter(kinds = listOf(9735), tags = eTags, limit = 500), // boosted
                    Filter(kinds = listOf(1), tags = eTags, limit = 300),
                    Filter(kinds = listOf(7), tags = eTags, limit = 300),
                    Filter(kinds = listOf(6), tags = eTags, limit = 150),
                    Filter(kinds = listOf(30011), tags = eTags, limit = 300)
                )
                else -> listOf(
                    Filter(kinds = listOf(1), tags = eTags, limit = 500),
                    Filter(kinds = listOf(6), tags = eTags, limit = 200),
                    Filter(kinds = listOf(7), tags = eTags, limit = 500),
                    Filter(kinds = listOf(9735), tags = eTags, limit = 200),
                    Filter(kinds = listOf(30011), tags = eTags, limit = 500)
                )
            }
            relayFilters[relayUrl] = filters
        }

        // Cancel previous subscription, then create a new one via CybinRelayPool
        countsSubscriptionHandle?.cancel()
        val rsm = RelayConnectionStateMachine.getInstance()
        countsSubscriptionHandle = rsm.requestTemporarySubscriptionPerRelay(
            relayFilters = relayFilters,
            onEvent = { event ->
                onCountsEvent(event)
                // Cross-pollinate reactions/zaps/reposts to notifications so they appear
                // in real-time (counts subscription is LOW priority but always active;
                // notification subscription is BACKGROUND and can be preempted).
                // Guard: NotificationsRepository.ingestEvent deduplicates via seenEventIds,
                // so duplicate forwarding is already safe. The real guard is that ingestEvent
                // is a no-op for already-processed events, preventing the feedback loop.
                if (event.kind == 7 || event.kind == 9735 || event.kind == 6) {
                    NotificationsRepository.ingestEvent(event)
                }
            },
            priority = SubscriptionPriority.LOW,
        )
    }

    private fun cancelCountsSubscription() {
        viewportCountsHandle?.cancel()
        viewportCountsHandle = null
        countsSubscriptionHandle?.cancel()
        countsSubscriptionHandle = null
    }

    private val FALLBACK_RELAYS = emptyList<String>()

    /** Max relays to fan out counts subscriptions to. Prevents subscription flood.
     *  Reduced from 10 — each relay takes a slot, and 10 was starving other subs. */
    private const val MAX_COUNTS_RELAYS = 6

    /**
     * Called when a kind-1, kind-7, or kind-9735 event is received from ANY source:
     * counts WebSocket, main feed relay, or thread reply WebSocket.
     * Events are enqueued and flushed in batches to avoid N map copies for N events.
     */
    fun onCountsEvent(event: Event) {
        // Dedup across relays
        if (!processedEventIds.add(event.id)) return
        if (event.kind != 1 && event.kind != 6 && event.kind != 7 && event.kind != 9735 && event.kind != 30011) return
        pendingCountEvents.add(event)
        if (firstPendingEventTs == 0L) firstPendingEventTs = System.currentTimeMillis()
        scheduleCountsFlush()
    }

    /**
     * Lightweight alias for onCountsEvent — call from any live event stream
     * (feed relay, thread WS, etc.) to keep counts in real time.
     */
    fun onLiveEvent(event: Event) = onCountsEvent(event)

    private fun scheduleCountsFlush() {
        countsFlushJob?.cancel()
        countsFlushJob = scope.launch {
            val elapsed = System.currentTimeMillis() - firstPendingEventTs
            val remaining = COUNTS_FLUSH_MAX_DELAY_MS - elapsed
            if (remaining > COUNTS_FLUSH_DEBOUNCE_MS) {
                delay(COUNTS_FLUSH_DEBOUNCE_MS)
            } else if (remaining > 0) {
                delay(remaining)
            }
            // else: max delay exceeded, flush immediately
            flushPendingCounts()
        }
    }

    /**
     * Drain all pending events and apply them to a single mutable snapshot of the counts map.
     * Emits exactly one StateFlow update at the end, regardless of how many events were queued.
     */
    private fun flushPendingCounts() {
        val batch = mutableListOf<Event>()
        while (true) {
            val ev = pendingCountEvents.poll() ?: break
            batch.add(ev)
        }
        firstPendingEventTs = 0L
        if (batch.isEmpty()) return

        val snapshot = _countsByNoteId.value.toMutableMap()
        val changedReplyNoteIds = mutableSetOf<String>()

        for (event in batch) {
            when (event.kind) {
                1 -> applyKind1Reply(event, snapshot, changedReplyNoteIds)
                6 -> applyKind6Repost(event, snapshot)
                7 -> applyKind7(event, snapshot)
                9735 -> applyKind9735(event, snapshot)
                30011 -> applyKind30011Vote(event)
            }
        }

        _countsByNoteId.value = snapshot.toMap()

        // Sync ReplyCountCache for any notes whose reply count changed
        if (changedReplyNoteIds.isNotEmpty()) {
            for (noteId in changedReplyNoteIds) {
                val count = snapshot[noteId]?.replyCount ?: continue
                ReplyCountCache.set(noteId, count)
            }
        }

        MLog.d(TAG, "Flushed ${batch.size} count events (${changedReplyNoteIds.size} reply count updates)")
    }

    private fun applyKind1Reply(event: Event, snapshot: MutableMap<String, NoteCounts>, changedReplyNoteIds: MutableSet<String>) {
        val eTags = event.tags.filter { it.size >= 2 && it.getOrNull(0) == "e" }
        if (eTags.isEmpty()) return

        val markedRoot = eTags.firstOrNull { pickETagMarker(it) == "root" }?.getOrNull(1)
        val markedReply = eTags.firstOrNull { pickETagMarker(it) == "reply" }?.getOrNull(1)

        // Count towards the ROOT note so feed reply counts reflect the total thread
        // size (all replies at any depth), not just direct/depth-1 replies.
        // Also count towards the direct parent when it differs, so thread-internal
        // counts stay accurate for nested replies.
        val rootId: String? = when {
            markedRoot != null -> markedRoot
            else -> eTags.firstOrNull()?.getOrNull(1)
        }
        val directParent: String? = when {
            markedReply != null -> markedReply
            markedRoot != null -> markedRoot // no reply marker = direct reply to root
            else -> eTags.lastOrNull()?.getOrNull(1)
        }

        if (rootId != null) {
            val counts = snapshot[rootId] ?: NoteCounts()
            snapshot[rootId] = counts.copy(replyCount = counts.replyCount + 1)
            changedReplyNoteIds.add(rootId)
        }
        // Also increment the direct parent if it's a different note than root
        if (directParent != null && directParent != rootId) {
            val counts = snapshot[directParent] ?: NoteCounts()
            snapshot[directParent] = counts.copy(replyCount = counts.replyCount + 1)
            changedReplyNoteIds.add(directParent)
        }
    }

    /**
     * Pick "root" or "reply" marker from an "e" tag, checking index 3, then 4, then 2
     * (same order as Amethyst MarkedETag.pickMarker) so we handle both NIP-10 orderings.
     */
    private fun pickETagMarker(tag: Array<out String>): String? {
        val m3 = tag.getOrNull(3)
        if (m3 == "root" || m3 == "reply") return m3
        val m4 = tag.getOrNull(4)
        if (m4 == "root" || m4 == "reply") return m4
        val m2 = tag.getOrNull(2)
        if (m2 == "root" || m2 == "reply") return m2
        return null
    }

    private fun applyKind6Repost(event: Event, snapshot: MutableMap<String, NoteCounts>) {
        // NIP-18: the reposted event id is the last e-tag (not first, which may be the root)
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val authorPubkey = event.pubKey
        // If this is our own boost, track it so the boost icon turns green
        if (authorPubkey == currentUserPubkey && noteId.isNotBlank()) {
            _ownBoostedNoteIds.add(noteId)
        }
        val counts = snapshot[noteId] ?: NoteCounts()
        val authors = counts.repostAuthors.toMutableList()
        if (authorPubkey !in authors) authors.add(authorPubkey)
        snapshot[noteId] = counts.copy(repostCount = authors.size, repostAuthors = authors)
    }

    private fun applyKind7(event: Event, snapshot: MutableMap<String, NoteCounts>) {
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val content = event.content.ifBlank { "+" }
        val emoji = when {
            content == "+" -> "❤️"
            content == "-" -> return // skip downvotes for display
            content.startsWith(":") && content.endsWith(":") -> content // :shortcode:
            content.length <= 4 -> content // single emoji
            else -> content
        }
        val customEmojiUrl: String? = if (emoji.startsWith(":") && emoji.endsWith(":")) {
            val shortcode = emoji.removePrefix(":").removeSuffix(":")
            event.tags.firstOrNull { it.getOrNull(0) == "emoji" && it.getOrNull(1) == shortcode }?.getOrNull(2)
        } else null
        val authorPubkey = event.pubKey
        // If this is our own reaction, populate the emoji button so it persists across cache clears
        ReactionsRepository.populateOwnReaction(noteId, emoji, authorPubkey, currentUserPubkey)
        // Track own reaction event ID for kind-5 deletion support
        if (authorPubkey == currentUserPubkey && noteId.isNotBlank()) {
            trackOwnReactionEvent(noteId, event.id, emoji)
        }
        val counts = snapshot[noteId] ?: NoteCounts()
        val existing = counts.reactions.toMutableSet()
        existing.add(emoji)
        val authors = counts.reactionAuthors.toMutableMap()
        val emojiAuthors = (authors[emoji] ?: emptyList()).toMutableList()
        if (authorPubkey !in emojiAuthors) emojiAuthors.add(authorPubkey)
        authors[emoji] = emojiAuthors
        val emojiUrls = if (customEmojiUrl != null) {
            counts.customEmojiUrls.toMutableMap().also { it[emoji] = customEmojiUrl }
        } else counts.customEmojiUrls
        snapshot[noteId] = counts.copy(reactions = existing.toList(), reactionAuthors = authors, customEmojiUrls = emojiUrls)
    }

    private fun applyKind9735(event: Event, snapshot: MutableMap<String, NoteCounts>) {
        val noteId = event.tags.filter { it.getOrNull(0) == "e" }.lastOrNull()?.getOrNull(1) ?: return
        val senderPubkey = extractZapSenderPubkey(event) ?: event.pubKey
        val amountSats = extractZapAmountSats(event)
        // If this is our own zap, track it so the bolt icon turns yellow
        if (senderPubkey == currentUserPubkey && noteId.isNotBlank()) {
            _ownZappedNoteIds.add(noteId)
        }
        if (amountSats > 0) MLog.d(TAG, "Zap receipt ${event.id.take(8)}: ${amountSats} sats for note ${noteId.take(8)}")
        val counts = snapshot[noteId] ?: NoteCounts()
        val authors = counts.zapAuthors.toMutableList()
        if (senderPubkey !in authors) authors.add(senderPubkey)
        val amountMap = counts.zapAmountByAuthor.toMutableMap()
        amountMap[senderPubkey] = (amountMap[senderPubkey] ?: 0L) + amountSats
        snapshot[noteId] = counts.copy(
            zapCount = counts.zapCount + 1,
            zapTotalSats = counts.zapTotalSats + amountSats,
            zapAuthors = authors,
            zapAmountByAuthor = amountMap
        )
    }

    /**
     * Process a kind-30011 vote event: extract target note ID, voter pubkey, and vote value,
     * then delegate to VoteRepository for aggregation.
     */
    private fun applyKind30011Vote(event: Event) {
        val noteId = event.tags.firstOrNull { it.getOrNull(0) == "e" }?.getOrNull(1) ?: return
        val voterPubkey = event.pubKey
        val voteValue = event.content.trim().toIntOrNull() ?: return
        VoteRepository.applyVoteEvent(noteId, voterPubkey, voteValue, event.createdAt, currentUserPubkey)
    }

    /**
     * Extract the actual zap sender pubkey from a kind-9735 receipt.
     * The receipt's "description" tag contains the original kind-9734 request JSON
     * whose pubKey is the sender.
     */
    private fun extractZapSenderPubkey(event: Event): String? {
        val descJson = event.tags.firstOrNull { it.getOrNull(0) == "description" }?.getOrNull(1) ?: return null
        return try {
            JSONObject(descJson).optString("pubkey").takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract zap amount in sats from a kind-9735 receipt.
     * The "bolt11" tag contains the Lightning invoice; amount is encoded in the invoice prefix.
     * Format: lnbc<amount><multiplier> where multiplier is m(milli), u(micro), n(nano), p(pico).
     */
    private fun extractZapAmountSats(event: Event): Long {
        val bolt11 = event.tags.firstOrNull { it.getOrNull(0) == "bolt11" }?.getOrNull(1)
        if (bolt11 != null) {
            val sats = parseBolt11AmountSats(bolt11)
            if (sats > 0) return sats
            MLog.d(TAG, "bolt11 parsed 0 sats for ${event.id.take(8)}: ${bolt11.take(30)}...")
        }
        // Fallback: NIP-57 kind-9734 request in "description" tag may have "amount" in millisats
        val descJson = event.tags.firstOrNull { it.getOrNull(0) == "description" }?.getOrNull(1)
        if (descJson != null) {
            try {
                val amountMsat = JSONObject(descJson).optLong("amount", 0L)
                if (amountMsat > 0) return amountMsat / 1000
            } catch (_: Exception) {}
        }
        return 0L
    }

    internal fun parseBolt11AmountSats(invoice: String): Long {
        val lower = invoice.lowercase()
        val prefix = when {
            lower.startsWith("lnbcrt") -> "lnbcrt"
            lower.startsWith("lnbc") -> "lnbc"
            lower.startsWith("lntbs") -> "lntbs"
            lower.startsWith("lntb") -> "lntb"
            else -> return 0L
        }
        val afterPrefix = lower.removePrefix(prefix)
        // BOLT11: amount is optional. If invoice starts with separator '1' immediately,
        // there's no amount (e.g. lnbc1... = no amount specified).
        if (afterPrefix.startsWith("1")) return 0L
        // Amount format: <digits>[.<digits>]<multiplier>1<data>
        // multiplier: m=milli, u=micro, n=nano, p=pico, empty=BTC
        // The '1' is the separator between human-readable and data parts.
        val amountRegex = Regex("^(\\d+\\.?\\d*)([munp]?)1")
        val match = amountRegex.find(afterPrefix) ?: return 0L
        val numStr = match.groupValues[1]
        val multiplier = match.groupValues[2]
        // If no multiplier, the number before '1' is BTC — but this is extremely rare.
        // Most zaps use u (micro) or m (milli). Guard against accidental 1 BTC parse.
        val btcAmount = numStr.toDoubleOrNull() ?: return 0L
        val sats = when (multiplier) {
            "m" -> (btcAmount * 100_000).toLong()       // milli-BTC
            "u" -> (btcAmount * 100).toLong()            // micro-BTC
            "n" -> (btcAmount * 0.1).toLong()             // nano-BTC
            "p" -> (btcAmount * 0.0001).toLong()          // pico-BTC
            "" -> (btcAmount * 100_000_000).toLong()      // BTC
            else -> 0L
        }
        return sats.coerceAtLeast(0L)
    }

    // ─── Optimistic injection (publish → instant UI) ───────────────────────────

    /**
     * Optimistically inject our own reaction into the counts so the UI updates
     * immediately after publishing, without waiting for relay echo of kind-7.
     */
    fun injectOwnReaction(noteId: String, emoji: String, authorPubkey: String, customEmojiUrl: String? = null, reactionEventId: String? = null) {
        if (noteId.isBlank() || emoji.isBlank()) return
        // Track event ID for kind-5 deletion support
        if (reactionEventId != null && reactionEventId.isNotBlank()) {
            trackOwnReactionEvent(noteId, reactionEventId, emoji)
        }
        val snapshot = _countsByNoteId.value.toMutableMap()
        val counts = snapshot[noteId] ?: NoteCounts()
        val existing = counts.reactions.toMutableSet()
        existing.add(emoji)
        val authors = counts.reactionAuthors.toMutableMap()
        val emojiAuthors = (authors[emoji] ?: emptyList()).toMutableList()
        if (authorPubkey !in emojiAuthors) emojiAuthors.add(authorPubkey)
        authors[emoji] = emojiAuthors
        val emojiUrls = if (customEmojiUrl != null) {
            counts.customEmojiUrls.toMutableMap().also { it[emoji] = customEmojiUrl }
        } else counts.customEmojiUrls
        snapshot[noteId] = counts.copy(reactions = existing.toList(), reactionAuthors = authors, customEmojiUrls = emojiUrls)
        _countsByNoteId.value = snapshot
        MLog.d(TAG, "Optimistic reaction injected: $emoji on ${noteId.take(8)} by ${authorPubkey.take(8)}")
    }

    /**
     * Optimistically inject our own repost into the counts so the boost count
     * updates immediately after publishing.
     */
    fun injectOwnRepost(noteId: String, authorPubkey: String) {
        if (noteId.isBlank()) return
        val snapshot = _countsByNoteId.value.toMutableMap()
        val counts = snapshot[noteId] ?: NoteCounts()
        val authors = counts.repostAuthors.toMutableList()
        if (authorPubkey !in authors) authors.add(authorPubkey)
        snapshot[noteId] = counts.copy(repostCount = counts.repostCount + 1, repostAuthors = authors)
        _countsByNoteId.value = snapshot
        _ownBoostedNoteIds.add(noteId)
        MLog.d(TAG, "Optimistic repost injected: ${noteId.take(8)} by ${authorPubkey.take(8)}")
    }

    /**
     * Clear all counts and cancel subscription (e.g. on logout).
     */
    fun clear() {
        cancelCountsSubscription()
        debounceJob?.cancel()
        countsFlushJob?.cancel()
        pendingCountEvents.clear()
        firstPendingEventTs = 0L
        feedNoteRelays = emptyMap()
        viewportNoteRelays = emptyMap()
        topicNoteRelays = emptyMap()
        threadNoteRelays = emptyMap()
        lastSubscribedNoteIds = emptySet()
        processedEventIds.clear()
        _countsByNoteId.value = emptyMap()
    }

    /**
     * Force a full reconnect: close all pool connections, clear dedup, and re-subscribe.
     * Use when connections may have died silently.
     */
    fun reconnect() {
        cancelCountsSubscription()
        countsFlushJob?.cancel()
        pendingCountEvents.clear()
        firstPendingEventTs = 0L
        viewportNoteRelays = emptyMap()
        processedEventIds.clear()
        _countsByNoteId.value = emptyMap()
        lastSubscribedNoteIds = emptySet()
        scheduleSubscriptionUpdate()
    }

    // ── Batched interest accumulator ─────────────────────────────────────────
    // Collects individual note ID registrations (from UI LaunchedEffects,
    // QuotedNoteCache depth-2 prefetch, etc.) and flushes them as a single
    // setNoteIdsOfInterest call, collapsing N subscription recreations into 1.

    private val pendingInterestIds = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, List<String>>>()
    private var interestFlushJob: Job? = null
    private const val INTEREST_FLUSH_DEBOUNCE_MS = 500L

    /**
     * Enqueue a single note ID for eventual counts subscription registration.
     * Call this from UI composables and cache callbacks instead of directly calling
     * [setNoteIdsOfInterest], which regenerates the entire subscription.
     *
     * IDs are accumulated and flushed as one batch after [INTEREST_FLUSH_DEBOUNCE_MS],
     * collapsing e.g. 40 individual quoted note resolutions into a single subscription update.
     */
    fun enqueueNoteIdOfInterest(noteId: String, relays: List<String>) {
        if (noteId.isBlank()) return
        pendingInterestIds.add(noteId to relays)
        scheduleInterestFlush()
    }

    /**
     * Enqueue multiple note IDs at once (convenience for batch registrations).
     */
    fun enqueueNoteIdsOfInterest(noteRelays: Map<String, List<String>>) {
        for ((id, relays) in noteRelays) {
            pendingInterestIds.add(id to relays)
        }
        if (noteRelays.isNotEmpty()) scheduleInterestFlush()
    }

    private fun scheduleInterestFlush() {
        interestFlushJob?.cancel()
        interestFlushJob = scope.launch {
            delay(INTEREST_FLUSH_DEBOUNCE_MS)
            val batch = LinkedHashMap<String, List<String>>()
            while (true) {
                val pair = pendingInterestIds.poll() ?: break
                batch[pair.first] = pair.second
            }
            if (batch.isNotEmpty()) {
                MLog.d(TAG, "Interest flush: ${batch.size} accumulated note IDs → single subscription update")
                setNoteIdsOfInterest(batch)
            }
        }
    }
}
