package social.mycelium.android.repository.feed

import android.content.Context
import android.util.Log
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    private var cacheRelayUrls = listOf<String>()

    /** Current user's hex pubkey (lowercase) for immediate own-event display. */
    @Volatile
    private var currentUserPubkey: String? = null

    /** Pending kind-1 events waiting to be flushed into the notes list in a single batch.
     *  PendingKind1Event: event, relayUrl, isPagination (bypasses age gate), isOutbox (bypasses cutoff gate). */
    data class PendingKind1Event(val event: Event, val relayUrl: String, val isPagination: Boolean, val isOutbox: Boolean = false)
    private val pendingKind1Events = ConcurrentLinkedQueue<PendingKind1Event>()
    /** Job that schedules the next batch flush (debounce timer). */
    private var kind1FlushJob: Job? = null
    /** Separate job for continuation flushes (not cancelled by new incoming events). */
    private var continuationFlushJob: Job? = null
    /** Pending relay URLs seen in feed notes that need NIP-11 preload. Debounced to avoid per-event overhead. */
    private val pendingNip11Urls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var nip11PreloadJob: Job? = null
    private val NIP11_PRELOAD_DEBOUNCE_MS = 2000L
    /** Debounce window for batching incoming kind-1 events before flushing to the notes list. */
    private val KIND1_BATCH_DEBOUNCE_MS = 120L
    /** Max events to process per flush cycle. Remaining events trigger an immediate follow-up flush
     *  so rendering spreads across multiple frames instead of one giant recomposition. */
    private val MAX_FLUSH_CHUNK_SIZE = 50
    /** Shorter debounce for continuation flushes (just enough to yield a frame). */
    private val CONTINUATION_FLUSH_MS = 16L

    private val outboxFeedManager = OutboxFeedManager.getInstance()
    private val globalFeedManager = GlobalFeedManager.getInstance()

    init {
        if (BuildConfig.DEBUG) {
            Log.i("MyceliumEvent", "Monitor enabled: kind-1 events will be logged here. Run: adb logcat -s MyceliumEvent")
            Log.i(TAG, "MyceliumEvent monitor enabled (debug). Use logcat -s MyceliumEvent or -s NotesRepository")
        }
        relayStateMachine.registerKind1Handler { event, relayUrl ->
            // Lock-free: just enqueue and schedule a batched flush
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false))
            scheduleKind1Flush()
        }
        relayStateMachine.registerKind6Handler { event, relayUrl ->
            EventRelayTracker.addRelay(event.id, relayUrl)
            scope.launch {
                processEventMutex.withLock { handleKind6Repost(event, relayUrl) }
            }
        }
        // Wire outbox feed events into the same ingestion pipeline
        // Mark as isOutbox=true so they bypass the cutoff gate and go directly to feed.
        // Outbox events represent the user's followed feed discovered late (NIP-65 takes 15+ sec),
        // so they should never be sent to the pending buffer.
        outboxFeedManager.onNoteReceived = { event, relayUrl ->
            Log.d(TAG, "\uD83D\uDCE8 Outbox event: ${event.id.take(8)} from $relayUrl (kind=${event.kind})")
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false, isOutbox = true))
            scheduleKind1Flush()
        }
        // Wire global feed enrichment events (hashtag/list indexer subscriptions) into the pipeline.
        // These arrive during Global mode and should bypass the follow filter but not the age gate.
        globalFeedManager.onNoteReceived = { event, relayUrl ->
            Log.d(TAG, "\uD83C\uDF0D Global enrichment event: ${event.id.take(8)} from $relayUrl")
            EventRelayTracker.addRelay(event.id, relayUrl)
            pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = false, isOutbox = false))
            scheduleKind1Flush()
        }
        startProfileUpdateCoalescer()
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
            Log.d(TAG, "Outbox feed skipped: followFilterEnabled=$followFilterEnabled, follows=${followedPubkeys.size}")
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
     * Schedule a debounced flush of pending kind-1 events. Resets the timer on each new event
     * so we accumulate a full batch before doing one sort + emit cycle.
     */
    private fun scheduleKind1Flush() {
        kind1FlushJob?.cancel()
        kind1FlushJob = scope.launch {
            delay(KIND1_BATCH_DEBOUNCE_MS)
            flushKind1Events()
        }
    }

    /**
     * Drain all pending kind-1 events, convert to Notes, deduplicate, and merge into the
     * notes list with a single sort + emit. This replaces the old per-event mutex+sort pattern.
     */
    private suspend fun flushKind1Events() {
        val batch = mutableListOf<PendingKind1Event>()
        var drained = 0
        while (drained < MAX_FLUSH_CHUNK_SIZE) {
            val item = pendingKind1Events.poll() ?: break
            batch.add(item)
            drained++
        }
        if (batch.isEmpty()) return

        // Queue raw events for Room persistence (before filtering — we want ALL events stored).
        // Event objects are queued as-is; toJson() runs on background IO thread during flush.
        if (eventDao != null && !isGlobalMode) {
            for (item in batch) {
                pendingEventObjects.add(Triple(item.event, item.relayUrl, Unit))
            }
            scheduleEventStoreFlush()
        }

        val flushStartMs = System.currentTimeMillis()
        android.os.Trace.beginSection("NotesRepo.flushKind1Events(${batch.size})")
        try {
            // Convert all events to Notes (no lock needed — convertEventToNote is stateless except profile cache)
            val newNotes = mutableListOf<Note>()
            val newNoteIds = HashSet<String>() // O(1) dedup within batch
            val relayUpdates = mutableMapOf<String, List<String>>() // noteId -> merged relayUrls
            val currentNotes = _notes.value
            val currentIds = currentNotes.associateBy { it.id }
            val pendingIds = synchronized(pendingNotesLock) { _pendingNewNotes.map { it.id }.toSet() }
            // Set of original note IDs that are already represented as reposts (for fast dedup)
            val repostedOriginalIds = buildSet {
                currentNotes.forEach { it.originalNoteId?.let(::add) }
                synchronized(pendingNotesLock) { _pendingNewNotes.forEach { it.originalNoteId?.let(::add) } }
            }
            // Kind 30023 (NIP-23 articles) are parameterized replaceable: same author + d-tag = same article.
            // Track existing articles by author:dTag so updated versions replace older ones.
            val existingArticleKeys = buildMap<String, Long> {
                currentNotes.forEach { note ->
                    if (note.kind == 30023 && note.dTag != null) {
                        val key = "${note.author.id.lowercase()}:${note.dTag}"
                        put(key, note.timestamp)
                    }
                }
            }.toMutableMap()

            // Age gate: events from the main subscription older than the feed floor are
            // silently dropped. Only loadOlderNotes (isPagination=true) may introduce older
            // notes. This prevents relays that ignore `since` from contaminating the feed
            // with ancient notes that corrupt the pagination cursor.
            val ageFloorMs = feedAgeFloorMs
            var ageGateDropped = 0
            // Track which note IDs came from outbox events so they bypass the cutoff gate
            val outboxNoteIds = HashSet<String>()

            // Diagnostic: count duplicate event IDs in this batch (same event from different relays)
            val batchEventIds = batch.filter { it.event.kind in listOf(1, 11, 1111, 1068, 6969, 30023) }
            val uniqueEventIds = batchEventIds.map { it.event.id }.toSet()
            val duplicateCount = batchEventIds.size - uniqueEventIds.size
            val relayDistribution = batchEventIds.groupBy { it.relayUrl }.mapValues { it.value.size }
            if (batchEventIds.isNotEmpty()) {
                Log.d(TAG, "\uD83D\uDD35 BATCH: ${batchEventIds.size} events, ${uniqueEventIds.size} unique, $duplicateCount dupes, relays=${relayDistribution.entries.joinToString { "${it.key.takeLast(25)}=${it.value}" }}")
            }

            for (pending in batch) {
                val event = pending.event
                val relayUrl = pending.relayUrl
                val isPagination = pending.isPagination
                val isOutbox = pending.isOutbox
                if (event.kind != 1 && event.kind != 30023 && event.kind != 1068 && event.kind != 6969) continue
                // Accumulate relay URL in tracker — builds up across duplicate deliveries
                if (relayUrl.isNotBlank()) EventRelayTracker.addRelay(event.id, relayUrl)

                // Age gate: drop ancient events from the live subscription
                // Outbox events bypass the age gate — they represent followed-user notes
                // discovered late via NIP-65 and should always enter the feed.
                if (!isPagination && !isOutbox && ageFloorMs > 0L) {
                    val eventMs = event.createdAt * 1000L
                    if (eventMs < ageFloorMs) {
                        ageGateDropped++
                        continue
                    }
                }

                if (isOutbox) {
                    outboxNoteIds.add(event.id)
                    outboxReceivedNoteIds.add(event.id)
                }
                if (BuildConfig.DEBUG) logIncomingEventSummary(event, relayUrl)
                val note = convertEventToNote(event, relayUrl)

                // Ingestion-level follow filter: when in Following mode, drop notes from
                // non-followed authors immediately. Global notes are never cached — they are
                // ephemeral and destroyed when leaving All/Global view. This saves memory,
                // CPU, and prevents global noise from polluting the followed-only feed.
                if (!isGlobalMode && followFilterEnabled) {
                    val ff = followFilter
                    if (ff != null && ff.isNotEmpty()) {
                        val authorKey = normalizeAuthorIdForCache(note.author.id)
                        val isOwnEvent = authorKey == currentUserPubkey
                        if (!isOwnEvent && authorKey !in ff) continue
                    } else {
                        // Follow filter is enabled but list is null/empty (still loading).
                        // During Loading state, let notes through so feed isn't blank.
                        // Once Live, drop to prevent global bleed into Following feed.
                        if (_feedSessionState.value == FeedSessionState.Live) continue
                    }
                }

                // Track kind:1 notes with I tags as topic replies (NIP-22)
                topicRepliesRepo.processKind1Note(note)

                if (note.isReply) {
                    Nip10ReplyDetector.getRootId(event)?.let { rootId ->
                        ThreadReplyCache.addReply(rootId, note)
                    }
                }

                // If a repost of this note already exists in the feed, merge relay URL
                // into the repost note so relay orbs show everywhere the note lives.
                val repostId = "repost:${event.id}"
                val existingRepost = currentIds[repostId]
                if (existingRepost != null) {
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    if (normalizedNew != null) {
                        val existingUrls = existingRepost.relayUrls.ifEmpty { listOfNotNull(existingRepost.relayUrl) }
                        val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                        if (normalizedNew !in existingNorm) {
                            relayUpdates[repostId] = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                            Log.d(TAG, "\uD83D\uDD35 Relay merge into repost: ${repostId.take(16)} += $normalizedNew (now ${relayUpdates[repostId]?.size} orbs)")
                        }
                    }
                    continue
                }
                if (pendingIds.contains(repostId)) {
                    // Merge relay URL into pending repost note
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    if (normalizedNew != null) {
                        synchronized(pendingNotesLock) {
                            val pendingIndex = _pendingNewNotes.indexOfFirst { it.id == repostId }
                            if (pendingIndex >= 0) {
                                val pendingNote = _pendingNewNotes[pendingIndex]
                                val existingUrls = pendingNote.relayUrls.ifEmpty { listOfNotNull(pendingNote.relayUrl) }
                                val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                                if (normalizedNew !in existingNorm) {
                                    _pendingNewNotes[pendingIndex] = pendingNote.copy(
                                        relayUrls = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                                    )
                                }
                            }
                        }
                    }
                    continue
                }
                // Also skip if this note's ID is already represented as a repost's originalNoteId
                // (but still merge relay URL into the repost)
                if (event.id in repostedOriginalIds) {
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    if (normalizedNew != null) {
                        // Find the repost note that contains this originalNoteId and merge
                        val repostNote = currentNotes.find { it.originalNoteId == event.id }
                        if (repostNote != null) {
                            val existingUrls = repostNote.relayUrls.ifEmpty { listOfNotNull(repostNote.relayUrl) }
                            val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                            if (normalizedNew !in existingNorm) {
                                relayUpdates[repostNote.id] = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                            }
                        }
                    }
                    continue
                }

                // Locally-published event echo: already in feed via injectOwnNote/injectOwnRepost.
                // Just merge relay URL from the relay that echoed it back.
                if (locallyPublishedIds.contains(note.id)) {
                    val existingLocal = currentIds[note.id]
                    if (existingLocal != null) {
                        val existingUrls = existingLocal.relayUrls.ifEmpty { listOfNotNull(existingLocal.relayUrl) }
                        val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                        val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                        if (normalizedNew != null && normalizedNew !in existingNorm) {
                            relayUpdates[note.id] = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                        }
                    }
                    continue
                }

                // Dedup: if already in feed, just merge relay URLs
                val existing = currentIds[note.id]
                if (existing != null) {
                    val existingUrls = existing.relayUrls.ifEmpty { listOfNotNull(existing.relayUrl) }
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                    if (normalizedNew != null && normalizedNew !in existingNorm) {
                        relayUpdates[note.id] = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                        Log.d(TAG, "\uD83D\uDD35 Relay merge: ${note.id.take(8)} += $normalizedNew (now ${relayUpdates[note.id]?.size} orbs)")
                    }
                    continue
                }
                if (pendingIds.contains(note.id)) {
                    // Merge relay URL into pending note (same logic as feed notes above)
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    if (normalizedNew != null) {
                        synchronized(pendingNotesLock) {
                            val pendingIndex = _pendingNewNotes.indexOfFirst { it.id == note.id }
                            if (pendingIndex >= 0) {
                                val pendingNote = _pendingNewNotes[pendingIndex]
                                val existingUrls = pendingNote.relayUrls.ifEmpty { listOfNotNull(pendingNote.relayUrl) }
                                val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                                if (normalizedNew !in existingNorm) {
                                    _pendingNewNotes[pendingIndex] = pendingNote.copy(
                                        relayUrls = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                                    )
                                }
                            }
                        }
                    }
                    continue
                }
                // Dedup within this batch — if already added, merge relay URL
                if (!newNoteIds.add(note.id)) {
                    val normalizedNew = note.relayUrl?.let { social.mycelium.android.utils.normalizeRelayUrl(it) }
                    if (normalizedNew != null) {
                        val batchIdx = newNotes.indexOfFirst { it.id == note.id }
                        if (batchIdx >= 0) {
                            val batchNote = newNotes[batchIdx]
                            val existingUrls = batchNote.relayUrls.ifEmpty { listOfNotNull(batchNote.relayUrl) }
                            val existingNorm = existingUrls.map { social.mycelium.android.utils.normalizeRelayUrl(it) }.toSet()
                            if (normalizedNew !in existingNorm) {
                                newNotes[batchIdx] = batchNote.copy(
                                    relayUrls = (existingUrls + normalizedNew).filter { it.isNotBlank() }
                                )
                            }
                        }
                    }
                    continue
                }

                // Kind 30023 article dedup: same author + d-tag = same article (NIP-33).
                // Keep only the newest version; drop stale copies from different relays.
                if (event.kind == 30023 && note.dTag != null) {
                    val articleKey = "${note.author.id.lowercase()}:${note.dTag}"
                    val existingTs = existingArticleKeys[articleKey]
                    if (existingTs != null && existingTs >= note.timestamp) {
                        // A newer or equal version already exists — skip this older copy
                        continue
                    }
                    // This is the newest version — remove any stale version from the batch
                    if (existingTs != null) {
                        newNotes.removeAll { it.kind == 30023 && it.dTag == note.dTag && it.author.id.lowercase() == note.author.id.lowercase() }
                    }
                    existingArticleKeys[articleKey] = note.timestamp
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
                    _notes.value = _notes.value.filter { it.id !in staleIds }.toImmutableList()
                    Log.d(TAG, "Removed ${staleIds.size} stale article versions replaced by newer events")
                }
            }

            // Apply relay URL merges to existing notes
            if (relayUpdates.isNotEmpty()) {
                Log.d(TAG, "\uD83D\uDD35 Applying ${relayUpdates.size} relay URL merges to _notes")
                val updatedList = currentNotes.map { note ->
                    relayUpdates[note.id]?.let { urls -> note.copy(relayUrls = urls) } ?: note
                }
                _notes.value = updatedList.toImmutableList()
                // Persist merged relay URLs to Room so cold-start restores all orbs.
                // Must flush pending event INSERTs first — the debounced flushEventStore
                // may not have run yet, so the row may not exist for UPDATE to match.
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

            if (newNotes.isEmpty()) {
                if (relayUpdates.isNotEmpty()) scheduleDisplayUpdate()
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
                val current = _notes.value
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
                _notes.value = merged.toImmutableList()
                advancePaginationCursor(feedNotes)
                if (!initialLoadComplete && merged.size % 50 == 0) {
                    Log.d(TAG, "Initial load: ${merged.size} notes so far")
                }
            }

            // Fire counts enrichment immediately at ingestion time — don't wait for
            // display filtering. The relay starts returning reactions/zaps/replies NOW,
            // so by the time updateDisplayedNotes renders the card the data is already
            // in NoteCountsRepository.countsByNoteId. Notes are newest-first (sorted at
            // line 476), so top-of-feed notes appear first in the subscription filter.
            if (feedNotes.isNotEmpty()) {
                val batchRelayMap = LinkedHashMap<String, List<String>>(feedNotes.size)
                for (note in feedNotes) {
                    val relays = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    val effectiveId = note.originalNoteId ?: note.id
                    if (effectiveId !in batchRelayMap) {
                        batchRelayMap[effectiveId] = relays
                    }
                    note.quotedEventIds.forEach { qid ->
                        if (qid !in batchRelayMap) {
                            batchRelayMap[qid] = relays
                        }
                    }
                }
                NoteCountsRepository.setNoteIdsOfInterest(batchRelayMap)
                // Eagerly prefetch quoted notes so cards render with content already
                // available instead of showing "loading quoted note" placeholders.
                // Resolution: memory cache → RawEventCache → Room DB → relay fetch.
                QuotedNoteCache.prefetchForNotes(feedNotes)
                // Eagerly prefetch URL previews so cards render with link metadata
                // already in UrlPreviewCache. Without this, previews only resolve
                // when the viewport-aware enrichment triggers (scroll-driven), causing
                // a loading spinner on every card with a link.
                scheduleUrlPreviewPrefetch(feedNotes)
            }

            // Add pending notes
            if (pendingNew.isNotEmpty()) {
                synchronized(pendingNotesLock) { _pendingNewNotes.addAll(pendingNew) }
                updateDisplayedNewNotesCount()
                // ── Full pre-enrichment for pending notes ──
                // These notes sit in the "X new notes" holding area until the user
                // taps the banner. Pre-fetch ALL data now so when they're revealed
                // everything renders instantly — no loading spinners for counts,
                // quoted embeds, or link previews.
                //
                // 1. Counts subscription (reactions, zaps, replies, reposts)
                val pendingRelayMap = LinkedHashMap<String, List<String>>(pendingNew.size)
                for (note in pendingNew) {
                    val relays = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
                    val effectiveId = note.originalNoteId ?: note.id
                    if (effectiveId !in pendingRelayMap) {
                        pendingRelayMap[effectiveId] = relays
                    }
                    note.quotedEventIds.forEach { qid ->
                        if (qid !in pendingRelayMap) {
                            pendingRelayMap[qid] = relays
                        }
                    }
                }
                NoteCountsRepository.setNoteIdsOfInterest(pendingRelayMap)
                // 2. Quoted note content (memory cache → RawEventCache → Room → relay)
                QuotedNoteCache.prefetchForNotes(pendingNew)
                // 3. URL preview metadata
                scheduleUrlPreviewPrefetch(pendingNew)
            }

            // Always debounce the display update
            scheduleDisplayUpdate()

            // Schedule ONE profile batch fetch for all pubkeys accumulated during this flush
            val profileRelayUrls = getProfileRelayUrls()
            if (pendingProfilePubkeys.isNotEmpty() && profileRelayUrls.isNotEmpty()) {
                scheduleBatchProfileRequest(profileRelayUrls)
            }

            // Collect unique relay URLs from this batch for debounced NIP-11 preload.
            // This ensures relay orb icons resolve for outbox relays not covered by
            // the initial preload (Fix 1) or user subscription relays.
            val nip11Cache = social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
            if (nip11Cache != null) {
                val allRelayUrls = (feedNotes + pendingNew).flatMap { it.relayUrls }.distinct()
                val uncached = allRelayUrls.filter { !nip11Cache.hasCachedRelayInfo(it) }
                if (uncached.isNotEmpty()) {
                    pendingNip11Urls.addAll(uncached)
                    scheduleNip11Preload()
                }
            }

            if (BuildConfig.DEBUG && batch.size > 5) {
                Log.d(TAG, "Flushed ${batch.size} events: ${feedNotes.size} to feed, ${pendingNew.size} to pending, ${relayUpdates.size} relay merges${if (ageGateDropped > 0) ", $ageGateDropped age-gated" else ""}")
            }
            if (ageGateDropped > 0) {
                Log.d(TAG, "Age gate dropped $ageGateDropped events older than ${fmtMs(ageFloorMs)}")
            }

            // If more events remain after this chunk, schedule a continuation flush
            // (separate from kind1FlushJob so incoming events don't cancel the continuation)
            if (pendingKind1Events.isNotEmpty()) {
                continuationFlushJob?.cancel()
                continuationFlushJob = scope.launch {
                    delay(CONTINUATION_FLUSH_MS)
                    flushKind1Events()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "flushKind1Events failed: ${e.message}", e)
        } finally {
            PipelineDiagnostics.recordBatch(batch.size, System.currentTimeMillis() - flushStartMs)
            if (BuildConfig.DEBUG) PipelineDiagnostics.logSummary(TAG)
            android.os.Trace.endSection()
        }
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
                Log.d(TAG, "NIP-11 preload: ${urls.size} new relay URLs from feed notes")
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
                Log.d(TAG, "URL preview prefetch: $prefetched URLs from ${batch.size} notes")
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
            while (true) {
                val triple = pendingEventObjects.poll() ?: break
                val (event, relayUrl, _) = triple
                val normalizedUrl = relayUrl.ifBlank { null }
                // Merge all known relay URLs from tracker into persisted relayUrls
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
                    eventJson = event.toJson(), // O(1) for relay events via rawJson
                    relayUrl = normalizedUrl,
                    relayUrls = allUrls
                ))
            }
            if (entities.isEmpty()) return@withContext
            try {
                dao.insertAll(entities)
                PipelineDiagnostics.recordDbCommit(entities.size)
                if (entities.size > 20) {
                    Log.d(TAG, "Event store: persisted ${entities.size} events (total=${dao.count()})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Event store flush failed: ${e.message}", e)
            }
        }
    }

    /** Persist a kind-6 repost event to the store. Called from handleKind6Repost. */
    private fun persistRepostEvent(event: Event, relayUrl: String) {
        if (eventDao == null || isGlobalMode) return
        pendingEventObjects.add(Triple(event, relayUrl, Unit))
        scheduleEventStoreFlush()
    }

    /**
     * Set the current user's public key. Used to identify own events for immediate display.
     */
    fun setCurrentUserPubkey(pubkey: String?) {
        currentUserPubkey = pubkey?.lowercase()
        Log.d(TAG, "Set current user pubkey: ${pubkey?.take(8)}...")
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()
    /** True when pagination produced too few new notes — stops further loadOlderNotes calls until feed resets. */
    private val _paginationExhausted = MutableStateFlow(false)
    val paginationExhausted: StateFlow<Boolean> = _paginationExhausted.asStateFlow()
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
    /** Hard age floor (ms): events from the main subscription older than this are dropped.
     *  Initialized to (now - FEED_SINCE_DAYS) when subscribing. loadOlderNotes lowers this
     *  as the user paginates so older pages can come through. Prevents relays that ignore
     *  the `since` filter from contaminating the feed with ancient notes. */
    @Volatile private var feedAgeFloorMs: Long = 0L
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
    private val pendingEventObjects = java.util.concurrent.ConcurrentLinkedQueue<Triple<Event, String, Unit>>()
    private var eventStoreFlushJob: Job? = null
    private val EVENT_STORE_FLUSH_DEBOUNCE_MS = 2_000L

    /** Event IDs that the user has deleted — persisted so they stay hidden after restart. */
    private val deletedEventIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val TAG = "NotesRepository"
        /** Max notes kept in memory; oldest dropped to keep feed bounded (scroll/layout performance). */
        private const val MAX_NOTES_IN_MEMORY = 5000
        /** Limit for following feed; relays return only notes from followed authors so we can ask for more.
         *  Raised from 1000→2000 to reduce crowding out of less active followed users by prolific posters. */
        private const val FOLLOWING_FEED_LIMIT = 2000
        private const val FEED_SINCE_DAYS = 7
        /** Debounce display updates so hundreds of events/sec don't thrash the UI. */
        private const val DISPLAY_UPDATE_DEBOUNCE_MS = 150L
        private const val DELETED_IDS_PREFS = "notes_deleted_ids"
        private const val DELETED_IDS_KEY = "deleted_event_ids"
        private const val DELETED_IDS_MAX = 500
        private const val FEED_CACHE_MAX = 200
        /** Max time to wait for older notes before declaring done. */
        private const val OLDER_NOTES_TIMEOUT_MS = 12_000L
        /** After last event arrives, wait this long for more before declaring done. */
        private const val OLDER_NOTES_SETTLE_MS = 1_500L
        /** Max cursor jump per page (14 days). Prevents a single outlier from skipping weeks. */
        private const val MAX_CURSOR_JUMP_MS = 14L * 86_400_000L
        /** Number of events to request per pagination page (limit param). */
        private const val PAGINATION_PAGE_SIZE = 500
        /** How far back (days) the age floor is lowered per pagination page. */
        private const val PAGINATION_PAGE_DAYS = 30L

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
            Log.w(TAG, "prewarmFeedCache: Room not initialized, skipping")
            return
        }
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "prewarmFeedCache: no relay URLs, skipping")
            return
        }

        Log.d(TAG, "prewarmFeedCache: preloading $limit notes from ${relayUrls.size} relays (${followedPubkeys.size} follows)")
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
            Log.d(TAG, "prewarmFeedCache: no events received after ${System.currentTimeMillis() - startMs}ms")
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
                Log.d(TAG, "prewarmFeedCache: persisted ${entities.size} events in ${System.currentTimeMillis() - startMs}ms")
            } catch (e: Exception) {
                Log.e(TAG, "prewarmFeedCache: Room insert failed: ${e.message}", e)
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
                    Log.d(TAG, "prewarmFeedCache: queued ${authorPubkeys.size} profile fetches")
                } catch (e: Exception) {
                    Log.w(TAG, "prewarmFeedCache: profile prefetch failed: ${e.message}")
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
        QuotedNoteCache.roomEventDao = eventDao
        loadDeletedIdsFromDisk(context.applicationContext)
        scope.launch { loadFeedCacheFromRoom() }
        // Migration: clear legacy SharedPreferences feed cache (one-time)
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = context.applicationContext.getSharedPreferences("notes_feed_cache", Context.MODE_PRIVATE)
                if (prefs.contains("feed_notes") || prefs.contains("feed_notes_following")) {
                    prefs.edit().clear().apply()
                    Log.d(TAG, "Cleared legacy SharedPreferences feed cache")
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
                for (entity in entities) {
                    if (entity.eventId in deletedEventIds) continue
                    try {
                        val event = Event.fromJson(entity.eventJson)
                        // Skip kind-6 reposts: their content is raw JSON of the original
                        // event. convertEventToNote would display it as-is (broken text).
                        // Reposts are reconstructed from live relay data via handleKind6Repost.
                        if (event.kind == 6) continue
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
                        Log.w(TAG, "Feed cache: skip bad event ${entity.eventId.take(8)}: ${e.message}")
                    }
                }
                if (notes.isNotEmpty() && _notes.value.isEmpty()) {
                    _notes.value = notes.toImmutableList()
                    _displayedNotes.value = notes.toImmutableList()
                    initialLoadComplete = true
                    val now = System.currentTimeMillis()
                    feedCutoffTimestampMs = now
                    latestNoteTimestampAtOpen = notes.maxOfOrNull { it.timestamp } ?: now
                    _feedSessionState.value = FeedSessionState.Live
                    _hasEverLoadedFeed.value = true
                    Log.d(TAG, "Restored ${notes.size} notes from Room event store")
                    // Prefetch quoted notes for restored feed so cards render without spinners
                    QuotedNoteCache.prefetchForNotes(notes)
                    refreshAuthorsFromCache()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load feed cache from Room failed: ${e.message}", e)
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
            Log.w(TAG, "BLOCKED global subscription while in Following mode — waiting for follow list to load")
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
        Log.d(TAG, "Subscription relays set: ${allUserRelayUrls.size} relays (stay connected to all)")
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
        Log.d(TAG, "Display filter: ${connectedRelays.size} relay(s)")
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
            Log.d(TAG, "Custom list filter applied: ${normalized.size} authors")
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
            Log.w(TAG, "setFollowFilter: ignoring null effective — keeping existing ${followFilter!!.size}-author filter")
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
                Log.d(TAG, "Saved ${currentNotes.size} following notes to memory snapshot")
            }
            // Stop outbox subscriptions so they don't inject followed-only notes into the global feed
            outboxFeedManager.stop()
            // Entering Global/All: clear feed, mark global, start fresh live subscription
            isGlobalMode = true
            kind1FlushJob?.cancel()
            pendingKind1Events.clear()
            _notes.value = persistentListOf()
            _displayedNotes.value = persistentListOf()
            synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
            initialLoadComplete = false
            paginationCursorMs = 0L
            paginationExtraCap = 0
            _paginationExhausted.value = false
            feedCutoffTimestampMs = System.currentTimeMillis()
            Log.d(TAG, "Entering Global mode — feed cleared, outbox stopped, live-only")
            // Start global enrichment with user's subscribed hashtags and any active lists
            val hashtags = PeopleListRepository.subscribedHashtags.value
            val listDTags = emptySet<String>() // Will be updated from DashboardScreen
            if (hashtags.isNotEmpty() || listDTags.isNotEmpty()) {
                globalFeedManager.start(hashtags = hashtags, listDTags = listDTags)
            }
        } else if (leavingGlobal) {
            // Leaving Global/All: destroy global notes, restore following feed from memory snapshot
            isGlobalMode = false
            kind1FlushJob?.cancel()
            pendingKind1Events.clear()
            synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
            // Restore from memory snapshot (instant) or fall back to disk cache (async)
            val snapshot = followingNotesSnapshot
            if (snapshot.isNotEmpty()) {
                _notes.value = snapshot
                _displayedNotes.value = snapshot
                initialLoadComplete = true
                val now = System.currentTimeMillis()
                feedCutoffTimestampMs = now
                latestNoteTimestampAtOpen = snapshot.maxOfOrNull { it.timestamp } ?: now
                _feedSessionState.value = FeedSessionState.Live
                Log.d(TAG, "Leaving Global mode — restored ${snapshot.size} following notes from memory")
            } else {
                _notes.value = persistentListOf()
                _displayedNotes.value = persistentListOf()
                initialLoadComplete = false
                paginationCursorMs = 0L
                paginationExtraCap = 0
                _paginationExhausted.value = false
                feedCutoffTimestampMs = System.currentTimeMillis()
                scope.launch { loadFeedCacheFromRoom() }
                Log.d(TAG, "Leaving Global mode — no memory snapshot, restoring from Room")
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
            Log.d(TAG, "Re-subscribed: $mode")
        }
    }

    private fun updateDisplayedNotes() {
        android.os.Trace.beginSection("NotesRepo.updateDisplayedNotes")
        try {
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
            val repostedOriginals = buildSet {
                allNotes.forEach { note -> note.originalNoteId?.let(::add) }
            }

            // Early exit: follow mode with empty follow list
            if (followEnabled) {
                if (currentFollowFilter == null || currentFollowFilter.isEmpty()) {
                    if (customListActive) {
                        // Intentional: user selected a people list with 0 members → blank feed
                        _displayedNotes.value = persistentListOf()
                        return
                    }
                    if (_displayedNotes.value.isNotEmpty()) {
                        Log.w(TAG, "Follow filter temporarily empty, keeping ${_displayedNotes.value.size} displayed notes")
                        return
                    } else {
                        _displayedNotes.value = persistentListOf()
                        return
                    }
                }
            }

            // Single-pass filter: relay match + follow filter + reply filter combined.
            // Replaces 3 sequential .filter() calls (3× O(N)) with one pass (1× O(N)).
            var afterRelay = 0
            var afterFollow = 0
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
                // 4. Boost dedup: skip original if a repost version exists
                if (note.originalNoteId == null && note.id in repostedOriginals) continue
                filtered.add(note)
            }
            if (filtered.size != _displayedNotes.value.size || filtered.isEmpty()) {
                Log.d(TAG, "updateDisplayed: total=${allNotes.size} →relay=$afterRelay →follow=$afterFollow →noReply=${filtered.size} (connectedRelays=${connectedRelays.size}, followEnabled=$followEnabled, followList=${currentFollowFilter?.size ?: 0})")
            }
            // Skip emission if the note ID list is identical — prevents unnecessary
            // LazyColumn re-layout that causes scroll jumps and nav bar reappearing.
            val currentIds = _displayedNotes.value
            val newList = filtered.toList()
            val idsMatch = newList.size == currentIds.size &&
                newList.indices.all { i -> newList[i].id == currentIds[i].id }
            if (idsMatch) {
                // IDs match but note data may have updated (relay URLs, author info) —
                // only emit if any note object actually differs (referential check).
                val dataChanged = newList.indices.any { i -> newList[i] !== currentIds[i] }
                if (dataChanged) {
                    val multiOrb = newList.count { it.relayUrls.size > 1 }
                    Log.d(TAG, "\uD83D\uDD35 Display emit (data changed, IDs same): ${newList.size} notes, $multiOrb multi-orb")
                    _displayedNotes.value = newList.toImmutableList()
                }
                updateDisplayedNewNotesCount()
                return
            }
            // Log relay orb stats for diagnostics
            val multiOrb = newList.count { it.relayUrls.size > 1 }
            val singleOrb = newList.count { it.relayUrls.size == 1 }
            Log.d(TAG, "\uD83D\uDD35 Display emit (IDs changed): ${newList.size} notes, $multiOrb multi-orb, $singleOrb single-orb")
            if (newList.isNotEmpty()) {
                val sample = newList.first()
                Log.d(TAG, "\uD83D\uDD35   sample[0] ${sample.id.take(8)}: relayUrls=${sample.relayUrls}, relayUrl=${sample.relayUrl}")
            }
            _displayedNotes.value = newList.toImmutableList()
            updateDisplayedNewNotesCount()
            // Fire counts subscription immediately — no debounce. Notes are already in
            // feed-position order (newest first), so top-of-viewport notes appear first
            // in the subscription filter and get enriched with reactions/zaps fastest.
            // NoteCountsRepository has its own 200ms debounce to coalesce rapid updates.
            val notes = newList.take(150)
            val noteRelayMap = LinkedHashMap<String, List<String>>(notes.size)
            for (note in notes) {
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
            // Depth-2: scan resolved depth-1 quoted notes for nested nostr: references
            // so counts for recursively quoted notes survive the replace=true below.
            val depth2Ids = mutableListOf<Pair<String, List<String>>>()
            for ((qid, qRelays) in noteRelayMap.toMap()) {
                val cached = QuotedNoteCache.getCached(qid) ?: continue
                val nested = social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventIds(cached.fullContent)
                for (nid in nested) {
                    if (nid !in noteRelayMap) {
                        val nestedCached = QuotedNoteCache.getCached(nid)
                        depth2Ids.add(nid to (listOfNotNull(nestedCached?.relayUrl).ifEmpty { qRelays }))
                    }
                }
            }
            for ((nid, nRelays) in depth2Ids) {
                noteRelayMap[nid] = nRelays
            }
            NoteCountsRepository.setNoteIdsOfInterest(noteRelayMap, replace = true)
        } catch (e: Throwable) {
            Log.e(TAG, "updateDisplayedNotes failed: ${e.message}", e)
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
            Log.e(TAG, "updateDisplayedNewNotesCount failed: ${e.message}", e)
            _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        }
    }

    /**
     * Disconnect from all relays (e.g. on screen exit). Delegates to shared state machine.
     */
    fun disconnectAll() {
        Log.d(TAG, "Disconnecting from all relays")
        outboxFeedManager.stop()
        kind1FlushJob?.cancel()
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
        _notes.value = persistentListOf()
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
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
            Log.w(TAG, "No relays configured")
            return
        }
        // Idempotency guard: skip only if we're already subscribed to the same relay set,
        // notes are loaded, AND the feed is Live (not still Loading from a previous call).
        val relaySet = allUserRelayUrls.toSet()
        if (relaySet == lastEnsuredRelaySet && _notes.value.isNotEmpty() && _feedSessionState.value == FeedSessionState.Live && RelayConnectionStateMachine.getInstance().isSubscriptionActive()) {
            Log.d(TAG, "ensureSubscriptionToNotes: already active for ${relaySet.size} relays, skipping")
            return
        }
        lastEnsuredRelaySet = relaySet
        setSubscriptionRelays(allUserRelayUrls)

        // Wait for Room cache check to complete before deciding resume vs fresh subscribe.
        // Without this, a race between loadFeedCacheFromRoom() (async from prepareFeedCache)
        // and this method would see _notes.value empty, fall through to subscribeToNotes(),
        // which wipes the feed — destroying Room-restored notes.
        if (!_feedCacheChecked.value) {
            Log.d(TAG, "ensureSubscriptionToNotes: waiting for feed cache check to complete...")
            kotlinx.coroutines.withTimeoutOrNull(3000L) {
                _feedCacheChecked.first { it }
            }
            Log.d(TAG, "ensureSubscriptionToNotes: feed cache check done, notes=${_notes.value.size}")
        }

        if (_notes.value.isNotEmpty()) {
            // Resume: keep existing feed; only set cutoff if not already set (avoid blocking all notes on re-subscribe).
            Log.d(TAG, "Restoring subscription for ${allUserRelayUrls.size} relays (keeping ${_notes.value.size} notes)")
            if (feedCutoffTimestampMs == 0L) {
                feedCutoffTimestampMs = System.currentTimeMillis()
            }
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
            Log.w(TAG, "No subscription relays set")
            _isLoading.value = false
            initialLoadComplete = true
            return
        }

        kind1FlushJob?.cancel()
        pendingKind1Events.clear()
        _isLoading.value = true
        _feedSessionState.value = FeedSessionState.Loading
        _error.value = null
        _notes.value = persistentListOf()
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        initialLoadComplete = false

        // Cutoff set exactly when we start the subscription: only notes older than this moment are shown in the feed
        feedCutoffTimestampMs = System.currentTimeMillis()
        latestNoteTimestampAtOpen = feedCutoffTimestampMs
        // Age floor: drop events from the main subscription older than the subscription's since window.
        // This is the hard boundary — relays that ignore `since` can't contaminate the feed.
        feedAgeFloorMs = System.currentTimeMillis() - FEED_SINCE_DAYS.toLong() * 86_400_000L
        paginationCursorMs = 0L
        paginationExtraCap = 0
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
                    kind1FlushJob?.cancel()
                    flushKind1Events()
                    if (_notes.value.isNotEmpty()) break
                }
                latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
                initialLoadComplete = true
                _isLoading.value = false
                _feedSessionState.value = FeedSessionState.Live
                _hasEverLoadedFeed.value = true
                updateDisplayedNotes()
                Log.d(TAG, "Subscription active for ${subscriptionRelays.size} relays, ${_notes.value.size} notes loaded in ${waited}ms (feed cutoff at $feedCutoffTimestampMs)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to notes: ${e.message}", e)
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
            Log.d(TAG, "Cursor advanced: ${fmtMs(prev)} → ${fmtMs(candidate)} (p5, absMin=${fmtMs(absMin)})")
        } else if (absMin < prev) {
            // Stall: p5 didn't advance (outlier cluster), fall back to absolute min
            paginationCursorMs = absMin
            Log.d(TAG, "Cursor stall-break: ${fmtMs(prev)} → ${fmtMs(absMin)} (absMin fallback)")
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

        val relays = subscriptionRelays.ifEmpty { return }

        _isLoadingOlder.value = true

        // ── Room-first path: serve deep-fetched history instantly ──────────
        // If the DeepHistoryFetcher has persisted events to Room, load them
        // before hitting relays. This makes scrolling back through history
        // instantaneous — no relay round-trip needed for cached windows.
        val dao = eventDao
        if (dao != null) {
            scope.launch {
                try {
                    val roomEvents = if (authors != null) {
                        dao.getOlderFeedEvents(authors, untilSec, PAGINATION_PAGE_SIZE)
                    } else {
                        dao.getOlderFeedEventsAll(untilSec, PAGINATION_PAGE_SIZE)
                    }
                    if (roomEvents.isNotEmpty()) {
                        Log.d(TAG, "loadOlderNotes: Room-first hit — ${roomEvents.size} cached events")
                        paginationExtraCap += roomEvents.size
                        for (entity in roomEvents) {
                            try {
                                val event = Event.fromJson(entity.eventJson)
                                // Seed tracker from cached relay URLs (may have multiple comma-separated)
                                val cachedRelayUrl = entity.relayUrl ?: ""
                                if (cachedRelayUrl.contains(",")) {
                                    cachedRelayUrl.split(",").forEach { url ->
                                        EventRelayTracker.addRelay(event.id, url.trim())
                                    }
                                } else {
                                    EventRelayTracker.addRelay(event.id, cachedRelayUrl)
                                }
                                pendingKind1Events.add(PendingKind1Event(event, cachedRelayUrl, isPagination = true))
                            } catch (e: Exception) {
                                // Skip malformed cached events
                            }
                        }
                        // Lower age floor so these events pass the age gate
                        val oldestCachedMs = roomEvents.minOf { it.createdAt } * 1000L
                        if (oldestCachedMs < feedAgeFloorMs) feedAgeFloorMs = oldestCachedMs
                        flushKind1Events()
                        val newSize = _notes.value.size
                        // Update cursor from oldest cached event
                        val oldestSec = roomEvents.minOf { it.createdAt }
                        if (paginationCursorMs == 0L || oldestSec * 1000L < paginationCursorMs) {
                            paginationCursorMs = oldestSec * 1000L
                        }
                        Log.d(TAG, "loadOlderNotes: Room served ${roomEvents.size} events, feed=$newSize, cursor=${fmtMs(paginationCursorMs)}")
                        _isLoadingOlder.value = false
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Room-first pagination failed: ${e.message}")
                }
                // Room empty for this window — fall back to relay fetch
                loadOlderNotesFromRelay(cursorMs, untilSec, authors, relays)
            }
            return
        }

        // No Room available — relay fetch directly
        loadOlderNotesFromRelay(cursorMs, untilSec, authors, relays)
    }

    /** Relay-based pagination (original loadOlderNotes logic). */
    private fun loadOlderNotesFromRelay(cursorMs: Long, untilSec: Long, authors: List<String>?, relays: List<String>) {
        val currentNotes = _notes.value
        olderNotesHandle?.cancel()
        paginationExtraCap += PAGINATION_PAGE_SIZE
        val beforeCount = currentNotes.size
        Log.d(TAG, "loadOlderNotes: until=${fmtMs(cursorMs)} cursor=$cursorMs ageFloor=${fmtMs(feedAgeFloorMs)} from ${relays.size} relays")
        relayStateMachine.dumpRelaySlots("loadOlder")

        // Lower the age floor so pagination events pass the age gate in flushKind1Events.
        // Set it far enough back that this page's events will be accepted.
        // Each page can reach back at most PAGINATION_PAGE_DAYS beyond the current cursor.
        val pageFloorMs = cursorMs - PAGINATION_PAGE_DAYS * 86_400_000L
        if (pageFloorMs < feedAgeFloorMs) {
            feedAgeFloorMs = pageFloorMs
            Log.d(TAG, "Age floor lowered to ${fmtMs(feedAgeFloorMs)} for pagination")
        }

        val filter = if (authors != null) {
            Filter(kinds = listOf(1), authors = authors, limit = PAGINATION_PAGE_SIZE, until = untilSec)
        } else {
            Filter(kinds = listOf(1), limit = PAGINATION_PAGE_SIZE, until = untilSec)
        }

        val lastEventAt = java.util.concurrent.atomic.AtomicLong(0)
        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)
        // Track the oldest event timestamp received so we know the exact next cursor
        val oldestReceivedMs = java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        olderNotesHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
            relayUrls = relays,
            filters = listOf(filter),
            priority = SubscriptionPriority.HIGH,
        ) { event, relayUrl ->
            if (event.kind == 1) {
                lastEventAt.set(System.currentTimeMillis())
                eventCount.incrementAndGet()
                val eventMs = event.createdAt * 1000L
                oldestReceivedMs.updateAndGet { prev -> minOf(prev, eventMs) }
                pendingKind1Events.add(PendingKind1Event(event, relayUrl, isPagination = true))
                scheduleKind1Flush()
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

            // Flush any remaining buffered events from this page
            kind1FlushJob?.cancel()
            flushKind1Events()

            val newSize = _notes.value.size
            val added = newSize - beforeCount
            val received = eventCount.get()

            // Update cursor from the oldest event we actually received (precise, no inference)
            val oldest = oldestReceivedMs.get()
            if (oldest < Long.MAX_VALUE && oldest > 0) {
                if (paginationCursorMs == 0L || oldest < paginationCursorMs) {
                    Log.d(TAG, "Pagination cursor: ${fmtMs(paginationCursorMs)} → ${fmtMs(oldest)} (from received events)")
                    paginationCursorMs = oldest
                }
            }

            // Exhaustion: the relay returned 0 raw events, meaning there truly is nothing
            // older in this relay set. We do NOT exhaust based on deduped additions —
            // receiving 200 dupes just means we already had them, not that the relay is empty.
            if (received == 0) {
                _paginationExhausted.value = true
                Log.d(TAG, "Pagination exhausted: relay returned 0 events")
            } else {
                Log.d(TAG, "Older notes loaded: +$added new (${received} received), feed=$newSize, cursor=${fmtMs(paginationCursorMs)}")
            }
            _isLoadingOlder.value = false
        }
    }

    /**
     * Subscribe to notes from a specific relay only. Uses requestFeedChange (subscription swap, no disconnect).
     */
    suspend fun subscribeToRelayNotes(relayUrl: String, limit: Int = 100) {
        _isLoading.value = true
        _error.value = null
        _notes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
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
            Log.d(TAG, "Subscription active for relay: $relayUrl (state machine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes from relay: ${e.message}", e)
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
            Log.w(TAG, "No relays provided for author subscription")
            return
        }

        _isLoading.value = true
        _error.value = null
        _notes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
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
                scheduleKind1Flush()
            }
            connectedRelays = relayUrls
            latestNoteTimestampAtOpen = _notes.value.maxOfOrNull { it.timestamp } ?: feedCutoffTimestampMs
            initialLoadComplete = true
            _isLoading.value = false
            Log.d(TAG, "Author subscription active (state machine)")
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to author notes: ${e.message}", e)
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
            Log.i("MyceliumEvent", "Monitor active: first kind-1 received (you should see one line per note from here)")
            Log.i(TAG, "MyceliumEvent: first kind-1 received")
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
        Log.i("MyceliumEvent", line)
        eventCountForSampling++
        if (eventCountForSampling % 20 == 0) {
            Log.d(TAG, "Event sample: $line")
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
     *  Cap grows dynamically as the user paginates older notes so they don't get trimmed immediately. */
    private fun trimNotesToCap(notes: List<Note>): List<Note> {
        val effectiveCap = MAX_NOTES_IN_MEMORY + paginationExtraCap
        return if (notes.size <= effectiveCap) notes else notes.take(effectiveCap)
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
                Log.d(TAG, "Skipping kind-6 echo of locally-published repost ${event.id.take(8)}")
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
                    Log.e(TAG, "Failed to parse kind-6 repost JSON: ${e.message}")
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
                    relayUrl = relayUrl.ifEmpty { null },
                    relayUrls = if (relayUrl.isNotEmpty()) listOf(relayUrl) else emptyList(),
                    isReply = repostIsReply,
                    rootNoteId = if (repostIsReply) repostRootId else null,
                    replyToId = if (repostIsReply) repostReplyToId else null,
                    originalNoteId = originalNoteId,
                    repostedByAuthors = listOf(reposterAuthor),
                    repostTimestamp = repostTimestampMs
                )
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
                    Log.w(TAG, "Kind-6 tag-only repost but no relays to fetch original note $originalNoteId")
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
            Log.e(TAG, "Error handling kind-6 repost: ${e.message}", e)
        }
    }

    /** Insert a repost Note into the feed (shared by content-embedded and tag-only repost paths).
     *  Deduplicates: if the same original note is already in the feed, merges boosters and keeps the latest repost timestamp. */
    private fun insertRepostNote(note: Note, repostTimestampMs: Long) {
        val currentNotes = _notes.value
        val existingIndex = currentNotes.indexOfFirst { it.id == note.id }

        if (existingIndex >= 0) {
            // Same original note already in feed — merge boosters AND relay URLs
            val existing = currentNotes[existingIndex]
            val newBooster = note.repostedByAuthors.firstOrNull() ?: return
            if (existing.repostedByAuthors.any { it.id == newBooster.id }) return // same person already listed
            val mergedAuthors = (listOf(newBooster) + existing.repostedByAuthors).distinctBy { it.id }
            val latestRepostTs = maxOf(repostTimestampMs, existing.repostTimestamp ?: 0L)
            // Merge relay URLs: existing note's relays + new repost delivery relay
            val existingUrls = existing.relayUrls.ifEmpty { listOfNotNull(existing.relayUrl) }
            val newUrls = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
            val mergedUrls = (existingUrls + newUrls).map { social.mycelium.android.utils.normalizeRelayUrl(it) }.distinct().filter { it.isNotBlank() }
            val merged = existing.copy(
                repostedByAuthors = mergedAuthors,
                repostTimestamp = latestRepostTs,
                relayUrls = mergedUrls
            )
            val updatedNotes = currentNotes.toMutableList()
            updatedNotes[existingIndex] = merged
            _notes.value = updatedNotes.sortedByDescending { it.repostTimestamp ?: it.timestamp }.toImmutableList()
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
                val newNotes = trimNotesToCap((updatedNotes + noteWithMergedRelays).sortedByDescending { it.repostTimestamp ?: it.timestamp })
                _notes.value = newNotes.toImmutableList()
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
            val newNotes = trimNotesToCap((currentNotes + note).sortedByDescending { it.repostTimestamp ?: it.timestamp })
            _notes.value = newNotes.toImmutableList()
            scheduleDisplayUpdate()
        } else {
            synchronized(pendingNotesLock) { _pendingNewNotes.add(note) }
            updateDisplayedNewNotesCount()
        }
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
        _notes.value = (listOf(withState) + currentNotes.toImmutableList()).take(MAX_NOTES_IN_MEMORY).toImmutableList()
        scheduleDisplayUpdate()
        Log.d(TAG, "Injected own note ${note.id.take(8)} (publishState=Sending)")
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
                _notes.value = updated.sortedByDescending { it.repostTimestamp ?: it.timestamp }.toImmutableList()
                scheduleDisplayUpdate()
                Log.d(TAG, "Merged own repost into existing ${compositeId.take(16)} (now ${mergedAuthors.size} boosters)")
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
            _notes.value = (listOf(note) + notesAfterRemoval).take(MAX_NOTES_IN_MEMORY).toImmutableList()
            scheduleDisplayUpdate()
            Log.d(TAG, "Injected own repost ${compositeId.take(16)} (${mergedAuthors.size} boosters, publishState=Sending)")
        } catch (e: Throwable) {
            Log.e(TAG, "injectOwnRepost failed: ${e.message}", e)
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
            _notes.value = filtered.toImmutableList()
            synchronized(pendingNotesLock) {
                _pendingNewNotes.removeAll { it.id == eventId || it.originalNoteId == eventId }
            }
            scheduleDisplayUpdate()
            Log.d(TAG, "Removed note ${eventId.take(8)} from feed (${currentNotes.size - filtered.size} entries)")
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
                Log.d(TAG, "Loaded ${raw.size} deleted event IDs from disk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load deleted IDs: ${e.message}")
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
                Log.e(TAG, "Failed to persist deleted IDs: ${e.message}")
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
                scheduleDisplayUpdate()
            }
            return
        }
        val updated = currentNotes.toMutableList()
        updated[index] = updated[index].copy(publishState = state)
        _notes.value = updated.toImmutableList()
        scheduleDisplayUpdate()
        Log.d(TAG, "Updated publishState for ${eventId.take(8)} → $state")

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
        scheduleDisplayUpdate()
        Log.d(TAG, "Merged publish relay $relayUrl into ${eventId.take(8)} (now ${updatedUrls.size} orbs)")
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
                Log.d(TAG, "Injected local repost for ${originalNote.id.take(8)} by ${reposterPubkey.take(8)}")
            } catch (e: Throwable) {
                Log.e(TAG, "injectLocalRepost failed: ${e.message}", e)
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

        Log.d(TAG, "Flushing repost batch: ${allNoteIds.size} notes across ${allRelayUrls.size} relays (was ${allNoteIds.size} individual subs)")

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
                    Log.e(TAG, "Error processing batched repost note: ${e.message}", e)
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
                            Log.d(TAG, "Batch profile fetch: ${batch.size} pubkeys from ${urls.size} indexer + ${batchHints.size} hint relays")
                            profileCache.requestProfileWithHints(batch, urls, batchHints)
                        } else {
                            Log.d(TAG, "Batch profile fetch: ${batch.size} pubkeys from ${urls.size} relays")
                            profileCache.requestProfiles(batch, urls)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Batch profile request failed: ${e.message}", e)
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
        // Request kind-0 for pubkeys mentioned in content (npub + nprofile + hex)
        // nprofile relay hints are preserved so profiles not on indexer relays can be resolved
        extractPubkeysWithHintsFromContent(event.content).forEach { (hex, relayHints) ->
            if (profileCache.getAuthor(hex) == null && profileRelayUrls.isNotEmpty()) {
                pendingProfilePubkeys.add(hex.lowercase())
                if (relayHints.isNotEmpty()) {
                    pendingProfileRelayHints[hex.lowercase()] = relayHints
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
        kind1FlushJob?.cancel()
        pendingKind1Events.clear()
        _notes.value = persistentListOf()
        _displayedNotes.value = persistentListOf()
        synchronized(pendingNotesLock) { _pendingNewNotes.clear() }
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        feedCutoffTimestampMs = 0L
        latestNoteTimestampAtOpen = 0L
        initialLoadComplete = false
        paginationCursorMs = 0L
        paginationExtraCap = 0
        _paginationExhausted.value = false
        _feedSessionState.value = FeedSessionState.Idle
        _hasEverLoadedFeed.value = false
        // Reset subscription guard so ensureSubscriptionToNotes re-applies after account switch
        lastEnsuredRelaySet = emptySet()
        // Clear relay tracker so old account's relay data doesn't leak
        EventRelayTracker.clear()
        // Clear Room event store so old account's notes don't leak on next cold start
        scope.launch {
            try { eventDao?.deleteAll() } catch (e: Exception) {
                Log.e(TAG, "Event store clear failed: ${e.message}", e)
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
                list
            }
        }
        if (toMerge.isEmpty()) return
        _feedSessionState.value = FeedSessionState.Refreshing
        val pendingCount = toMerge.size
        val merged = trimNotesToCap((_notes.value + toMerge).distinctBy { it.id }.sortedByDescending { it.repostTimestamp ?: it.timestamp })
        _notes.value = merged.toImmutableList()
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
            Log.d(TAG, "Re-prefetching ${notesWithUnresolvedQuotes.size} notes with unresolved quoted content")
            QuotedNoteCache.prefetchForNotes(notesWithUnresolvedQuotes)
        }
        updateDisplayedNotes()
        latestNoteTimestampAtOpen = merged.maxOfOrNull { it.timestamp } ?: 0L
        _newNotesCounts.value = NewNotesCounts(0, 0, System.currentTimeMillis())
        _feedSessionState.value = FeedSessionState.Live
        Log.d(TAG, "Applied $pendingCount pending notes (total: ${merged.size})")
    }

    /**
     * Refresh: merge pending notes into the feed and re-apply subscription (no clear).
     * Does not call subscribeToNotes() so the feed is not wiped; avoids the "recount" where total drops and rolls back up.
     */
    suspend fun refresh() {
        if (subscriptionRelays.isEmpty()) {
            Log.w(TAG, "Refresh skipped: no subscription relays")
            updateDisplayedNotes()
            return
        }
        Log.d(TAG, "Refresh: applying pending and re-subscribing (keeping ${_notes.value.size} notes)")
        applyPendingNotes()
        applySubscriptionToStateMachine(subscriptionRelays)
    }

    fun getConnectedRelays(): List<String> = connectedRelays

    fun isConnected(): Boolean = connectedRelays.isNotEmpty()

    /**
     * Get a note by id from the in-memory feed cache (e.g. when opening thread from notification).
     * Returns null if not in current feed.
     */
    fun getNoteFromCache(noteId: String): Note? = _notes.value.find { it.id == noteId }

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
        Log.d(TAG, "Batch fetch: requested=${noteIds.size}, found=${results.size} in ${waited}ms")
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
                    }
                    if (updatedCount > 0) {
                        _notes.value = newNotes.toImmutableList()
                        Log.d(TAG, "Profile batch: updated $updatedCount notes from ${authorMap.size} profiles")
                        scheduleDisplayUpdate()
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "updateAuthorsInNotesBatch failed: ${e.message}", e)
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
                        Log.d(TAG, "refreshAuthorsFromCache: no author changes detected, skipping")
                        return@launch
                    }
                    _notes.value = updatedNotes.toImmutableList()
                    synchronized(pendingNotesLock) {
                        val updated = _pendingNewNotes.map { note ->
                            val key = normalizeAuthorIdForCache(note.author.id)
                            authorMap[key]?.let { note.copy(author = it) } ?: note
                        }
                        _pendingNewNotes.clear()
                        _pendingNewNotes.addAll(updated)
                    }
                    scheduleDisplayUpdate()
                    Log.d(TAG, "refreshAuthorsFromCache: updated $changedCount/${_notes.value.size} notes with ${authorMap.size} profiles")
                } catch (e: Throwable) {
                    Log.e(TAG, "refreshAuthorsFromCache failed: ${e.message}", e)
                }
            }
        }
    }
}
