package social.mycelium.android.repository

import android.util.Log
import social.mycelium.android.cache.ThreadReplyCache
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.utils.UrlDetector
import social.mycelium.android.utils.extractPubkeysFromContent
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job

/**
 * Repository for fetching and managing Kind 1 replies to Kind 1 notes using the shared
 * RelayConnectionStateMachine (one-off subscription API). Handles threaded conversations
 * for regular notes following NIP-10 (Reply Tags). Uses the same NostrClient as the feed
 * to avoid duplicate connections to the same relays.
 *
 * Kind 1 events are standard text notes. Replies are also Kind 1 events that:
 * - Reference the root note via "e" tags with "root" marker
 * - Reference the parent reply via "e" tags with "reply" marker
 * - Can be nested to create threaded conversations
 */
class Kind1RepliesRepository {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })

    init {
        // Observe locally-published replies from ThreadReplyCache and inject them into the
        // live _replies state so the UI updates immediately without waiting for relay echo.
        scope.launch {
            ThreadReplyCache.localReplyAdded.collect { (rootId, note) ->
                // Only inject if this thread is currently active
                if (_replies.value.containsKey(rootId)) {
                    injectLocalReply(rootId, note)
                }
            }
        }
    }

    // Replies for a specific note ID
    private val _replies = MutableStateFlow<Map<String, List<Note>>>(emptyMap())
    val replies: StateFlow<Map<String, List<Note>>> = _replies.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var connectedRelays = listOf<String>()
    private var cacheRelayUrls = listOf<String>()
    private val activeSubscriptions = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val profileCache = ProfileMetadataCache.getInstance()

    /** Active CybinRelayPool subscription handle for reply fetching. Cancel before re-subscribing. */
    @Volatile
    private var replySubscriptionHandle: TemporarySubscriptionHandle? = null

    /** Parent ids we've requested (per thread root) to avoid duplicate fetches. */
    private val pendingParentFetches = mutableMapOf<String, MutableSet<String>>()
    /** Batched parent IDs waiting to be flushed as ONE subscription. */
    private val pendingParentBatch = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    /** Debounce job for batched parent fetch. */
    private var parentBatchJob: Job? = null
    private val PARENT_BATCH_DELAY_MS = 300L

    /** Per-thread cache: rootNoteId -> (replyId -> Note) for fast lookup and tree building. */
    private val threadReplyCache = mutableMapOf<String, MutableMap<String, Note>>()

    /** Pending pubkeys for batched kind-0 profile fetch. */
    private val pendingProfilePubkeys = java.util.Collections.synchronizedSet(HashSet<String>())
    private var profileBatchJob: kotlinx.coroutines.Job? = null
    private val PROFILE_BATCH_DELAY_MS = 120L
    private val PROFILE_BATCH_SIZE = 80

    /** Incoming events buffered for batch processing. Thread-safe queue written from WS threads. */
    private val pendingEvents = ConcurrentLinkedQueue<Pair<String, Note>>()
    /** Debounce job for flushing pending events into _replies. */
    private var flushJob: kotlinx.coroutines.Job? = null
    /** Short debounce: batch events arriving within this window into one sort+emit cycle. */
    private val FLUSH_DEBOUNCE_MS = 60L
    /** Max time before we force-flush even if events keep arriving (prevents starvation). */
    private val FLUSH_MAX_DELAY_MS = 200L
    /** Timestamp of the first un-flushed event in the current batch. */
    @Volatile private var batchStartMs = 0L

    /** Relay URLs that yielded replies for the current thread root (outbox-discovered propagation). */
    private val _replySourceRelays = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val replySourceRelays: StateFlow<Map<String, Set<String>>> = _replySourceRelays.asStateFlow()

    /** Reply IDs we've already sent deep-fetch REQs for (per thread root). Prevents duplicate queries. */
    private val deepFetchedIds = mutableMapOf<String, MutableSet<String>>()
    /** Max depth of iterative deep-fetch rounds (prevents runaway chains). */
    private val MAX_DEEP_FETCH_ROUNDS = 5

    /**
     * Set cache relay URLs for kind-0 profile fetches (from RelayStorageManager.loadIndexerRelays).
     */
    fun setCacheRelayUrls(urls: List<String>) {
        cacheRelayUrls = urls
    }

    companion object {
        private const val TAG = "Kind1RepliesRepository"
        /** Short initial window; loading clears on first reply (live) or after this. Subscription stays open for more replies. */
        private const val INITIAL_LOAD_WINDOW_MS = 800L
        /** Timeout for one-off parent fetch; then we destroy subscription to avoid leaks. */
        private const val PARENT_FETCH_TIMEOUT_MS = 6_000L
    }

    /**
     * Set relay URLs for subsequent fetchRepliesForNote. Uses shared client; no separate connect.
     */
    fun connectToRelays(relayUrls: List<String>) {
        Log.d(TAG, "Relay URLs set for Kind 1 replies: ${relayUrls.size}")
        connectedRelays = relayUrls
    }

    /**
     * Cancel all reply subscriptions and clear state. Does not disconnect the shared client.
     */
    fun disconnectAll() {
        Log.d(TAG, "Cleaning up Kind 1 reply subscriptions")
        flushJob?.cancel()
        pendingEvents.clear()
        batchStartMs = 0L
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        replySubscriptionHandle?.cancel()
        replySubscriptionHandle = null
        connectedRelays = emptyList()
        deepFetchedIds.clear()
        _replies.value = emptyMap()
    }

    /**
     * Inject a locally-published reply into the live replies state.
     * Called after successfully publishing a kind-1 reply so the UI updates immediately
     * without waiting for the relay echo.
     */
    fun injectLocalReply(rootId: String, reply: Note) {
        val current = _replies.value[rootId] ?: emptyList()
        if (current.any { it.id == reply.id }) return
        val updated = (current + reply).sortedBy { it.timestamp }
        _replies.value = _replies.value + (rootId to updated)
        // Also update internal cache for tree building
        val cacheMap = threadReplyCache.getOrPut(rootId) { mutableMapOf() }
        cacheMap[reply.id] = reply
        Log.d(TAG, "Injected local reply ${reply.id.take(8)} into thread ${rootId.take(8)} (now ${updated.size} replies)")
    }

    /**
     * Fetch Kind 1 replies for a specific note
     *
     * @param noteId The ID of the note to fetch replies for
     * @param relayUrls Optional list of relays to query (uses connected relays if not provided)
     * @param limit Maximum number of replies to fetch
     */
    suspend fun fetchRepliesForNote(
        noteId: String,
        relayUrls: List<String>? = null,
        limit: Int = 100,
        authorPubkey: String? = null
    ) {
        val targetRelays = relayUrls ?: connectedRelays
        if (targetRelays.isEmpty()) {
            Log.w(TAG, "No relays available to fetch replies")
            return
        }

        // Cancel previous subscription FIRST to stop stale events from arriving
        flushJob?.cancel()
        pendingEvents.clear()
        batchStartMs = 0L
        replySubscriptionHandle?.cancel()
        replySubscriptionHandle = null

        _isLoading.value = true
        _error.value = null

        // Clear all other notes from the replies map so only the current thread is tracked.
        // This prevents stale replies from lingering when navigating between threads.
        val staleKeys = _replies.value.keys.filter { it != noteId }
        if (staleKeys.isNotEmpty()) {
            _replies.value = _replies.value - staleKeys.toSet()
            staleKeys.forEach { threadReplyCache.remove(it); pendingParentFetches.remove(it) }
        }

        // Seed from ThreadReplyCache (populated by the feed) for instant display
        val cached = ThreadReplyCache.getReplies(noteId)
        if (cached.isNotEmpty()) {
            val sorted = cached.distinctBy { it.id }.sortedBy { it.timestamp }
            _replies.value = mapOf(noteId to sorted)
            updateThreadReplyCache(noteId, sorted)
            _isLoading.value = false
            Log.d(TAG, "Emitted ${sorted.size} cached replies for note ${noteId.take(8)}... (instant)")
            scheduleFetchMissingParents(noteId)
        } else {
            // Emit an empty entry so the ViewModel collector fires and clears stale UI
            _replies.value = mapOf(noteId to emptyList())
        }

        try {
            // Enrich with author's outbox relays if available (NIP-65 kind-10002)
            val authorOutbox = authorPubkey?.let {
                Nip65RelayListRepository.getCachedOutboxRelays(it)
            }?.takeIf { it.isNotEmpty() } ?: emptyList()
            val allRelays = social.mycelium.android.relay.RelayHealthTracker.filterBlocked(
                (targetRelays + authorOutbox).distinct()
            )

            Log.d(TAG, "Fetching Kind 1 replies for note ${noteId.take(8)}... from ${allRelays.size} relays (CybinRelayPool)")

            // Subscribe for kind-1 replies + kind-7 reactions + kind-9735 zap receipts
            val replyFilter = Filter(kinds = listOf(1), tags = mapOf("e" to listOf(noteId)), limit = limit)
            val countsFilter = Filter(kinds = listOf(7, 9735), tags = mapOf("e" to listOf(noteId)), limit = 500)

            val rsm = RelayConnectionStateMachine.getInstance()
            replySubscriptionHandle = rsm.requestTemporarySubscriptionWithRelay(
                relayUrls = allRelays,
                filter = replyFilter,
                onEvent = { event, relayUrl ->
                    NoteCountsRepository.onLiveEvent(event)
                    if (event.kind == 1) handleReplyEvent(noteId, event, relayUrl)
                },
                priority = SubscriptionPriority.CRITICAL,
            )
            // Separate subscription for counts (kind-7 + kind-9735)
            rsm.requestTemporarySubscription(
                relayUrls = allRelays,
                filters = listOf(countsFilter),
                onEvent = { event -> NoteCountsRepository.onLiveEvent(event) },
                priority = SubscriptionPriority.HIGH,
            )

            // Clear loading after short window so UI shows live
            delay(INITIAL_LOAD_WINDOW_MS)
            _isLoading.value = false
            Log.d(TAG, "Replies live for note ${noteId.take(8)}... (${getRepliesForNote(noteId).size} so far)")

            // Schedule deep-fetch rounds for replies-to-replies
            scheduleDeepReplyFetch(allRelays, noteId, mutableSetOf(noteId), 0)

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching replies: ${e.message}", e)
            _error.value = "Failed to load replies: ${e.message}"
            _isLoading.value = false
        }
    }

    /**
     * Iterative deep-fetch: after initial replies arrive, check for reply IDs we haven't
     * queried yet and open a new temporary subscription for those IDs. This catches
     * replies-to-replies where the client only tagged the direct parent, not the root.
     */
    private fun scheduleDeepReplyFetch(
        relayUrls: List<String>,
        rootNoteId: String,
        queriedIds: MutableSet<String>,
        round: Int
    ) {
        if (round >= MAX_DEEP_FETCH_ROUNDS) return

        scope.launch {
            // Wait for the flush to complete + some relay delivery time
            delay(FLUSH_DEBOUNCE_MS + 500)

            val currentReplies = _replies.value[rootNoteId] ?: return@launch
            val replyIds = currentReplies.map { it.id }.toSet()
            val newIds = replyIds - queriedIds
            if (newIds.isEmpty()) return@launch

            queriedIds.addAll(newIds)
            val newIdList = newIds.toList()
            Log.d(TAG, "Deep-fetch round $round: querying ${newIdList.size} reply IDs across ${relayUrls.size} relays")

            val deepReplyFilter = Filter(kinds = listOf(1), tags = mapOf("e" to newIdList), limit = 200)
            val deepCountsFilter = Filter(kinds = listOf(7, 9735), tags = mapOf("e" to newIdList), limit = 500)

            val rsm = RelayConnectionStateMachine.getInstance()
            rsm.requestTemporarySubscriptionWithRelay(
                relayUrls = relayUrls,
                filter = deepReplyFilter,
                onEvent = { event, relayUrl ->
                    NoteCountsRepository.onLiveEvent(event)
                    if (event.kind == 1) handleReplyEvent(rootNoteId, event, relayUrl)
                },
                priority = SubscriptionPriority.HIGH,
            )
            rsm.requestTemporarySubscription(
                relayUrls = relayUrls,
                filters = listOf(deepCountsFilter),
                onEvent = { event -> NoteCountsRepository.onLiveEvent(event) },
                priority = SubscriptionPriority.NORMAL,
            )

            // Schedule next round
            scheduleDeepReplyFetch(relayUrls, rootNoteId, queriedIds, round + 1)
        }
    }

    /**
     * Handle incoming reply event from relay.
     * Buffers the event and schedules a debounced flush so rapid-fire events from multiple
     * relays are batched into a single sort+emit cycle (instead of N sorts for N events).
     */
    private fun handleReplyEvent(noteId: String, event: Event, relayUrl: String = "") {
        try {
            if (event.kind != 1) return
            // Accept if event references the root note OR any reply already in this thread.
            // Deep-fetch replies only tag their direct parent (a reply ID), not the root.
            val referencedNoteIds = extractReferencedNoteIds(event)
            val threadCache = threadReplyCache[noteId]
            val belongsToThread = noteId in referencedNoteIds ||
                (threadCache != null && referencedNoteIds.any { it in threadCache })
            if (!belongsToThread) return

            val reply = convertEventToNote(event, relayUrl)
            // Log nested replies (replyToId != root) for threading diagnostics
            if (reply.replyToId != null && reply.replyToId != reply.rootNoteId) {
                Log.d(TAG, "Nested reply ${reply.id.take(8)} → parent=${reply.replyToId.take(8)} root=${reply.rootNoteId?.take(8)}")
            }

            // Quick duplicate check against thread cache (fast O(1) lookup)
            if (threadCache != null && reply.id in threadCache) return

            // Track which relays yielded replies (for parent note RelayOrbs enrichment)
            if (relayUrl.isNotBlank()) {
                val current = _replySourceRelays.value[noteId] ?: emptySet()
                if (relayUrl !in current) {
                    _replySourceRelays.value = _replySourceRelays.value + (noteId to (current + relayUrl))
                }
            }

            // Buffer the event for batch processing
            pendingEvents.add(noteId to reply)

            // Clear loading as soon as we have at least one reply
            _isLoading.value = false

            scheduleFlush()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling reply event: ${e.message}", e)
        }
    }

    /**
     * Schedule a debounced flush of pending events. Resets the debounce timer on each call
     * so events arriving close together are batched. Force-flushes after FLUSH_MAX_DELAY_MS
     * to prevent starvation when events trickle in continuously.
     */
    private fun scheduleFlush() {
        if (batchStartMs == 0L) batchStartMs = System.currentTimeMillis()

        flushJob?.cancel()
        flushJob = scope.launch {
            val elapsed = System.currentTimeMillis() - batchStartMs
            val waitMs = if (elapsed >= FLUSH_MAX_DELAY_MS) 0L else FLUSH_DEBOUNCE_MS
            if (waitMs > 0) delay(waitMs)
            flushPendingEvents()
        }
    }

    /**
     * Drain the pending event queue, deduplicate against current replies, sort, and emit
     * a single _replies update. Also triggers missing-parent fetches once per batch.
     */
    private fun flushPendingEvents() {
        batchStartMs = 0L
        val batch = mutableListOf<Pair<String, Note>>()
        while (true) {
            val item = pendingEvents.poll() ?: break
            batch.add(item)
        }
        if (batch.isEmpty()) return

        // Group by noteId (normally all the same thread, but defensive)
        val byNote = batch.groupBy({ it.first }, { it.second })
        var totalAdded = 0

        for ((noteId, newReplies) in byNote) {
            val currentReplies = _replies.value[noteId]?.toMutableList() ?: mutableListOf()
            val existingIds = currentReplies.map { it.id }.toHashSet()
            var added = 0
            var relayMerged = false
            for (reply in newReplies) {
                if (reply.id !in existingIds) {
                    currentReplies.add(reply)
                    existingIds.add(reply.id)
                    added++
                } else if (reply.relayUrls.isNotEmpty()) {
                    // Same reply from a different relay — merge relay URLs
                    val idx = currentReplies.indexOfFirst { it.id == reply.id }
                    if (idx >= 0) {
                        val existing = currentReplies[idx]
                        val merged = (existing.relayUrls + reply.relayUrls).distinct()
                        if (merged.size > existing.relayUrls.size) {
                            currentReplies[idx] = existing.copy(relayUrls = merged)
                            relayMerged = true
                        }
                    }
                }
            }
            if (added > 0 || relayMerged) {
                val sorted = currentReplies.sortedBy { it.timestamp }
                _replies.value = _replies.value + (noteId to sorted)
                updateThreadReplyCache(noteId, sorted)
                totalAdded += added
                if (added > 0) {
                    // Fetch missing parents once per batch (not per event)
                    scheduleFetchMissingParents(noteId)
                }
            }
        }
        if (totalAdded > 0) {
            Log.d(TAG, "Flushed batch: +$totalAdded replies (${batch.size} events processed)")
        }
    }

    /**
     * Keep thread cache in sync with reply list for fast id->Note lookup per thread.
     */
    private fun updateThreadReplyCache(rootNoteId: String, replies: List<Note>) {
        threadReplyCache.getOrPut(rootNoteId) { mutableMapOf() }.clear()
        replies.forEach { threadReplyCache[rootNoteId]!![it.id] = it }
    }

    /**
     * Find replyToIds that are not in the current list (and not the thread root) and batch-fetch those events
     * so the thread tree can attach children to parents (Amethyst-style: resolve missing parents).
     */
    private fun scheduleFetchMissingParents(rootNoteId: String) {
        val currentReplies = _replies.value[rootNoteId] ?: return
        val existingIds = currentReplies.map { it.id }.toSet() + rootNoteId
        val missingParentIds = currentReplies.mapNotNull { it.replyToId }.filter { it !in existingIds }.toSet()
        val pending = pendingParentFetches.getOrPut(rootNoteId) { mutableSetOf() }
        val batch = pendingParentBatch.getOrPut(rootNoteId) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        var added = false
        missingParentIds.forEach { parentId ->
            if (parentId in pending) return@forEach
            pending.add(parentId)
            batch.add(parentId)
            added = true
        }
        if (added) scheduleParentBatchFlush(rootNoteId)
    }

    /** Schedule a debounced flush of the batched parent fetch buffer. */
    private fun scheduleParentBatchFlush(rootNoteId: String) {
        parentBatchJob?.cancel()
        parentBatchJob = scope.launch {
            delay(PARENT_BATCH_DELAY_MS)
            flushParentBatch(rootNoteId)
        }
    }

    /**
     * Flush all pending parent IDs as ONE batched subscription instead of one-per-parent.
     * Adds fetched events to this thread's replies if they belong (same root).
     */
    private suspend fun flushParentBatch(rootNoteId: String) {
        val batch = pendingParentBatch.remove(rootNoteId) ?: return
        val parentIds = batch.toList()
        if (parentIds.isEmpty()) return

        val targetRelays = connectedRelays
        if (targetRelays.isEmpty()) {
            parentIds.forEach { pendingParentFetches[rootNoteId]?.remove(it) }
            return
        }

        Log.d(TAG, "Flushing parent batch: ${parentIds.size} parents for thread ${rootNoteId.take(8)} (was ${parentIds.size} individual subs)")

        val parentFilter = Filter(kinds = listOf(1), ids = parentIds, limit = parentIds.size)
        val rsm = RelayConnectionStateMachine.getInstance()
        val handle = rsm.requestTemporarySubscriptionWithRelay(
            relayUrls = targetRelays.distinct(),
            filter = parentFilter,
            onEvent = { event, relayUrl ->
                if (event.kind == 1 && event.id in parentIds) {
                    val note = convertEventToNote(event, relayUrl)
                    val referencesRoot = note.rootNoteId == rootNoteId ||
                        note.replyToId == rootNoteId ||
                        extractReferencedNoteIds(event).contains(rootNoteId)
                    if (!referencesRoot) {
                        pendingParentFetches[rootNoteId]?.remove(event.id)
                        return@requestTemporarySubscriptionWithRelay
                    }
                    val currentReplies = _replies.value[rootNoteId]?.toMutableList() ?: mutableListOf()
                    if (!currentReplies.any { it.id == note.id }) {
                        currentReplies.add(note)
                        val sorted = currentReplies.sortedBy { it.timestamp }
                        _replies.value = _replies.value + (rootNoteId to sorted)
                        updateThreadReplyCache(rootNoteId, sorted)
                        Log.d(TAG, "Fetched missing parent ${event.id.take(8)}... for thread ${rootNoteId.take(8)}...")
                    }
                    pendingParentFetches[rootNoteId]?.remove(event.id)
                }
            }
        )

        delay(PARENT_FETCH_TIMEOUT_MS)
        handle.cancel()
        // Clean up unfetched parents
        val stillPending = parentIds.filter { pendingParentFetches[rootNoteId]?.contains(it) == true }
        if (stillPending.isNotEmpty()) {
            Log.d(TAG, "Timeout: ${stillPending.size}/${parentIds.size} parents not found for thread ${rootNoteId.take(8)}")
            stillPending.forEach { pendingParentFetches[rootNoteId]?.remove(it) }
        }
        // Recursively resolve any newly-discovered missing parents
        scheduleFetchMissingParents(rootNoteId)
    }

    /**
     * Extract all note IDs referenced in "e" tags
     */
    private fun extractReferencedNoteIds(event: Event): List<String> {
        val referencedIds = mutableListOf<String>()
        event.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] == "e") {
                referencedIds.add(tag[1])
            }
        }
        return referencedIds
    }

    /**
     * Parse root and reply IDs for NIP-10 threading.
     * Uses e-tag parsing with marker support (root/reply/mention).
     */
    private fun parseRootAndReplyFromEvent(event: Event): Pair<String?, String?> {
        val (rootId, replyToId, _) = parseThreadRelationship(event)
        return rootId to replyToId
    }

    /**
     * Parse thread relationship from "e" tags (NIP-10).
     * Returns Triple of (rootId, replyToId, isDirectReply)
     */
    private fun parseThreadRelationship(event: Event): Triple<String?, String?, Boolean> {
        var rootId: String? = null
        var replyToId: String? = null

        // Exclude "mention" e-tags from threading — they reference notes in content, not parents
        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" && !isMentionTag(it) }

        if (eTags.isEmpty()) {
            return Triple(null, null, false)
        }

        // Look for marked tags first (NIP-10 preferred format).
        // Marker can be at index 3 (["e", id, relay, "root"/"reply"]) or index 4 when
        // pubkey comes before marker (["e", id, relay, pubkey, "reply"]) — match Amethyst's pickMarker.
        eTags.forEach { tag ->
            val eventId = tag[1]
            val marker = pickETagMarker(tag)

            when (marker) {
                "root" -> rootId = eventId
                "reply" -> replyToId = eventId
            }
        }

        // Fallback to positional format if no markers found
        if (rootId == null && replyToId == null) {
            when (eTags.size) {
                1 -> {
                    // Single "e" tag is a direct reply to that note
                    rootId = eTags[0][1]
                    replyToId = eTags[0][1]
                }
                else -> {
                    // First "e" is root, last "e" is reply-to (NIP-10 positional)
                    rootId = eTags.first()[1]
                    replyToId = eTags.last()[1]
                }
            }
        }

        // Nested reply with marked "root" but no marked "reply": many clients send
        // ["e", rootId, relay, "root"], ["e", parentId, relay] (reply-to only positional).
        // Without this, replyToId stays null and the reply is shown at root — fragmenting the tree.
        // Prefer the tag whose id != rootId (the actual parent); if all same, direct reply to root.
        if (rootId != null && replyToId == null && eTags.size >= 2) {
            val ids = eTags.map { it[1] }
            replyToId = ids.lastOrNull { it != rootId } ?: ids.last()
        }

        // Determine if this is a direct reply to root
        val isDirectReply = rootId == replyToId || replyToId == null

        return Triple(rootId, replyToId, isDirectReply)
    }

    /**
     * Pick "root" or "reply" marker from an "e" tag, checking index 3, then 4, then 2
     * (same order as Amethyst MarkedETag.pickMarker) so we handle both NIP-10 orderings:
     * ["e", id, relay, marker] and ["e", id, relay, pubkey, marker].
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

    /** True if this e-tag has a "mention" marker at any of the standard positions. */
    private fun isMentionTag(tag: Array<out String>): Boolean {
        return tag.getOrNull(3) == "mention" || tag.getOrNull(4) == "mention" || tag.getOrNull(2) == "mention"
    }

    /**
     * Convert Nostr Event to Note data model
     */
    /**
     * Schedule a debounced batch kind-0 profile fetch. Accumulates pubkeys and fires one request
     * after PROFILE_BATCH_DELAY_MS, instead of one coroutine per reply event.
     */
    private fun scheduleBatchProfileRequest() {
        profileBatchJob?.cancel()
        profileBatchJob = scope.launch {
            delay(PROFILE_BATCH_DELAY_MS)
            while (pendingProfilePubkeys.isNotEmpty()) {
                val batch = synchronized(pendingProfilePubkeys) {
                    pendingProfilePubkeys.take(PROFILE_BATCH_SIZE).also { pendingProfilePubkeys.removeAll(it.toSet()) }
                }
                if (batch.isEmpty()) break
                val urls = cacheRelayUrls.ifEmpty { return@launch }
                try {
                    profileCache.requestProfiles(batch, urls)
                } catch (e: Throwable) {
                    Log.e(TAG, "Batch profile request failed: ${e.message}", e)
                }
                if (pendingProfilePubkeys.isNotEmpty()) delay(200)
            }
        }
    }

    private fun convertEventToNote(event: Event, relayUrl: String = ""): Note {
        val pubkeyHex = event.pubKey
        val author = profileCache.resolveAuthor(pubkeyHex)
        if (profileCache.getAuthor(pubkeyHex) == null) {
            pendingProfilePubkeys.add(pubkeyHex.lowercase())
        }
        // Request kind-0 for pubkeys mentioned in content so @mentions resolve to display names
        val contentPubkeys = extractPubkeysFromContent(event.content).filter { profileCache.getAuthor(it) == null }
        contentPubkeys.forEach { pendingProfilePubkeys.add(it.lowercase()) }
        if (pendingProfilePubkeys.isNotEmpty() && cacheRelayUrls.isNotEmpty()) {
            scheduleBatchProfileRequest()
        }

        // Extract hashtags from tags
        val hashtags = event.tags.toList()
            .filter { tag -> tag.size >= 2 && tag[0] == "t" }
            .mapNotNull { tag -> tag.getOrNull(1) }

        // Extract image and video URLs from content (embedded in card media area)
        val mediaUrls = UrlDetector.findUrls(event.content)
            .filter { UrlDetector.isImageUrl(it) || UrlDetector.isVideoUrl(it) }
            .distinct()

        // NIP-10 thread relationship
        val (rootId, replyToId) = parseRootAndReplyFromEvent(event)

        return Note(
            id = event.id,
            author = author,
            content = event.content,
            timestamp = event.createdAt * 1000L, // Convert to milliseconds
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = mediaUrls,
            isReply = rootId != null || replyToId != null,
            rootNoteId = rootId,
            replyToId = replyToId,
            relayUrl = relayUrl.ifEmpty { null },
            relayUrls = if (relayUrl.isNotBlank()) listOf(relayUrl) else emptyList()
        )
    }

    /**
     * Get replies for a specific note ID
     */
    fun getRepliesForNote(noteId: String): List<Note> {
        return _replies.value[noteId] ?: emptyList()
    }

    /**
     * Get a reply by id from the thread cache (fast lookup for a thread root).
     * Returns null if not in cache or not part of that thread.
     */
    fun getNoteInThread(rootNoteId: String, replyId: String): Note? =
        threadReplyCache[rootNoteId]?.get(replyId)

    /**
     * Get reply count for a specific note
     */
    fun getReplyCount(noteId: String): Int {
        return _replies.value[noteId]?.size ?: 0
    }

    /**
     * Build threaded structure from flat list of replies using NIP-10 replyToId.
     * Returns a map of parentId to list of direct child notes.
     */
    fun buildThreadStructure(noteId: String): Map<String, List<Note>> {
        val replies = getRepliesForNote(noteId)
        val threadMap = mutableMapOf<String, MutableList<Note>>()

        replies.forEach { reply ->
            val parentId = reply.replyToId ?: noteId
            threadMap.getOrPut(parentId) { mutableListOf() }.add(reply)
        }

        return threadMap
    }

    /**
     * Update author in all reply lists when profile cache is updated.
     */
    fun updateAuthorInReplies(pubkey: String) {
        updateAuthorsInRepliesBatch(setOf(pubkey))
    }

    /**
     * Batch update authors in all reply lists. Resolves all pubkeys from cache, applies in one
     * pass, and emits _replies only once. Much faster than N individual updateAuthorInReplies calls.
     */
    fun updateAuthorsInRepliesBatch(pubkeys: Set<String>) {
        val authorMap = pubkeys.mapNotNull { key ->
            val lower = key.lowercase()
            profileCache.getAuthor(lower)?.let { lower to it }
        }.toMap()
        if (authorMap.isEmpty()) return

        val current = _replies.value
        var updated = false
        val newMap = current.mapValues { (_, list) ->
            list.map { note ->
                val newAuthor = authorMap[note.author.id.lowercase()]
                if (newAuthor != null) {
                    updated = true
                    note.copy(author = newAuthor)
                } else note
            }
        }
        if (updated) _replies.value = newMap
    }

    /**
     * Clear replies for a specific note (and pending parent fetches / thread cache for that thread).
     */
    fun clearRepliesForNote(noteId: String) {
        flushJob?.cancel()
        pendingEvents.clear()
        batchStartMs = 0L
        activeSubscriptions[noteId]?.cancel()
        activeSubscriptions.remove(noteId)
        pendingParentFetches.remove(noteId)
        threadReplyCache.remove(noteId)
        deepFetchedIds.remove(noteId)
        _replySourceRelays.value = _replySourceRelays.value - noteId
        _replies.value = _replies.value - noteId
    }

    /**
     * Clear all replies, pending parent fetches, and thread cache.
     */
    fun clearAllReplies() {
        flushJob?.cancel()
        pendingEvents.clear()
        batchStartMs = 0L
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
        pendingParentFetches.clear()
        threadReplyCache.clear()
        deepFetchedIds.clear()
        _replySourceRelays.value = emptyMap()
        _replies.value = emptyMap()
    }

    /**
     * Check if currently loading replies
     */
    fun isLoadingReplies(): Boolean = _isLoading.value
}
