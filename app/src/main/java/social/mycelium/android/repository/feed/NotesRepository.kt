package social.mycelium.android.repository.feed

import android.content.Context
import social.mycelium.android.debug.MLog
import social.mycelium.android.data.Note
import social.mycelium.android.data.Author
import social.mycelium.android.data.PublishState
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.cache.ThreadReplyCache
import social.mycelium.android.utils.EventRelayTracker
import social.mycelium.android.utils.Nip10ReplyDetector
import social.mycelium.android.utils.Nip19QuoteParser
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.extractPubkeysFromContent
import social.mycelium.android.utils.extractPubkeysWithHintsFromContent
import social.mycelium.android.utils.normalizeAuthorIdForCache
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.BuildConfig
import social.mycelium.android.db.AppDatabase
import social.mycelium.android.db.CachedEventEntity
import com.example.cybin.core.Event
import com.example.cybin.relay.RelayUrlNormalizer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import social.mycelium.android.debug.PipelineDiagnostics
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.cache.QuotedNoteCache
import social.mycelium.android.repository.relay.Nip65RelayListRepository
import social.mycelium.android.repository.relay.RelayStorageManager
import social.mycelium.android.repository.social.ContactListRepository
import social.mycelium.android.repository.social.PeopleListRepository
import social.mycelium.android.repository.social.NoteCountsRepository
import social.mycelium.android.repository.content.TopicRepliesRepository

/** Separate counts of pending new notes for All vs Following. Nonce ensures StateFlow always emits on update. */
data class NewNotesCounts(val all: Int, val following: Int, val nonce: Long = 0L)

/** Debug-only: session counts of event content (md, img, vid, gif, imeta, emoji) for in-app event stats. */
data class DebugEventStatsSnapshot(
    val total: Int,
    val mdCount: Int,
    val imgCount: Int,
    val vidCount: Int,
    val gifCount: Int,
    val imetaCount: Int,
    val emojiCount: Int
) {
    fun mdPct(): Int = if (total == 0) 0 else (mdCount * 100 / total)
    fun imgPct(): Int = if (total == 0) 0 else (imgCount * 100 / total)
    fun vidPct(): Int = if (total == 0) 0 else (vidCount * 100 / total)
    fun gifPct(): Int = if (total == 0) 0 else (gifCount * 100 / total)
    fun imetaPct(): Int = if (total == 0) 0 else (imetaCount * 100 / total)
    fun emojiPct(): Int = if (total == 0) 0 else (emojiCount * 100 / total)
}

/** Lightweight feed session lifecycle so UI and re-subscribe logic don't fight (e.g. return from notifications vs refresh). */
enum class FeedSessionState { Idle, Loading, Live, Refreshing }

/**
 * Repository for fetching and managing Nostr notes using the shared RelayConnectionStateMachine.
 * Does not own a NostrClient; uses requestFeedChange so switching feeds only updates subscription (no full reconnect).
 *
 * **Singleton**: only one kind-1 handler is ever registered. Dashboard uses this single instance.
 *
 * **Subscription ownership**: This repo is the single owner of the main kind-1 subscription. It sets
 * [RelayConnectionStateMachine.resumeSubscriptionProvider] so on app resume the state machine re-applies
 * (relayUrls, kind1Filter) without clearing the feed. When the user returns to the dashboard with notes already
 * loaded, the UI should call [setDisplayFilterOnly] only (not [loadNotesFromFavoriteCategory]) to avoid
 * re-requesting the subscription and screwing up the feed. Pull-to-refresh calls [applyPendingNotes] and
 * requestRetry(); it does not re-subscribe. [refresh] re-subscribes for a full re-fetch.
 */
class NotesRepository private constructor() {

    private val relayStateMachine = RelayConnectionStateMachine.getInstance()
    private val profileCache = ProfileMetadataCache.getInstance()
    private val topicRepliesRepo = TopicRepliesRepository.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) })

    private var cacheRelayUrls = listOf<String>()

    /** Current user's hex pubkey (lowercase) for immediate own-event display. */
    @Volatile
    private var currentUserPubkey: String? = null

    /** Pending kind-1 events waiting to be flushed into the notes list in a single batch.
     *  PendingKind1Event: event, relayUrl, isPagination (bypasses age gate), isOutbox (bypasses cutoff gate). */
    data class PendingKind1Event(val event: Event, val relayUrl: String, val isPagination: Boolean, val isOutbox: Boolean = false)
    private val pendingKind1Events = ConcurrentLinkedQueue<PendingKind1Event>()
    /** Dirty flag: set atomically by event producers, cleared by the flush poller.
     *  Replaces the old debounce timer — no Jobs to cancel, no events lost. */
    private val kind1Dirty = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Pending relay URLs seen in feed notes that need NIP-11 preload. Debounced to avoid per-event overhead. */
    private val pendingNip11Urls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var nip11PreloadJob: Job? = null
    private val NIP11_PRELOAD_DEBOUNCE_MS = 2000L
    // ── Adaptive batching: relay-count-aware chunk sizing ───────────────────
    // More relays = more concurrent event delivery = need larger batches.
    // Base values are for ≤4 relays. Each additional relay adds ~10% capacity.

    /** Base steady-state chunk size (≤4 relays). */
    private val BASE_STEADY_CHUNK = 50
    /** Base burst chunk size (≤4 relays). */
    private val BASE_BURST_CHUNK = 500
    /** Base enrichment drain threshold (≤4 relays). */
    private val BASE_DRAIN_THRESHOLD = 200

    /** Compute adaptive steady-state chunk size based on relay count.
     *  Scales: 50 at 4 relays → 75 at 10 → 125 at 20 → caps at 200. */
    private fun steadyStateChunkSize(): Int {
        val relayCount = subscriptionRelays.size.coerceAtLeast(1)
        return (BASE_STEADY_CHUNK * (1.0 + (relayCount - 4).coerceAtLeast(0) * 0.10)).toInt().coerceIn(BASE_STEADY_CHUNK, 200)
    }

    /** Compute adaptive burst chunk size based on relay count.
     *  Scales: 500 at 4 relays → 750 at 10 → 1250 at 20 → caps at 2000. */
    private fun burstChunkSize(): Int {
        val relayCount = subscriptionRelays.size.coerceAtLeast(1)
        return (BASE_BURST_CHUNK * (1.0 + (relayCount - 4).coerceAtLeast(0) * 0.10)).toInt().coerceIn(BASE_BURST_CHUNK, 2000)
    }

    /** Compute adaptive drain threshold based on relay count.
     *  Scales: 200 at 4 relays → 300 at 10 → 500 at 20 → caps at 800. */
    private fun enrichmentDrainThreshold(): Int {
        val relayCount = subscriptionRelays.size.coerceAtLeast(1)
        return (BASE_DRAIN_THRESHOLD * (1.0 + (relayCount - 4).coerceAtLeast(0) * 0.10)).toInt().coerceIn(BASE_DRAIN_THRESHOLD, 800)
    }

    /** Notes awaiting enrichment (counts/quotes/URL previews). Accumulated across flush cycles
     *  during burst ingestion and fired once when the queue drains below the drain threshold. */
    private val deferredEnrichmentNotes = java.util.concurrent.ConcurrentLinkedQueue<Note>()
    /** During burst ingestion, throttle _notes.value StateFlow emissions to prevent
     *  Compose snapshot churn. Only emit if 200ms has passed since the last write. */
    private val BURST_EMISSION_THROTTLE_MS = 200L
    /** Shadow list: accumulates merged notes during burst without emitting to StateFlow.
     *  Flushed to _notes.value when the throttle window opens or the burst ends. */
    @Volatile private var burstShadowNotes: List<Note>? = null
    /** Timestamp of the last _notes.value emission — used to enforce BURST_EMISSION_THROTTLE_MS. */
    @Volatile private var lastEmissionMs = 0L

    // ── Viewport-aware enrichment ────────────────────────────────────────────
    /** Number of notes treated as "viewport" for prioritized enrichment.
     *  These notes get NORMAL-priority counts subscriptions and are enriched
     *  before off-screen content. Covers ~2 screens of typical card height. */
    private val VIEWPORT_ENRICHMENT_SIZE = 15
    /** Delay (ms) before firing background enrichment after viewport enrichment.
     *  Gives viewport subscriptions time to claim relay slots before background
     *  content competes for the same slots. */
    private val BACKGROUND_ENRICHMENT_DELAY_MS = 100L

    private val outboxFeedManager = OutboxFeedManager.getInstance()
    private val globalFeedManager = GlobalFeedManager.getInstance()

    init {
        if (BuildConfig.DEBUG) {
            MLog.i("MyceliumEvent", "Monitor enabled: kind-1 events will be logged here. Run: adb logcat -s MyceliumEvent")
            MLog.i(TAG, "MyceliumEvent monitor enabled (debug). Use logcat -s MyceliumEvent or -s NotesRepository")
        }
        relayStateMachine.registerKind1Handler { event, relayUrl ->
            // Lock-free: enqueue and signal the poller. Never blocks, never drops.
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false))
            kind1Dirty.set(true)
        }
        relayStateMachine.registerKind6Handler { event, relayUrl ->
            // Queue into the same pipeline as kind-1 events. The flush poller
            // processes them in batch under a SINGLE mutex acquisition, eliminating
            // 150+ individual coroutine launches + mutex contentions per burst.
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false))
            kind1Dirty.set(true)
        }
        // Wire outbox feed events into the same ingestion pipeline
        // Mark as isOutbox=true so they bypass the cutoff gate and go directly to feed.
        outboxFeedManager.onNoteReceived = { event, relayUrl ->
            MLog.d(TAG, "\uD83D\uDCE8 Outbox event: ${event.id.take(8)} from $relayUrl (kind=${event.kind})")
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false, isOutbox = true))
            kind1Dirty.set(true)
        }
        // Wire global feed enrichment events (hashtag/list indexer subscriptions) into the pipeline.
        globalFeedManager.onNoteReceived = { event, relayUrl ->
            MLog.d(TAG, "\uD83C\uDF0D Global enrichment event: ${event.id.take(8)} from $relayUrl")
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false, isOutbox = false))
            kind1Dirty.set(true)
        }
        startProfileUpdateCoalescer()
        startKind1FlushPoller()
    }

    /**
     * Start outbox-aware feed: discover followed users' write relays via NIP-65
     * and subscribe to them for kind-1 notes we'd otherwise miss.
     * Call after the main feed subscription is active and follow list is loaded.
     *
     * @param followedPubkeys The user's follow list (hex pubkeys)
     * @param indexerRelayUrls Indexer relay URLs for NIP-65 discovery
     */
    fun startOutboxFeed(followedPubkeys: Set<String>, indexerRelayUrls: List<String>) {
        if (!followFilterEnabled || followedPubkeys.isEmpty()) {
            MLog.d(TAG, "Outbox feed skipped: followFilterEnabled=$followFilterEnabled, follows=${followedPubkeys.size}")
            return
        }
        outboxFeedManager.start(
            followedPubkeys = followedPubkeys,
            indexerRelayUrls = indexerRelayUrls,
            inboxRelayUrls = subscriptionRelays
        )
    }

    /**
     * Update global enrichment subscriptions (hashtags + list members on indexer relays).
     * Call from the dashboard when the user changes hashtag/list selections while in Global mode.
     * No-op when not in global mode.
     *
     * @param hashtags  Active hashtags to subscribe to on indexers (from kind-10015 or dropdown)
     * @param listDTags Active people list d-tags (their pubkeys will be used as author filter)
     */
    fun updateGlobalEnrichment(hashtags: Set<String>, listDTags: Set<String>) {
        if (!isGlobalMode) return
        if (hashtags.isEmpty() && listDTags.isEmpty()) {
            globalFeedManager.stop()
        } else {
            globalFeedManager.start(hashtags = hashtags, listDTags = listDTags)
        }
    }

    /**
     * Data-driven flush poller. Runs once for the lifetime of the repository.
     * Checks for pending events and processes bounded chunks. No timer cancellation,
     * no event loss — if enqueuers set kind1Dirty, the next poll iteration picks it up.
     *
     * Idle: polls every 100ms (sleeping, no CPU cost).
     * Burst (queue > adaptive drain threshold): processes large chunks (relay-scaled),
     *   defers enrichment, uses adaptive inter-flush delay to drain fast.
     * Steady-state: processes small chunks (relay-scaled), fires enrichment
     *   immediately, yields one frame (~16ms) between chunks.
     */
    private fun startKind1FlushPoller() {
        scope.launch {
            while (true) {
                if (kind1Dirty.getAndSet(false) || pendingKind1Events.isNotEmpty()) {
                    flushKind1Events()
                    // Adaptive inter-flush delay: tight during burst, relaxed in steady-state
                    val queueSize = pendingKind1Events.size
                    if (queueSize > enrichmentDrainThreshold()) {
                        // Adaptive burst delay: scales down with relay count
                        // More relays → more events → need tighter drain loop
                        val burstDelay = if (subscriptionRelays.size > 10) 2L else 4L
                        delay(burstDelay)
                    } else {
                        // Queue just drained below threshold — flush burst shadow
                        val shadow = burstShadowNotes
                        if (shadow != null) {
                            burstShadowNotes = null
                            _notes.value = shadow.toImmutableList()
                            lastEmissionMs = System.currentTimeMillis()
                            MLog.d(TAG, "Burst ended: flushed shadow list (${shadow.size} notes)")
                        }
                        // Fire accumulated enrichment
                        if (deferredEnrichmentNotes.isNotEmpty()) {
                            fireDeferredEnrichment()
                        }
                        delay(16) // Steady-state: yield a frame for Compose
                    }
                } else {
                    // Idle — no data to process. Sleep longer to save battery.
                    delay(100)
                }
            }
        }
    }

    /**
     * Drain all pending kind-1 events, convert to Notes, deduplicate, and merge into the
     * notes list with a single sort + emit. This replaces the old per-event mutex+sort pattern.
     *
     * Runs under [processEventMutex] to serialize against [handleKind6Repost] and
     * [updateAuthorsInNotesBatch], which also mutate [feedIndex] (a plain HashMap).
     * Without this lock, concurrent reads/writes to feedIndex.byId would cause
     * ConcurrentModificationException.
     */
    private suspend fun flushKind1Events() {
        // Adaptive chunk sizing: scales with relay count. More relays = larger batches.
        // Burst during initial load or when queue is backing up; steady-state otherwise.
        val drainThreshold = enrichmentDrainThreshold()
        val chunkSize = if (!initialLoadComplete || pendingKind1Events.size > drainThreshold) {
            burstChunkSize()
        } else {
            steadyStateChunkSize()
        }
        val batch = mutableListOf<PendingKind1Event>()
        var drained = 0
        while (drained < chunkSize) {
            val item = pendingKind1Events.poll() ?: break
            batch.add(item)
            drained++
        }
        if (batch.isEmpty()) return

        // Queue raw events for Room persistence (before filtering — we want ALL events stored).
        // Event objects are queued as-is; toJson() runs on background IO thread during flush.
        if (eventDao != null && !isGlobalMode) {
            for (item in batch) {
                if (pendingEventObjects.size < EVENT_STORE_QUEUE_CAP) {
                    pendingEventObjects.add(Triple(item.event, item.relayUrl, Unit))
                }
            }
            scheduleEventStoreFlush()
        }

        val flushStartMs = System.currentTimeMillis()
        android.os.Trace.beginSection("NotesRepo.flushKind1Events(${batch.size})")
        processEventMutex.withLock {
        try {
            // Convert all events to Notes (no lock needed — convertEventToNote is stateless except profile cache)
            val newNotes = mutableListOf<Note>()
            val newNoteIds = HashSet<String>() // O(1) dedup within batch
            val relayUpdates = mutableMapOf<String, List<String>>() // noteId -> merged relayUrls
            val currentNotes = _notes.value
            // O(1) lookups from persistent FeedIndex — replaces per-flush O(n) rebuilds
            val currentIds = feedIndex.byId
            val pendingIds = synchronized(pendingNotesLock) { pendingIndex.ids.toSet() }
            // Set of original note IDs that are already represented as reposts (for fast dedup)
            val repostedOriginalIds = buildSet(feedIndex.repostedOriginalIds.size + synchronized(pendingNotesLock) { pendingIndex.repostOriginals.size }) {
                addAll(feedIndex.repostedOriginalIds)
                synchronized(pendingNotesLock) { addAll(pendingIndex.repostOriginals) }
            }
            // Kind 30023 (NIP-23 articles) are parameterized replaceable: same author + d-tag = same article.
            // Track existing articles by author:dTag so updated versions replace older ones.
            val existingArticleKeys = feedIndex.articleKeys.toMutableMap()

            // Age gate: events from the main subscription older than the feed floor are
            // silently dropped. Only loadOlderNotes (isPagination=true) may introduce older
            // notes. This prevents relays that ignore `since` from contaminating the feed
            // with ancient notes that corrupt the pagination cursor.
            val ageFloorMs = feedAgeFloorMs
            // Hard absolute floor: always reject events older than 14 days regardless of
            // mainSubscriptionFloorMs init state. This is the safety net for the race where
            // events arrive before the subscription sets the floor (e.g. late prewarm echo).
            val absoluteFloorMs = System.currentTimeMillis() - 14L * 86_400_000L
            var ageGateDropped = 0
            // Track which note IDs came from outbox events so they bypass the cutoff gate
            val outboxNoteIds = HashSet<String>()

            // Diagnostic: count duplicate event IDs in this batch (same event from different relays)
            val batchEventIds = batch.filter { it.event.kind in listOf(1, 11, 1111, 1068, 6969, 30023) }
            val uniqueEventIds = batchEventIds.map { it.event.id }.toSet()
            val duplicateCount = batchEventIds.size - uniqueEventIds.size
            if (batchEventIds.isNotEmpty()) {
                val relayDistribution = batchEventIds.groupBy { it.relayUrl }.mapValues { it.value.size }
                MLog.d(TAG, "\uD83D\uDD35 BATCH: ${batchEventIds.size} events, ${uniqueEventIds.size} unique, $duplicateCount dupes, relays=${relayDistribution.entries.joinToString { "${it.key.takeLast(25)}=${it.value}" }}")
            }

            // Separate kind-6/16 reposts for batch processing after kind-1
            val kind6Batch = mutableListOf<PendingKind1Event>()
            for (pending in batch) {
                val event = pending.event
                val relayUrl = pending.relayUrl
                val isPagination = pending.isPagination
                val isOutbox = pending.isOutbox
                // Route kind-6/16 reposts to batch handler (processed below, same mutex)
                // Apply age gate to reposts too — use the repost timestamp (event.createdAt),
                // not the embedded original note's timestamp, since that's when the boost happened.
                if (event.kind == 6 || event.kind == 16) {
                    val repostMs = event.createdAt * 1000L
                    // Hard floor: reject non-pagination reposts older than 14 days
                    if (!isPagination && repostMs < absoluteFloorMs) { ageGateDropped++; continue }
                    if (!isPagination && mainSubscriptionFloorMs > 0L && repostMs < mainSubscriptionFloorMs) {
                        ageGateDropped++
                        continue
                    }
                    if (isPagination && ageFloorMs > 0L && repostMs < ageFloorMs) {
                        ageGateDropped++
                        continue
                    }
                    kind6Batch.add(pending)
                    continue
                }
                if (event.kind != 1 && event.kind != 30023 && event.kind != 1068 && event.kind != 6969) continue
                // Accumulate relay URL in tracker — builds up across duplicate deliveries
                if (relayUrl.isNotBlank()) EventRelayTracker.addRelay(event.id, relayUrl)

                // Age gate: separate floors for live vs pagination events.
                // Live subscription uses the fixed mainSubscriptionFloorMs so pagination
                // can't widen the window for the main feed. Pagination events use the
                // progressively-lowered feedAgeFloorMs.
                // Outbox events use the same mainSubscriptionFloorMs as live events —
                // they represent recent notes from followed users' write relays, not
                // historical content. Ancient events from outbox relays that ignore
                // `since` are dropped here.
                val eventMs = event.createdAt * 1000L
                // Hard floor: reject non-pagination events older than 14 days
                if (!isPagination && eventMs < absoluteFloorMs) { ageGateDropped++; continue }
                if (!isPagination && mainSubscriptionFloorMs > 0L && eventMs < mainSubscriptionFloorMs) {
                    ageGateDropped++
                    continue
                }
                if (isPagination && ageFloorMs > 0L && eventMs < ageFloorMs) {
                    ageGateDropped++
                    continue
                }

                if (isOutbox) {
                    outboxNoteIds.add(event.id)
                    outboxReceivedNoteIds.add(event.id)
                }

                // ── Pre-conversion dedup: all checks use event.id (no regex) ──
                // Normalize relay URL once (cheap string op — no regex, no Bech32)
                // This replaces the full convertEventToNote call for duplicate events.
                val newUrl = relayUrl.ifEmpty { null }?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }

                // 1. Repost composite exists in feed — relay merge only
                val repostId = "repost:${event.id}"
                val existingRepost = currentIds[repostId]
                if (existingRepost != null) {
                    if (newUrl != null && newUrl.isNotBlank()) {
                        val existingUrls = existingRepost.relayUrls.ifEmpty { listOfNotNull(existingRepost.relayUrl) }
                        if (newUrl !in existingUrls) {
                            relayUpdates[repostId] = (existingUrls + newUrl)
                            MLog.d(TAG, "\uD83D\uDD35 Relay merge into repost: ${repostId.take(16)} += $newUrl")
                        }
                    }
                    continue
                }
                // 2. Repost composite exists in pending — relay merge only
                if (pendingIds.contains(repostId)) {
                    if (newUrl != null && newUrl.isNotBlank()) {
                        synchronized(pendingNotesLock) {
                            val pendingNote = pendingIndex.byId[repostId]
                            if (pendingNote != null) {
                                val existingUrls = pendingNote.relayUrls.ifEmpty { listOfNotNull(pendingNote.relayUrl) }
                                if (newUrl !in existingUrls) {
                                    val updated = pendingNote.copy(relayUrls = existingUrls + newUrl)
                                    val listIdx = _pendingNewNotes.indexOfFirst { it.id == repostId }
                                    if (listIdx >= 0) _pendingNewNotes[listIdx] = updated
                                    pendingIndex.updateNote(updated)
                                }
                            }
                        }
                    }
                    continue
                }
                // 3. Original note already represented as repost — relay merge only
                if (event.id in repostedOriginalIds) {
                    if (newUrl != null && newUrl.isNotBlank()) {
                        val repostNoteId = feedIndex.repostOriginalToComposite[event.id]
                        val repostNote = repostNoteId?.let { feedIndex.byId[it] }
                        if (repostNote != null) {
                            val existingUrls = repostNote.relayUrls.ifEmpty { listOfNotNull(repostNote.relayUrl) }
                            if (newUrl !in existingUrls) {
                                relayUpdates[repostNote.id] = existingUrls + newUrl
                            }
                        }
                    }
                    continue
                }
                // 4. Locally-published event echo — relay merge only
                if (locallyPublishedIds.contains(event.id)) {
                    val existingLocal = currentIds[event.id]
                    if (existingLocal != null) {
                        val existingUrls = existingLocal.relayUrls.ifEmpty { listOfNotNull(existingLocal.relayUrl) }
                        if (newUrl != null && newUrl.isNotBlank() && newUrl !in existingUrls) {
                            relayUpdates[event.id] = existingUrls + newUrl
                        }
                    }
                    continue
                }
                // 5. Already in feed — relay merge only
                val existing = currentIds[event.id]
                if (existing != null) {
                    val existingUrls = existing.relayUrls.ifEmpty { listOfNotNull(existing.relayUrl) }
                    if (newUrl != null && newUrl.isNotBlank() && newUrl !in existingUrls) {
                        relayUpdates[event.id] = existingUrls + newUrl
                        MLog.d(TAG, "\uD83D\uDD35 Relay merge: ${event.id.take(8)} += $newUrl")
                    }
                    continue
                }
                // 6. Already in pending — relay merge only
                if (pendingIds.contains(event.id)) {
                    if (newUrl != null && newUrl.isNotBlank()) {
                        synchronized(pendingNotesLock) {
                            val pendingNote = pendingIndex.byId[event.id]
                            if (pendingNote != null) {
                                val existingUrls = pendingNote.relayUrls.ifEmpty { listOfNotNull(pendingNote.relayUrl) }
                                if (newUrl !in existingUrls) {
                                    val updated = pendingNote.copy(relayUrls = existingUrls + newUrl)
                                    val listIdx = _pendingNewNotes.indexOfFirst { it.id == event.id }
                                    if (listIdx >= 0) _pendingNewNotes[listIdx] = updated
                                    pendingIndex.updateNote(updated)
                                }
                            }
                        }
                    }
                    continue
                }
                // 7. Already in this batch — relay merge only
                if (event.id in newNoteIds) {
                    if (newUrl != null && newUrl.isNotBlank()) {
                        val batchIdx = newNotes.indexOfFirst { it.id == event.id }
                        if (batchIdx >= 0) {
                            val batchNote = newNotes[batchIdx]
                            val existingUrls = batchNote.relayUrls.ifEmpty { listOfNotNull(batchNote.relayUrl) }
                            if (newUrl !in existingUrls) {
                                newNotes[batchIdx] = batchNote.copy(relayUrls = existingUrls + newUrl)
                            }
                        }
                    }
                    continue
                }

                // ── Follow filter: uses event.pubKey directly (no conversion) ──
                if (!isGlobalMode && followFilterEnabled) {
                    val ff = followFilter
                    if (ff != null && ff.isNotEmpty()) {
                        val authorKey = normalizeAuthorIdForCache(event.pubKey)
                        val isOwnEvent = authorKey == currentUserPubkey
                        if (!isOwnEvent && authorKey !in ff) continue
                    } else {
                        // Follow filter is enabled but list is null/empty (still loading).
                        // During Loading state, let notes through so feed isn't blank.
                        // Once Live, drop to prevent global bleed into Following feed.
                        if (_feedSessionState.value == FeedSessionState.Live) continue
                    }
                }

                // ── Article dedup: uses event tags directly (no regex) ──
                if (event.kind == 30023) {
                    val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1)
                    if (dTag != null) {
                        val articleKey = "${event.pubKey.lowercase()}:$dTag"
                        val existingTs = existingArticleKeys[articleKey]
                        val eventTs = event.createdAt * 1000L
                        if (existingTs != null && existingTs >= eventTs) continue
                        if (existingTs != null) {
                            newNotes.removeAll { it.kind == 30023 && it.dTag == dTag && it.author.id.lowercase() == event.pubKey.lowercase() }
                        }
                        existingArticleKeys[articleKey] = eventTs
                    }
                }

                // ── ALL dedup/filter checks passed — NOW do the expensive conversion ──
                newNoteIds.add(event.id)
                if (BuildConfig.DEBUG) logIncomingEventSummary(event, relayUrl)
                val note = convertEventToNote(event, relayUrl)

                // Track kind:1 notes with I tags as topic replies (NIP-22)
                topicRepliesRepo.processKind1Note(note)

                if (note.isReply) {
                    Nip10ReplyDetector.getRootId(event)?.let { rootId ->
                        ThreadReplyCache.addReply(rootId, note)
                    }
                    // Replies are only needed on-demand in thread view.
                    // Skipping here prevents them from consuming feed cap space —
                    // previously 77% of _notes were replies that got filtered in display.
                    continue
                }

                newNotes.add(note)
            }

            // Remove stale article versions from existing feed (newer version arrived in this batch)
            val articleReplacements = newNotes.filter { it.kind == 30023 && it.dTag != null }
            if (articleReplacements.isNotEmpty()) {
                val staleIds = mutableSetOf<String>()
                for (replacement in articleReplacements) {
                    val key = "${replacement.author.id.lowercase()}:${replacement.dTag}"
                    currentNotes.filter { it.kind == 30023 && it.dTag != null && "${it.author.id.lowercase()}:${it.dTag}" == key && it.id != replacement.id }
                        .forEach { staleIds.add(it.id) }
                }
                if (staleIds.isNotEmpty()) {
                    val staleNotes = _notes.value.filter { it.id in staleIds }
                    staleNotes.forEach { feedIndex.removeNote(it) }
                    _notes.value = _notes.value.filter { it.id !in staleIds }.toImmutableList()
                    MLog.d(TAG, "Removed ${staleIds.size} stale article versions replaced by newer events")
                }
            }

            // Relay URL merges are deferred until after new notes are merged into
            // _notes (below). Applying them here would be overwritten by the second
            // _notes.value write in the feed-merge block, silently dropping the merged URLs.

            if (newNotes.isEmpty() && relayUpdates.isEmpty()) {
                return
            }

            // Partition new notes into feed vs pending
            val feedNotes = mutableListOf<Note>()
            val pendingNew = mutableListOf<Note>()
            val cutoff = feedCutoffTimestampMs

            for (note in newNotes) {
                val isOlderThanCutoff = cutoff <= 0L || note.timestamp <= cutoff
                val isOwnEvent = note.author.id.lowercase() == currentUserPubkey

                if (!initialLoadComplete || isOlderThanCutoff || isOwnEvent) {
                    feedNotes.add(note)
                } else {
                    pendingNew.add(note)
                }
            }

            // Merge feed notes efficiently based on temporal position:
            // - Pagination notes (older) → sort batch only, append to end
            // - Live notes (newer) → sort batch only, prepend to start
            // - Mixed → fall back to full merge-sort (rare)
            // This avoids re-sorting the entire 4000+ element list on every flush.
            if (feedNotes.isNotEmpty()) {
                // Read from shadow list if available (burst accumulation), otherwise from StateFlow
                val current = burstShadowNotes ?: _notes.value
                val sortedBatch = feedNotes.sortedByDescending { it.repostTimestamp ?: it.timestamp }
                val merged = if (current.isEmpty()) {
                    trimNotesToCap(sortedBatch)
                } else {
                    val oldestCurrent = current.last().let { it.repostTimestamp ?: it.timestamp }
                    val newestBatch = sortedBatch.first().let { it.repostTimestamp ?: it.timestamp }
                    if (newestBatch <= oldestCurrent) {
                        // All batch notes are older — append (pagination path, most common)
                        trimNotesToCap(current + sortedBatch)
                    } else {
                        val oldestBatch = sortedBatch.last().let { it.repostTimestamp ?: it.timestamp }
                        val newestCurrent = current.first().let { it.repostTimestamp ?: it.timestamp }
                        if (oldestBatch > newestCurrent) {
                            // All batch notes are newer — prepend (live notes path)
                            trimNotesToCap(sortedBatch + current)
                        } else {
                            // Mixed — full merge required (rare: batch spans existing range)
                            trimNotesToCap((current + sortedBatch).sortedByDescending { it.repostTimestamp ?: it.timestamp })
                        }
                    }
                }
                // Incrementally update feedIndex with new feed notes (O(batch), not O(n))
                for (note in feedNotes) { feedIndex.addNote(note) }

                // Apply relay URL merges to the merged list BEFORE emitting (coalesced single write)
                val finalMerged = if (relayUpdates.isNotEmpty()) {
                    MLog.d(TAG, "\uD83D\uDD35 Applying ${relayUpdates.size} relay URL merges inline")
                    merged.map { note ->
                        relayUpdates[note.id]?.let { urls ->
                            val updated = note.copy(relayUrls = urls)
                            feedIndex.updateNote(updated)
                            updated
                        } ?: note
                    }
                } else {
                    merged
                }

                // Burst emission throttle: during burst ingestion, accumulate in shadow
                // list and only emit to StateFlow every 200ms. This reduces emissions
                // from ~250/sec to ~5/sec, preventing Compose snapshot churn.
                val now = System.currentTimeMillis()
                val queueStillLargeForEmission = pendingKind1Events.size > enrichmentDrainThreshold()
                if (queueStillLargeForEmission && (now - lastEmissionMs) < BURST_EMISSION_THROTTLE_MS) {
                    // Accumulate in shadow — skip StateFlow write
                    burstShadowNotes = finalMerged
                } else {
                    // Emit: either throttle window opened, or steady-state
                    burstShadowNotes = null
                    _notes.value = finalMerged.toImmutableList()
                    lastEmissionMs = now
                }
                advancePaginationCursor(feedNotes)
                if (!initialLoadComplete && finalMerged.size % 50 == 0) {
                    MLog.d(TAG, "Initial load: ${finalMerged.size} notes so far")
                }

                // Persist relay URL merges to Room (background, best-effort)
                if (relayUpdates.isNotEmpty()) {
                    val dao = eventDao
                    if (dao != null) {
                        scope.launch(Dispatchers.IO) {
                            flushEventStore() // ensure rows exist before UPDATE
                            for ((noteId, urls) in relayUpdates) {
                                try { dao.updateRelayUrls(noteId, urls.joinToString(",")) }
                                catch (e: Exception) { /* best-effort */ }
                            }
                        }
                    }
                }
            } else if (relayUpdates.isNotEmpty()) {
                // No new feed notes, but relay URL merges to apply
                MLog.d(TAG, "\uD83D\uDD35 Applying ${relayUpdates.size} relay URL merges (no new notes)")
                val afterMerge = _notes.value
                val updatedList = afterMerge.map { note ->
                    relayUpdates[note.id]?.let { urls ->
                        val updated = note.copy(relayUrls = urls)
                        feedIndex.updateNote(updated)
                        updated
                    } ?: note
                }
                _notes.value = updatedList.toImmutableList()
                lastEmissionMs = System.currentTimeMillis()
                // Persist merged relay URLs to Room
                val dao = eventDao
                if (dao != null) {
                    scope.launch(Dispatchers.IO) {
                        flushEventStore()
                        for ((noteId, urls) in relayUpdates) {
                            try { dao.updateRelayUrls(noteId, urls.joinToString(",")) }
                            catch (e: Exception) { /* best-effort */ }
                        }
                    }
                }
            }

            // ── Enrichment: counts, quotes, URL previews, profiles, NIP-11 ──
            // During burst ingestion (queue still large), defer enrichment to avoid
            // 1200 rounds of subscription fan-out. Accumulate notes and fire once
            // when the queue drains below the adaptive drain threshold.
            val queueStillLarge = pendingKind1Events.size > enrichmentDrainThreshold()

            if (feedNotes.isNotEmpty()) {
                if (queueStillLarge) {
                    // Defer: accumulate for later enrichment
                    deferredEnrichmentNotes.addAll(feedNotes)
                } else {
                    // Steady-state: fire enrichment immediately
                    // Include any previously deferred notes
                    val enrichBatch = mutableListOf<Note>()
                    while (true) { val n = deferredEnrichmentNotes.poll() ?: break; enrichBatch.add(n) }
                    enrichBatch.addAll(feedNotes)
                    fireEnrichmentForNotes(enrichBatch)
                }
            }

            // Add pending notes
            if (pendingNew.isNotEmpty()) {
                synchronized(pendingNotesLock) {
                    _pendingNewNotes.addAll(pendingNew)
                    for (note in pendingNew) { pendingIndex.addNote(note) }
                }
                updateDisplayedNewNotesCount()
                if (queueStillLarge) {
                    deferredEnrichmentNotes.addAll(pendingNew)
                } else {
                    val enrichBatch = mutableListOf<Note>()
                    while (true) { val n = deferredEnrichmentNotes.poll() ?: break; enrichBatch.add(n) }
                    enrichBatch.addAll(pendingNew)
                    fireEnrichmentForNotes(enrichBatch)
                }
            }

            // Display update: only during steady-state. During burst ingestion the
            // display layer can't keep up anyway, and updateDisplayedNotes does 5 O(n)
            // passes + fires counts subscriptions — wasted work while draining 60k events.
            if (!queueStillLarge) {
                scheduleDisplayUpdate()
            }

            // Profiles and NIP-11: also deferred during burst
            if (!queueStillLarge) {
                val profileRelayUrls = getProfileRelayUrls()
                if (pendingProfilePubkeys.isNotEmpty() && profileRelayUrls.isNotEmpty()) {
                    scheduleBatchProfileRequest(profileRelayUrls)
                }

                val nip11Cache = social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
                if (nip11Cache != null) {
                    val allRelayUrls = (feedNotes + pendingNew).flatMap { it.relayUrls }.distinct()
                    val uncached = allRelayUrls.filter { !nip11Cache.hasCachedRelayInfo(it) }
                    if (uncached.isNotEmpty()) {
                        pendingNip11Urls.addAll(uncached)
                        scheduleNip11Preload()
                    }
                }
            } else {
                // Accumulate NIP-11 URLs even during burst (cheap, no network)
                val nip11Cache = social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
                if (nip11Cache != null) {
                    val allRelayUrls = (feedNotes + pendingNew).flatMap { it.relayUrls }.distinct()
                    val uncached = allRelayUrls.filter { !nip11Cache.hasCachedRelayInfo(it) }
                    if (uncached.isNotEmpty()) pendingNip11Urls.addAll(uncached)
                }
            }

            if (BuildConfig.DEBUG && batch.size > 5) {
                MLog.d(TAG, "Flushed ${batch.size} events (${kind6Batch.size} reposts): ${feedNotes.size} to feed, ${pendingNew.size} to pending, ${relayUpdates.size} relay merges${if (ageGateDropped > 0) ", $ageGateDropped age-gated" else ""}")
            }
            if (ageGateDropped > 0) {
                MLog.w(TAG, "Age gate dropped $ageGateDropped events (floor=${fmtMs(ageFloorMs)}, mainFloor=${fmtMs(mainSubscriptionFloorMs)}, absFloor=${fmtMs(absoluteFloorMs)})")
            }

            // ── Batch kind-6/16 reposts ──────────────────────────────────────────
            // Processed inside the SAME mutex lock as kind-1: eliminates 150+
            // individual coroutine launches + mutex acquisitions per burst.
            if (kind6Batch.isNotEmpty()) {
                for (pending in kind6Batch) {
                    handleKind6Repost(pending.event, pending.relayUrl)
                }
                if (BuildConfig.DEBUG) {
                    MLog.d(TAG, "Processed ${kind6Batch.size} kind-6 reposts in batch")
                }
            }

            // Poller handles remaining events automatically — no continuation flush needed
        } catch (e: Throwable) {
            MLog.e(TAG, "flushKind1Events failed: ${e.message}", e)
        } finally {
            PipelineDiagnostics.recordBatch(batch.size, System.currentTimeMillis() - flushStartMs)
            if (BuildConfig.DEBUG) PipelineDiagnostics.logSummary(TAG)
            android.os.Trace.endSection()
        }
        } // processEventMutex.withLock
    }

    /**
     * Fire counts, quoted-note, and URL preview enrichment for a batch of notes.
     *
     * ## Timestamp-ordered tiered enrichment
     *
     * Notes are sorted by timestamp descending (newest first = top of feed) and
     * split into two tiers:
     *
     * **Viewport (top [VIEWPORT_ENRICHMENT_SIZE])** — what the user actually sees:
     * - Counts subscription at NORMAL priority (fires immediately, no debounce)
     * - Quoted note prefetch fires first (processed serially → resolves first)
     * - URL preview prefetch queued first (FIFO → dequeued first)
     *
     * **Background (rest)** — off-screen, can wait:
     * - Counts subscription at LOW priority (debounced, existing behavior)
     * - Quoted note + URL prefetch deferred by [BACKGROUND_ENRICHMENT_DELAY_MS]
     *
     * This provides **algorithmic certainty** of top-down feed population:
     * the relay scheduler always gives viewport enrichment relay slots before
     * background enrichment can compete for them.
     */
    private fun fireEnrichmentForNotes(notes: List<Note>) {
        if (notes.isEmpty()) return

        // ── Sort by feed-position timestamp: newest first ──
        // For reposts (kind-6), the feed position is determined by the BOOST
        // timestamp (repostTimestamp), not the original note's creation time.
        // Without this, a boost of a week-old note would sort to the bottom
        // of enrichment even though it appears at the top of the feed.
        val sorted = notes.sortedByDescending { it.repostTimestamp ?: it.timestamp }

        // ── Split into viewport and background tiers ──
        val viewport: List<Note>
        val background: List<Note>
        if (sorted.size > VIEWPORT_ENRICHMENT_SIZE) {
            viewport = sorted.subList(0, VIEWPORT_ENRICHMENT_SIZE)
            background = sorted.subList(VIEWPORT_ENRICHMENT_SIZE, sorted.size)
        } else {
            viewport = sorted
            background = emptyList()
        }

        // ── Phase 1: Viewport — immediate, NORMAL priority ──
        val viewportRelayMap = buildEnrichmentRelayMap(viewport)
        NoteCountsRepository.setViewportNoteIds(viewportRelayMap)
        QuotedNoteCache.prefetchForNotes(viewport)
        scheduleUrlPreviewPrefetch(viewport)

        // ── Phase 2: Background — deferred, LOW priority ──
        if (background.isNotEmpty()) {
            val backgroundRelayMap = buildEnrichmentRelayMap(background)
            // Register background IDs for LOW-priority counts (debounced)
            NoteCountsRepository.setNoteIdsOfInterest(backgroundRelayMap)
            // Defer background quoted notes + URL previews so viewport
            // subscriptions claim relay slots first
            scope.launch {
                delay(BACKGROUND_ENRICHMENT_DELAY_MS)
                QuotedNoteCache.prefetchForNotes(background)
                scheduleUrlPreviewPrefetch(background)
            }
        }
    }

    /** Build noteId → relay URLs map from a list of notes, including their quoted event IDs.
     *  Preserves insertion order (LinkedHashMap) so timestamp ordering is maintained
     *  through to the subscription layer. */
    private fun buildEnrichmentRelayMap(notes: List<Note>): LinkedHashMap<String, List<String>> {
        val map = LinkedHashMap<String, List<String>>(notes.size)
        for (note in notes) {
            val relays = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
            val effectiveId = note.originalNoteId ?: note.id
            if (effectiveId !in map) {
                map[effectiveId] = relays
            }
            // Include quoted event IDs so counts for embedded notes resolve too
            note.quotedEventIds.forEach { qid ->
                if (qid !in map) {
                    map[qid] = relays
                }
            }
        }
        return map
    }

    /**
     * Drain all deferred enrichment notes and fire enrichment + profile + NIP-11
     * in one shot. Called by the poller when the queue drops below the adaptive drain threshold.
     */
    private fun fireDeferredEnrichment() {
        val enrichBatch = mutableListOf<Note>()
        while (true) { val n = deferredEnrichmentNotes.poll() ?: break; enrichBatch.add(n) }
        if (enrichBatch.isNotEmpty()) {
            MLog.d(TAG, "🔶 Firing deferred enrichment for ${enrichBatch.size} accumulated notes")
            fireEnrichmentForNotes(enrichBatch)
        }
        // Profiles
        val profileRelayUrls = getProfileRelayUrls()
        if (pendingProfilePubkeys.isNotEmpty() && profileRelayUrls.isNotEmpty()) {
            scheduleBatchProfileRequest(profileRelayUrls)
        }
        // NIP-11
        if (pendingNip11Urls.isNotEmpty()) {
            scheduleNip11Preload()
        }
        // Display update: suppressed during burst, fire now that the queue has drained
        scheduleDisplayUpdate()
    }

    /** Schedule a debounced NIP-11 preload for relay URLs seen in feed notes.
     *  Multiple flush cycles accumulate URLs; preload fires once after the debounce window. */
    private fun scheduleNip11Preload() {
        nip11PreloadJob?.cancel()
        nip11PreloadJob = scope.launch {
            delay(NIP11_PRELOAD_DEBOUNCE_MS)
            val urls = pendingNip11Urls.toList()
            pendingNip11Urls.clear()
            if (urls.isNotEmpty()) {
                MLog.d(TAG, "NIP-11 preload: ${urls.size} new relay URLs from feed notes")
                social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
                    ?.preloadRelayInfo(urls, scope)
            }
        }
    }

    // ── Eager URL preview prefetch ──────────────────────────────────────────
    /** Pending notes whose URLs haven't been prefetched yet. Debounced to batch across flush cycles. */
    private val pendingUrlPrefetchNotes = java.util.concurrent.ConcurrentLinkedQueue<Note>()
    private var urlPrefetchJob: Job? = null
    private val URL_PREFETCH_DEBOUNCE_MS = 300L
    /** Max notes to prefetch URL previews for per batch to cap network usage. */
    private val URL_PREFETCH_LIMIT = 20

    /**
     * Schedule a debounced URL preview prefetch for newly ingested notes.
     * Runs on IO dispatcher with bounded concurrency (3 concurrent fetches).
     * Already-cached URLs are skipped. Results land in [UrlPreviewCache] so
     * [UrlPreviewLoader] gets an instant cache hit when the card renders.
     */
    private fun scheduleUrlPreviewPrefetch(notes: List<Note>) {
        pendingUrlPrefetchNotes.addAll(notes)
        urlPrefetchJob?.cancel()
        urlPrefetchJob = scope.launch {
            delay(URL_PREFETCH_DEBOUNCE_MS)
            val batch = mutableListOf<Note>()
            while (batch.size < URL_PREFETCH_LIMIT) {
                val note = pendingUrlPrefetchNotes.poll() ?: break
                batch.add(note)
            }
            if (batch.isEmpty()) return@launch

            val urlPreviewService = social.mycelium.android.services.UrlPreviewService()
            val cache = social.mycelium.android.services.UrlPreviewCache
            val detector = social.mycelium.android.utils.UrlDetector
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            var prefetched = 0

            kotlinx.coroutines.coroutineScope {
                for (note in batch) {
                    val urls = detector.findUrls(note.content)
                        .filter { !detector.isImageUrl(it) && !detector.isVideoUrl(it) }
                        .filter { cache.get(it) == null && !cache.isLoading(it) }
                        .take(2)
                    for (url in urls) {
                        launch {
                            semaphore.acquire()
                            try {
                                cache.setLoadingState(url, social.mycelium.android.data.UrlPreviewState.Loading)
                                val result = urlPreviewService.fetchPreview(url)
                                cache.setLoadingState(url, result)
                                prefetched++
                            } catch (_: Exception) { } finally {
                                semaphore.release()
                            }
                        }
                    }
                }
            }
            if (prefetched > 0) {
                MLog.d(TAG, "URL preview prefetch: $prefetched URLs from ${batch.size} notes")
            }
        }
    }

    /** Coalesce profileUpdated emissions and apply author updates in batches to avoid O(notes) work per profile. */
    private fun startProfileUpdateCoalescer() {
        scope.launch {
            val batch = mutableSetOf<String>()
            var flushJob: Job? = null
            profileCache.profileUpdated.collect { pubkey ->
                batch.add(pubkey.lowercase())
                flushJob?.cancel()
                flushJob = scope.launch {
                    delay(PROFILE_UPDATE_DEBOUNCE_MS)
                    val snapshot = batch.toSet()
                    batch.clear()
                    flushJob = null
                    if (snapshot.isNotEmpty()) updateAuthorsInNotesBatch(snapshot)
                }
            }
        }
    }

    // ── Event store write path (deferred toJson on IO thread) ────────────────
    private fun scheduleEventStoreFlush() {
        eventStoreFlushJob?.cancel()
        eventStoreFlushJob = scope.launch {
            delay(EVENT_STORE_FLUSH_DEBOUNCE_MS)
            flushEventStore()
        }
    }

    private suspend fun flushEventStore() {
        val dao = eventDao ?: return
        withContext(Dispatchers.IO) {
            val entities = mutableListOf<CachedEventEntity>()
            var drained = 0
            while (drained < EVENT_STORE_BATCH_CAP) {
                val triple = pendingEventObjects.poll() ?: break
                val (event, relayUrl, _) = triple
                val normalizedUrl = relayUrl.ifBlank { null }
                val trackedUrls = EventRelayTracker.getRelays(event.id)
                val allUrls = if (trackedUrls.isNotEmpty()) {
                    (trackedUrls + listOfNotNull(normalizedUrl)).distinct().joinToString(",")
                } else {
                    normalizedUrl
                }
                entities.add(CachedEventEntity(
                    eventId = event.id,
                    kind = event.kind,
                    pubkey = event.pubKey.lowercase(),
                    createdAt = event.createdAt,
                    eventJson = event.toJson(),
                    relayUrl = normalizedUrl,
                    relayUrls = allUrls,
                    isReply = social.mycelium.android.utils.Nip10ReplyDetector.isReply(event)
                ))
                drained++
            }
            if (entities.isEmpty()) return@withContext
            try {
                dao.insertAll(entities)
                PipelineDiagnostics.recordDbCommit(entities.size)
                feedWindowManager?.onRoomDataChanged()
                val remaining = pendingEventObjects.size
                if (entities.size > 20 || remaining > 0) {
                    MLog.d(TAG, "Event store: persisted ${entities.size} events, $remaining queued")
                }
            } catch (e: Exception) {
                MLog.e(TAG, "Event store flush failed: ${e.message}", e)
            }
            // More events waiting — yield briefly (let GC breathe) then drain next batch.
            // Don't use scheduleEventStoreFlush() here — its 2s debounce would delay
            // draining when we already know there's work. 50ms gap between batches
            // spreads allocation pressure across GC windows.
            if (pendingEventObjects.isNotEmpty()) {
                delay(50)
                flushEventStore()
            }
        }
    }

    /** Persist a kind-6 repost event to the store. Called from handleKind6Repost. */
    private fun persistRepostEvent(event: Event, relayUrl: String) {
        if (eventDao == null || isGlobalMode) return
        if (pendingEventObjects.size >= EVENT_STORE_QUEUE_CAP) return
        pendingEventObjects.add(Triple(event, relayUrl, Unit))
        scheduleEventStoreFlush()
    }

    /**
     * Set the current user's public key. Used to identify own events for immediate display.
     */
    fun setCurrentUserPubkey(pubkey: String?) {
        currentUserPubkey = pubkey?.lowercase()
        MLog.d(TAG, "Set current user pubkey: ${pubkey?.take(8)}...")
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches (from RelayStorageManager.loadIndexerRelays). Call when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    // All notes (with relayUrl set when received); filtered by connectedRelays for display
    private val _notes = MutableStateFlow<ImmutableList<Note>>(persistentListOf())
    private val _displayedNotes = MutableStateFlow<ImmutableList<Note>>(persistentListOf())
    /** Displayed notes (filtered by relay + follow filter, debounced). Primary feed source for UI. */
    val notes: StateFlow<ImmutableList<Note>> = _displayedNotes.asStateFlow()
    /** Alias for clarity when distinguishing from allNotes. */
    val displayedNotes: StateFlow<ImmutableList<Note>> = _displayedNotes.asStateFlow()
    /** Raw unfiltered notes list — emits on every event batch + profile update. Use for fast UI; use displayedNotes for enrichment. */
    val allNotes: StateFlow<ImmutableList<Note>> = _notes.asStateFlow()

    /** Notes loaded from Room via windowed paging. These live OUTSIDE _notes to avoid
     *  the in-memory cap. updateDisplayedNotes() appends these after the filtered
     *  in-memory portion so they survive enrichment/emission cycles.
     *  Capped at [MAX_ROOM_PAGINATED] to prevent unbounded memory growth. */
    private val roomPaginatedNotes = java.util.Collections.synchronizedList(mutableListOf<Note>())
    /** IDs of Room-paginated notes for O(1) dedup. */
    private val roomPaginatedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Max Room-paginated notes kept in memory. Older entries are trimmed. */
    private val MAX_ROOM_PAGINATED = 500

    /** Persistent O(1) index over _notes. Updated incrementally on the hot path (flushKind1Events)
     *  and rebuilt on cold paths (mode switch, snapshot restore, clearNotes). */
    private val feedIndex = FeedIndex()
    /** Persistent O(1) index over _pendingNewNotes. Synchronized via pendingNotesLock. */
    private val pendingIndex = PendingIndex()

    /**
     * Cold-path helper: replace _notes.value AND rebuild the feed index from scratch.
     * Use for operations that wholesale-replace the list (snapshot restore, clearNotes,
     * Room prewarm, full-list profile updates). The hot path (flushKind1Events)
     * updates the index incrementally instead of calling this.
     */
    private fun setNotes(notes: ImmutableList<Note>) {
        _notes.value = notes
        feedIndex.rebuild(notes)
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()
    /** True when pagination produced too few new notes — stops further loadOlderNotes calls until feed resets. */
    private val _paginationExhausted = MutableStateFlow(false)
    val paginationExhausted: StateFlow<Boolean> = _paginationExhausted.asStateFlow()
    /** Consecutive pagination pages that returned 0 events from relays. Exhaustion requires 2 consecutive
      * empty responses to guard against transient network failures killing pagination permanently. */
    private var paginationEmptyStreak = 0
    /** Handle for the active "load older" subscription; cancelled when a new one starts or feed resets. */
    @Volatile private var olderNotesHandle: social.mycelium.android.relay.TemporarySubscriptionHandle? = null

    /** Index in the sorted notes list where a large temporal gap (>14 days) was detected after pagination. */
    private val _timeGapIndex = MutableStateFlow<Int?>(null)
    val timeGapIndex: StateFlow<Int?> = _timeGapIndex.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Debug-only: event content stats for this session (total, md, img, vid, gif, imeta, emoji). Only updated when BuildConfig.DEBUG. */
    private val _debugEventStats = MutableStateFlow(DebugEventStatsSnapshot(0, 0, 0, 0, 0, 0, 0))
    val debugEventStats: StateFlow<DebugEventStatsSnapshot> = _debugEventStats.asStateFlow()

    private var connectedRelays = listOf<String>()
    private var subscriptionRelays = listOf<String>()

    /** When non-null and followFilterEnabled, only notes whose author.id is in this set are shown. Volatile so relay callback thread sees latest. */
    @Volatile
    private var followFilter: Set<String>? = null
    @Volatile
    private var followFilterEnabled: Boolean = true
    /** True when a NIP-51 custom people list is actively selected as feed filter.
     *  When true, an empty followFilter means "show nothing" (intentional) rather than
     *  "still loading" (transient). Controls display behavior in updateDisplayedNotes. */
    @Volatile
    private var customListActive: Boolean = false

    /** True when user is viewing All/Global feed. Global notes are ephemeral: never cached, destroyed on exit. */
    @Volatile
    private var isGlobalMode: Boolean = false

    /** In-memory snapshot of following notes saved before entering Global mode; restored instantly on return. */
    private var followingNotesSnapshot: ImmutableList<Note> = persistentListOf()

    /** Last applied kind-1 filter (authors) when Following was active; used on resume when follow list is temporarily empty so All notes do not bleed into Following. */
    @Volatile
    private var lastAppliedKind1Filter: Filter? = null

    /** Serializes event processing so follow filter and displayed notes stay consistent; avoids blocking WebSocket thread. */
    private val processEventMutex = Mutex()

    /** Debounced display update: one run after event burst settles so UI stays smooth under high throughput. */
    private var displayUpdateJob: Job? = null
    
    /** Background job for initial note polling after subscription starts (non-blocking). */
    private var initialLoadJob: Job? = null

    /** Batched kind-0 profile requests: uncached authors are added here and fetched in batches to avoid flooding relays and speed up feed resolution. */
    private val pendingProfilePubkeys = Collections.synchronizedSet(LinkedHashSet<String>())
    /** Relay hints from nprofile TLV data, keyed by pubkey. Used to resolve profiles
     *  that aren't on indexer relays (e.g. relays specified in nprofile1 mentions). */
    private val pendingProfileRelayHints = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    /** Track in-flight tag-only repost fetches to avoid duplicate requests for the same repost event. */
    private val pendingRepostFetches = Collections.synchronizedSet(HashSet<String>())

    /** Metadata for a pending repost fetch (batched instead of one-sub-per-repost). */
    private data class PendingRepost(
        val originalNoteId: String,
        val compositeId: String,
        val reposterAuthor: Author,
        val repostTimestampMs: Long,
        val relayUrl: String,
        val fetchRelays: List<String>,
    )
    /** Buffer of pending repost fetches waiting to be flushed as a single batched subscription. */
    private val pendingRepostBuffer = java.util.concurrent.ConcurrentHashMap<String, PendingRepost>()
    /** Debounce job for batched repost flush. */
    private var repostBatchJob: Job? = null
    private val REPOST_BATCH_DELAY_MS = 500L

    /** Job that schedules the next batch (debounce timer). Cancelled and re-set on each new pubkey. */
    private var profileBatchScheduleJob: Job? = null
    /** Job that is actively fetching profiles from relays. Never cancelled by new pubkeys. */
    private var profileBatchFetchJob: Job? = null
    private val PROFILE_BATCH_DELAY_MS = 200L
    private val PROFILE_BATCH_SIZE = 80
    /** Debounce window for coalescing profileUpdated before applying to notes list (one list update per batch). */
    private val PROFILE_UPDATE_DEBOUNCE_MS = 80L

    // Feed cutoff: only notes with timestamp <= this are shown; everything else builds up in pending until refresh
    private var feedCutoffTimestampMs: Long = 0L
    private var latestNoteTimestampAtOpen: Long = 0L
    private var initialLoadComplete: Boolean = false
    /** Fixed age floor for the main (live) subscription — never lowered by pagination.
     *  Set at subscribe time to (now - FEED_SINCE_DAYS). Events from the live subscription
     *  older than this are dropped, preventing relays that ignore `since` from contaminating
     *  the feed with ancient notes. */
    @Volatile private var mainSubscriptionFloorMs: Long = 0L
    /** Pagination age floor (ms): starts equal to mainSubscriptionFloorMs but gets lowered
     *  by each loadOlderNotes call so progressively older pages can pass through. Only
     *  applies to events marked isPagination=true. */
    @Volatile private var feedAgeFloorMs: Long = 0L
    /** Per-relay pagination cursors: tracks the oldest event timestamp (ms) seen from each relay.
     *  Used by loadOlderNotesFromRelay to send relay-specific `until` values so dense relays
     *  (damus) advance independently from sparse relays (pickle). Cleared on fresh subscription. */
    private val perRelayCursorMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Relays that returned 0 events on their last pagination request — skipped until reset. */
    private val exhaustedPaginationRelays = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _pendingNewNotes = mutableListOf<Note>()
    private val pendingNotesLock = Any()
    private val _newNotesCounts = MutableStateFlow(NewNotesCounts(0, 0))
    val newNotesCounts: StateFlow<NewNotesCounts> = _newNotesCounts.asStateFlow()

    /** Event IDs of notes published locally by the user. Used to:
     *  1. Skip the pending queue when the relay echo arrives (reconcile instead).
     *  2. Prevent the "X new notes" counter from counting our own events. */
    private val locallyPublishedIds = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Event IDs of notes received via outbox relays (NIP-65). Used to bypass the
     *  relay display filter in updateDisplayedNotes — outbox relay URLs are not in
     *  the user's subscription relay set, so without this bypass outbox notes would
     *  be filtered out of the displayed feed despite being valid followed-user notes. */
    private val outboxReceivedNoteIds = Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Feed session state for UI and to avoid redundant load on tab return (Idle -> Loading -> Live; Refreshing during applyPendingNotes/refresh). */
    private val _feedSessionState = MutableStateFlow(FeedSessionState.Idle)
    val feedSessionState: StateFlow<FeedSessionState> = _feedSessionState.asStateFlow()

    /** True after the on-disk feed cache has been checked (whether or not notes were found).
     *  The dashboard overlay waits for this before showing the loading indicator so it
     *  doesn't flash on resume after process death. */
    private val _feedCacheChecked = MutableStateFlow(false)
    val feedCacheChecked: StateFlow<Boolean> = _feedCacheChecked.asStateFlow()

    /**
     * True once the feed has been successfully loaded at least once in this session.
     * Set when feedSessionState first reaches Live OR when notes are restored from
     * the Room cache. Persists across relay reconnections and app backgrounding,
     * but is reset on account switch (clearNotes).
     *
     * The UI uses this to suppress the full-page "Connecting to relays" overlay
     * during transient disconnections. After the user has seen their feed once,
     * reconnection status is shown only via the subtle top-bar relay orbs.
     */
    private val _hasEverLoadedFeed = MutableStateFlow(false)
    val hasEverLoadedFeed: StateFlow<Boolean> = _hasEverLoadedFeed.asStateFlow()

    /** Optional context for feed cache persistence so notes survive process death. Set from MainActivity. */
    @Volatile private var appContext: Context? = null

    // ── Event store (Room DB) — persists events for cold-start feed restoration ──
    @Volatile private var eventDao: social.mycelium.android.db.EventDao? = null
    /** Room-backed windowed paging: loads feed pages from Room for unlimited scroll depth.
     *  Initialized in prepareFeedCache() when eventDao becomes available. */
    @Volatile private var feedWindowManager: FeedWindowManager? = null
    private val pendingEventObjects = java.util.concurrent.ConcurrentLinkedQueue<Triple<Event, String, Unit>>()
    private var eventStoreFlushJob: Job? = null
    private val EVENT_STORE_FLUSH_DEBOUNCE_MS = 2_000L
    /** Max events to persist per flush cycle. Spreads allocation across GC windows
     *  instead of creating a 453-event spike that triggers 300ms stop-the-world GC. */
    private val EVENT_STORE_BATCH_CAP = 100
    /** Max events queued for Room persistence. Beyond this, oldest entries are dropped
     *  (they're already in the feed pipeline; Room persistence is best-effort cache). */
    private val EVENT_STORE_QUEUE_CAP = 2_000

    /** Event IDs that the user has deleted — persisted so they stay hidden after restart. */
    private val deletedEventIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "NotesRepository"
        /** Max notes kept in memory; oldest dropped to keep heap bounded.
         *  With reply-skip, ~85% of internal notes pass display filter.
         *  706 displayed notes = 256MB (OOM). Safe zone: ~500 displayed.
         *  600 base + 100 pagination cap = 700 max → ~595 displayed → ~140MB stable. */
        private const val MAX_NOTES_IN_MEMORY = 600
        /** Hard ceiling for pagination extra cap. Prevents unbounded growth. */
        private const val MAX_PAGINATION_EXTRA_CAP = 100
        /** Limit for following feed; relays return only notes from followed authors so we can ask for more. */
        private const val FOLLOWING_FEED_LIMIT = 300
        /** Limit for global feed. */
        private const val GLOBAL_FEED_LIMIT = 300
        private const val FEED_SINCE_DAYS = 7
        /** Display window: only render notes within this duration from the newest note.
         *  Older notes are evicted from memory and served from Room on demand.
         *  Matches FEED_SINCE_DAYS so the visible feed and relay subscription window align. */
        private const val DISPLAY_WINDOW_MS = FEED_SINCE_DAYS.toLong() * 86_400_000L
        /** Debounce display updates so hundreds of events/sec don't thrash the UI. */
        private const val DISPLAY_UPDATE_DEBOUNCE_MS = 150L
        private const val DELETED_IDS_PREFS = "notes_deleted_ids"
        private const val DELETED_IDS_KEY = "deleted_event_ids"
        private const val DELETED_IDS_MAX = 500
        private const val FEED_CACHE_MAX = 200
        /** Max time to wait for older notes before declaring done. */
        private const val OLDER_NOTES_TIMEOUT_MS = 12_000L
        /** After last event arrives, wait this long for more before declaring done.
         *  Raised from 1.5s→3s: outbox relays (damus) can be slower than local relays,
         *  and the subscription was closing before they finished delivering. */
        private const val OLDER_NOTES_SETTLE_MS = 3_000L
        /** Max cursor jump per page (30 days). Prevents a single outlier from skipping months.
         *  Raised from 14d→30d: Following feeds on outbox relays (damus) can have sparse
         *  activity with 2-3 week gaps between posts by followed authors. */
        private const val MAX_CURSOR_JUMP_MS = 30L * 86_400_000L
        /** Number of events to request per pagination page (limit param). */
        private const val PAGINATION_PAGE_SIZE = 100
        /** Per-relay limit for pagination requests (total events ≈ relayCount × this). */
        private const val PAGINATION_PER_RELAY_LIMIT = 50
        /** How far back (days) the age floor is lowered per pagination page. */
        private const val PAGINATION_PAGE_DAYS = 7L

        @Volatile
        private var instance: NotesRepository? = null
        fun getInstance(): NotesRepository =
            instance ?: synchronized(this) { instance ?: NotesRepository().also { instance = it } }
    }

    /**
     * Preload up to [limit] notes into the Room event cache during onboarding's PREFETCHING_LISTS
     * phase, before the dashboard is visible. Uses a temporary one-shot subscription so it does
     * NOT touch the live feed state (no notes.value mutation, no cutoff change). When the dashboard
     * later calls loadFeedCacheFromRoom() it will find these events and render the feed instantly
     * without any relay round-trip.
     *
     * Uses the follow list when available so the prewarmed cache matches the Following feed.
     * Also batch-prefetches kind-0 profiles for all unique authors in one subscription so
     * the feed renders with resolved display names from the very first frame.
     *
     * Safe to call multiple times; no-ops if Room is not yet initialized.
     */
    suspend fun prewarmFeedCache(
        relayUrls: List<String>,
        followedPubkeys: Set<String>,
        indexerUrls: List<String>,
        limit: Int = 500
    ) {
        val dao = eventDao
        if (dao == null) {
            MLog.w(TAG, "prewarmFeedCache: Room not initialized, skipping")
            return
        }
        if (relayUrls.isEmpty()) {
            MLog.w(TAG, "prewarmFeedCache: no relay URLs, skipping")
            return
        }

        MLog.d(TAG, "prewarmFeedCache: preloading $limit notes from ${relayUrls.size} relays (${followedPubkeys.size} follows)")
        val startMs = System.currentTimeMillis()

        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400L * FEED_SINCE_DAYS
        // Include kind-1 (notes), kind-6 (reposts), kind-30023 (articles) so the
        // prewarm cache matches what loadFeedCacheFromRoom expects to restore.
        val prewarmKinds = listOf(1, 6, 30023)
        val filter = if (followedPubkeys.isNotEmpty()) {
            // Following mode: fetch from followed authors only to match the default feed view
            Filter(
                kinds = prewarmKinds,
                authors = followedPubkeys.toList(),
                limit = limit,
                since = sevenDaysAgo
            )
        } else {
            // No follow list yet: fetch globally so the cache isn't empty
            Filter(kinds = prewarmKinds, limit = limit, since = sevenDaysAgo)
        }

        val receivedEvents = java.util.concurrent.ConcurrentLinkedQueue<Pair<Event, String>>()
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(0L)

        val handle = relayStateMachine.requestTemporarySubscriptionWithRelay(
            relayUrls = relayUrls,
            filters = listOf(filter),
            priority = SubscriptionPriority.HIGH
        ) { event, relayUrl ->
            if (event.kind in prewarmKinds) {
                receivedEvents.add(event to relayUrl)
                lastEventAt.set(System.currentTimeMillis())
            }
        }

        // Settle-based wait: stop early when events go quiet, cap at 8s
        val deadline = System.currentTimeMillis() + 8_000L
        val settleMs = 1_200L
        while (System.currentTimeMillis() < deadline) {
            delay(300)
            val last = lastEventAt.get()
            if (last > 0 && System.currentTimeMillis() - last > settleMs) break
            if (receivedEvents.size >= limit) break
        }
        handle.cancel()

        val events = receivedEvents.toList()
        if (events.isEmpty()) {
            MLog.d(TAG, "prewarmFeedCache: no events received after ${System.currentTimeMillis() - startMs}ms")
            return
        }

        // Persist to Room (same pipeline as live events, deduped by eventId)
        val entities = events.mapNotNull { (event, relayUrl) ->
            if (event.id in deletedEventIds) return@mapNotNull null
            val normalizedUrl = relayUrl.ifBlank { null }
            EventRelayTracker.addRelay(event.id, relayUrl)
            CachedEventEntity(
                eventId = event.id,
                kind = event.kind,
                pubkey = event.pubKey.lowercase(),
                createdAt = event.createdAt,
                eventJson = event.toJson(),
                relayUrl = normalizedUrl,
                relayUrls = normalizedUrl
            )
        }
        withContext(Dispatchers.IO) {
            try {
                dao.insertAll(entities)
                MLog.d(TAG, "prewarmFeedCache: persisted ${entities.size} events in ${System.currentTimeMillis() - startMs}ms")
            } catch (e: Exception) {
                MLog.e(TAG, "prewarmFeedCache: Room insert failed: ${e.message}", e)
            }
        }

        // Prefetch kind-0 profiles for all unique authors in the prewarm batch
        // so the feed renders with real display names from the first frame
        val profileRelayUrls = (indexerUrls + relayUrls).distinct().take(6)
        if (profileRelayUrls.isNotEmpty()) {
            val authorPubkeys = events.map { (ev, _) -> ev.pubKey.lowercase() }.distinct()
            scope.launch {
                try {
                    profileCache.requestProfiles(authorPubkeys, profileRelayUrls)
                    MLog.d(TAG, "prewarmFeedCache: queued ${authorPubkeys.size} profile fetches")
                } catch (e: Exception) {
                    MLog.w(TAG, "prewarmFeedCache: profile prefetch failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Call once from MainActivity.onCreate so the feed can be persisted and restored across app restarts.
     * Restores last saved notes if current feed is empty (e.g. after process death). The save job
     * is not cancelled on app pause. On cold start the dashboard re-applies the subscription when
     * currentAccount and relayCategories are ready.
     */
    fun prepareFeedCache(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        eventDao = AppDatabase.getInstance(context.applicationContext).eventDao()
        feedWindowManager = FeedWindowManager(
            eventDao = eventDao!!,
            entityToNote = { entity -> convertEntityToNote(entity) },
        )
        QuotedNoteCache.roomEventDao = eventDao
        loadDeletedIdsFromDisk(context.applicationContext)
        scope.launch { loadFeedCacheFromRoom() }
        // Migration: clear legacy SharedPreferences feed cache (one-time)
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = context.applicationContext.getSharedPreferences("notes_feed_cache", Context.MODE_PRIVATE)
                if (prefs.contains("feed_notes") || prefs.contains("feed_notes_following")) {
                    prefs.edit().clear().apply()
                    MLog.d(TAG, "Cleared legacy SharedPreferences feed cache")
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Load feed from Room event store on cold start. Replays raw events through
     * convertEventToNote() so the feed is populated identically to live events.
     * Replaces the old SharedPreferences JSON blob — no more 500KB-2MB I/O stalls.
     */
    private suspend fun loadFeedCacheFromRoom() {
        val dao = eventDao ?: run { _feedCacheChecked.value = true; return }
        withContext(Dispatchers.IO) {
            try {
                val entities = dao.getFeedEvents(FEED_CACHE_MAX)
                if (entities.isEmpty()) {
                    _feedCacheChecked.value = true
                    return@withContext
                }
                val notes = mutableListOf<Note>()
                val absoluteFloorMs = System.currentTimeMillis() - 14L * 86_400_000L
                var cacheDropped = 0
                for (entity in entities) {
                    if (entity.eventId in deletedEventIds) continue
                    try {
                        val event = Event.fromJson(entity.eventJson)
                        // Skip kind-6 reposts: their content is raw JSON of the original
                        // event. convertEventToNote would display it as-is (broken text).
                        // Reposts are reconstructed from live relay data via handleKind6Repost.
                        if (event.kind == 6) continue
                        // Hard age floor: reject events older than 14 days to prevent ancient
                        // notes from previous pagination sessions appearing on cold start.
                        val eventMs = event.createdAt * 1000L
                        if (eventMs < absoluteFloorMs) { cacheDropped++; continue }
                        val note = convertEventToNote(event, entity.relayUrl ?: "")
                        // Restore merged relay URLs from the dedicated column (schema v3+)
                        val allUrls = entity.relayUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                        // Seed EventRelayTracker from cached data so orbs survive cold starts
                        if (allUrls.isNotEmpty()) {
                            allUrls.forEach { url -> EventRelayTracker.addRelay(event.id, url) }
                        } else {
                            EventRelayTracker.addRelay(event.id, entity.relayUrl ?: "")
                        }
                        val restored = if (allUrls.isNotEmpty()) note.copy(relayUrls = allUrls) else note
                        if (!restored.isReply) notes.add(restored)
                    } catch (e: Exception) {
                        MLog.w(TAG, "Feed cache: skip bad event ${entity.eventId.take(8)}: ${e.message}")
                    }
                }
                if (cacheDropped > 0) MLog.w(TAG, "Feed cache: dropped $cacheDropped ancient events (>14d)")
                if (notes.isNotEmpty() && _notes.value.isEmpty()) {
                    setNotes(notes.toImmutableList())
                    _displayedNotes.value = notes.toImmutableList()
                    initialLoadComplete = true
                    val now = System.currentTimeMillis()
                    feedCutoffTimestampMs = now
                    latestNoteTimestampAtOpen = notes.maxOfOrNull { it.timestamp } ?: now
                    _feedSessionState.value = FeedSessionState.Live
                    _hasEverLoadedFeed.value = true
                    MLog.d(TAG, "Restored ${notes.size} notes from Room event store")
                    // Prefetch quoted notes for restored feed so cards render without spinners
                    QuotedNoteCache.prefetchForNotes(notes)
                    refreshAuthorsFromCache()
                }
            } catch (e: Exception) {
                MLog.e(TAG, "Load feed cache from Room failed: ${e.message}", e)
            } finally {
                _feedCacheChecked.value = true
            }
        }
    }

    /** Build kind-1 filter for current mode: when Following is on and we have follows, filter by authors; else null (global). */
    private fun buildKind1FilterForSubscription(): Filter? {
        if (!followFilterEnabled) return null
        val authors = followFilter ?: return null
        if (authors.isEmpty()) return null
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400L * FEED_SINCE_DAYS
        return Filter(
            kinds = listOf(1, 6969),
            authors = authors.toList(),
            limit = FOLLOWING_FEED_LIMIT,
            since = sevenDaysAgo
        )
    }

    /** Returns (relayUrls, kind1Filter) for resume so Following filter is never lost. Called by state machine on app resume. When Following is on but follow list is temporarily empty, use last applied filter so All notes do not bleed in. */
    private fun getSubscriptionForResume(): Pair<List<String>, Filter?> {
        val filter = buildKind1FilterForSubscription()
        return subscriptionRelays to (
            if (filter != null) filter
            else if (followFilterEnabled && (followFilter == null || followFilter!!.isEmpty())) lastAppliedKind1Filter
            else null
        )
    }

    /** Push current subscription to state machine (global or following). Call after setFollowFilter or when (re)subscribing. */
    private fun applySubscriptionToStateMachine(relayUrls: List<String>) {
        val filter = buildKind1FilterForSubscription()
        // Only update lastAppliedKind1Filter when we have a real filter or when explicitly
        // switching to global (!followFilterEnabled). When followFilterEnabled=true but
        // followFilter is null (follow list still loading), preserve the last known filter
        // so resume/keepalive reconnect doesn't fall back to global.
        if (filter != null) {
            lastAppliedKind1Filter = filter
        } else if (!followFilterEnabled) {
            lastAppliedKind1Filter = null
        }
        // When Following is on but follow list is empty (loading), use lastAppliedKind1Filter
        // to avoid sending a global subscription to relays.
        val effectiveFilter = filter ?: if (followFilterEnabled) lastAppliedKind1Filter else null
        // SAFETY NET: Never send a global (null filter) subscription when user expects Following mode.
        // If followFilterEnabled=true but we have no filter at all (cold start, no lastApplied),
        // skip the subscription entirely — the ingestion filter will drop everything anyway,
        // and the subscription will be re-applied once the follow list loads.
        if (effectiveFilter == null && followFilterEnabled && !isGlobalMode) {
            MLog.w(TAG, "BLOCKED global subscription while in Following mode — waiting for follow list to load")
            return
        }
        relayStateMachine.requestFeedChange(relayUrls, effectiveFilter)
    }

    /** Schedule a single display update after the event burst settles (smooth UI under high throughput). */
    private fun scheduleDisplayUpdate() {
        displayUpdateJob?.cancel()
        displayUpdateJob = scope.launch {
            delay(DISPLAY_UPDATE_DEBOUNCE_MS)
            updateDisplayedNotes()
            displayUpdateJob = null
        }
    }

    /**
     * Set the relay set used for the shared subscription (all user relays). Call once on load.
     * Registers as the resume subscription provider so app resume always re-applies the correct Following filter.
     */
    fun setSubscriptionRelays(allUserRelayUrls: List<String>) {
        if (allUserRelayUrls.sorted() == subscriptionRelays.sorted()) return
        MLog.d(TAG, "Subscription relays set: ${allUserRelayUrls.size} relays (stay connected to all)")
        subscriptionRelays = allUserRelayUrls
        // Keep the idempotency guard in sync so ensureSubscriptionToNotes doesn't
        // overwrite the state machine's subscription with a stale relay set.
        lastEnsuredRelaySet = allUserRelayUrls.toSet()
        profileCache.setFallbackRelayUrls(allUserRelayUrls)
        relayStateMachine.resumeSubscriptionProvider = { getSubscriptionForResume() }
        // Preload NIP-11 info for all subscription relays so relay orb icons are
        // available as soon as feed notes start arriving (avoids per-orb fetch lag).
        social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
            ?.preloadRelayInfo(allUserRelayUrls, scope)
        // Only reset to Idle when no notes exist (first load). When notes are already
        // present (e.g. user added a relay), keep the current session state so the
        // loading overlay doesn't flash over the existing feed.
        if (_notes.value.isEmpty()) {
            _feedSessionState.value = FeedSessionState.Idle
        }
    }

    /**
     * Set display filter only (sidebar selection). Does NOT change subscription or follow/reply filters;
     * only updates which relays' notes are shown. Follow and reply filters are preserved and re-applied.
     * Normalizes URLs so they match note.relayUrl from the state machine (avoids blank feed when switching).
     *
     * The app stays subscribed to ALL active relays globally — this only controls which
     * relays' notes are visible in the feed. Notes accumulate relay URLs as they arrive
     * from multiple relays (dedup merge in flushKind1Events), so filtering by a specific
     * relay shows notes that were actually received from it.
     */
    fun connectToRelays(displayFilterUrls: List<String>) {
        val normalized = displayFilterUrls.mapNotNull { RelayUrlNormalizer.normalizeOrNull(it)?.url }.distinct()
        connectedRelays = if (normalized.isNotEmpty()) normalized else displayFilterUrls
        MLog.d(TAG, "Display filter: ${connectedRelays.size} relay(s)")
        updateDisplayedNotes()
    }

    /**
     * Set follow filter: when enabled and followList is non-null, only notes from authors in followList are shown.
     * Pubkeys are normalized to lowercase so matching is case-insensitive (kind-3 vs event.pubKey can differ).
     * Re-subscribes with authors filter when Following is on so relays return follower notes directly (bandwidth savings).
     *
     * **Global mode**: When switching to All (enabled=false), marks global mode — notes are live-only and
     * never cached. When switching back to Following (enabled=true), global notes are destroyed and the
     * following feed is restored from disk cache.
     *
     * getSubscriptionForResume uses lastAppliedKind1Filter when follow list is temporarily empty on resume.
     */
    /**
     * Apply a custom pubkey set as the feed filter (e.g. from a NIP-51 people list).
     * Unlike [setFollowFilter], this bypasses the safety guard that prevents blanking
     * the feed when the follow list is transiently empty. An empty set here is intentional
     * (the user selected a list with no members) and should blank the feed.
     */
    fun setCustomListFilter(pubkeys: Set<String>) {
        val normalized = pubkeys.map { it.lowercase() }.toSet()
        followFilter = normalized.takeIf { it.isNotEmpty() }
        followFilterEnabled = true
        customListActive = true
        isGlobalMode = false
        profileCache.setPinnedPubkeys(normalized.takeIf { it.isNotEmpty() })
        updateDisplayedNotes()
        if (subscriptionRelays.isNotEmpty()) {
            applySubscriptionToStateMachine(subscriptionRelays)
            MLog.d(TAG, "Custom list filter applied: ${normalized.size} authors")
        }
    }

    fun setFollowFilter(followList: Set<String>?, enabled: Boolean) {
        customListActive = false
        val wasGlobal = isGlobalMode
        // Normalize to lowercase; treat empty set as null (still loading).
        // When effective is null and enabled=true, ingestion filter drops ALL notes (no global bleed).
        val effective = followList?.map { it.lowercase() }?.toSet()?.takeIf { it.isNotEmpty() }
        // Idempotency: skip if nothing actually changed (prevents re-subscription loop
        // when DashboardScreen's LaunchedEffect re-fires with identical follow list content)
        if (enabled == followFilterEnabled && effective == followFilter) return
        // SAFETY: never null out a populated follow filter — transient empty follow list
        // (e.g. from ContactListRepository re-fetch or LaunchedEffect re-fire) would blank the feed.
        // Keep the existing filter; it will be overwritten once the real follow list arrives.
        if (effective == null && enabled && followFilter != null && !wasGlobal) {
            MLog.w(TAG, "setFollowFilter: ignoring null effective — keeping existing ${followFilter!!.size}-author filter")
            return
        }
        followFilter = effective
        followFilterEnabled = enabled
        profileCache.setPinnedPubkeys(if (enabled && !followFilter.isNullOrEmpty()) followFilter else null)

        val enteringGlobal = !enabled && !wasGlobal
        val leavingGlobal = enabled && wasGlobal

        if (enteringGlobal) {
            // Snapshot following notes in memory before clearing so we can restore instantly
            val currentNotes = _notes.value
            if (currentNotes.isNotEmpty()) {
                followingNotesSnapshot = currentNotes
                MLog.d(TAG, "Saved ${currentNotes.size} following notes to memory snapshot")
            }
            // Stop outbox subscriptions so they don't inject followed-only notes into the global feed
            outboxFeedManager.stop()
            // Entering Global/All: clear feed, mark global, start fresh live subscription
            isGlobalMode = true
            kind1Dirty.set(false)
            pendingKind1Events.clear()
            setNotes(persistentListOf())
            _displayedNotes.value = persistentListOf()
            synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
            initialLoadComplete = false
            paginationCursorMs = 0L
            paginationExtraCap = 0
            paginationEmptyStreak = 0
            _paginationExhausted.value = false
            feedCutoffTimestampMs = System.currentTimeMillis()
            MLog.d(TAG, "Entering Global mode — feed cleared, outbox stopped, live-only")
            // Start global enrichment with user's subscribed hashtags and any active lists
            val hashtags = PeopleListRepository.subscribedHashtags.value
            val listDTags = emptySet<String>() // Will be updated from DashboardScreen
            if (hashtags.isNotEmpty() || listDTags.isNotEmpty()) {
                globalFeedManager.start(hashtags = hashtags, listDTags = listDTags)
            }
        } else if (leavingGlobal) {
            // Leaving Global/All: destroy global notes, restore following feed from memory snapshot
            isGlobalMode = false
            kind1Dirty.set(false)
            pendingKind1Events.clear()
            synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
            // Restore from memory snapshot (instant) or fall back to disk cache (async)
            val snapshot = followingNotesSnapshot
            if (snapshot.isNotEmpty()) {
                setNotes(snapshot)
                _displayedNotes.value = snapshot
                initialLoadComplete = true
                val now = System.currentTimeMillis()
                feedCutoffTimestampMs = now
                latestNoteTimestampAtOpen = snapshot.maxOfOrNull { it.timestamp } ?: now
                _feedSessionState.value = FeedSessionState.Live
                MLog.d(TAG, "Leaving Global mode — restored ${snapshot.size} following notes from memory")
            } else {
                setNotes(persistentListOf())
                _displayedNotes.value = persistentListOf()
                initialLoadComplete = false
                paginationCursorMs = 0L
                paginationExtraCap = 0
                paginationEmptyStreak = 0
                _paginationExhausted.value = false
                feedCutoffTimestampMs = System.currentTimeMillis()
                scope.launch { loadFeedCacheFromRoom() }
                MLog.d(TAG, "Leaving Global mode — no memory snapshot, restoring from Room")
            }
            // Stop global enrichment subscriptions when leaving global mode
            globalFeedManager.stop()
        } else {
            // Not entering or leaving global — just updating follow list within same mode.
            // Do NOT touch isGlobalMode here; it was already set correctly on mode entry/exit.
        }

        updateDisplayedNotes()
        if (subscriptionRelays.isNotEmpty()) {
            applySubscriptionToStateMachine(subscriptionRelays)
            val mode = if (enabled && !followFilter.isNullOrEmpty()) "following (${followFilter!!.size} authors)" else "global"
            MLog.d(TAG, "Re-subscribed: $mode")
        }
    }

    /** Tracks which note IDs were last sent to NoteCountsRepository.
     *  Used to skip the expensive relay-map + depth-2 scan when only note
     *  data changes (relay merges, profile resolution) without new IDs. */
    @Volatile private var lastDisplayedNoteIdSet: Set<String> = emptySet()

    /** Current scroll position (first visible item index) — set by DashboardScreen.
     *  Used to scope interest registration to a sliding window around the viewport. */
    @Volatile private var currentScrollPosition: Int = 0

    /** Called by DashboardScreen when the user scrolls. Updates the viewport-scoped
     *  interest window without triggering a full display update.
     *  Also drives memory eviction: when the user scrolls back toward the top,
     *  Room-paginated history that is far below the viewport is trimmed. */
    fun updateVisibleScrollPosition(firstVisibleIndex: Int) {
        currentScrollPosition = firstVisibleIndex
        // Evict Room-paginated tail when user scrolls back toward the top.
        // The in-memory _notes covers the live window; Room tail is deep history
        // loaded by loadOlderNotes. When the user scrolls up, that history is no
        // longer visible and can be released (it's still in Room for re-pagination).
        val inMemSize = _notes.value.size
        if (roomPaginatedNotes.isNotEmpty() && firstVisibleIndex < inMemSize / 2) {
            // User is in the top half of in-memory notes — trim Room tail
            val tailSize = roomPaginatedNotes.size
            if (tailSize > 50) {
                synchronized(roomPaginatedNotes) {
                    val trimCount = tailSize / 2
                    repeat(trimCount) {
                        if (roomPaginatedNotes.isNotEmpty()) {
                            val removed = roomPaginatedNotes.removeAt(roomPaginatedNotes.size - 1)
                            roomPaginatedIds.remove(removed.id)
                        }
                    }
                }
                MLog.d(TAG, "Scroll eviction: trimmed roomTail $tailSize → ${roomPaginatedNotes.size} (scroll=$firstVisibleIndex, inMem=$inMemSize)")
            }
        }
    }

    /** Size of the sliding interest window around the scroll position.
     *  Notes outside this window still display (with cached counts) but
     *  don't actively consume relay subscription slots.
     *  Matches the viewport render window (~30 full NoteCards + 20 buffer). */
    private val INTEREST_WINDOW_SIZE = 50

    private fun updateDisplayedNotes() {
        android.os.Trace.beginSection("NotesRepo.updateDisplayedNotes")
        try {
            // ── Retroactive relay enrichment from EventRelayTracker ──────────
            // Events arriving from multiple relays record ALL relay URLs in the
            // tracker, but Note objects only capture the relay from their first
            // delivery. This pass merges accumulated tracker URLs back into Notes
            // so relay orbs grow as duplicates arrive from additional relays.
            //
            // Dirty-flag optimization: only run the O(n) pass when EventRelayTracker
            // has actually recorded new URLs since the last enrichment. During
            // steady-state with no new relay data, this skips the entire pass.
            if (EventRelayTracker.consumeEnrichmentDirty()) {
                val preEnrich = _notes.value
                // First pass: identify indices that need enrichment (cheap — no allocation per note)
                val dirtyIndices = mutableListOf<Int>()
                for (i in preEnrich.indices) {
                    val note = preEnrich[i]
                    val noteId = note.originalNoteId ?: note.id
                    val trackerId = if (noteId.startsWith("repost:")) noteId.removePrefix("repost:") else noteId
                    val existing = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    if (EventRelayTracker.hasNewRelays(trackerId, existing.size)) {
                        dirtyIndices.add(i)
                    }
                }
                // Only rebuild list if there are actual changes
                if (dirtyIndices.isNotEmpty()) {
                    val enriched = preEnrich.toMutableList()
                    for (i in dirtyIndices) {
                        val note = enriched[i]
                        val noteId = note.originalNoteId ?: note.id
                        val trackerId = if (noteId.startsWith("repost:")) noteId.removePrefix("repost:") else noteId
                        val existing = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                        val merged = EventRelayTracker.enrichRelayUrls(trackerId, existing)
                        if (merged.size > existing.size) {
                            enriched[i] = note.copy(relayUrls = merged)
                        }
                    }
                    setNotes(enriched.toImmutableList())
                    MLog.d(TAG, "\uD83D\uDD35 Tracker enrichment: ${dirtyIndices.size} notes gained relay URLs (of ${preEnrich.size} total)")
                }
            }

            // Pre-build a combined set of raw + normalized URLs for O(1) lookup (avoids per-note normalization)
            val hasRelayFilter = connectedRelays.isNotEmpty()
            val connectedSet = if (hasRelayFilter) buildSet {
                connectedRelays.forEach { url ->
                    add(url)
                    RelayUrlNormalizer.normalizeOrNull(url)?.url?.let { add(it) }
                }
            } else emptySet()
            val allNotes = _notes.value
            val currentFollowFilter = followFilter
            val followEnabled = followFilterEnabled
            val ownPk = currentUserPubkey

            // Boost dedup: if both original note X and repost:X exist, hide the original.
            // The repost version carries the boost badge, boosters list, and repost timestamp.
            // O(1) from persistent FeedIndex — replaces O(n) buildSet scan
            val repostedOriginals = feedIndex.repostedOriginalIds

            // Early exit: follow mode with empty follow list
            if (followEnabled) {
                if (currentFollowFilter == null || currentFollowFilter.isEmpty()) {
                    if (customListActive) {
                        // Intentional: user selected a people list with 0 members → blank feed
                        _displayedNotes.value = persistentListOf()
                        return
                    }
                    if (_displayedNotes.value.isNotEmpty()) {
                        MLog.w(TAG, "Follow filter temporarily empty, keeping ${_displayedNotes.value.size} displayed notes")
                        return
                    } else {
                        _displayedNotes.value = persistentListOf()
                        return
                    }
                }
            }

            // Single-pass filter: relay match + follow filter + reply filter + display window.
            // Replaces multiple sequential .filter() calls with one pass (1× O(N)).
            var afterRelay = 0
            var afterFollow = 0
            var afterReply = 0
            var afterWindow = 0
            // Display window: only render notes within DISPLAY_WINDOW_MS of the newest note.
            // Older notes are served from Room via pagination. The window floor is computed
            // from the newest note timestamp so the window slides forward as new notes arrive.
            val newestMs = allNotes.maxOfOrNull { it.repostTimestamp ?: it.timestamp } ?: System.currentTimeMillis()
            val displayWindowFloorMs = newestMs - DISPLAY_WINDOW_MS
            val filtered = ArrayList<Note>(minOf(allNotes.size, 500))
            for (note in allNotes) {
                // 1. Relay match — notes must come from a user-configured relay.
                // Outbox-discovered notes pass only if their relay URLs include a
                // configured relay (i.e. the same event also arrived from inbox).
                if (hasRelayFilter) {
                    val isLocal = locallyPublishedIds.contains(note.id) ||
                        (note.originalNoteId != null && locallyPublishedIds.contains(note.originalNoteId))
                    if (!isLocal) {
                        val urls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                        if (urls.isNotEmpty() && urls.none { it in connectedSet }) continue
                    }
                }
                afterRelay++
                // 2. Follow filter
                if (followEnabled && currentFollowFilter != null) {
                    val authorKey = normalizeAuthorIdForCache(note.author.id)
                    if (authorKey != ownPk && authorKey !in currentFollowFilter) continue
                }
                afterFollow++
                // 3. Reply filter
                if (note.isReply) continue
                afterReply++
                // 4. Boost dedup: skip original if a repost version exists
                if (note.originalNoteId == null && note.id in repostedOriginals) continue
                // 5. Temporal display window: exclude notes older than the sliding window.
                //    These notes exist in Room and can be loaded via loadOlderNotes pagination.
                //    User's own recently-published notes always pass (immediate feedback).
                val noteTs = note.repostTimestamp ?: note.timestamp
                if (noteTs < displayWindowFloorMs) {
                    val isLocal = locallyPublishedIds.contains(note.id) ||
                        (note.originalNoteId != null && locallyPublishedIds.contains(note.originalNoteId))
                    if (!isLocal) continue
                }
                afterWindow++
                filtered.add(note)
            }
            if (filtered.size != _displayedNotes.value.size || filtered.isEmpty() || roomPaginatedNotes.isNotEmpty()) {
                MLog.w(TAG, "displayFilter: total=${allNotes.size} →relay=$afterRelay →follow=$afterFollow →noReply=$afterReply →window=$afterWindow →final=${filtered.size} +roomTail=${roomPaginatedNotes.size} (connRelays=${connectedRelays.size}, followList=${currentFollowFilter?.size ?: 0})")
            }
            // Skip emission if the note ID list is identical — prevents unnecessary
            // LazyColumn re-layout that causes scroll jumps and nav bar reappearing.
            val currentIds = _displayedNotes.value
            // Append Room-paginated notes (deep history from Room, outside _notes cap)
            val roomTail = if (roomPaginatedNotes.isNotEmpty()) {
                synchronized(roomPaginatedNotes) { roomPaginatedNotes.toList() }
            } else emptyList()
            val newList = if (roomTail.isNotEmpty()) filtered.toList() + roomTail else filtered.toList()
            val idsMatch = newList.size == currentIds.size &&
                newList.indices.all { i -> newList[i].id == currentIds[i].id }
            if (idsMatch) {
                // IDs match but note data may have updated (relay URLs, author info) —
                // only emit if any note object actually differs (referential check).
                val dataChanged = newList.indices.any { i -> newList[i] !== currentIds[i] }
                if (dataChanged) {
                    val multiOrb = newList.count { it.relayUrls.size > 1 }
                    MLog.d(TAG, "\uD83D\uDD35 Display emit (data changed, IDs same): ${newList.size} notes, $multiOrb multi-orb")
                    _displayedNotes.value = newList.toImmutableList()
                }
                updateDisplayedNewNotesCount()
                return
            }
            // Log relay orb stats for diagnostics
            val multiOrb = newList.count { it.relayUrls.size > 1 }
            val singleOrb = newList.count { it.relayUrls.size == 1 }
            MLog.d(TAG, "\uD83D\uDD35 Display emit (IDs changed): ${newList.size} notes, $multiOrb multi-orb, $singleOrb single-orb")
            if (newList.isNotEmpty()) {
                val sample = newList.first()
                MLog.d(TAG, "\uD83D\uDD35   sample[0] ${sample.id.take(8)}: relayUrls=${sample.relayUrls}, relayUrl=${sample.relayUrl}")
            }
            _displayedNotes.value = newList.toImmutableList()
            updateDisplayedNewNotesCount()
            // Register note IDs for counts only when the windowed ID set changes.
            // Use a sliding window around the scroll position instead of the full list
            // so the relay subscription cost stays O(INTEREST_WINDOW_SIZE) regardless of
            // feed depth. Notes outside the window keep their cached counts from
            // _countsByNoteId — they just don't get active relay subscriptions.
            val scrollPos = currentScrollPosition
            val windowStart = maxOf(0, scrollPos - 30) // small lookback buffer
            val windowEnd = minOf(newList.size, scrollPos + INTEREST_WINDOW_SIZE)
            val windowNotes = if (windowEnd > windowStart) newList.subList(windowStart, windowEnd) else newList
            val newIdSet = buildSet(windowNotes.size) {
                for (note in windowNotes) add(note.originalNoteId ?: note.id)
            }
            if (newIdSet != lastDisplayedNoteIdSet) {
                lastDisplayedNoteIdSet = newIdSet
                val noteRelayMap = LinkedHashMap<String, List<String>>(windowNotes.size)
                for (note in windowNotes) {
                    val relays = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    val effectiveId = note.originalNoteId ?: note.id
                    if (effectiveId !in noteRelayMap) {
                        noteRelayMap[effectiveId] = relays
                    }
                    note.quotedEventIds.forEach { qid ->
                        if (qid !in noteRelayMap) {
                            val cached = QuotedNoteCache.getCached(qid)
                            noteRelayMap[qid] = listOfNotNull(cached?.relayUrl).ifEmpty { relays }
                        }
                    }
                }
                NoteCountsRepository.setNoteIdsOfInterest(noteRelayMap)
            }
        } catch (e: Throwable) {
            MLog.e(TAG, "updateDisplayedNotes failed: ${e.message}", e)
        } finally {
            android.os.Trace.endSection()
        }
    }

    /** Pending new notes counts for All and Following (by current relay set); separate so both filters show correct counts. */
    private fun updateDisplayedNewNotesCount() {
        try {
            val hasRelayFilter = connectedRelays.isNotEmpty()
            val connectedSet = if (hasRelayFilter) buildSet {
                connectedRelays.forEach { url ->
                    add(url)
                    RelayUrlNormalizer.normalizeOrNull(url)?.url?.let { add(it) }
                }
            } else emptySet()
            val filter = followFilter
            var countAll = 0
            var countFollowing = 0
            val pendingSnapshot = synchronized(pendingNotesLock) { _pendingNewNotes.toList() }
            val relayMatch: (Note) -> Boolean = { note ->
                if (!hasRelayFilter) true
                else {
                    val urls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    urls.isEmpty() || urls.any { it in connectedSet }
                }
            }
            for (note in pendingSnapshot) {
                val relayOk = relayMatch(note)
                val notReply = !note.isReply
                if (!relayOk || !notReply) continue
                countAll++
                val followOk = filter != null && note.author.id.lowercase() in filter
                if (followOk) countFollowing++
            }
            _newNotesCounts.value = NewNotesCounts(countAll, countFollowing, System.currentTimeMillis())
        } catch (e: Throwable) {
            MLog.e(TAG, "updateDisplayedNewNotesCount failed: ${e.message}", e)
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        }
    }

    /**
     * Disconnect from all relays (e.g. on screen exit). Delegates to shared state machine.
     */
    fun disconnectAll() {
        MLog.d(TAG, "Disconnecting from all relays")
        outboxFeedManager.stop()
        kind1Dirty.set(false)
        pendingKind1Events.clear()
        relayStateMachine.resumeSubscriptionProvider = null
        relayStateMachine.requestDisconnect()
        connectedRelays = emptyList()
        subscriptionRelays = emptyList()
        followFilter = null
        followFilterEnabled = false
        isGlobalMode = false
        followingNotesSnapshot = persistentListOf()
        lastAppliedKind1Filter = null
        _feedSessionState.value = FeedSessionState.Idle
        setNotes(persistentListOf())
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        feedCutoffTimestampMs = 0L
        latestNoteTimestampAtOpen = 0L
        initialLoadComplete = false
    }

    /**
     * Ensure subscription to kind-1 notes for ALL user relays. Pass allUserRelayUrls (not sidebar selection).
     * Display filter is set separately via connectToRelays(displayFilterUrls).
     */
    /** Relay set from the last successful ensureSubscriptionToNotes call (idempotency guard). */
    @Volatile private var lastEnsuredRelaySet: Set<String> = emptySet()

    /** Invalidate the idempotency guard so the next ensureSubscriptionToNotes re-applies.
     *  Call when relay config changes externally (e.g. relay manager add/remove). */
    fun invalidateSubscriptionGuard() {
        lastEnsuredRelaySet = emptySet()
    }

    suspend fun ensureSubscriptionToNotes(allUserRelayUrls: List<String>, limit: Int = 100) {
        if (allUserRelayUrls.isEmpty()) {
            MLog.w(TAG, "No relays configured")
            return
        }
        // Idempotency guard: skip only if we're already subscribed to the same relay set,
        // notes are loaded, AND the feed is Live (not still Loading from a previous call).
        val relaySet = allUserRelayUrls.toSet()
        if (relaySet == lastEnsuredRelaySet && _notes.value.isNotEmpty() && _feedSessionState.value == FeedSessionState.Live && RelayConnectionStateMachine.getInstance().isSubscriptionActive()) {
            MLog.d(TAG, "ensureSubscriptionToNotes: already active for ${relaySet.size} relays, skipping")
            return
        }
        lastEnsuredRelaySet = relaySet
        setSubscriptionRelays(allUserRelayUrls)

        // Wait for Room cache check to complete before deciding resume vs fresh subscribe.
        // Without this, a race between loadFeedCacheFromRoom() (async from prepareFeedCache)
        // and this method would see _notes.value empty, fall through to subscribeToNotes(),
        // which wipes the feed — destroying Room-restored notes.
        if (!_feedCacheChecked.value) {
            MLog.d(TAG, "ensureSubscriptionToNotes: waiting for feed cache check to complete...")
            kotlinx.coroutines.withTimeoutOrNull(3000L) {
                _feedCacheChecked.first { it }
            }
            MLog.d(TAG, "ensureSubscriptionToNotes: feed cache check done, notes=${_notes.value.size}")
        }

        if (_notes.value.isNotEmpty()) {
            // Resume: keep existing feed from Room cache; re-apply subscription without clearing.
            MLog.d(TAG, "Restoring subscription for ${allUserRelayUrls.size} relays (keeping ${_notes.value.size} notes)")
            // Initialize guards that were lost on process kill:
            val now = System.currentTimeMillis()
            if (feedCutoffTimestampMs == 0L) {
                feedCutoffTimestampMs = now
            }
            // Set the latest note timestamp so the pending gate works correctly:
            // events newer than this go to "X new notes" instead of polluting the feed.
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: now
            // Re-initialize age floor so the gate actually fires (was 0 after process kill)
            if (mainSubscriptionFloorMs == 0L) {
                mainSubscriptionFloorMs = now - FEED_SINCE_DAYS.toLong() * 86_400_000L
                feedAgeFloorMs = mainSubscriptionFloorMs
            }
            // Mark initial load complete so new events go to pending, not directly to feed
            initialLoadComplete = true
            applySubscriptionToStateMachine(allUserRelayUrls)
            // Mark feed as Live so UI scroll-to-top and other session-aware logic fires
            if (_feedSessionState.value != FeedSessionState.Live) {
                _feedSessionState.value = FeedSessionState.Live
            }
            _hasEverLoadedFeed.value = true
            // Re-trigger counts now that the main feed subscription is active (counts may have
            // fired too early when notes were restored from cache before relay connected)
            NoteCountsRepository.retrigger()
            return
        }
        subscribeToNotes(limit)
    }

    /**
     * Subscribe to kind-1 notes from ALL subscription relays. Uses subscriptionRelays (all user relays), not display filter.
     * Sets feed cutoff at connection start: only notes with timestamp <= cutoff are shown; newer notes build up in pending until refresh.
     */
    suspend fun subscribeToNotes(limit: Int = 100) {
        if (subscriptionRelays.isEmpty()) {
            MLog.w(TAG, "No subscription relays set")
            _isLoading.value = false
            initialLoadComplete = true
            return
        }

        kind1Dirty.set(false)
        pendingKind1Events.clear()
        _isLoading.value = true
        _feedSessionState.value = FeedSessionState.Loading
        _error.value = null
        setNotes(persistentListOf())
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false

        // Cutoff set exactly when we start the subscription: only notes older than this moment are shown in the feed
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs
        // Age floor: drop events from the main subscription older than the subscription's since window.
        // This is the hard boundary — relays that ignore `since` can't contaminate the feed.
        mainSubscriptionFloorMs = System.currentTimeMillis() - FEED_SINCE_DAYS.toLong() * 86_400_000L
        feedAgeFloorMs = mainSubscriptionFloorMs
        paginationCursorMs = 0L
        paginationExtraCap = 0
        paginationEmptyStreak = 0
        perRelayCursorMs.clear()
        exhaustedPaginationRelays.clear()
        _paginationExhausted.value = false
        _timeGapIndex.value = null

        try {
            applySubscriptionToStateMachine(subscriptionRelays)
            // Non-blocking: launch a background job that polls for initial notes.
            // The caller (loadNotesFromFavoriteCategory) returns immediately so the
            // viewModelScope coroutine is not blocked. This prevents the feed hang
            // that occurred when a racing second subscription killed the first one's
            // events mid-delivery, leaving the polling loop stuck for 3 seconds.
            initialLoadJob?.cancel()
            initialLoadJob = scope.launch {
                val maxWaitMs = 3000L
                val pollIntervalMs = 100L
                var waited = 0L
                while (waited < maxWaitMs) {
                    delay(pollIntervalMs)
                    waited += pollIntervalMs
                    kind1Dirty.set(false)
                    flushKind1Events()
                    if (_notes.value.isNotEmpty()) break
                }
                latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
                initialLoadComplete = true
                _isLoading.value = false
                _feedSessionState.value = FeedSessionState.Live
                _hasEverLoadedFeed.value = true
                updateDisplayedNotes()
                MLog.d(TAG, "Subscription active for ${subscriptionRelays.size} relays, ${_notes.value.size} notes loaded in ${waited}ms (feed cutoff at $feedCutoffTimestampMs)")
            }
        } catch (e: Exception) {
            MLog.e(TAG, "Error subscribing to notes: ${e.message}", e)
            _error.value = "Failed to load notes: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
            _feedSessionState.value = FeedSessionState.Live
            _hasEverLoadedFeed.value = true
            updateDisplayedNotes()
        }
    }

    /** Pagination cursor: timestamp (ms) of the oldest kind-1 note we've shown in the feed.
     *  Updated as notes are added. Excludes repost timestamps so a repost of a 2020 note
     *  doesn't jump the cursor back years. */
    @Volatile private var paginationCursorMs: Long = 0L
    /** Extra capacity added by pagination; grows by page size each time loadOlderNotes fires. */
    @Volatile private var paginationExtraCap: Int = 0

    /** Called from flushKind1Events after merging feed notes to advance the pagination cursor.
     *  Uses 5th-percentile timestamp to resist outliers. If cursor stalls (same value after a
     *  page), falls back to absolute min to guarantee forward progress. */
    private fun advancePaginationCursor(notes: List<Note>) {
        if (notes.isEmpty()) return
        val timestamps = notes.map { it.timestamp }.sorted()
        // 5th percentile: skip bottom 5% outliers
        val p5Index = (timestamps.size * 0.05).toInt().coerceIn(0, timestamps.lastIndex)
        val candidate = timestamps[p5Index]
        val absMin = timestamps.first()
        val prev = paginationCursorMs
        if (prev == 0L || candidate < prev) {
            paginationCursorMs = candidate
            MLog.d(TAG, "Cursor advanced: ${fmtMs(prev)} → ${fmtMs(candidate)} (p5, absMin=${fmtMs(absMin)})")
        } else if (absMin < prev) {
            // Stall: p5 didn't advance (outlier cluster), fall back to absolute min
            paginationCursorMs = absMin
            MLog.d(TAG, "Cursor stall-break: ${fmtMs(prev)} → ${fmtMs(absMin)} (absMin fallback)")
        }
    }

    private fun fmtMs(ms: Long): String =
        if (ms <= 0) "0" else java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.US).format(ms)

    /**
     * Load older notes beyond the current feed. Creates a temporary subscription with
     * `until` = pagination cursor (relay-side cursor pagination).
     * No `since` parameter — let the relay return whatever it has up to `limit`.
     * Called when user scrolls to the bottom of the feed.
     *
     * Uses HIGH priority so it can preempt NORMAL enrichment subs (counts, profiles)
     * on constrained relays (e.g. single-relay testing).
     */
    fun loadOlderNotes() {
        if (_isLoadingOlder.value) return
        if (_paginationExhausted.value) return
        val currentNotes = _notes.value
        if (currentNotes.isEmpty()) return

        // Use the dedicated pagination cursor if available; fall back to oldest note timestamp
        val cursorMs = if (paginationCursorMs > 0) paginationCursorMs
            else currentNotes.minOf { it.timestamp }
        val untilSec = cursorMs / 1000

        val authors = if (followFilterEnabled) followFilter?.toList() else null
        if (followFilterEnabled && authors.isNullOrEmpty()) return

        val outboxUrls = outboxFeedManager.activeOutboxRelays.value
            .map { it.url }
            .filter { it.isNotBlank() }
        val relays = (subscriptionRelays + outboxUrls).distinct().ifEmpty { return }

        _isLoadingOlder.value = true

        // ── Room-backed windowed paging: load from Room into the Room tail ──
        // Notes go to roomPaginatedNotes, which updateDisplayedNotes() always
        // appends after the filtered in-memory portion. This prevents the
        // in-memory cap from trimming paginated history.
        val wm = feedWindowManager
        if (wm != null) {
            // Sync follow filter state
            wm.followFilterEnabled = followFilterEnabled
            wm.followPubkeys = authors ?: emptyList()
            scope.launch {
                try {
                    val roomNotes = wm.loadPage(untilSec)
                    if (roomNotes.isNotEmpty()) {
                        // Dedup against in-memory and existing Room-paginated notes
                        val existingMemIds = feedIndex.byId.keys
                        val newNotes = roomNotes.filter { it.id !in existingMemIds && it.id !in roomPaginatedIds }
                        if (newNotes.isNotEmpty()) {
                            // Add to Room tail buffer
                            for (note in newNotes) {
                                roomPaginatedNotes.add(note)
                                roomPaginatedIds.add(note.id)
                            }
                            // Cap: trim oldest entries to prevent unbounded growth
                            if (roomPaginatedNotes.size > MAX_ROOM_PAGINATED) {
                                synchronized(roomPaginatedNotes) {
                                    while (roomPaginatedNotes.size > MAX_ROOM_PAGINATED) {
                                        val removed = roomPaginatedNotes.removeAt(roomPaginatedNotes.size - 1)
                                        roomPaginatedIds.remove(removed.id)
                                    }
                                }
                                MLog.d(TAG, "loadOlderNotes: trimmed roomTail to $MAX_ROOM_PAGINATED")
                            }
                            // Update cursor from oldest loaded note
                            val oldestMs = newNotes.minOf { it.timestamp }
                            if (paginationCursorMs == 0L || oldestMs < paginationCursorMs) {
                                paginationCursorMs = oldestMs
                            }
                            if (oldestMs < feedAgeFloorMs) feedAgeFloorMs = oldestMs
                            MLog.w(TAG, "loadOlderNotes: Room served ${newNotes.size} notes, roomTail=${roomPaginatedNotes.size}, cursor=${fmtMs(paginationCursorMs)}")
                            // Trigger display update to append Room tail
                            updateDisplayedNotes()
                        } else {
                            MLog.d(TAG, "loadOlderNotes: Room ${roomNotes.size} events all dupes")
                        }
                        _isLoadingOlder.value = false
                        return@launch
                    }
                } catch (e: Exception) {
                    MLog.e(TAG, "Room-backed pagination failed: ${e.message}", e)
                }
                // Room empty for this window — fall back to relay fetch
                loadOlderNotesFromRelay(cursorMs, untilSec, authors, relays)
            }
            return
        }

        // No Room available — relay fetch directly
        loadOlderNotesFromRelay(cursorMs, untilSec, authors, relays)
    }

    /** Relay-based pagination with per-relay cursors.
     *  Each relay tracks its own oldest-event timestamp so dense relays (damus) paginate
     *  independently from sparse relays (pickle). Exhausted relays are skipped. */
    private fun loadOlderNotesFromRelay(cursorMs: Long, untilSec: Long, authors: List<String>?, relays: List<String>) {
        olderNotesHandle?.cancel()

        // Filter out exhausted relays — they returned 0 events last time
        val activeRelays = relays.filter { it !in exhaustedPaginationRelays }
        if (activeRelays.isEmpty()) {
            MLog.w(TAG, "loadOlderNotes: all ${relays.size} relays exhausted — pagination done")
            _paginationExhausted.value = true
            _isLoadingOlder.value = false
            return
        }

        // Build per-relay filters: each relay gets its own `until` based on where it left off
        val perRelayFilters = activeRelays.associate { relayUrl ->
            val relayCursor = perRelayCursorMs[relayUrl]
            val effectiveUntilSec = if (relayCursor != null && relayCursor > 0) {
                relayCursor / 1000  // Use this relay's own cursor
            } else {
                untilSec  // First page: use global cursor
            }
            val filter = if (authors != null) {
                Filter(kinds = listOf(1), authors = authors, limit = PAGINATION_PER_RELAY_LIMIT, until = effectiveUntilSec)
            } else {
                Filter(kinds = listOf(1), limit = PAGINATION_PER_RELAY_LIMIT, until = effectiveUntilSec)
            }
            relayUrl to listOf(filter)
        }

        MLog.w(TAG, "loadOlderNotes: ${activeRelays.size}/${relays.size} active relays " +
            "(${exhaustedPaginationRelays.size} exhausted), cursors: ${activeRelays.take(5).joinToString { 
                val c = perRelayCursorMs[it]
                "${it.removePrefix("wss://").removeSuffix("/").takeLast(20)}=${if (c != null) fmtMs(c) else "init"}"
            }}")

        // Lower the age floor based on HEALTHY relay cursors only (relays returning
        // meaningful data). Zombie relays stuck at ancient timestamps (e.g. pickle at 11/30)
        // must not pull the floor back months.
        val healthyCursors = perRelayCursorMs.filter { it.key !in exhaustedPaginationRelays }
        val oldestHealthyCursorMs = healthyCursors.values.minOrNull() ?: cursorMs
        val pageFloorMs = oldestHealthyCursorMs - PAGINATION_PAGE_DAYS * 86_400_000L
        if (pageFloorMs < feedAgeFloorMs) {
            feedAgeFloorMs = pageFloorMs
        }

        val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)
        val oldestReceivedMs = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        // Track per-relay event counts and oldest timestamps for cursor updates
        val perRelayEventCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val perRelayOldestMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
        // Collect raw events for Room persistence — pagination events bypass _notes entirely
        // and go Room → roomPaginatedNotes so they aren't killed by trimNotesToCap's window.
        val collectedEvents = java.util.concurrent.ConcurrentLinkedQueue<Pair<Event, String>>()

        olderNotesHandle = relayStateMachine.requestTemporarySubscriptionPerRelayWithRelay(
            relayFilters = perRelayFilters,
            priority = SubscriptionPriority.HIGH,
        ) { event, relayUrl ->
            if (event.kind == 1) {
                lastEventAt.set(System.currentTimeMillis())
                eventCount.incrementAndGet()
                val eventMs = event.createdAt * 1000L
                oldestReceivedMs.updateAndGet { prev -> minOf(prev, eventMs) }
                // Track per-relay stats
                perRelayEventCount.merge(relayUrl, 1) { old, inc -> old + inc }
                perRelayOldestMs.merge(relayUrl, eventMs) { old, new -> minOf(old, new) }
                EventRelayTracker.addRelay(event.id, relayUrl)
                collectedEvents.add(event to relayUrl)
            }
        }

        // Settle-based wait: break early when stream goes quiet
        scope.launch {
            val deadline = System.currentTimeMillis() + OLDER_NOTES_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(300)
                val lastAt = lastEventAt.get()
                if (lastAt > 0) {
                    val quietMs = System.currentTimeMillis() - lastAt
                    if (quietMs >= OLDER_NOTES_SETTLE_MS) break
                }
            }
            olderNotesHandle?.cancel()
            olderNotesHandle = null

            val received = eventCount.get()

            // ── Persist to Room (same as flushEventStore but inline for pagination) ──
            val dao = eventDao
            if (dao != null && collectedEvents.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val entities = collectedEvents.mapNotNull { (event, relayUrl) ->
                        val normalizedUrl = relayUrl.ifBlank { null }
                        val trackedUrls = EventRelayTracker.getRelays(event.id)
                        val allUrls = if (trackedUrls.isNotEmpty()) {
                            (trackedUrls + listOfNotNull(normalizedUrl)).distinct().joinToString(",")
                        } else normalizedUrl
                        CachedEventEntity(
                            eventId = event.id,
                            kind = event.kind,
                            pubkey = event.pubKey.lowercase(),
                            createdAt = event.createdAt,
                            eventJson = event.toJson(),
                            relayUrl = normalizedUrl,
                            relayUrls = allUrls,
                            isReply = Nip10ReplyDetector.isReply(event)
                        )
                    }
                    try {
                        dao.insertAll(entities)
                        feedWindowManager?.onRoomDataChanged()
                        MLog.d(TAG, "Pagination: persisted ${entities.size} events to Room")
                    } catch (e: Exception) {
                        MLog.e(TAG, "Pagination Room persist failed: ${e.message}", e)
                    }
                }
            }

            // ── Load from Room into roomPaginatedNotes (unified path) ──
            val wm = feedWindowManager
            var roomAdded = 0
            if (wm != null && received > 0) {
                wm.followFilterEnabled = followFilterEnabled
                wm.followPubkeys = authors ?: emptyList()
                val roomNotes = wm.loadPage(untilSec)
                if (roomNotes.isNotEmpty()) {
                    val existingMemIds = feedIndex.byId.keys
                    val newNotes = roomNotes.filter { it.id !in existingMemIds && it.id !in roomPaginatedIds }
                    if (newNotes.isNotEmpty()) {
                        for (note in newNotes) {
                            roomPaginatedNotes.add(note)
                            roomPaginatedIds.add(note.id)
                        }
                        if (roomPaginatedNotes.size > MAX_ROOM_PAGINATED) {
                            synchronized(roomPaginatedNotes) {
                                while (roomPaginatedNotes.size > MAX_ROOM_PAGINATED) {
                                    val removed = roomPaginatedNotes.removeAt(roomPaginatedNotes.size - 1)
                                    roomPaginatedIds.remove(removed.id)
                                }
                            }
                        }
                        roomAdded = newNotes.size
                    }
                }
            }

            // Update per-relay cursors from actual received data
            for ((relayUrl, oldest) in perRelayOldestMs) {
                val current = perRelayCursorMs[relayUrl]
                if (current != null && current - oldest > MAX_CURSOR_JUMP_MS) {
                    exhaustedPaginationRelays.add(relayUrl)
                    MLog.w(TAG, "Relay cursor-clamped: ${relayUrl.removePrefix("wss://").removeSuffix("/")} " +
                        "jumped ${fmtMs(current)} → ${fmtMs(oldest)} (>${MAX_CURSOR_JUMP_MS / 86_400_000L}d gap)")
                } else if (current == null || oldest < current) {
                    perRelayCursorMs[relayUrl] = oldest
                }
            }
            val MIN_USEFUL_EVENTS = 2
            for (relayUrl in activeRelays) {
                if (relayUrl in exhaustedPaginationRelays) continue
                val count = perRelayEventCount[relayUrl] ?: 0
                if (count < MIN_USEFUL_EVENTS) {
                    exhaustedPaginationRelays.add(relayUrl)
                    MLog.w(TAG, "Relay exhausted: ${relayUrl.removePrefix("wss://").removeSuffix("/")} ($count < $MIN_USEFUL_EVENTS events this page)")
                }
            }

            // Update global cursor from the oldest event we actually received
            val oldest = oldestReceivedMs.get()
            if (oldest < Long.MAX_VALUE && oldest > 0) {
                if (paginationCursorMs == 0L || oldest < paginationCursorMs) {
                    paginationCursorMs = oldest
                }
                if (oldest < feedAgeFloorMs) feedAgeFloorMs = oldest
            }

            // Exhaustion detection
            if (received == 0) {
                paginationEmptyStreak++
                if (paginationEmptyStreak >= 2) {
                    _paginationExhausted.value = true
                    MLog.w(TAG, "Pagination exhausted: $paginationEmptyStreak consecutive empty responses")
                }
            } else if (roomAdded == 0) {
                paginationEmptyStreak++
                if (paginationEmptyStreak >= 2) {
                    _paginationExhausted.value = true
                    MLog.w(TAG, "Pagination stopped: $paginationEmptyStreak pages yielded 0 displayable notes ($received recv)")
                }
            } else {
                paginationEmptyStreak = 0
            }
            val relayStats = activeRelays.take(5).joinToString { r ->
                val cnt = perRelayEventCount[r] ?: 0
                val cur = perRelayCursorMs[r]?.let { fmtMs(it) } ?: "?"
                "${r.removePrefix("wss://").removeSuffix("/").takeLast(18)}=$cnt→$cur"
            }
            MLog.w(TAG, "Older notes (relay→Room→tail): +$roomAdded displayed ($received recv), roomTail=${roomPaginatedNotes.size} | $relayStats")

            if (roomAdded > 0) updateDisplayedNotes()
            _isLoadingOlder.value = false
        }
    }

    /**
     * Subscribe to notes from a specific relay only. Uses requestFeedChange (subscription swap, no disconnect).
     */
    suspend fun subscribeToRelayNotes(relayUrl: String, limit: Int = 100) {
        _isLoading.value = true
        _error.value = null
        setNotes(persistentListOf())
        synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs

        try {
            relayStateMachine.requestFeedChange(listOf(relayUrl))
            connectedRelays = listOf(relayUrl)
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            MLog.d(TAG, "Subscription active for relay: $relayUrl (state machine)")
        } catch (e: Exception) {
            MLog.e(TAG, "Error loading notes from relay: ${e.message}", e)
            _error.value = "Failed to load notes from relay: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
        }
    }

    /**
     * Subscribe to notes from a specific author (for announcements).
     */
    suspend fun subscribeToAuthorNotes(relayUrls: List<String>, authorPubkey: String, limit: Int = 50) {
        if (relayUrls.isEmpty()) {
            MLog.w(TAG, "No relays provided for author subscription")
            return
        }

        _isLoading.value = true
        _error.value = null
        setNotes(persistentListOf())
        synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs

        try {
            val filter = Filter(
                kinds = listOf(1),
                authors = listOf(authorPubkey),
                limit = limit
            )
            relayStateMachine.requestFeedChange(relayUrls, filter) { event ->
                pendingKind1Events.add(PendingKind1Event(event, "", isPagination = false))
                kind1Dirty.set(true)
            }
            connectedRelays = relayUrls
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            MLog.d(TAG, "Author subscription active (state machine)")
        } catch (e: Exception) {
            MLog.e(TAG, "Error subscribing to author notes: ${e.message}", e)
            _error.value = "Failed to load author notes: ${e.message}"
            _isLoading.value = false
            initialLoadComplete = true
        }
    }

    /**
     * Handle incoming event from relay.
     * During initial load: notes with timestamp <= cutoff go to feed; newer go to pending.
     * After initial load: only notes newer than the current top (latest in feed) go to pending and count as "new".
     * Late-arriving OLD notes (timestamp <= top) are merged into the feed in sorted order so the feed expands
     * to the historical side without inflating the "new" counter or pushing truly new notes down.
     */
    /** Debug-only: log a one-line summary of each incoming kind-1 event. Uses INFO level so it shows even when debug logs are filtered. */
    private fun logIncomingEventSummary(event: Event, relayUrl: String) {
        if (!eventLoggedOnce) {
            eventLoggedOnce = true
            MLog.i("MyceliumEvent", "Monitor active: first kind-1 received (you should see one line per note from here)")
            MLog.i(TAG, "MyceliumEvent: first kind-1 received")
        }
        val id = event.id.take(8)
        val relay = relayUrl.takeLast(30).takeLast(25)
        val contentLen = event.content.length
        val eCount = event.tags.count { it.isNotEmpty() && it[0] == "e" }
        val pCount = event.tags.count { it.isNotEmpty() && it[0] == "p" }
        val tCount = event.tags.count { it.isNotEmpty() && it[0] == "t" }
        val imeta = event.tags.count { it.isNotEmpty() && it[0] == "imeta" }
        val emoji = event.tags.count { it.isNotEmpty() && it[0] == "emoji" }
        val urls = UrlDetector.findUrls(event.content)
        val imageCount = urls.count { UrlDetector.isImageUrl(it) }
        val videoCount = urls.count { UrlDetector.isVideoUrl(it) }
        val gifLike = urls.any { it.contains(".gif", ignoreCase = true) }
        val content = event.content
        val hasMarkdown = content.contains("**") || content.contains("##") || content.contains("```") ||
            Regex("\\[.+?]\\(.+?\\)").containsMatchIn(content)
        val line = "id=$id relay=…$relay len=$contentLen e=$eCount p=$pCount t=$tCount imeta=$imeta emoji=$emoji urls=${urls.size} img=$imageCount vid=$videoCount gif=$gifLike md=$hasMarkdown"
        MLog.i("MyceliumEvent", line)
        eventCountForSampling++
        if (eventCountForSampling % 20 == 0) {
            MLog.d(TAG, "Event sample: $line")
        }
        updateDebugEventStats(hasMarkdown, imageCount > 0, videoCount > 0, gifLike, imeta > 0, emoji > 0)
    }

    private var eventLoggedOnce = false
    private var eventCountForSampling = 0
    private var debugStatsTotal = 0
    private var debugStatsMd = 0
    private var debugStatsImg = 0
    private var debugStatsVid = 0
    private var debugStatsGif = 0
    private var debugStatsImeta = 0
    private var debugStatsEmoji = 0

    private fun updateDebugEventStats(md: Boolean, img: Boolean, vid: Boolean, gif: Boolean, imeta: Boolean, emoji: Boolean) {
        debugStatsTotal++
        if (md) debugStatsMd++
        if (img) debugStatsImg++
        if (vid) debugStatsVid++
        if (gif) debugStatsGif++
        if (imeta) debugStatsImeta++
        if (emoji) debugStatsEmoji++
        _debugEventStats.value = DebugEventStatsSnapshot(
            debugStatsTotal, debugStatsMd, debugStatsImg, debugStatsVid, debugStatsGif, debugStatsImeta, debugStatsEmoji
        )
    }

    /** Keep only the newest notes to cap memory; list must be sorted by timestamp descending.
     *  Cap grows dynamically as the user paginates older notes so they don't get trimmed immediately.
     *  Also enforces the temporal display window: notes older than DISPLAY_WINDOW_MS from the
     *  newest note are trimmed even if under the count cap. This dual bound (count + time)
     *  ensures memory stays controlled regardless of event density. */
    private fun trimNotesToCap(notes: List<Note>): List<Note> {
        if (notes.isEmpty()) return notes
        val effectiveCap = MAX_NOTES_IN_MEMORY + minOf(paginationExtraCap, MAX_PAGINATION_EXTRA_CAP)
        // Phase 1: count-based trim
        val countTrimmed = if (notes.size > effectiveCap) {
            MLog.w(TAG, "Trimming feed (count): ${notes.size} → $effectiveCap (base=$MAX_NOTES_IN_MEMORY + paginationCap=${minOf(paginationExtraCap, MAX_PAGINATION_EXTRA_CAP)})")
            notes.take(effectiveCap)
        } else notes
        // Phase 2: timestamp-based trim — remove notes older than the display window
        val newestMs = countTrimmed.first().let { it.repostTimestamp ?: it.timestamp }
        val windowFloorMs = newestMs - DISPLAY_WINDOW_MS
        val windowTrimmed = countTrimmed.filter { note ->
            val ts = note.repostTimestamp ?: note.timestamp
            ts >= windowFloorMs
        }
        if (windowTrimmed.size < countTrimmed.size) {
            MLog.w(TAG, "Trimming feed (window): ${countTrimmed.size} → ${windowTrimmed.size} (floor=${fmtMs(windowFloorMs)})")
        }
        return windowTrimmed
    }

    /**
     * Handle kind-6 repost event: parse the reposted kind-1 note from the event content (JSON),
     * set repostedBy to the reposter's author, and inject into the feed as a normal note.
     * The repost event's pubkey is the reposter; the content contains the original note JSON.
     * Uses the repost event's timestamp so it appears at the time of repost, not the original note time.
     */
    private suspend fun handleKind6Repost(event: Event, relayUrl: String) {
        try {
            persistRepostEvent(event, relayUrl)

            // Track own boosts so the repost icon turns green (even for relay echoes)
            val reposterPubkey = event.pubKey
            if (reposterPubkey.lowercase() == currentUserPubkey) {
                val boostedId = event.tags.firstOrNull { it.getOrNull(0) == "e" }?.getOrNull(1)
                if (boostedId != null) NoteCountsRepository.trackOwnBoost(boostedId)
            }

            // Skip relay echo of our own repost — already in feed via injectOwnRepost
            if (locallyPublishedIds.contains(event.id)) {
                MLog.d(TAG, "Skipping kind-6 echo of locally-published repost ${event.id.take(8)}")
                return
            }
            val reposterAuthor = profileCache.resolveAuthor(reposterPubkey)
            val profileRelayUrls = getProfileRelayUrls()
            if (profileCache.getAuthor(reposterPubkey) == null && profileRelayUrls.isNotEmpty()) {
                pendingProfilePubkeys.add(reposterPubkey.lowercase())
            }

            val repostTimestampMs = event.createdAt * 1000L

            val content = event.content

            if (content.isNotBlank()) {
                // Content-embedded repost: original note JSON is in event.content
                val jsonObj = try {
                    Json.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject
                } catch (e: Exception) {
                    MLog.e(TAG, "Failed to parse kind-6 repost JSON: ${e.message}")
                    null
                } ?: return

                val originalNoteId = (jsonObj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
                // Cache the original signed event JSON for NIP-18 re-reposts
                social.mycelium.android.utils.RawEventCache.put(originalNoteId, content)
                val notePubkey = (jsonObj["pubkey"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
                val noteCreatedAt = (jsonObj["created_at"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                val noteContent = (jsonObj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

                val followSet = followFilter
                if (followFilterEnabled && followSet != null && notePubkey.lowercase() !in followSet) return

                val noteAuthor = profileCache.resolveAuthor(notePubkey)
                if (profileCache.getAuthor(notePubkey) == null && profileRelayUrls.isNotEmpty()) {
                    pendingProfilePubkeys.add(notePubkey.lowercase())
                }

                val hashtags = (jsonObj["tags"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { tag ->
                        val arr = tag as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                        if (arr.size >= 2 && (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content == "t") {
                            (arr[1] as? kotlinx.serialization.json.JsonPrimitive)?.content
                        } else null
                    } ?: emptyList()

                val mediaUrls = UrlDetector.findUrls(noteContent).filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }.distinct()
                // NIP-92: parse imeta from reposted note's JSON tags
                val repostImetaTags = (jsonObj["tags"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { tag ->
                        val arr = tag as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                        if (arr.size >= 2 && (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content == "imeta") {
                            arr.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }.toTypedArray()
                        } else null
                    } ?: emptyList()
                val repostMediaMeta = if (repostImetaTags.isNotEmpty()) {
                    social.mycelium.android.data.IMetaData.parseAll(repostImetaTags.toTypedArray())
                } else emptyMap()
                for ((mUrl, mMeta) in repostMediaMeta) {
                    if (mMeta.width != null && mMeta.height != null && mMeta.height > 0) {
                        social.mycelium.android.utils.MediaAspectRatioCache.add(mUrl, mMeta.width, mMeta.height)
                    }
                }
                val quotedRefs = Nip19QuoteParser.extractQuotedEventRefs(noteContent)
                val quotedEventIds = quotedRefs.map { it.eventId }
                quotedRefs.forEach { ref ->
                    if (ref.relayHints.isNotEmpty()) QuotedNoteCache.putRelayHints(ref.eventId, ref.relayHints)
                }
                val originalTimestampMs = noteCreatedAt * 1000L

                // Parse NIP-10 e-tags from JSON to detect if the reposted note is a reply
                val jsonTags = (jsonObj["tags"] as? kotlinx.serialization.json.JsonArray)
                val eTags = jsonTags?.mapNotNull { tag ->
                    val arr = tag as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                    if (arr.size >= 2 && (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content == "e") {
                        arr.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }.toTypedArray()
                    } else null
                } ?: emptyList()
                // Detect root and reply-to using same NIP-10 logic as Nip10ReplyDetector
                val repostRootId = eTags.firstOrNull { it.size >= 4 && (it.getOrNull(3) == "root" || it.getOrNull(4) == "root") }?.getOrNull(1)
                    ?: eTags.firstOrNull()?.getOrNull(1)
                val repostReplyToId = eTags.lastOrNull { it.size >= 4 && (it.getOrNull(3) == "reply" || it.getOrNull(4) == "reply") }?.getOrNull(1)
                    ?: if (eTags.size >= 2) eTags.last().getOrNull(1) else eTags.firstOrNull()?.getOrNull(1)
                val repostIsReply = eTags.isNotEmpty() && eTags.any { tag ->
                    when {
                        tag.size <= 3 -> true
                        tag.size >= 4 -> tag.getOrNull(3) == "reply" || tag.getOrNull(3) == "root" || tag.getOrNull(4) == "reply" || tag.getOrNull(4) == "root"
                        else -> false
                    }
                }

                val note = Note(
                    id = "repost:$originalNoteId",
                    author = noteAuthor,
                    content = noteContent,
                    timestamp = originalTimestampMs,
                    likes = 0, shares = 0, comments = 0, isLiked = false,
                    hashtags = hashtags, mediaUrls = mediaUrls, mediaMeta = repostMediaMeta,
                    quotedEventIds = quotedEventIds,
                    relayUrl = relayUrl.ifEmpty { null }?.let { social.mycelium.android.utils.normalizeRelayUrl(it) },
                    relayUrls = if (relayUrl.isNotEmpty()) listOf(social.mycelium.android.utils.normalizeRelayUrl(relayUrl)) else emptyList(),
                    isReply = repostIsReply,
                    rootNoteId = if (repostIsReply) repostRootId else null,
                    replyToId = if (repostIsReply) repostReplyToId else null,
                    originalNoteId = originalNoteId,
                    repostedByAuthors = listOf(reposterAuthor),
                    repostTimestamp = repostTimestampMs
                )
                EventRelayTracker.addRelay(originalNoteId, relayUrl)
                insertRepostNote(note, repostTimestampMs)
                // Schedule profile fetch for accumulated repost pubkeys
                if (pendingProfilePubkeys.isNotEmpty() && profileRelayUrls.isNotEmpty()) {
                    scheduleBatchProfileRequest(profileRelayUrls)
                }
            } else {
                // Tag-only repost: content is blank, original note ID is in the e-tag.
                // Extract the event ID and optional relay hint from tags.
                val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
                val originalNoteId = eTag?.getOrNull(1) ?: return
                val relayHint = eTag.getOrNull(2)?.takeIf { it.isNotBlank() }

                // Build relay list for fetching the original note
                val fetchRelays = buildList {
                    if (relayHint != null) add(relayHint)
                    if (relayUrl.isNotBlank()) add(relayUrl)
                    addAll(profileRelayUrls)
                }.distinct().take(5)

                if (fetchRelays.isEmpty()) {
                    MLog.w(TAG, "Kind-6 tag-only repost but no relays to fetch original note $originalNoteId")
                    return
                }

                val compositeId = "repost:$originalNoteId"

                // Track pending fetch to avoid duplicate requests
                if (!pendingRepostFetches.add(compositeId)) return

                // Buffer the repost for batched fetch (one subscription for all pending reposts)
                pendingRepostBuffer[originalNoteId] = PendingRepost(
                    originalNoteId = originalNoteId,
                    compositeId = compositeId,
                    reposterAuthor = reposterAuthor,
                    repostTimestampMs = repostTimestampMs,
                    relayUrl = relayUrl,
                    fetchRelays = fetchRelays,
                )
                scheduleRepostBatchFlush()
            }
        } catch (e: Throwable) {
            MLog.e(TAG, "Error handling kind-6 repost: ${e.message}", e)
        }
    }

    /** Binary-search insertion position in a descending-timestamp-sorted list.
      * Returns the index where a note with the given timestamp should be inserted
      * to maintain descending order. O(log n) vs O(n log n) for full re-sort. */
    private fun findInsertionPosition(notes: List<Note>, timestampMs: Long): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val midTs = notes[mid].let { it.repostTimestamp ?: it.timestamp }
            if (midTs > timestampMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Insert a repost Note into the feed (shared by content-embedded and tag-only repost paths).
      *  Deduplicates: if the same original note is already in the feed, merges boosters and keeps the latest repost timestamp. */
    private fun insertRepostNote(note: Note, repostTimestampMs: Long) {
        // Read from shadow list if available (burst accumulation), otherwise from StateFlow
        val currentNotes = burstShadowNotes ?: _notes.value
        // O(1) lookup via FeedIndex instead of O(n) indexOfFirst
        val existingNote = feedIndex.byId[note.id]

        if (existingNote != null) {
            // Same original note already in feed — merge boosters AND relay URLs
            val newBooster = note.repostedByAuthors.firstOrNull() ?: return
            if (existingNote.repostedByAuthors.any { it.id == newBooster.id }) return
            val mergedAuthors = (listOf(newBooster) + existingNote.repostedByAuthors).distinctBy { it.id }
            val latestRepostTs = maxOf(repostTimestampMs, existingNote.repostTimestamp ?: 0L)
            val existingUrls = existingNote.relayUrls.ifEmpty { listOfNotNull(existingNote.relayUrl) }
            val newUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
            val mergedUrls = (existingUrls + newUrls).map { social.mycelium.android.utils.normalizeRelayUrl(it) }.distinct().filter { it.isNotBlank() }
            val merged = existingNote.copy(
                repostedByAuthors = mergedAuthors,
                repostTimestamp = latestRepostTs,
                relayUrls = mergedUrls
            )
            val updatedNotes = currentNotes.toMutableList()
            val existingIndex = updatedNotes.indexOfFirst { it.id == note.id }
            if (existingIndex >= 0) {
                // If timestamp changed, remove and re-insert at correct position
                if (latestRepostTs != (existingNote.repostTimestamp ?: 0L)) {
                    updatedNotes.removeAt(existingIndex)
                    val insertPos = findInsertionPosition(updatedNotes, latestRepostTs)
                    updatedNotes.add(insertPos, merged)
                } else {
                    updatedNotes[existingIndex] = merged
                }
            }
            val resultList = updatedNotes.toImmutableList()
            if (burstShadowNotes != null) burstShadowNotes = resultList else _notes.value = resultList
            feedIndex.updateNote(merged)
            scheduleDisplayUpdate()
            return
        }

        // Also check pending notes for dedup
        synchronized(pendingNotesLock) {
            val pendingIndex = _pendingNewNotes.indexOfFirst { it.id == note.id }
            if (pendingIndex >= 0) {
                val existing = _pendingNewNotes[pendingIndex]
                val newBooster = note.repostedByAuthors.firstOrNull() ?: return
                if (existing.repostedByAuthors.any { it.id == newBooster.id }) return
                val mergedAuthors = (listOf(newBooster) + existing.repostedByAuthors).distinctBy { it.id }
                val latestRepostTs = maxOf(repostTimestampMs, existing.repostTimestamp ?: 0L)
                val existUrls = existing.relayUrls.ifEmpty { listOfNotNull(existing.relayUrl) }
                val newUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                val mergedUrls = (existUrls + newUrls).map { social.mycelium.android.utils.normalizeRelayUrl(it) }.distinct().filter { it.isNotBlank() }
                _pendingNewNotes[pendingIndex] = existing.copy(
                    repostedByAuthors = mergedAuthors,
                    repostTimestamp = latestRepostTs,
                    relayUrls = mergedUrls
                )
                return
            }
        }

        // Check if the original kind-1 note is already visible in the feed.
        // If so, the repost should upgrade it in-place (add boost badge, reposition
        // to top) instead of hiding it behind the "X new notes" gate.
        val origId = note.originalNoteId
        if (origId != null) {
            val origIndex = currentNotes.indexOfFirst { it.id == origId }
            if (origIndex >= 0) {
                // Original note is already visible — replace with repost version in-place
                // Preserve the original note's accumulated relay URLs
                val originalNote = currentNotes[origIndex]
                val origUrls = originalNote.relayUrls.ifEmpty { listOfNotNull(originalNote.relayUrl) }
                val noteUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                val mergedUrls = (origUrls + noteUrls).map { social.mycelium.android.utils.normalizeRelayUrl(it) }.distinct().filter { it.isNotBlank() }
                val noteWithMergedRelays = note.copy(relayUrls = mergedUrls)
                val updatedNotes = currentNotes.toMutableList().apply { removeAt(origIndex) }
                val insertPos = findInsertionPosition(updatedNotes, noteWithMergedRelays.repostTimestamp ?: noteWithMergedRelays.timestamp)
                updatedNotes.add(insertPos, noteWithMergedRelays)
                val newNotes = trimNotesToCap(updatedNotes)
                val resultList = newNotes.toImmutableList()
                if (burstShadowNotes != null) burstShadowNotes = resultList else _notes.value = resultList
                feedIndex.removeNote(originalNote)
                feedIndex.addNote(noteWithMergedRelays)
                scheduleDisplayUpdate()
                // Also clean up any pending duplicate
                synchronized(pendingNotesLock) { _pendingNewNotes.removeAll { it.id == origId } }
                return
            }
            // Check if the original is in the pending queue — upgrade it there
            synchronized(pendingNotesLock) {
                val pendingOrigIndex = _pendingNewNotes.indexOfFirst { it.id == origId }
                if (pendingOrigIndex >= 0) {
                    val pendingOrig = _pendingNewNotes[pendingOrigIndex]
                    val pOrigUrls = pendingOrig.relayUrls.ifEmpty { listOfNotNull(pendingOrig.relayUrl) }
                    val pNoteUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    val pMergedUrls = (pOrigUrls + pNoteUrls).map { social.mycelium.android.utils.normalizeRelayUrl(it) }.distinct().filter { it.isNotBlank() }
                    _pendingNewNotes[pendingOrigIndex] = note.copy(relayUrls = pMergedUrls)
                    return
                }
            }
        }

        val cutoff = feedCutoffTimestampMs
        val isOlderThanCutoff = cutoff <= 0L || repostTimestampMs <= cutoff

        if (!initialLoadComplete || isOlderThanCutoff) {
            val mutableNotes = currentNotes.toMutableList()
            val insertPos = findInsertionPosition(mutableNotes, note.repostTimestamp ?: note.timestamp)
            mutableNotes.add(insertPos, note)
            val newNotes = trimNotesToCap(mutableNotes)
            val resultList = newNotes.toImmutableList()
            if (burstShadowNotes != null) burstShadowNotes = resultList else _notes.value = resultList
            feedIndex.addNote(note)
            scheduleDisplayUpdate()
        } else {
            synchronized(pendingNotesLock) { _pendingNewNotes.add(note); pendingIndex.addNote(note) }
            updateDisplayedNewNotesCount()
        }

        // Buffer for batch enrichment: the repost's original note needs counts, quotes,
        // and URL previews, but firing per-repost creates a subscription storm (100+
        // individual subscription updates during burst). Instead, buffer into the
        // deferred enrichment queue — the flush poller will fire enrichment in batch
        // when the queue drains, or the next steady-state cycle picks them up.
        deferredEnrichmentNotes.add(note)
    }

    // ── Optimistic local rendering ────────────────────────────────────────────

    /**
     * Inject the user's own kind-1 note directly into the displayed feed with [PublishState.Sending].
     * Bypasses the pending queue and feedCutoffTimestampMs entirely — the user's own note always
     * appears at the top of the feed immediately after signing.
     *
     * @param note A Note built from the signed event (id = event.id, author = current user, etc.)
     * @return true if injected, false if duplicate
     */
    fun injectOwnNote(note: Note): Boolean {
        val currentNotes = _notes.value
        if (currentNotes.any { it.id == note.id }) return false
        locallyPublishedIds.add(note.id)
        // Clear relayUrls so the note passes the relay display filter in updateDisplayedNotes().
        // The publish relay URLs (outbox) may not overlap with subscription relays (inbox),
        // which would cause the note to be filtered out. The relay will echo it back with
        // proper URLs once it's accepted.
        val withState = note.copy(publishState = PublishState.Sending, relayUrls = emptyList(), relayUrl = null)
        val newList = (listOf(withState) + currentNotes.toImmutableList()).take(MAX_NOTES_IN_MEMORY).toImmutableList()
        setNotes(newList)
        scheduleDisplayUpdate()
        MLog.d(TAG, "Injected own note ${note.id.take(8)} (publishState=Sending)")
        return true
    }

    /**
     * Inject the user's own repost directly into the displayed feed with [PublishState.Sending].
     * Same as [injectOwnNote] but builds the repost composite note from the original.
     */
    suspend fun injectOwnRepost(originalNote: Note, reposterPubkey: String, repostEventId: String) {
        try {
            val reposterAuthor = profileCache.resolveAuthor(reposterPubkey)
            val repostTimestampMs = System.currentTimeMillis()
            // Resolve the real original note ID (originalNote might itself be a repost composite)
            val realOrigId = originalNote.originalNoteId ?: originalNote.id
            val compositeId = "repost:$realOrigId"

            locallyPublishedIds.add(repostEventId)
            locallyPublishedIds.add(compositeId)

            val currentNotes = _notes.value

            // If composite already exists (others already boosted), merge our author in
            val existingIndex = currentNotes.indexOfFirst { it.id == compositeId }
            if (existingIndex >= 0) {
                val existing = currentNotes[existingIndex]
                if (existing.repostedByAuthors.any { it.id == reposterAuthor.id }) return // already listed
                val mergedAuthors = (listOf(reposterAuthor) + existing.repostedByAuthors).distinctBy { it.id }
                val updated = currentNotes.toMutableList()
                updated[existingIndex] = existing.copy(
                    repostedByAuthors = mergedAuthors,
                    repostTimestamp = repostTimestampMs,
                    publishState = PublishState.Sending
                )
                setNotes(updated.sortedByDescending { it.repostTimestamp ?: it.timestamp }.toImmutableList())
                scheduleDisplayUpdate()
                MLog.d(TAG, "Merged own repost into existing ${compositeId.take(16)} (now ${mergedAuthors.size} boosters)")
                return
            }

            // Remove the original kind-1 from feed if present (repost supersedes it)
            var notesAfterRemoval = currentNotes
            val origIndex = currentNotes.indexOfFirst { it.id == realOrigId }
            if (origIndex >= 0) {
                notesAfterRemoval = currentNotes.toMutableList().apply { removeAt(origIndex) }.toImmutableList()
            }
            synchronized(pendingNotesLock) { _pendingNewNotes.removeAll { it.id == realOrigId } }

            // Preserve existing repostedByAuthors from the original note (if it was already a repost)
            val existingAuthors = originalNote.repostedByAuthors.filter { it.id != reposterAuthor.id }
            val mergedAuthors = listOf(reposterAuthor) + existingAuthors

            val note = originalNote.copy(
                id = compositeId,
                originalNoteId = realOrigId,
                repostedByAuthors = mergedAuthors,
                repostTimestamp = repostTimestampMs,
                publishState = PublishState.Sending
            )
            setNotes((listOf(note) + notesAfterRemoval).take(MAX_NOTES_IN_MEMORY).toImmutableList())
            scheduleDisplayUpdate()
            MLog.d(TAG, "Injected own repost ${compositeId.take(16)} (${mergedAuthors.size} boosters, publishState=Sending)")
        } catch (e: Throwable) {
            MLog.e(TAG, "injectOwnRepost failed: ${e.message}", e)
        }
    }

    /**
     * Optimistically remove a note from the feed (e.g. after deletion).
     * Removes by event ID, also handles repost composites ("repost:eventId").
     */
    fun removeNote(eventId: String) {
        val currentNotes = _notes.value
        val filtered = currentNotes.filter { note ->
            note.id != eventId &&
                note.id != "repost:$eventId" &&
                note.originalNoteId != eventId
        }
        if (filtered.size < currentNotes.size) {
            setNotes(filtered.toImmutableList())
            synchronized(pendingNotesLock) {
                _pendingNewNotes.removeAll { it.id == eventId || it.originalNoteId == eventId }
            }
            scheduleDisplayUpdate()
            MLog.d(TAG, "Removed note ${eventId.take(8)} from feed (${currentNotes.size - filtered.size} entries)")
        }
        deletedEventIds.add(eventId)
        persistDeletedIds()
    }

    /** Check whether an event has been locally deleted by the user. */
    fun isDeletedEvent(eventId: String): Boolean = eventId in deletedEventIds

    private fun loadDeletedIdsFromDisk(context: Context) {
        try {
            val prefs = context.getSharedPreferences(DELETED_IDS_PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getStringSet(DELETED_IDS_KEY, null)
            if (!raw.isNullOrEmpty()) {
                deletedEventIds.addAll(raw)
                MLog.d(TAG, "Loaded ${raw.size} deleted event IDs from disk")
            }
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to load deleted IDs: ${e.message}")
        }
    }

    private fun persistDeletedIds() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val ids = deletedEventIds.toSet()
                val trimmed = if (ids.size > DELETED_IDS_MAX) ids.take(DELETED_IDS_MAX).toSet() else ids
                ctx.getSharedPreferences(DELETED_IDS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(DELETED_IDS_KEY, trimmed)
                    .apply()
            } catch (e: Exception) {
                MLog.e(TAG, "Failed to persist deleted IDs: ${e.message}")
            }
        }
    }

    /**
     * Update the publish state of a locally-published note (e.g. Sending → Confirmed or Failed).
     * Called by the publisher after relay OK responses arrive.
     */
    fun updatePublishState(eventId: String, state: PublishState) {
        val currentNotes = _notes.value
        val index = currentNotes.indexOfFirst { it.id == eventId || it.id == "repost:$eventId" || it.originalNoteId?.let { oid -> "repost:$oid" == it.id && eventId == it.id } == true }
        if (index < 0) {
            // Try matching by the actual event id for reposts
            val repostIndex = currentNotes.indexOfFirst { it.id.startsWith("repost:") && locallyPublishedIds.contains(eventId) && it.publishState != null }
            if (repostIndex >= 0) {
                val updated = currentNotes.toMutableList()
                updated[repostIndex] = updated[repostIndex].copy(publishState = state)
                _notes.value = updated.toImmutableList()
                feedIndex.updateNote(updated[repostIndex])
                scheduleDisplayUpdate()
            }
            return
        }
        val updated = currentNotes.toMutableList()
        updated[index] = updated[index].copy(publishState = state)
        _notes.value = updated.toImmutableList()
        feedIndex.updateNote(updated[index])
        scheduleDisplayUpdate()
        MLog.d(TAG, "Updated publishState for ${eventId.take(8)} → $state")

        // Auto-clear Confirmed state after a short delay so the progress line fades out
        if (state == PublishState.Confirmed) {
            scope.launch {
                delay(3000L)
                clearPublishState(eventId)
            }
        }
    }

    /** Clear publish state (note becomes a normal subscription note). */
    private fun clearPublishState(eventId: String) {
        val currentNotes = _notes.value
        val index = currentNotes.indexOfFirst { it.id == eventId || it.id == "repost:$eventId" }
        if (index >= 0 && currentNotes[index].publishState != null) {
            val updated = currentNotes.toMutableList()
            updated[index] = updated[index].copy(publishState = null)
            _notes.value = updated.toImmutableList()
            feedIndex.updateNote(updated[index])
            scheduleDisplayUpdate()
        }
    }

    /**
     * Check if an incoming event from a subscription is one we published locally.
     * If so, reconcile (update relay URLs, clear from pending) instead of adding to pending queue.
     * @return true if reconciled (caller should skip normal processing), false otherwise.
     */
    fun isLocallyPublished(eventId: String): Boolean = locallyPublishedIds.contains(eventId)

    /**
     * Merge a relay URL into a locally-published note when a relay sends OK.
     * Called from RelayHealthTracker's publish tracking path so relay orbs
     * update in real-time as relay confirmations arrive.
     */
    fun mergePublishRelayUrl(eventId: String, relayUrl: String) {
        if (relayUrl.isBlank()) return
        val normalized = social.mycelium.android.utils.normalizeRelayUrl(relayUrl)
        val currentNotes = _notes.value
        val index = currentNotes.indexOfFirst { it.id == eventId || it.id == "repost:$eventId" }
        if (index < 0) return
        val note = currentNotes[index]
        val existingUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
        val existingNormalized = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
        if (normalized in existingNormalized) return
        val updatedUrls = (existingUrls + normalized).distinct().filter { it.isNotBlank() }
        val updated = currentNotes.toMutableList()
        updated[index] = note.copy(relayUrls = updatedUrls)
        _notes.value = updated.toImmutableList()
        feedIndex.updateNote(updated[index])
        scheduleDisplayUpdate()
        MLog.d(TAG, "Merged publish relay $relayUrl into ${eventId.take(8)} (now ${updatedUrls.size} orbs)")
        // Persist to Room
        val dao = eventDao
        if (dao != null) {
            scope.launch(Dispatchers.IO) {
                try { dao.updateRelayUrls(eventId, updatedUrls.joinToString(",")) }
                catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    /**
     * Inject a local repost into the feed immediately after the user publishes a boost.
     * This makes the boost appear instantly without waiting for the relay echo.
     * @param originalNote The note being boosted
     * @param reposterPubkey The current user's pubkey
     */
    @Deprecated("Use injectOwnRepost() for optimistic rendering with publish progress", ReplaceWith("injectOwnRepost(originalNote, reposterPubkey, \"\")"))
    fun injectLocalRepost(originalNote: Note, reposterPubkey: String) {
        scope.launch {
            try {
                val reposterAuthor = profileCache.resolveAuthor(reposterPubkey)
                val repostTimestampMs = System.currentTimeMillis()
                val compositeId = "repost:${originalNote.id}"

                // Check if already in feed (avoid duplicate)
                val currentNotes = _notes.value
                if (currentNotes.any { it.id == compositeId }) return@launch

                val note = originalNote.copy(
                    id = compositeId,
                    originalNoteId = originalNote.id,
                    repostedByAuthors = listOf(reposterAuthor),
                    repostTimestamp = repostTimestampMs
                )
                insertRepostNote(note, repostTimestampMs)
                MLog.d(TAG, "Injected local repost for ${originalNote.id.take(8)} by ${reposterPubkey.take(8)}")
            } catch (e: Throwable) {
                MLog.e(TAG, "injectLocalRepost failed: ${e.message}", e)
            }
        }
    }

    /** Schedule a debounced flush of the repost buffer. Resets on each new repost so we batch them. */
    private fun scheduleRepostBatchFlush() {
        repostBatchJob?.cancel()
        repostBatchJob = scope.launch {
            delay(REPOST_BATCH_DELAY_MS)
            flushRepostBatch()
        }
    }

    /** Flush all pending repost fetches as ONE batched subscription. */
    private fun flushRepostBatch() {
        if (pendingRepostBuffer.isEmpty()) return
        // Drain the buffer
        val batch = pendingRepostBuffer.toMap()
        pendingRepostBuffer.clear()

        // Collect all note IDs and union of all relay URLs
        val allNoteIds = batch.keys.toList()
        val allRelayUrls = batch.values.flatMap { it.fetchRelays }.distinct().take(8)
        if (allNoteIds.isEmpty() || allRelayUrls.isEmpty()) return

        MLog.d(TAG, "Flushing repost batch: ${allNoteIds.size} notes across ${allRelayUrls.size} relays (was ${allNoteIds.size} individual subs)")

        val filter = Filter(ids = allNoteIds, kinds = listOf(1), limit = allNoteIds.size)
        relayStateMachine.requestOneShotSubscriptionWithRelay(allRelayUrls, filter, priority = SubscriptionPriority.LOW,
            settleMs = 500L, maxWaitMs = 8_000L) { originalEvent, actualRelayUrl ->
            val pending = batch[originalEvent.id] ?: return@requestOneShotSubscriptionWithRelay
            scope.launch {
                try {
                    processEventMutex.withLock {
                        val notePubkey = originalEvent.pubKey
                        val followSet = followFilter
                        if (followFilterEnabled && followSet != null && notePubkey.lowercase() !in followSet) return@launch

                        val noteAuthor = profileCache.resolveAuthor(notePubkey)
                        val profRelays = getProfileRelayUrls()
                        if (profileCache.getAuthor(notePubkey) == null && profRelays.isNotEmpty()) {
                            pendingProfilePubkeys.add(notePubkey.lowercase())
                        }

                        val noteContent = originalEvent.content
                        val hashtags = originalEvent.tags
                            .filter { it.size >= 2 && it[0] == "t" }
                            .map { it[1] }
                        val mediaUrls = UrlDetector.findUrls(noteContent).filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }.distinct()
                        val quotedRefs = Nip19QuoteParser.extractQuotedEventRefs(noteContent)
                        val quotedEventIds = quotedRefs.map { it.eventId }
                        quotedRefs.forEach { ref ->
                            if (ref.relayHints.isNotEmpty()) QuotedNoteCache.putRelayHints(ref.eventId, ref.relayHints)
                        }
                        val originalTimestampMs = originalEvent.createdAt * 1000L

                        // Parse NIP-10 e-tags to detect if the reposted note is a reply
                        val isReply = Nip10ReplyDetector.isReply(originalEvent)
                        val rootNoteId = if (isReply) Nip10ReplyDetector.getRootId(originalEvent) else null
                        val replyToId = if (isReply) Nip10ReplyDetector.getReplyToId(originalEvent) else null

                        // Use the relay that actually delivered the original note during fetch,
                        // NOT the relay that delivered the kind-6 repost event (pending.relayUrl).
                        // This ensures relay orbs accurately reflect where the note was fetched from.
                        val fetchRelayUrl = actualRelayUrl.ifBlank { null }
                        val note = Note(
                            id = pending.compositeId,
                            author = noteAuthor,
                            content = noteContent,
                            timestamp = originalTimestampMs,
                            likes = 0, shares = 0, comments = 0, isLiked = false,
                            hashtags = hashtags, mediaUrls = mediaUrls, quotedEventIds = quotedEventIds,
                            relayUrl = fetchRelayUrl,
                            relayUrls = listOfNotNull(fetchRelayUrl),
                            isReply = isReply,
                            rootNoteId = rootNoteId,
                            replyToId = replyToId,
                            originalNoteId = pending.originalNoteId,
                            repostedByAuthors = listOf(pending.reposterAuthor),
                            repostTimestamp = pending.repostTimestampMs
                        )
                        insertRepostNote(note, pending.repostTimestampMs)
                    }
                } catch (e: Throwable) {
                    MLog.e(TAG, "Error processing batched repost note: ${e.message}", e)
                } finally {
                    pendingRepostFetches.remove(pending.compositeId)
                }
            }
        }

        // Clean up any pending that didn't get fetched after EOSE auto-close + buffer
        scope.launch {
            delay(9_000L)
            batch.values.forEach { pendingRepostFetches.remove(it.compositeId) }
        }
    }

    /**
     * Schedule a batched kind-0 fetch. The debounce timer resets on each new pubkey so we accumulate
     * a full batch before firing. In-flight fetches are never cancelled — only the schedule timer is.
     * After the debounce fires, we drain ALL pending pubkeys in one request (up to PROFILE_BATCH_SIZE
     * per relay call). If more remain, we chain another fetch after the current one completes.
     */
    private fun scheduleBatchProfileRequest(profileRelayUrls: List<String>) {
        // Only reset the debounce timer; never cancel an active fetch
        profileBatchScheduleJob?.cancel()
        profileBatchScheduleJob = scope.launch {
            delay(PROFILE_BATCH_DELAY_MS)
            // Wait for any in-flight fetch to finish before starting a new one
            profileBatchFetchJob?.join()
            profileBatchFetchJob = scope.launch {
                while (pendingProfilePubkeys.isNotEmpty()) {
                    val batch = synchronized(pendingProfilePubkeys) {
                        pendingProfilePubkeys.take(PROFILE_BATCH_SIZE).also { pendingProfilePubkeys.removeAll(it.toSet()) }
                    }
                    if (batch.isEmpty()) break
                    val urls = getProfileRelayUrls()
                    if (urls.isEmpty()) {
                        // Put them back — we'll try again when relays are available
                        pendingProfilePubkeys.addAll(batch)
                        break
                    }
                    try {
                        // Collect nprofile relay hints for this batch
                        val batchHints = batch.flatMap { pk ->
                            pendingProfileRelayHints.remove(pk) ?: emptyList()
                        }.distinct()
                        if (batchHints.isNotEmpty()) {
                            MLog.d(TAG, "Batch profile fetch: ${batch.size} pubkeys from ${urls.size} indexer + ${batchHints.size} hint relays")
                            profileCache.requestProfileWithHints(batch, urls, batchHints)
                        } else {
                            MLog.d(TAG, "Batch profile fetch: ${batch.size} pubkeys from ${urls.size} relays")
                            profileCache.requestProfiles(batch, urls)
                        }
                    } catch (e: Throwable) {
                        MLog.e(TAG, "Batch profile request failed: ${e.message}", e)
                    }
                    // Small pause between batches to avoid overwhelming relays
                    if (pendingProfilePubkeys.isNotEmpty()) delay(200)
                }
                profileBatchFetchJob = null
            }
            profileBatchScheduleJob = null
        }
    }

    private fun getProfileRelayUrls(): List<String> {
        // Use indexer relays only for kind-0 profile fetches — indexers can serve all
        // profiles without overwhelming outbox/subscription relays with extra subs.
        return cacheRelayUrls.filter { it.isNotBlank() }
    }

    /**
     * Convert a Room CachedEventEntity to a Note. Used by FeedWindowManager
     * for Room-backed windowed paging. Parses the stored JSON, restores
     * relay URLs, seeds the tracker, and runs through convertEventToNote.
     *
     * Returns null if the entity is a kind-6 repost (needs handleKind6Repost)
     * or if conversion fails.
     */
    private fun convertEntityToNote(entity: social.mycelium.android.db.CachedEventEntity): Note? {
        val event = Event.fromJson(entity.eventJson)
        // Skip kind-6 reposts — they need handleKind6Repost for proper enrichment
        if (event.kind == 6) return null
        val note = convertEventToNote(event, entity.relayUrl ?: "")
        // Skip replies (belt-and-suspenders — Room query already filters, but entity might be stale)
        if (note.isReply) return null
        // Restore merged relay URLs from the dedicated column
        val allUrls = entity.relayUrls?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (allUrls.isNotEmpty()) {
            allUrls.forEach { url -> EventRelayTracker.addRelay(event.id, url) }
        } else if (!entity.relayUrl.isNullOrBlank()) {
            EventRelayTracker.addRelay(event.id, entity.relayUrl)
        }
        return if (allUrls.isNotEmpty()) note.copy(relayUrls = allUrls) else note
    }

    private fun convertEventToNote(event: Event, relayUrl: String): Note {
        android.os.Trace.beginSection("NotesRepo.convertEventToNote")
        // Cache raw signed event JSON for NIP-18 reposts (kind-6 content field).
        // event.toJson() returns rawJson (zero-copy from relay wire) when available.
        social.mycelium.android.utils.RawEventCache.put(event.id, event.toJson())
        val storedRelayUrl = relayUrl.ifEmpty { null }?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        val profileRelayUrls = getProfileRelayUrls()
        if (profileCache.getAuthor(pubkeyHex) == null && profileRelayUrls.isNotEmpty()) {
            pendingProfilePubkeys.add(pubkeyHex.lowercase())
        }
        // Request kind-0 for pubkeys mentioned in content (npub + nprofile + hex).
        // Skip during burst ingestion: extractPubkeysWithHintsFromContent is regex-heavy
        // and most mentioned pubkeys overlap across notes. The author's own pubkey (above)
        // is always queued; content mentions are lower priority and can wait for steady state.
        if (pendingProfilePubkeys.size < 200 && profileRelayUrls.isNotEmpty()) {
            extractPubkeysWithHintsFromContent(event.content).forEach { (hex, relayHints) ->
                val lowerHex = hex.lowercase()
                if (profileCache.getAuthor(lowerHex) == null && lowerHex !in pendingProfilePubkeys) {
                    pendingProfilePubkeys.add(lowerHex)
                    if (relayHints.isNotEmpty()) {
                        pendingProfileRelayHints[lowerHex] = relayHints
                    }
                }
            }
        }
        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }
        val mediaUrls = UrlDetector.findUrls(event.content).filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }.distinct()
        // NIP-92: parse imeta tags for dimensions, blurhash, mimeType
        val mediaMeta = social.mycelium.android.data.IMetaData.parseAll(event.tags)
        // Seed aspect ratio cache from imeta dimensions so containers size correctly on first render
        for ((url, meta) in mediaMeta) {
            if (meta.width != null && meta.height != null && meta.height > 0) {
                social.mycelium.android.utils.MediaAspectRatioCache.add(url, meta.width, meta.height)
            }
        }
        val quotedRefs = Nip19QuoteParser.extractQuotedEventRefs(event.content)
        val quotedEventIds = quotedRefs.map { it.eventId }
        // Register relay hints from nevent1 TLV so QuotedNoteCache can use them for fetching
        quotedRefs.forEach { ref ->
            if (ref.relayHints.isNotEmpty()) QuotedNoteCache.putRelayHints(ref.eventId, ref.relayHints)
        }
        // Extract relay hints from e-tags and p-tags (position 2 = relay URL hint).
        // Feed into NIP-65 cache as supplementary data for authors without kind-10002.
        // This enriches relay orbs for users whose outbox relays weren't discovered yet.
        if (storedRelayUrl != null && Nip65RelayListRepository.getCachedOutboxRelays(event.pubKey) == null) {
            Nip65RelayListRepository.addRelayHintsForAuthor(event.pubKey, listOf(storedRelayUrl))
        }
        for (tag in event.tags) {
            if (tag.size >= 3 && tag[2].isNotBlank()) {
                val tagType = tag[0]
                val relayHint = tag[2].trim()
                if ((tagType == "e" || tagType == "p") && (relayHint.startsWith("wss://") || relayHint.startsWith("ws://"))) {
                    // For p-tags, the hint tells us where the mentioned author reads (inbox)
                    if (tagType == "p" && tag[1].length == 64) {
                        Nip65RelayListRepository.addRelayHintsForAuthor(tag[1], listOf(relayHint))
                    }
                }
            }
        }
        val isReply = Nip10ReplyDetector.isReply(event)
        val rootNoteId = if (isReply) Nip10ReplyDetector.getRootId(event) else null
        val replyToId = if (isReply) Nip10ReplyDetector.getReplyToId(event) else null
        // Relay orbs: only show relays that actually delivered/confirmed this event.
        // Additional relays are merged naturally when the same event arrives from
        // other relays (flushKind1Events dedup) or via publish OK (mergePublishRelayUrl).
        // NIP-65 outbox relays are NOT added here — they are speculative and would show
        // relays where the note may not actually exist.
        val relayUrls = if (storedRelayUrl != null) listOf(storedRelayUrl) else emptyList()
        
        // Convert event tags to List<List<String>> for NIP-22 I tags and better e tag tracking
        val tags = event.tags.map { it.toList() }
        
        // NIP-23 long-form content fields (kind 30023)
        val isArticle = event.kind == 30023
        val articleTitle = if (isArticle) event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.getOrNull(1) else null
        val articleSummary = if (isArticle) event.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.getOrNull(1) else null
        val articleImage = if (isArticle) event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.getOrNull(1) else null
        val dTag = if (isArticle) event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.getOrNull(1) else null

        // NIP-88 poll data (kind 1068)
        val pollData = if (event.kind == 1068) social.mycelium.android.data.PollData.parseFromTags(tags) else null

        // Zap poll data (kind 6969)
        val zapPollData = if (event.kind == 6969) social.mycelium.android.data.ZapPollData.parseFromTags(tags) else null

        val note = Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            mediaMeta = mediaMeta,
            quotedEventIds = quotedEventIds,
            relayUrl = storedRelayUrl,
            relayUrls = relayUrls,
            isReply = isReply,
            rootNoteId = rootNoteId,
            replyToId = replyToId,
            kind = event.kind,
            topicTitle = articleTitle,
            tags = tags,
            mentionedPubkeys = event.tags
                .filter { tag -> tag.size >= 2 && tag[0] == "p" }
                .mapNotNull { tag -> tag.getOrNull(1)?.takeIf { it.length == 64 } }
                .distinct(),
            summary = articleSummary,
            imageUrl = articleImage,
            dTag = dTag,
            pollData = pollData,
            zapPollData = zapPollData
        )
        android.os.Trace.endSection()
        return note
    }

    fun clearNotes() {
        kind1Dirty.set(false)
        pendingKind1Events.clear()
        burstShadowNotes = null
        setNotes(persistentListOf())
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear(); pendingIndex.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        feedCutoffTimestampMs = 0L
        latestNoteTimestampAtOpen = 0L
        initialLoadComplete = false
        paginationCursorMs = 0L
        paginationExtraCap = 0
        paginationEmptyStreak = 0
        perRelayCursorMs.clear()
        exhaustedPaginationRelays.clear()
        _paginationExhausted.value = false
        // Clear Room-paginated tail
        synchronized(roomPaginatedNotes) { roomPaginatedNotes.clear() }
        roomPaginatedIds.clear()
        feedWindowManager?.clear()
        _feedSessionState.value = FeedSessionState.Idle
        _hasEverLoadedFeed.value = false
        // Reset subscription guard so ensureSubscriptionToNotes re-applies after account switch
        lastEnsuredRelaySet = emptySet()
        // Clear relay tracker so old account's relay data doesn't leak
        EventRelayTracker.clear()
        // Clear Room event store so old account's notes don't leak on next cold start
        scope.launch {
            try { eventDao?.deleteAll() } catch (e: Exception) {
                MLog.e(TAG, "Event store clear failed: ${e.message}", e)
            }
        }
    }

    /**
     * Merge pending new notes into the visible list, update baseline, clear pending.
     * Call from pull-to-refresh so the user sees the held-back notes.
     */
    fun applyPendingNotes() {
        val toMerge = synchronized(pendingNotesLock) {
            if (_pendingNewNotes.isEmpty()) emptyList()
            else {
                val list = _pendingNewNotes.toList()
                _pendingNewNotes.clear()
                pendingIndex.clear()
                list
            }
        }
        if (toMerge.isEmpty()) return
        _feedSessionState.value = FeedSessionState.Refreshing
        val pendingCount = toMerge.size
        val merged = trimNotesToCap((_notes.value + toMerge).distinctBy { it.id }.sortedByDescending { it.repostTimestamp ?: it.timestamp })
        setNotes(merged.toImmutableList())
        // Re-enrich quoted notes: clear failed IDs so stale fetch failures are retried,
        // then re-prefetch any quoted notes that weren't resolved during initial ingestion.
        val allQuotedIds = toMerge.flatMap { it.quotedEventIds }.distinct()
        for (qid in allQuotedIds) {
            if (QuotedNoteCache.getCached(qid) == null) {
                QuotedNoteCache.clearFailed(qid)
            }
        }
        val notesWithUnresolvedQuotes = toMerge.filter { note ->
            note.quotedEventIds.any { QuotedNoteCache.getCached(it) == null }
        }
        if (notesWithUnresolvedQuotes.isNotEmpty()) {
            MLog.d(TAG, "Re-prefetching ${notesWithUnresolvedQuotes.size} notes with unresolved quoted content")
            QuotedNoteCache.prefetchForNotes(notesWithUnresolvedQuotes)
        }
        updateDisplayedNotes()
        latestNoteTimestampAtOpen = merged.maxOfOrNull { it.timestamp } ?: 0L
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        _feedSessionState.value = FeedSessionState.Live
        MLog.d(TAG, "Applied $pendingCount pending notes (total: ${merged.size})")
    }

    /**
     * Refresh: merge pending notes into the feed and re-apply subscription (no clear).
     * Does not call subscribeToNotes() so the feed is not wiped; avoids the "recount" where total drops and rolls back up.
     */
    suspend fun refresh() {
        if (subscriptionRelays.isEmpty()) {
            MLog.w(TAG, "Refresh skipped: no subscription relays")
            updateDisplayedNotes()
            return
        }
        MLog.d(TAG, "Refresh: applying pending and re-subscribing (keeping ${_notes.value.size} notes)")
        applyPendingNotes()
        applySubscriptionToStateMachine(subscriptionRelays)
    }

    fun getConnectedRelays(): List<String> = connectedRelays

    fun isConnected(): Boolean = connectedRelays.isNotEmpty()

    /**
     * Get a note by id from the in-memory feed cache (e.g. when opening thread from notification).
     * Returns null if not in current feed.
     */
    fun getNoteFromCache(noteId: String): Note? = feedIndex.byId[noteId]

    /**
     * Fetch a single note by id from relays (one-off subscription). Use when opening thread from
     * reply notification and the root note is not in the feed cache.
     * No kinds filter — event IDs are globally unique, and the root may be any kind.
     */
    suspend fun fetchNoteById(noteId: String, relayUrls: List<String>): Note? {
        if (relayUrls.isEmpty()) return null
        val filter = Filter(ids = listOf(noteId), limit = 1)
        val result = kotlinx.coroutines.CompletableDeferred<Note?>()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val handle = stateMachine.requestTemporarySubscriptionWithRelay(relayUrls, filter, priority = SubscriptionPriority.HIGH) { ev, actualRelayUrl ->
            val note = convertEventToNote(ev, actualRelayUrl)
            result.complete(note)
        }
        // Return immediately when found, or timeout after 5 seconds
        val fetched = kotlinx.coroutines.withTimeoutOrNull(5000L) { result.await() }
        handle.cancel()
        return fetched
    }

    /**
     * Batch-fetch multiple notes by ID from indexer relays in a single subscription.
     * Much faster than fetching one-by-one for reply parent context on profile pages.
     * Returns a map of noteId → Note for all successfully resolved notes.
     */
    suspend fun fetchNotesByIdsBatch(
        noteIds: List<String>,
        userRelayUrls: List<String> = emptyList()
    ): Map<String, Note> {
        if (noteIds.isEmpty()) return emptyMap()
        val relays = buildList {
            addAll(INDEXER_RELAYS)
            addAll(userRelayUrls)
        }.distinct().take(8)
        if (relays.isEmpty()) return emptyMap()

        val results = java.util.concurrent.ConcurrentHashMap<String, Note>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(noteIds.size)
        val filter = Filter(ids = noteIds, limit = noteIds.size)
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(0L)

        val handle = stateMachine.requestTemporarySubscriptionWithRelay(
            relays, filter, priority = SubscriptionPriority.HIGH
        ) { event, actualRelayUrl ->
            if (event.id in noteIds && !results.containsKey(event.id)) {
                results[event.id] = convertEventToNote(event, actualRelayUrl)
                remaining.decrementAndGet()
                lastEventAt.set(System.currentTimeMillis())
            }
        }

        // Settle-based wait: stop when stream goes quiet or all found
        var waited = 0L
        val maxWait = 6000L
        val settleMs = 1000L
        while (waited < maxWait && remaining.get() > 0) {
            delay(150)
            waited += 150
            val lastAt = lastEventAt.get()
            if (lastAt > 0 && System.currentTimeMillis() - lastAt >= settleMs) break
        }
        handle.cancel()
        MLog.d(TAG, "Batch fetch: requested=${noteIds.size}, found=${results.size} in ${waited}ms")
        return results
    }

    /** Well-known indexer relays that archive most public events (fallback for old threads). */
    val INDEXER_RELAYS = listOf(
        "wss://relay.nostr.band",
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://indexer.coracle.social",
        "wss://directory.yabu.me"
    )

    /**
     * Fetch a note by ID using an expanded relay strategy (Amethyst-inspired):
     * 1. Relay hints from e-tags (where the event was seen)
     * 2. Author's NIP-65 outbox relays (where they publish)
     * 3. User's configured relays
     * 4. Well-known indexer relays as fallback
     */
    suspend fun fetchNoteByIdExpanded(
        noteId: String,
        userRelayUrls: List<String>,
        relayHints: List<String> = emptyList(),
        authorPubkey: String? = null
    ): Note? {
        // Build expanded relay list: hints first (most likely to have it), then outbox, then user relays, then indexers
        val expandedRelays = buildList {
            addAll(relayHints.filter { it.startsWith("wss://") || it.startsWith("ws://") })
            if (authorPubkey != null) {
                Nip65RelayListRepository.getCachedOutboxRelays(authorPubkey)?.let { addAll(it) }
            }
            addAll(userRelayUrls)
            addAll(INDEXER_RELAYS)
        }.distinct().take(12) // cap to avoid flooding

        return fetchNoteById(noteId, expandedRelays)
    }

    /**
     * Apply author updates for a batch of pubkeys in one pass. Called by profile update coalescer.
     * One map over notes and pending instead of one per profile.
     */
    private fun updateAuthorsInNotesBatch(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return
        scope.launch {
            processEventMutex.withLock {
                try {
                    val authorMap = pubkeys.mapNotNull { key ->
                        profileCache.getAuthor(key)?.let { key to it }
                    }.toMap()
                    if (authorMap.isEmpty()) return@launch
                    var updatedCount = 0
                    fun updateNote(note: Note): Note {
                        val key = normalizeAuthorIdForCache(note.author.id)
                        val updatedAuthor = authorMap[key]
                        val updatedReposters = if (note.repostedByAuthors.isNotEmpty()) {
                            val mapped = note.repostedByAuthors.map { rb ->
                                authorMap[normalizeAuthorIdForCache(rb.id)] ?: rb
                            }
                            if (mapped != note.repostedByAuthors) mapped else null
                        } else null
                        val result = when {
                            updatedAuthor != null && updatedReposters != null -> note.copy(author = updatedAuthor, repostedByAuthors = updatedReposters)
                            updatedAuthor != null -> note.copy(author = updatedAuthor)
                            updatedReposters != null -> note.copy(repostedByAuthors = updatedReposters)
                            else -> note
                        }
                        if (result !== note) updatedCount++
                        return result
                    }
                    val newNotes = _notes.value.map { updateNote(it) }
                    synchronized(pendingNotesLock) {
                        val updated = _pendingNewNotes.map { updateNote(it) }
                        _pendingNewNotes.clear()
                        _pendingNewNotes.addAll(updated)
                        pendingIndex.rebuild(updated)
                    }
                    if (updatedCount > 0) {
                        setNotes(newNotes.toImmutableList())
                        MLog.d(TAG, "Profile batch: updated $updatedCount notes from ${authorMap.size} profiles")
                        scheduleDisplayUpdate()
                    }
                } catch (e: Throwable) {
                    MLog.e(TAG, "updateAuthorsInNotesBatch failed: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Update notes for a single author (e.g. from external caller). Batched updates are handled internally by the coalescer.
     */
    fun updateAuthorInNotes(pubkey: String) {
        updateAuthorsInNotesBatch(setOf(pubkey.lowercase()))
    }

    /**
     * Re-resolve all authors in the current feed from the profile cache and update notes.
     * Call after a bulk profile load (e.g. debug "Fetch all") so the home feed reflects new cache data.
     * Does not depend on profileUpdated emissions (which can be dropped when many profiles load at once).
     */
    fun refreshAuthorsFromCache() {
        scope.launch {
            processEventMutex.withLock {
                try {
                    val allAuthorIds = mutableSetOf<String>().apply {
                        _notes.value.forEach { add(normalizeAuthorIdForCache(it.author.id)) }
                        synchronized(pendingNotesLock) {
                            _pendingNewNotes.forEach { add(normalizeAuthorIdForCache(it.author.id)) }
                        }
                    }
                    if (allAuthorIds.isEmpty()) return@launch
                    val authorMap = allAuthorIds.mapNotNull { key ->
                        profileCache.getAuthor(key)?.let { key to it }
                    }.toMap()
                    if (authorMap.isEmpty()) return@launch
                    var changedCount = 0
                    val updatedNotes = _notes.value.map { note ->
                        val key = normalizeAuthorIdForCache(note.author.id)
                        val cached = authorMap[key]
                        if (cached != null && cached != note.author) {
                            changedCount++
                            note.copy(author = cached)
                        } else note
                    }
                    if (changedCount == 0) {
                        MLog.d(TAG, "refreshAuthorsFromCache: no author changes detected, skipping")
                        return@launch
                    }
                    setNotes(updatedNotes.toImmutableList())
                    synchronized(pendingNotesLock) {
                        val updated = _pendingNewNotes.map { note ->
                            val key = normalizeAuthorIdForCache(note.author.id)
                            authorMap[key]?.let { note.copy(author = it) } ?: note
                        }
                        _pendingNewNotes.clear()
                        _pendingNewNotes.addAll(updated)
                        pendingIndex.rebuild(updated)
                    }
                    scheduleDisplayUpdate()
                    MLog.d(TAG, "refreshAuthorsFromCache: updated $changedCount/${_notes.value.size} notes with ${authorMap.size} profiles")
                } catch (e: Throwable) {
                    MLog.e(TAG, "refreshAuthorsFromCache failed: ${e.message}", e)
                }
            }
        }
    }
}
