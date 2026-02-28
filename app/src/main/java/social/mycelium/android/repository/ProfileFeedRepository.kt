package social.mycelium.android.repository

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
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.utils.Nip10ReplyDetector
import social.mycelium.android.utils.UrlDetector
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent feed repository for a single author's profile page.
 *
 * Opens a dedicated relay subscription (separate from the main home feed)
 * for the author's kind-1 notes. Supports relay-side cursor pagination
 * via the `until` filter parameter to load older history.
 *
 * All tabs (Notes, Replies, Media) share the same underlying kind-1 stream
 * because relays can't distinguish reply vs root notes. [loadMore] accepts
 * an `activeTab` index so pagination continues until the *active* tab gets
 * enough new items — e.g. scrolling on Replies keeps fetching even if most
 * new events are root notes. Per-tab exhaustion prevents infinite load loops.
 *
 * Lifecycle: create when entering a profile screen, call [dispose] when leaving.
 */
class ProfileFeedRepository(
    private val authorPubkey: String,
    private val relayUrls: List<String>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        private const val TAG = "ProfileFeedRepo"
        private const val PAGE_SIZE = 200
        private const val INITIAL_LOAD_SIZE = 200
        private const val FLUSH_DEBOUNCE_MS = 120L
        private const val SETTLE_QUIET_MS = 1200L
        private const val MAX_WAIT_MS = 8000L
        /** Minimum new items the *active tab* must gain before we stop a load-more page. */
        private const val TAB_MIN_NEW = 15
        /** Maximum consecutive pages that produced zero items for a tab before declaring exhaustion. */
        private const val TAB_EMPTY_PAGE_LIMIT = 3

        // Tab indices — shared with ProfileScreen
        const val TAB_NOTES = 0
        const val TAB_REPLIES = 1
        const val TAB_MEDIA = 2

        // ── Static instance cache ──────────────────────────────────────
        // Survives composable lifecycle stops during Navigation Compose transitions
        // (e.g. profile → image_viewer → back). Without this, remember() creates a
        // new repo on every lifecycle restart, and start() wipes the notes.
        private val instanceCache = HashMap<String, ProfileFeedRepository>()

        /** Get or create a ProfileFeedRepository for [authorPubkey]. If the relay list
         *  changed, the old instance is disposed and a fresh one is created. */
        fun getOrCreate(authorPubkey: String, relayUrls: List<String>): ProfileFeedRepository {
            val existing = instanceCache[authorPubkey]
            if (existing != null && existing.relayUrls == relayUrls) return existing
            existing?.dispose()
            val repo = ProfileFeedRepository(authorPubkey, relayUrls)
            instanceCache[authorPubkey] = repo
            return repo
        }

        /** Remove a cached instance (call when the profile back-stack entry is fully destroyed). */
        fun evict(authorPubkey: String) {
            instanceCache.remove(authorPubkey)?.dispose()
        }
    }

    // ── Public state ────────────────────────────────────────────────────
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** Per-tab exhaustion: map of tab index → still has more content. */
    private val _perTabHasMore = MutableStateFlow(mapOf(TAB_NOTES to true, TAB_REPLIES to true, TAB_MEDIA to true))
    val perTabHasMore: StateFlow<Map<Int, Boolean>> = _perTabHasMore.asStateFlow()

    private val _timeGapIndex = MutableStateFlow<Int?>(null)
    val timeGapIndex: StateFlow<Int?> = _timeGapIndex.asStateFlow()

    // ── Internal state ──────────────────────────────────────────────────
    private var subscription: TemporarySubscriptionHandle? = null
    private var loadMoreSubscription: TemporarySubscriptionHandle? = null
    private val pendingEvents = java.util.concurrent.ConcurrentLinkedQueue<Event>()
    private var flushJob: Job? = null
    private val seenIds = HashSet<String>()
    private val profileCache = ProfileMetadataCache.getInstance()

    /** 5th-percentile pagination cursor — resists outliers pulling cursor back years. */
    @Volatile private var paginationCursorMs: Long = 0L
    /** Tracks consecutive pages that added zero items to a specific tab. */
    private val tabEmptyPageCount = intArrayOf(0, 0, 0)

    /**
     * Start the initial subscription for the author's recent notes.
     * Call once when the profile screen becomes visible.
     */
    fun start() {
        if (subscription != null) return
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relay URLs for profile feed ($authorPubkey)")
            return
        }

        _isLoading.value = true
        _notes.value = emptyList()
        seenIds.clear()
        paginationCursorMs = 0L
        tabEmptyPageCount.fill(0)

        val filter = Filter(
            kinds = listOf(1),
            authors = listOf(authorPubkey),
            limit = INITIAL_LOAD_SIZE
        )

        Log.d(TAG, "Starting profile feed for ${authorPubkey.take(8)}… on ${relayUrls.size} relays")
        val lastEventAt = AtomicLong(0L)
        subscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscription(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
        ) { event ->
            if (event.kind == 1 && event.pubKey == authorPubkey) {
                lastEventAt.set(System.currentTimeMillis())
                pendingEvents.add(event)
                scheduleFlush()
            }
        }

        // Settle-based wait for initial batch
        scope.launch {
            var waited = 0L
            while (waited < MAX_WAIT_MS) {
                delay(200)
                waited += 200
                flushEvents()
                val lastAt = lastEventAt.get()
                if (lastAt > 0 && System.currentTimeMillis() - lastAt >= SETTLE_QUIET_MS) break
                if (_notes.value.size >= INITIAL_LOAD_SIZE) break
            }
            flushEvents()
            _isLoading.value = false
            Log.d(TAG, "Initial load: ${_notes.value.size} notes in ${waited}ms")
        }
    }

    /**
     * Load older notes, continuing until the [activeTab] gains at least [TAB_MIN_NEW]
     * new items or the global stream is exhausted.
     *
     * Since relays can't filter notes vs replies at the protocol level, a single kind-1
     * page may produce mostly root notes when the user is on the Replies tab. This method
     * will fetch up to 3 consecutive pages to satisfy the active tab's needs.
     */
    fun loadMore(activeTab: Int = TAB_NOTES) {
        if (_isLoadingMore.value) return
        val tabExhausted = _perTabHasMore.value[activeTab] == false
        if (tabExhausted || !_hasMore.value) return
        val currentNotes = _notes.value
        if (currentNotes.isEmpty()) return

        _isLoadingMore.value = true
        scope.launch {
            val tabCountBefore = countForTab(activeTab, currentNotes)
            var pagesLoaded = 0
            var globalExhausted = false

            while (pagesLoaded < 3) {
                val beforeCount = _notes.value.size
                val pageAdded = loadOnePage()
                pagesLoaded++

                if (pageAdded < 10) {
                    globalExhausted = true
                    break
                }

                // Check if the active tab got enough new items
                val tabCountNow = countForTab(activeTab, _notes.value)
                val tabGained = tabCountNow - tabCountBefore
                if (tabGained >= TAB_MIN_NEW) break
            }

            // Update per-tab exhaustion
            if (globalExhausted) {
                _hasMore.value = false
                _perTabHasMore.value = mapOf(TAB_NOTES to false, TAB_REPLIES to false, TAB_MEDIA to false)
                Log.d(TAG, "Global exhaustion after $pagesLoaded pages")
            } else {
                // Check if this specific tab gained anything across all pages
                val tabCountNow = countForTab(activeTab, _notes.value)
                val tabGained = tabCountNow - tabCountBefore
                if (tabGained == 0) {
                    tabEmptyPageCount[activeTab]++
                    if (tabEmptyPageCount[activeTab] >= TAB_EMPTY_PAGE_LIMIT) {
                        val updated = _perTabHasMore.value.toMutableMap()
                        updated[activeTab] = false
                        _perTabHasMore.value = updated
                        Log.d(TAG, "Tab $activeTab exhausted after ${TAB_EMPTY_PAGE_LIMIT} empty pages")
                    }
                } else {
                    tabEmptyPageCount[activeTab] = 0
                }
            }

            _isLoadingMore.value = false
            Log.d(TAG, "Load more (tab=$activeTab): total=${_notes.value.size}, pages=$pagesLoaded")
        }
    }

    /** Fetch a single page of older notes. Returns the number of new notes added to the global list. */
    private suspend fun loadOnePage(): Int {
        val beforeCount = _notes.value.size
        val cursorMs = if (paginationCursorMs > 0) paginationCursorMs
            else _notes.value.minOfOrNull { it.timestamp } ?: return 0
        val untilSeconds = cursorMs / 1000

        val filter = Filter(
            kinds = listOf(1),
            authors = listOf(authorPubkey),
            limit = PAGE_SIZE,
            until = untilSeconds
        )

        Log.d(TAG, "Loading page: until=$untilSeconds (cursor=$cursorMs)")
        loadMoreSubscription?.cancel()
        val lastEventAt = AtomicLong(0L)
        loadMoreSubscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscription(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.HIGH,
        ) { event ->
            if (event.kind == 1 && event.pubKey == authorPubkey) {
                lastEventAt.set(System.currentTimeMillis())
                pendingEvents.add(event)
                scheduleFlush()
            }
        }

        // Settle-based wait
        var waited = 0L
        while (waited < MAX_WAIT_MS) {
            delay(200)
            waited += 200
            flushEvents()
            val lastAt = lastEventAt.get()
            if (lastAt > 0 && System.currentTimeMillis() - lastAt >= SETTLE_QUIET_MS) break
        }
        flushEvents()
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null

        return _notes.value.size - beforeCount
    }

    /** Count items relevant to a specific tab. */
    private fun countForTab(tab: Int, notes: List<Note>): Int = when (tab) {
        TAB_NOTES -> notes.count { !it.isReply }
        TAB_REPLIES -> notes.count { it.isReply }
        TAB_MEDIA -> notes.count { it.mediaUrls.isNotEmpty() }
        else -> notes.size
    }

    /** Pause the live subscription (e.g. when navigating away but keeping the entry). */
    fun pause() {
        subscription?.pause()
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null
        Log.d(TAG, "Paused profile feed for ${authorPubkey.take(8)}…")
    }

    /** Resume a paused live subscription. */
    fun resume() {
        subscription?.resume()
        Log.d(TAG, "Resumed profile feed for ${authorPubkey.take(8)}…")
    }

    /** Stop all subscriptions and clean up. Call when leaving the profile screen. */
    fun dispose() {
        subscription?.cancel()
        subscription = null
        loadMoreSubscription?.cancel()
        loadMoreSubscription = null
        flushJob?.cancel()
        flushJob = null
        pendingEvents.clear()
        Log.d(TAG, "Disposed profile feed for ${authorPubkey.take(8)}…")
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DEBOUNCE_MS)
            flushEvents()
        }
    }

    private fun flushEvents() {
        val batch = mutableListOf<Event>()
        while (true) {
            val event = pendingEvents.poll() ?: break
            batch.add(event)
        }
        if (batch.isEmpty()) return

        val newNotes = mutableListOf<Note>()
        for (event in batch) {
            if (event.id in seenIds) continue
            seenIds.add(event.id)

            val note = convertEventToNote(event)
            newNotes.add(note)
        }

        if (newNotes.isEmpty()) return

        val merged = (_notes.value + newNotes)
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }

        _notes.value = merged
        advancePaginationCursor(merged)

        // Detect temporal gap
        _timeGapIndex.value = social.mycelium.android.ui.screens.detectTimeGapIndex(merged)
    }

    /** 5th-percentile cursor advancement — mirrors NotesRepository pattern. */
    private fun advancePaginationCursor(notes: List<Note>) {
        if (notes.isEmpty()) return
        val timestamps = notes.map { it.timestamp }.sorted()
        val p5Index = (timestamps.size * 0.05).toInt().coerceIn(0, timestamps.lastIndex)
        val candidate = timestamps[p5Index]
        val absMin = timestamps.first()
        val prev = paginationCursorMs
        if (prev == 0L || candidate < prev) {
            paginationCursorMs = candidate
        } else if (absMin < prev) {
            paginationCursorMs = absMin // stall-break
        }
    }

    private fun convertEventToNote(event: Event): Note {
        val author = profileCache.resolveAuthor(event.pubKey)
        val content = event.content
        val urls = UrlDetector.findUrls(content)
        val imageUrls = urls.filter { UrlDetector.isImageUrl(it) }
        val videoUrls = urls.filter { UrlDetector.isVideoUrl(it) }
        val hashtags = event.tags
            .filter { it.isNotEmpty() && it[0] == "t" }
            .mapNotNull { it.getOrNull(1) }
        val isReply = Nip10ReplyDetector.isReply(event)
        val rootNoteId = Nip10ReplyDetector.getRootId(event)
        val replyToId = Nip10ReplyDetector.getReplyToId(event)
        val quotedEventIds = social.mycelium.android.utils.Nip19QuoteParser.extractQuotedEventIds(content)

        return Note(
            id = event.id,
            author = author,
            content = content,
            timestamp = event.createdAt * 1000,
            likes = 0,
            shares = 0,
            comments = 0,
            isLiked = false,
            hashtags = hashtags,
            mediaUrls = imageUrls + videoUrls,
            isReply = isReply,
            rootNoteId = rootNoteId,
            replyToId = replyToId,
            tags = event.tags.toList().map { it.toList() },
            relayUrl = null,
            relayUrls = emptyList(),
            quotedEventIds = quotedEventIds,
        )
    }
}
