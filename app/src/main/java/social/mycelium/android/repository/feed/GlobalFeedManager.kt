package social.mycelium.android.repository.feed

import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
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
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.relay.Nip65RelayListRepository
import social.mycelium.android.repository.social.PeopleListRepository

/**
 * Global feed enrichment manager: subscribes to indexer relays with advanced
 * filters (hashtags, list members) to surface targeted content in Global mode.
 *
 * Unlike the main feed subscription (which sends an unfiltered kind-1 REQ to
 * the user's own relays), this manager targets NIP-66–ranked indexer/search
 * relays with specific filter criteria:
 *
 * 1. **Hashtag filters**: When the user has subscribed hashtags (kind-10015)
 *    or selects hashtags from the dropdown, we subscribe to indexers with
 *    `#t` tag filters to find matching notes globally.
 *
 * 2. **List member filters**: When the user has active people lists selected,
 *    we extract their pubkeys and subscribe to indexers with `authors` filters
 *    to find notes from those users that may not appear on the user's relays.
 *
 * Events are injected into [NotesRepository]'s existing pipeline via
 * [onNoteReceived], just like [OutboxFeedManager]. Deduplication happens
 * in the standard flush pipeline.
 *
 * ## Lifecycle
 * - [start] when entering global mode (or when hashtag/list selections change)
 * - [stop] when leaving global mode
 * - Idempotent: calling [start] with the same params is a no-op
 */
class GlobalFeedManager private constructor() {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> MLog.e(TAG, "Coroutine failed: ${t.message}", t) }
    )
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    /** Active indexer subscription handles — cancel on stop. */
    private var hashtagHandle: TemporarySubscriptionHandle? = null
    private var listMembersHandle: TemporarySubscriptionHandle? = null

    /** Discovery + subscription job. */
    private var subscriptionJob: Job? = null

    /** Callback to inject events into NotesRepository's ingestion pipeline. */
    @Volatile
    var onNoteReceived: ((Event, String) -> Unit)? = null

    // ── Observable state for UI / diagnostics ──

    enum class Phase { IDLE, SUBSCRIBING, ACTIVE, STOPPED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Number of notes received from global indexer subscriptions (session counter). */
    private val _globalNotesReceived = MutableStateFlow(0)
    val globalNotesReceived: StateFlow<Int> = _globalNotesReceived.asStateFlow()

    /** Number of indexer relays we're subscribed to. */
    private val _indexerRelayCount = MutableStateFlow(0)
    val indexerRelayCount: StateFlow<Int> = _indexerRelayCount.asStateFlow()

    /** Last params we started with — skip if unchanged. */
    @Volatile private var lastHashtags: Set<String>? = null
    @Volatile private var lastListDTags: Set<String>? = null

    /**
     * Start global indexer subscriptions for the given hashtags and/or list members.
     *
     * @param hashtags  Subscribed hashtags (from kind-10015 or dropdown selection)
     * @param listDTags Active people list d-tags (from dropdown multi-select)
     */
    fun start(
        hashtags: Set<String> = emptySet(),
        listDTags: Set<String> = emptySet()
    ) {
        if (hashtags.isEmpty() && listDTags.isEmpty()) {
            MLog.d(TAG, "No hashtags or lists for global enrichment — skipping")
            return
        }

        // Idempotency: skip if already running with same params
        if (hashtags == lastHashtags && listDTags == lastListDTags &&
            _phase.value != Phase.IDLE && _phase.value != Phase.STOPPED) {
            MLog.d(TAG, "Global enrichment already active with same params, skipping")
            return
        }

        // Stop prior session
        stop()
        lastHashtags = hashtags
        lastListDTags = listDTags

        MLog.d(TAG, "Starting global enrichment: ${hashtags.size} hashtags, ${listDTags.size} lists")
        _phase.value = Phase.SUBSCRIBING
        _globalNotesReceived.value = 0

        subscriptionJob = scope.launch {
            try {
                // Get indexer relays from NIP-66 discovery
                val indexerRelays = Nip65RelayListRepository.getIndexerRelayUrls(limit = 8)
                if (indexerRelays.isEmpty()) {
                    // Fallback to hardcoded indexer relays from NotesRepository
                    val fallback = NotesRepository.getInstance().INDEXER_RELAYS
                    if (fallback.isEmpty()) {
                        MLog.w(TAG, "No indexer relays available — cannot start global enrichment")
                        _phase.value = Phase.STOPPED
                        return@launch
                    }
                    subscribeToIndexers(fallback, hashtags, listDTags)
                } else {
                    subscribeToIndexers(indexerRelays, hashtags, listDTags)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                MLog.e(TAG, "Global enrichment failed: ${e.message}", e)
                _phase.value = Phase.STOPPED
            }
        }
    }

    /**
     * Stop all global indexer subscriptions. Call when leaving global mode.
     */
    fun stop() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        hashtagHandle?.cancel()
        hashtagHandle = null
        listMembersHandle?.cancel()
        listMembersHandle = null
        lastHashtags = null
        lastListDTags = null
        _phase.value = Phase.STOPPED
        _indexerRelayCount.value = 0
        MLog.d(TAG, "Global enrichment stopped")
    }

    // ── Internal ──

    private fun subscribeToIndexers(
        indexerRelays: List<String>,
        hashtags: Set<String>,
        listDTags: Set<String>
    ) {
        val callback = onNoteReceived
        if (callback == null) {
            MLog.w(TAG, "No onNoteReceived callback — global notes will be dropped")
            _phase.value = Phase.ACTIVE
            return
        }

        val sevenDaysAgo = System.currentTimeMillis() / 1000 - SINCE_WINDOW_SECS
        var totalRelays = 0

        // Subscribe for hashtag-filtered notes
        if (hashtags.isNotEmpty()) {
            val hashtagFilter = Filter(
                kinds = listOf(1),
                tags = mapOf("t" to hashtags.toList()),
                since = sevenDaysAgo,
                limit = HASHTAG_LIMIT
            )

            hashtagHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
                indexerRelays, hashtagFilter, priority = SubscriptionPriority.NORMAL
            ) { event, relayUrl ->
                if (event.kind == 1) {
                    _globalNotesReceived.value = _globalNotesReceived.value + 1
                    callback(event, relayUrl)
                }
            }
            totalRelays += indexerRelays.size
            MLog.d(TAG, "Hashtag subscription: #${hashtags.joinToString(", #")} on ${indexerRelays.size} indexers")
        }

        // Subscribe for list member notes
        if (listDTags.isNotEmpty()) {
            val listPubkeys = PeopleListRepository.getPubkeysForLists(listDTags)
            if (listPubkeys.isNotEmpty()) {
                // Cap authors to avoid exceeding relay filter limits
                val cappedAuthors = listPubkeys.take(MAX_LIST_AUTHORS).toList()
                val listFilter = Filter(
                    kinds = listOf(1),
                    authors = cappedAuthors,
                    since = sevenDaysAgo,
                    limit = LIST_MEMBERS_LIMIT
                )

                listMembersHandle = relayStateMachine.requestTemporarySubscriptionWithRelay(
                    indexerRelays, listFilter, priority = SubscriptionPriority.NORMAL
                ) { event, relayUrl ->
                    if (event.kind == 1) {
                        _globalNotesReceived.value = _globalNotesReceived.value + 1
                        callback(event, relayUrl)
                    }
                }
                totalRelays += indexerRelays.size
                MLog.d(TAG, "List members subscription: ${cappedAuthors.size} authors from ${listDTags.size} lists on ${indexerRelays.size} indexers")
            } else {
                MLog.d(TAG, "Selected lists have no pubkeys — skipping list member subscription")
            }
        }

        _indexerRelayCount.value = totalRelays
        _phase.value = Phase.ACTIVE
        MLog.d(TAG, "Global enrichment active: $totalRelays relay subscriptions")
    }

    companion object {
        private const val TAG = "GlobalFeedManager"

        /** Only fetch notes from the last 7 days. */
        private const val SINCE_WINDOW_SECS = 7 * 24 * 3600L

        /** Max notes per hashtag subscription. */
        private const val HASHTAG_LIMIT = 200

        /** Max notes per list member subscription. */
        private const val LIST_MEMBERS_LIMIT = 200

        /** Max authors in a single list-member filter (relay limit safety). */
        private const val MAX_LIST_AUTHORS = 500

        @Volatile
        private var instance: GlobalFeedManager? = null
        fun getInstance(): GlobalFeedManager =
            instance ?: synchronized(this) { instance ?: GlobalFeedManager().also { instance = it } }
    }
}
