package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
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

/**
 * Outbox-aware feed manager: discovers followed users' outbox (write) relays via
 * NIP-65 and subscribes to those relays for kind-1 notes. This ensures we see
 * notes from followed users even when they publish to relays we don't subscribe to.
 *
 * ## Architecture
 * 1. **Discovery phase**: Batch-fetch kind-10002 for all followed pubkeys via indexer
 *    relays. Results populate [Nip65RelayListRepository.authorOutboxCache].
 * 2. **Grouping**: Invert the author→relays map to relay→authors. Each unique outbox
 *    relay gets a filter with only the authors who publish there.
 * 3. **Subscription**: Open temporary subscriptions per outbox relay using
 *    [RelayConnectionStateMachine.requestTemporarySubscriptionPerRelay]. Events flow
 *    into [NotesRepository]'s existing kind-1 ingestion pipeline via [onNoteReceived].
 * 4. **Lifecycle**: Start on feed load, stop on disconnect. Bounded by a 7-day `since`
 *    window. Caps concurrent outbox relay connections at [MAX_OUTBOX_RELAYS].
 *
 * ## Deduplication
 * Notes from outbox relays that also arrive from inbox relays are deduplicated by
 * [NotesRepository.flushKind1Events] (existing dedup by note ID). No extra logic needed.
 *
 * ## Connection budget
 * We cap outbox relays at [MAX_OUTBOX_RELAYS] to avoid opening hundreds of WebSockets.
 * Relays are ranked by how many followed authors publish there (most popular first).
 * The user's own inbox relays are excluded (already subscribed via main feed).
 */
class OutboxFeedManager private constructor() {

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) }
    )
    private val relayStateMachine = RelayConnectionStateMachine.getInstance()

    /** Active outbox subscription handles — cancel on stop. */
    private var outboxHandle: TemporarySubscriptionHandle? = null

    /** Discovery job — cancel if we stop before discovery finishes. */
    private var discoveryJob: Job? = null

    /** Callback to inject events into NotesRepository's ingestion pipeline. */
    @Volatile
    var onNoteReceived: ((Event, String) -> Unit)? = null

    // ── Observable state for UI / diagnostics ──

    /** Current phase of the outbox feed manager. */
    enum class Phase { IDLE, DISCOVERING, SUBSCRIBING, ACTIVE, STOPPED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Number of unique outbox relays we're subscribed to. */
    private val _outboxRelayCount = MutableStateFlow(0)
    val outboxRelayCount: StateFlow<Int> = _outboxRelayCount.asStateFlow()

    /** Number of followed authors whose outbox relays were discovered. */
    private val _discoveredAuthorCount = MutableStateFlow(0)
    val discoveredAuthorCount: StateFlow<Int> = _discoveredAuthorCount.asStateFlow()

    /** Number of kind-1 notes received from outbox relays (session counter). */
    private val _outboxNotesReceived = MutableStateFlow(0)
    val outboxNotesReceived: StateFlow<Int> = _outboxNotesReceived.asStateFlow()

    /**
     * Start outbox discovery and subscription for the given follow list.
     *
     * @param followedPubkeys  Set of hex pubkeys the user follows
     * @param indexerRelayUrls Indexer relay URLs to use for NIP-65 discovery
     * @param inboxRelayUrls   The user's own inbox/subscription relays (excluded from outbox set)
     */
    /** Last follow set we started with — skip if unchanged to prevent redundant restarts. */
    @Volatile private var lastStartedFollowSet: Set<String>? = null

    fun start(
        followedPubkeys: Set<String>,
        indexerRelayUrls: List<String>,
        inboxRelayUrls: List<String>
    ) {
        if (followedPubkeys.isEmpty()) {
            Log.d(TAG, "No followed pubkeys — skipping outbox discovery")
            return
        }
        if (indexerRelayUrls.isEmpty()) {
            Log.w(TAG, "No indexer relays — cannot discover outbox relays")
            return
        }
        // Skip if already running with the same follow set
        if (followedPubkeys == lastStartedFollowSet && _phase.value != Phase.IDLE && _phase.value != Phase.STOPPED) {
            Log.d(TAG, "Outbox feed already active for ${followedPubkeys.size} follows, skipping")
            return
        }

        // Stop any prior session
        stop()
        lastStartedFollowSet = followedPubkeys

        Log.d(TAG, "Starting outbox feed: ${followedPubkeys.size} follows, ${indexerRelayUrls.size} indexers")
        _phase.value = Phase.DISCOVERING
        _outboxNotesReceived.value = 0

        discoveryJob = scope.launch {
            try {
                // Phase 1: Batch-fetch NIP-65 relay lists for all followed users
                discoverOutboxRelays(followedPubkeys.toList(), indexerRelayUrls)

                // Phase 2: Build relay→authors map and subscribe
                val inboxSet = inboxRelayUrls.map { normalizeUrl(it) }.toSet()
                subscribeToOutboxRelays(followedPubkeys, inboxSet)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Outbox feed failed: ${e.message}", e)
                _phase.value = Phase.STOPPED
            }
        }
    }

    /**
     * Stop all outbox subscriptions and discovery. Call on feed disconnect or logout.
     */
    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        outboxHandle?.cancel()
        outboxHandle = null
        lastStartedFollowSet = null
        _phase.value = Phase.STOPPED
        _outboxRelayCount.value = 0
        _discoveredAuthorCount.value = 0
        Log.d(TAG, "Outbox feed stopped")
    }

    // ── Internal ──

    /**
     * Phase 1: Batch-fetch kind-10002 for followed users from indexer relays.
     * Uses [Nip65RelayListRepository.batchFetchRelayLists] which populates
     * authorOutboxCache. We wait for it to complete (with timeout).
     */
    private suspend fun discoverOutboxRelays(
        followedPubkeys: List<String>,
        indexerRelayUrls: List<String>
    ) {
        val uncachedCount = followedPubkeys.count {
            Nip65RelayListRepository.getCachedOutboxRelays(it) == null
        }

        if (uncachedCount > 0) {
            Log.d(TAG, "Discovering outbox relays: $uncachedCount/${followedPubkeys.size} uncached")
            Nip65RelayListRepository.batchFetchRelayLists(followedPubkeys, indexerRelayUrls)

            // Wait for batch fetch to populate cache (poll with timeout)
            val maxWaitMs = 15_000L
            val pollMs = 500L
            var waited = 0L
            while (waited < maxWaitMs) {
                delay(pollMs)
                waited += pollMs
                val cached = followedPubkeys.count {
                    Nip65RelayListRepository.getCachedOutboxRelays(it) != null
                }
                // Good enough when 80% are cached or all are done
                if (cached >= followedPubkeys.size * 0.8 || cached >= followedPubkeys.size) {
                    Log.d(TAG, "Discovery sufficient: $cached/${followedPubkeys.size} cached after ${waited}ms")
                    break
                }
            }
        } else {
            Log.d(TAG, "All ${followedPubkeys.size} followed users already have cached outbox relays")
        }

        val discovered = followedPubkeys.count {
            val relays = Nip65RelayListRepository.getCachedOutboxRelays(it)
            relays != null && relays.isNotEmpty()
        }
        _discoveredAuthorCount.value = discovered
        Log.d(TAG, "Discovery complete: $discovered/${followedPubkeys.size} authors have outbox relays")
    }

    /**
     * Phase 2: Build relay→authors map from cached outbox data, then subscribe.
     * Excludes the user's own inbox relays (already covered by main feed subscription).
     * Caps at [MAX_OUTBOX_RELAYS] sorted by author count (most popular relays first).
     */
    private fun subscribeToOutboxRelays(
        followedPubkeys: Set<String>,
        inboxRelayUrls: Set<String>
    ) {
        _phase.value = Phase.SUBSCRIBING

        // Build relay → set of authors who publish there
        val relayToAuthors = mutableMapOf<String, MutableSet<String>>()
        for (pubkey in followedPubkeys) {
            val outboxRelays = Nip65RelayListRepository.getCachedOutboxRelays(pubkey) ?: continue
            for (relayUrl in outboxRelays) {
                val normalized = normalizeUrl(relayUrl)
                // Skip relays we're already subscribed to via main feed
                if (normalized in inboxRelayUrls) continue
                relayToAuthors.getOrPut(normalized) { mutableSetOf() }.add(pubkey)
            }
        }

        if (relayToAuthors.isEmpty()) {
            Log.d(TAG, "No additional outbox relays to subscribe to (all covered by inbox)")
            _phase.value = Phase.ACTIVE
            return
        }

        // Rank by author count (most popular first), cap at MAX_OUTBOX_RELAYS
        val ranked = relayToAuthors.entries
            .sortedByDescending { it.value.size }
            .take(MAX_OUTBOX_RELAYS)

        val totalAuthors = ranked.flatMap { it.value }.toSet().size
        Log.d(TAG, "Subscribing to ${ranked.size} outbox relays covering $totalAuthors authors " +
            "(${relayToAuthors.size} total discovered, capped at $MAX_OUTBOX_RELAYS)")

        // Build per-relay filters: each relay gets a kind-1 filter with only its authors
        val sevenDaysAgo = System.currentTimeMillis() / 1000 - SINCE_WINDOW_SECS
        val relayFilters = ranked.associate { (relayUrl, authors) ->
            relayUrl to listOf(
                Filter(
                    kinds = listOf(1),
                    authors = authors.toList(),
                    since = sevenDaysAgo,
                    limit = OUTBOX_PER_RELAY_LIMIT
                )
            )
        }

        _outboxRelayCount.value = relayFilters.size

        // Subscribe using per-relay filter map — each relay only gets its relevant authors
        val callback = onNoteReceived
        if (callback == null) {
            Log.w(TAG, "No onNoteReceived callback set — outbox notes will be dropped")
            _phase.value = Phase.ACTIVE
            return
        }

        outboxHandle = relayStateMachine.requestTemporarySubscriptionPerRelay(
            relayFilters = relayFilters.mapValues { it.value }
        ) { event ->
            if (event.kind == 1) {
                _outboxNotesReceived.value = _outboxNotesReceived.value + 1
                // Inject into NotesRepository's existing pipeline.
                // Per-relay subscription doesn't expose source relay URL, so pass empty.
                // convertEventToNote treats empty relayUrl as null (no relay attribution).
                // The note's relayUrls will be enriched from NIP-65 outbox cache instead.
                callback(event, "")
            }
        }

        _phase.value = Phase.ACTIVE
        Log.d(TAG, "Outbox subscriptions active: ${relayFilters.size} relays")

        // Log top 5 relays for diagnostics
        ranked.take(5).forEach { (url, authors) ->
            Log.d(TAG, "  ${url.removePrefix("wss://").removeSuffix("/")}: ${authors.size} authors")
        }
    }

    private fun normalizeUrl(url: String): String {
        return com.example.cybin.relay.RelayUrlNormalizer.normalizeOrNull(url)?.url ?: url
    }

    companion object {
        private const val TAG = "OutboxFeedManager"

        /** Max outbox relays to subscribe to. Prevents opening hundreds of WebSockets. */
        private const val MAX_OUTBOX_RELAYS = 30

        /** Per-relay note limit for outbox subscriptions. */
        private const val OUTBOX_PER_RELAY_LIMIT = 50

        /** Only fetch notes from the last 7 days. */
        private const val SINCE_WINDOW_SECS = 7 * 24 * 3600L

        @Volatile
        private var instance: OutboxFeedManager? = null
        fun getInstance(): OutboxFeedManager =
            instance ?: synchronized(this) { instance ?: OutboxFeedManager().also { instance = it } }
    }
}
