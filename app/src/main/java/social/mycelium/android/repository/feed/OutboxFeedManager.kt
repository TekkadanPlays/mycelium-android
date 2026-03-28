package social.mycelium.android.repository.feed

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
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.TemporarySubscriptionHandle
import java.util.concurrent.ConcurrentHashMap
import social.mycelium.android.repository.relay.Nip65RelayListRepository
import social.mycelium.android.repository.relay.Nip66RelayDiscoveryRepository

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
 * ## NIP-66 pre-filtering
 * Before ranking, relays are filtered against [Nip66RelayDiscoveryRepository] liveness data.
 * Relays not seen by any monitor in the last 48 hours are excluded, saving connection
 * slots for live relays. Auth-required and payment-required relays are also excluded.
 * If NIP-66 data is not yet available, the filter is skipped gracefully.
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

    /** Delivery measurement job — records outcomes after subscription settles. */
    private var deliveryMeasurementJob: Job? = null

    /** Tracks which authors delivered events this session (for relay attribution). */
    private val deliveredAuthors = ConcurrentHashMap.newKeySet<String>()

    /** The relay→authors assignment from the current session (for delivery attribution). */
    @Volatile
    private var currentRelayAssignment: Map<String, Set<String>> = emptyMap()

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

    /** Per-relay outbox connection state — exposed so the relay manager can show
     *  what the outbox model is connecting to and why. */
    data class OutboxRelayState(
        val url: String,
        val authorCount: Int,
        val notesReceived: Int = 0,
        val isHealing: Boolean = false
    )

    private val _activeOutboxRelays = MutableStateFlow<List<OutboxRelayState>>(emptyList())
    val activeOutboxRelays: StateFlow<List<OutboxRelayState>> = _activeOutboxRelays.asStateFlow()

    /** Per-relay note counter for outbox relays. */
    private val outboxRelayNoteCounts = ConcurrentHashMap<String, Int>()

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
        deliveredAuthors.clear()

        // Decay historical delivery stats so the algorithm adapts to relay changes
        RelayDeliveryTracker.decayAll()

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
        // Finalize delivery stats before stopping (if measurement hasn't run yet)
        finalizeDeliveryMeasurement()
        deliveryMeasurementJob?.cancel()
        deliveryMeasurementJob = null
        discoveryJob?.cancel()
        discoveryJob = null
        outboxHandle?.cancel()
        outboxHandle = null
        lastStartedFollowSet = null
        currentRelayAssignment = emptyMap()
        _phase.value = Phase.STOPPED
        _outboxRelayCount.value = 0
        _discoveredAuthorCount.value = 0
        _activeOutboxRelays.value = emptyList()
        outboxRelayNoteCounts.clear()
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
                // Good enough when 90% are cached or all are done
                if (cached >= followedPubkeys.size * 0.9 || cached >= followedPubkeys.size) {
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

        // Blocklist pre-filter: drop relays the user has blocked or that were auto-blocked
        val blockedCount = relayToAuthors.keys.count { RelayHealthTracker.isBlocked(it) }
        if (blockedCount > 0) {
            relayToAuthors.keys.removeAll { RelayHealthTracker.isBlocked(it) }
            Log.d(TAG, "Blocklist pre-filter: removed $blockedCount blocked relays (${relayToAuthors.size} remaining)")
            if (relayToAuthors.isEmpty()) {
                Log.d(TAG, "All outbox relays are blocked — nothing to subscribe to")
                _phase.value = Phase.ACTIVE
                return
            }
        }

        // NIP-66 pre-filter: remove dead/stale relays before ranking.
        // Also enforces the auth guard: we MUST NOT connect to auth-required or
        // payment-required relays on behalf of users who did not opt in to those relays.
        val nip11Cache = social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
        val liveRelays = Nip66RelayDiscoveryRepository.discoveredRelays.value
        val candidateRelays = if (liveRelays.isNotEmpty()) {
            val now = System.currentTimeMillis() / 1000
            val staleThreshold = now - LIVENESS_WINDOW_SECS
            val beforeCount = relayToAuthors.size
            val filtered = relayToAuthors.filterKeys { url ->
                val discovered = liveRelays[url]
                if (discovered == null) {
                    // Relay not in NIP-66 data — keep only if it has previously connected
                    // successfully AND is not known to require auth (from NIP-11 cache).
                    // We must not silently connect to auth-required relays the user never
                    // explicitly added to their relay manager.
                    val health = RelayHealthTracker.getHealth(url)
                    val knownAuthRequired = nip11Cache?.isAuthRequired(url) == true
                    val knownPaymentRequired = nip11Cache?.isPaymentRequired(url) == true
                    health != null && health.lastConnectedAt > 0 &&
                        !RelayHealthTracker.isBlocked(url) &&
                        !knownAuthRequired && !knownPaymentRequired
                } else {
                    // Exclude if last seen too long ago, or requires auth/payment
                    discovered.lastSeen >= staleThreshold &&
                        !discovered.authRequired &&
                        !discovered.paymentRequired
                }
            }
            val removedCount = beforeCount - filtered.size
            if (removedCount > 0) {
                Log.d(TAG, "NIP-66 pre-filter: removed $removedCount dead/stale/auth-restricted relays " +
                    "($beforeCount → ${filtered.size})")
            }
            filtered
        } else {
            Log.d(TAG, "NIP-66 data not available — applying NIP-11 auth-only filter")
            // NIP-66 dataset absent: fall back but still enforce auth guard via NIP-11 cache
            relayToAuthors.filterKeys { url ->
                val knownAuthRequired = nip11Cache?.isAuthRequired(url) == true
                val knownPaymentRequired = nip11Cache?.isPaymentRequired(url) == true
                !RelayHealthTracker.isBlocked(url) && !knownAuthRequired && !knownPaymentRequired
            }
        }

        if (candidateRelays.isEmpty()) {
            Log.d(TAG, "All outbox relays filtered out by NIP-66/NIP-11 — falling back to unfiltered (excluding auth-required)")
            // Fall back to avoid zero coverage, but still strip auth/payment-required relays
            // regardless — we must never authenticate with relays the user didn't explicitly configure.
        }
        // When falling back to unfiltered, apply the minimum safety filter: strip any relay
        // confirmed as auth-required or payment-required by NIP-66 OR NIP-11 cache.
        // This closes the loophole where candidateRelays.isEmpty() would previously restore them.
        val authSafePool = (candidateRelays.ifEmpty { relayToAuthors }).filterKeys { url ->
            val nip66 = liveRelays[url]
            val nip66AuthRequired = nip66?.authRequired == true
            val nip66PaymentRequired = nip66?.paymentRequired == true
            val nip11AuthRequired = nip11Cache?.isAuthRequired(url) == true
            val nip11PaymentRequired = nip11Cache?.isPaymentRequired(url) == true
            !nip66AuthRequired && !nip66PaymentRequired && !nip11AuthRequired && !nip11PaymentRequired
        }
        val selectionPool = authSafePool

        // Pre-compute Thompson Sampling scores — each call is stochastic, so we must
        // sample once and sort by the cached value. Re-evaluating inside the comparator
        // violates TimSort's transitivity contract (different random draw each call).
        val precomputedScores = selectionPool.entries.associate { entry ->
            entry.key to RelayDeliveryTracker.sampleScore(entry.key, entry.value.size)
        }

        // Rank by pre-computed score (delivery quality × popularity), cap at MAX_OUTBOX_RELAYS
        val greedyRanked = selectionPool.entries
            .sortedByDescending { entry -> precomputedScores[entry.key] ?: 0.0 }
            .take(MAX_OUTBOX_RELAYS)

        // Phase 3a: Ensure per-author diversity — authors only on niche relays may be
        // uncovered by the greedy top-N. Add their best relay up to a soft budget.
        val coveredAuthors = greedyRanked.flatMap { it.value }.toMutableSet()
        val selectedUrls = greedyRanked.map { it.key }.toMutableSet()
        val diversityRelays = mutableListOf<Map.Entry<String, MutableSet<String>>>()

        if (coveredAuthors.size < followedPubkeys.size) {
            val uncovered = followedPubkeys.filter { it !in coveredAuthors }
            // For each uncovered author, find their best relay (by score) that isn't already selected
            for (pubkey in uncovered) {
                if (selectedUrls.size >= MAX_OUTBOX_RELAYS + DIVERSITY_BUDGET) break
                val bestRelay = selectionPool.entries
                    .filter { pubkey in it.value && it.key !in selectedUrls }
                    .maxByOrNull { precomputedScores[it.key] ?: 0.0 }
                if (bestRelay != null) {
                    diversityRelays.add(bestRelay)
                    selectedUrls.add(bestRelay.key)
                    coveredAuthors.addAll(bestRelay.value)
                }
            }
            if (diversityRelays.isNotEmpty()) {
                Log.d(TAG, "Diversity pass: added ${diversityRelays.size} relays for ${uncovered.size} uncovered authors")
            }
        }

        val ranked = greedyRanked + diversityRelays
        val totalAuthors = ranked.flatMap { it.value }.toSet().size
        Log.d(TAG, "Subscribing to ${ranked.size} outbox relays covering $totalAuthors authors " +
            "(${relayToAuthors.size} total discovered, greedy=$MAX_OUTBOX_RELAYS + diversity=${diversityRelays.size})")

        // Coverage gap report: identify followed authors with NO outbox relay coverage
        val coveredByOutbox = ranked.flatMap { it.value }.toSet()
        val uncoveredAuthors = followedPubkeys.filter { pk ->
            pk !in coveredByOutbox && Nip65RelayListRepository.getCachedOutboxRelays(pk).isNullOrEmpty()
        }
        val coveredByInboxOnly = followedPubkeys.filter { pk ->
            pk !in coveredByOutbox && !Nip65RelayListRepository.getCachedOutboxRelays(pk).isNullOrEmpty()
        }
        if (uncoveredAuthors.isNotEmpty()) {
            Log.w(TAG, "⚠️ ${uncoveredAuthors.size} followed authors have NO outbox relay (no NIP-65): ${uncoveredAuthors.take(10).joinToString { it.take(8) + "…" }}")
        }
        if (coveredByInboxOnly.isNotEmpty()) {
            Log.w(TAG, "⚠️ ${coveredByInboxOnly.size} followed authors' outbox relays not in selected set (rely on inbox only): ${coveredByInboxOnly.take(10).joinToString { it.take(8) + "…" }}")
        }
        Log.d(TAG, "Coverage: ${coveredByOutbox.size}/${followedPubkeys.size} via outbox, ${followedPubkeys.size - uncoveredAuthors.size - coveredByInboxOnly.size} via inbox overlap")

        // Phase 5: Self-healing — add indexer fallback for chronically missed authors
        val missedAuthors = RelayDeliveryTracker.getMissedAuthors()
            .filter { it in followedPubkeys }.toSet() // only care about current follows
        // Also include authors with NO NIP-65 data at all — they have zero outbox coverage
        val noNip65Authors = uncoveredAuthors.toSet()
        val allFallbackAuthors = (missedAuthors + noNip65Authors).filter { it in followedPubkeys }.toSet()
        val healingRelay = if (allFallbackAuthors.isNotEmpty()) {
            // Pick the first indexer relay not already in our selection
            val selectedRelayUrls = ranked.map { it.key }.toSet()
            NotesRepository.getInstance().INDEXER_RELAYS.firstOrNull { it !in selectedRelayUrls }
        } else null

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
        }.toMutableMap()

        // Inject healing relay with fallback authors' filter (missed + no-NIP-65)
        if (healingRelay != null && allFallbackAuthors.isNotEmpty()) {
            relayFilters[healingRelay] = listOf(
                Filter(
                    kinds = listOf(1),
                    authors = allFallbackAuthors.toList(),
                    since = sevenDaysAgo,
                    limit = OUTBOX_PER_RELAY_LIMIT
                )
            )
            Log.d(TAG, "Indexer fallback: added $healingRelay for ${allFallbackAuthors.size} authors (${missedAuthors.size} missed + ${noNip65Authors.size} no-NIP-65)")
        }

        // _outboxRelayCount is updated after the auth guard (below) with the final count.
        // It is set here as a provisional value and overwritten after authGuardedFilters is built.

        // ── Final auth guard (catch-all) ──────────────────────────────────────────────
        // Strip any relay that is confirmed to require authentication or payment by
        // EITHER the NIP-66 monitor dataset OR the NIP-11 HTTP cache. This is the
        // last line of defence before any WebSocket connection is opened.
        //
        // Rationale: we are connecting to other users' write relays on their behalf.
        // The local user has NOT opted in to authenticate with these relays. If we
        // respond to an AUTH challenge from a relay the user never explicitly added
        // to their relay manager, we leak their identity without consent.
        //
        // This sweep is intentionally redundant with the NIP-66/NIP-11 pre-filter
        // above — it catches auth-required relays that slipped through via the
        // diversity pass, the healing relay slot, or a stale/empty NIP-66 cache.
        val authGuardedFilters = relayFilters.filterKeys { url ->
            val nip66 = liveRelays[url]
            val nip66Auth = nip66?.authRequired == true
            val nip66Pay  = nip66?.paymentRequired == true
            val nip11Auth = nip11Cache?.isAuthRequired(url) == true
            val nip11Pay  = nip11Cache?.isPaymentRequired(url) == true
            val blocked   = nip66Auth || nip66Pay || nip11Auth || nip11Pay
            if (blocked) {
                Log.w(TAG, "Auth guard: refusing outbox connection to $url " +
                    "(nip66Auth=$nip66Auth pay=$nip66Pay | nip11Auth=$nip11Auth pay=$nip11Pay) — " +
                    "user has not consented to authenticate with this relay")
            }
            !blocked
        }
        val removedByAuthGuard = relayFilters.size - authGuardedFilters.size
        if (removedByAuthGuard > 0) {
            Log.w(TAG, "Auth guard: blocked $removedByAuthGuard auth/payment-required outbox relay(s) " +
                "from connecting")
        }
        // Set the final relay count after auth guard filtering
        _outboxRelayCount.value = authGuardedFilters.size

        // Save relay→authors assignment for delivery attribution.
        // Use authGuardedFilters (not relayFilters) so the assignment only tracks
        // relays we actually connected to (auth-required relays are excluded).
        val assignmentMap = ranked
            .filter { (url, _) -> url in authGuardedFilters }
            .associate { (url, authors) -> url to authors.toSet() }.toMutableMap()
        if (healingRelay != null && allFallbackAuthors.isNotEmpty() && healingRelay in authGuardedFilters) {
            assignmentMap[healingRelay] = allFallbackAuthors
        }
        currentRelayAssignment = assignmentMap

        // Expose per-relay state for relay manager transparency
        outboxRelayNoteCounts.clear()
        _activeOutboxRelays.value = assignmentMap.map { (url, authors) ->
            OutboxRelayState(
                url = url,
                authorCount = authors.size,
                isHealing = url == healingRelay
            )
        }

        // Record that we expect delivery from each selected relay (including healing relay)
        authGuardedFilters.keys.forEach { url -> RelayDeliveryTracker.recordExpected(url) }

        // Subscribe using per-relay filter map — each relay only gets its relevant authors
        val callback = onNoteReceived
        if (callback == null) {
            Log.w(TAG, "No onNoteReceived callback set — outbox notes will be dropped")
            _phase.value = Phase.ACTIVE
            return
        }

        outboxHandle = relayStateMachine.requestTemporarySubscriptionPerRelayWithRelay(
            relayFilters = authGuardedFilters.mapValues { it.value },
            priority = SubscriptionPriority.NORMAL,
        ) { event, relayUrl ->
            if (event.kind == 1) {
                _outboxNotesReceived.value = _outboxNotesReceived.value + 1
                // Track which authors delivered events (for relay attribution)
                deliveredAuthors.add(event.pubKey)
                // Track per-relay note counts for transparency
                val newCount = outboxRelayNoteCounts.merge(relayUrl, 1) { old, inc -> old + inc } ?: 1
                _activeOutboxRelays.value = _activeOutboxRelays.value.map { state ->
                    if (state.url == relayUrl) state.copy(notesReceived = newCount) else state
                }
                // Inject into NotesRepository's existing pipeline with the actual
                // source relay URL so relay orbs display correctly.
                callback(event, relayUrl)
            }
        }

        _phase.value = Phase.ACTIVE
        Log.d(TAG, "Outbox subscriptions active: ${authGuardedFilters.size} relays" +
            if (removedByAuthGuard > 0) " ($removedByAuthGuard auth-required relays blocked)" else "")

        // Batch preload NIP-11 for all outbox relay URLs so relay orb icons
        // are ready when notes arrive (avoids per-orb waterfall fetch on scroll).
        social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
            ?.preloadRelayInfo(authGuardedFilters.keys.toList(), scope)

        // Log top 5 relays for diagnostics
        ranked.take(5).forEach { (url, authors) ->
            val stats = RelayDeliveryTracker.getStats()[url]
            val statsStr = if (stats != null) " (delivery: ${String.format("%.0f%%", stats.successRate * 100)})" else " (new)"
            Log.d(TAG, "  ${url.removePrefix("wss://").removeSuffix("/")}: ${authors.size} authors$statsStr")
        }

        // Schedule delivery measurement after events have had time to arrive
        scheduleDeliveryMeasurement()
    }

    /**
     * Schedule delivery measurement after a delay, giving relays time to deliver events.
     * After the window, we check which relays produced events (via author attribution)
     * and record success/failure in [RelayDeliveryTracker].
     */
    private fun scheduleDeliveryMeasurement() {
        deliveryMeasurementJob?.cancel()
        deliveryMeasurementJob = scope.launch {
            delay(DELIVERY_MEASUREMENT_DELAY_MS)
            finalizeDeliveryMeasurement()
        }
    }

    /**
     * Attribute delivery outcomes to relays based on which authors delivered events.
     *
     * Logic: For each relay in [currentRelayAssignment], if ANY author assigned to that
     * relay delivered an event, mark the relay as "delivered". This is a conservative
     * heuristic — we can't know exactly which relay sent which event (the per-relay
     * subscription callback doesn't expose source URL), but if a relay's assigned authors
     * produced events, it's likely the relay contributed.
     */
    private fun finalizeDeliveryMeasurement() {
        val assignment = currentRelayAssignment
        if (assignment.isEmpty() || deliveredAuthors.isEmpty()) return

        var deliveredCount = 0
        for ((relayUrl, assignedAuthors) in assignment) {
            val relayDelivered = assignedAuthors.any { it in deliveredAuthors }
            if (relayDelivered) {
                RelayDeliveryTracker.recordDelivered(relayUrl)
                deliveredCount++
            }
        }

        Log.d(TAG, "Delivery measurement: $deliveredCount/${assignment.size} relays delivered " +
            "(${deliveredAuthors.size} unique authors seen)")

        // Record per-author outcomes for self-healing (Phase 5)
        val allAssignedAuthors = assignment.values.flatten().toSet()
        RelayDeliveryTracker.recordAuthorOutcomes(allAssignedAuthors, deliveredAuthors.toSet())
        val missedAuthors = RelayDeliveryTracker.getMissedAuthors()
        if (missedAuthors.isNotEmpty()) {
            Log.d(TAG, "Self-healing: ${missedAuthors.size} authors chronically missed (≥${2} sessions)")
        }

        // Persist stats to survive app restarts
        RelayDeliveryTracker.saveToDisk()
    }

    private fun normalizeUrl(url: String): String {
        return com.example.cybin.relay.RelayUrlNormalizer.normalizeOrNull(url)?.url ?: url
    }

    companion object {
        private const val TAG = "OutboxFeedManager"

        /** Max outbox relays to subscribe to. Prevents opening hundreds of WebSockets.
         *  Raised from 12→18 for better per-author coverage across diverse relay sets. */
        private const val MAX_OUTBOX_RELAYS = 18

        /** Per-relay note limit for outbox subscriptions.
         *  Raised from 50→100 so each outbox relay returns more notes from followed authors. */
        private const val OUTBOX_PER_RELAY_LIMIT = 100

        /** Only fetch notes from the last 7 days. */
        private const val SINCE_WINDOW_SECS = 7 * 24 * 3600L

        /** NIP-66 liveness window: relays not seen in 48 hours are considered dead. */
        private const val LIVENESS_WINDOW_SECS = 48 * 3600L

        /** Delay before measuring delivery outcomes. Gives relays time to send events. */
        private const val DELIVERY_MEASUREMENT_DELAY_MS = 30_000L

        /** Extra relay slots beyond MAX_OUTBOX_RELAYS for per-author diversity coverage.
         *  Raised from 4→8 to ensure niche-relay authors aren't left uncovered. */
        private const val DIVERSITY_BUDGET = 8

        @Volatile
        private var instance: OutboxFeedManager? = null
        fun getInstance(): OutboxFeedManager =
            instance ?: synchronized(this) { instance ?: OutboxFeedManager().also { instance = it } }
    }
}
