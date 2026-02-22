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
import social.mycelium.android.utils.UrlDetector

/**
 * Independent feed repository for a single author's profile page.
 *
 * Opens a dedicated relay subscription (separate from the main home feed)
 * for the author's kind-1 notes. Supports relay-side cursor pagination
 * via the `until` filter parameter to load older history.
 *
 * Lifecycle: create when entering a profile screen, call [dispose] when leaving.
 * Each instance manages its own subscription and note list.
 */
class ProfileFeedRepository(
    private val authorPubkey: String,
    private val relayUrls: List<String>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        private const val TAG = "ProfileFeedRepo"
        private const val PAGE_SIZE = 50
        private const val INITIAL_LOAD_SIZE = 50
        private const val FLUSH_DEBOUNCE_MS = 120L
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

    // ── Internal state ──────────────────────────────────────────────────
    private var subscription: TemporarySubscriptionHandle? = null
    private var loadMoreSubscription: TemporarySubscriptionHandle? = null
    private val pendingEvents = java.util.concurrent.ConcurrentLinkedQueue<Event>()
    private var flushJob: Job? = null
    private val seenIds = HashSet<String>()
    private val profileCache = ProfileMetadataCache.getInstance()

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

        val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400 * 7
        val filter = Filter(
            kinds = listOf(1),
            authors = listOf(authorPubkey),
            limit = INITIAL_LOAD_SIZE,
            since = sevenDaysAgo
        )

        Log.d(TAG, "Starting profile feed for ${authorPubkey.take(8)}… on ${relayUrls.size} relays")
        subscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscription(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.NORMAL,
        ) { event ->
            if (event.kind == 1 && event.pubKey == authorPubkey) {
                pendingEvents.add(event)
                scheduleFlush()
            }
        }

        // Wait for initial batch then mark loading complete
        scope.launch {
            val maxWaitMs = 3000L
            val pollMs = 100L
            var waited = 0L
            while (waited < maxWaitMs) {
                delay(pollMs)
                waited += pollMs
                flushEvents()
                if (_notes.value.isNotEmpty()) break
            }
            flushEvents()
            _isLoading.value = false
            Log.d(TAG, "Initial load: ${_notes.value.size} notes in ${waited}ms")
        }
    }

    /**
     * Load older notes by opening a new subscription with `until` set to the
     * oldest note's timestamp. This is relay-side cursor pagination.
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        val currentNotes = _notes.value
        if (currentNotes.isEmpty()) return

        _isLoadingMore.value = true
        val oldestTimestamp = currentNotes.minOf { it.timestamp }
        // `until` is exclusive in most relay implementations
        val untilSeconds = oldestTimestamp / 1000

        val filter = Filter(
            kinds = listOf(1),
            authors = listOf(authorPubkey),
            limit = PAGE_SIZE,
            until = untilSeconds
        )

        Log.d(TAG, "Loading more: until=${untilSeconds} (oldest=${oldestTimestamp})")
        loadMoreSubscription?.cancel()
        var receivedCount = 0
        loadMoreSubscription = RelayConnectionStateMachine.getInstance().requestTemporarySubscription(
            relayUrls = relayUrls,
            filter = filter,
            priority = SubscriptionPriority.LOW,
        ) { event ->
            if (event.kind == 1 && event.pubKey == authorPubkey) {
                receivedCount++
                pendingEvents.add(event)
                scheduleFlush()
            }
        }

        // Wait for batch, then close the load-more subscription
        scope.launch {
            val maxWaitMs = 3000L
            val pollMs = 100L
            var waited = 0L
            while (waited < maxWaitMs) {
                delay(pollMs)
                waited += pollMs
                flushEvents()
                if (receivedCount >= PAGE_SIZE) break
            }
            flushEvents()
            loadMoreSubscription?.cancel()
            loadMoreSubscription = null
            _isLoadingMore.value = false
            if (receivedCount < PAGE_SIZE / 2) {
                _hasMore.value = false
                Log.d(TAG, "No more history (got $receivedCount < ${PAGE_SIZE / 2})")
            }
            Log.d(TAG, "Load more: +$receivedCount notes, total=${_notes.value.size}")
        }
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
        val isReply = event.tags.any { it.size >= 2 && it[0] == "e" }
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
            relayUrl = null,
            relayUrls = emptyList(),
            quotedEventIds = quotedEventIds,
        )
    }
}
