package social.mycelium.android.relay

import android.util.Log
import com.tinder.StateMachine
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.core.CybinUtils
import com.example.cybin.relay.CybinRelayPool
import com.example.cybin.relay.CybinSubscription
import com.example.cybin.relay.RelayConnectionListener
import com.example.cybin.relay.SubscriptionPriority
import social.mycelium.android.network.MyceliumHttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * States for the relay connection lifecycle.
 * Avoids full disconnect+reconnect when only the feed/subscription changes.
 */
sealed class RelayState {
    object Disconnected : RelayState()
    object Connecting : RelayState()
    object Connected : RelayState()
    object Subscribed : RelayState()
    /** Connection or subscription failed; retry with backoff or user-triggered. */
    data class ConnectFailed(val message: String?) : RelayState()
}

/** Optional error details for UI (e.g. "Connection failed" + message). */
data class ConnectionError(val message: String?, val isConnect: Boolean)

/** Per-relay connection status for UI (e.g. "3/5 relays connected"). */
enum class RelayEndpointStatus { Connecting, Connected, Failed }

/**
 * Handle for a one-off subscription created via requestTemporarySubscription.
 * Call cancel() when done (e.g. thread screen closed, timeout) to avoid duplicate connections.
 * Use pause()/resume() for temporary deactivation without losing subscription config.
 */
interface TemporarySubscriptionHandle {
    fun cancel()
    fun pause()
    fun resume()
    val isPaused: Boolean
}

internal object NoOpTemporaryHandle : TemporarySubscriptionHandle {
    override fun cancel() {}
    override fun pause() {}
    override fun resume() {}
    override val isPaused: Boolean get() = false
}

/** Current subscription config (relayUrls + kind1Filter) for idempotent feed change and UI. */
data class CurrentSubscription(
    val relayUrls: List<String>,
    val kind1Filter: Filter?
)

/**
 * Events that drive the relay connection state machine.
 */
sealed class RelayEvent {
    data class ConnectRequested(val relayUrls: List<String>) : RelayEvent()
    object Connected : RelayEvent()
    data class ConnectFailed(val message: String?) : RelayEvent()
    object RetryRequested : RelayEvent()
    data class FeedChangeRequested(
        val relayUrls: List<String>,
        val customFilter: Filter? = null,
        val customOnEvent: ((Event) -> Unit)? = null,
        /** When set, used for kind-1 in default path (relay-aware); e.g. authors=followList for Following feed. */
        val kind1Filter: Filter? = null
    ) : RelayEvent()
    object DisconnectRequested : RelayEvent()
}

/**
 * Side effects executed on transition (connect client, update subscription, disconnect).
 */
sealed class RelaySideEffect {
    data class ConnectRelays(val relayUrls: List<String>) : RelaySideEffect()
    object OnConnected : RelaySideEffect()
    object ScheduleRetry : RelaySideEffect()
    data class UpdateSubscription(
        val relayUrls: List<String>,
        val customFilter: Filter? = null,
        val customOnEvent: ((Event) -> Unit)? = null,
        val kind1Filter: Filter? = null
    ) : RelaySideEffect()
    object DisconnectClient : RelaySideEffect()
}

/**
 * Single shared component that owns one NostrClient and drives it with a Tinder State Machine.
 * On feed/relay switch we only update the subscription (no disconnect), so re-connectivity is fast.
 *
 * ## EOSE (End of Stored Events)
 * Relays send EOSE when they have finished sending stored events for a subscription. EOSE does
 * **not** close the connection or the subscription: the WebSocket stays open and the relay
 * continues to deliver live events. We do not stop retrieval on EOSE; that would be incorrect.
 *
 * ## Retrieval lifecycle (avoid careless start/stop)
 * - **Startup**: NotesRepository calls [requestFeedChange] (or connect then feed change) once relays
 *   and follow filter are known; we open one REQ subscription across all user relays.
 * - **Refresh**: Pull-to-refresh re-applies the same subscription (idempotent) and NotesRepository
 *   merges pending notes; we do not disconnect.
 * - **Wake/Resume**: [requestReconnectOnResume] re-applies the subscription from
 *   [resumeSubscriptionProvider] (e.g. NotesRepository) so Following filter and relay set are
 *   preserved without reconnecting from scratch.
 * - **User config**: Relay or follow filter changes call [requestFeedChange]; we update the
 *   subscription only (no full disconnect).
 * - **Organic Nostr**: The client sends REQ; each relay responds with events then EOSE per sub_id.
 *   We rely on Quartz's NostrClient for per-relay sockets; aggregate state is [RelayState].
 *
 * ## Per-relay awareness
 * We expose [perRelayState] (Connecting/Connected/Failed per URL) for UI (e.g. "3/5 relays").
 *
 * ## Subscription ownership
 * The primary main-feed subscription is owned by [NotesRepository] (singleton). Only NotesRepository
 * should call [requestFeedChange] for the main feed. TopicsRepository may call
 * requestFeedChange(relayUrls, getCurrentKind1Filter()) to preserve the kind-1 filter when opening
 * Topics. One-off or long-lived auxiliary subscriptions (thread replies, notifications, profile/contact
 * fetches) use [requestTemporarySubscription] and do not replace the main subscription.
 */
class RelayConnectionStateMachine {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) })
    val relayPool: CybinRelayPool = CybinRelayPool(MyceliumHttpClient.instance, scope)

    /** NIP-42 relay authentication handler — intercepts AUTH challenges and signs via Amber.
     *  Eagerly initialized so the onAuth listener is registered BEFORE any relay connections. */
    val nip42AuthHandler: Nip42AuthHandler = Nip42AuthHandler(this)

    /** Pending relay state changes accumulated between flush cycles. */
    private val pendingRelayStateChanges = java.util.concurrent.ConcurrentHashMap<String, RelayEndpointStatus>()
    private var relayStateFlushJob: kotlinx.coroutines.Job? = null
    private val RELAY_STATE_FLUSH_MS = 100L

    /** Batch relay state updates: accumulate changes and flush to StateFlow on a debounce. */
    private fun scheduleRelayStateFlush() {
        relayStateFlushJob?.cancel()
        relayStateFlushJob = scope.launch {
            kotlinx.coroutines.delay(RELAY_STATE_FLUSH_MS)
            if (pendingRelayStateChanges.isNotEmpty()) {
                val current = _perRelayState.value
                val updates = HashMap(pendingRelayStateChanges)
                pendingRelayStateChanges.clear()
                // Only apply changes for URLs in the current map
                val merged = current.toMutableMap()
                for ((url, status) in updates) {
                    if (url in merged) merged[url] = status
                }
                _perRelayState.value = merged
            }
        }
    }

    private fun updateRelayStatus(url: String, status: RelayEndpointStatus) {
        pendingRelayStateChanges[url] = status
        scheduleRelayStateFlush()
    }

    init {
        // Wire app-layer blocking into the pool so its internal reconnect loop
        // stops hammering relays that RelayHealthTracker has auto-blocked.
        relayPool.connectionFilter = { url -> !RelayHealthTracker.isBlocked(url) }

        // Register connection listener to track per-relay status from actual WebSocket events
        relayPool.addListener(object : RelayConnectionListener {
            override fun onConnecting(url: String) {
                updateRelayStatus(url, RelayEndpointStatus.Connecting)
                RelayHealthTracker.recordConnectionAttempt(url)
            }

            override fun onConnected(url: String) {
                updateRelayStatus(url, RelayEndpointStatus.Connected)
                RelayHealthTracker.recordConnectionSuccess(url)
            }

            override fun onDisconnected(url: String) {
                Log.d(TAG, "[$url] Disconnected — pool will auto-reconnect if subs active")
                updateRelayStatus(url, RelayEndpointStatus.Connecting)
            }

            override fun onError(url: String, message: String) {
                updateRelayStatus(url, RelayEndpointStatus.Failed)
                RelayHealthTracker.recordConnectionFailure(url, message)
            }

            override fun onOk(url: String, eventId: String, success: Boolean, message: String) {
                RelayHealthTracker.recordPublishOk(url, eventId, success, message)
                if (!success && message.contains("auth-required", ignoreCase = true)) {
                    nip42AuthHandler.onAuthRequiredPublishFailure(url, eventId)
                }
            }
        })
    }

    /**
     * Set the signer for NIP-42 relay authentication. Call after login with the current
     * Amber signer so AUTH challenges can be answered automatically.
     */
    fun setNip42Signer(signer: com.example.cybin.signer.NostrSigner?) {
        nip42AuthHandler.setSigner(signer)
    }

    private var mainFeedSubscription: CybinSubscription? = null
    private var currentSubId: String? = null

    private val _state = MutableStateFlow<RelayState>(RelayState.Disconnected)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _connectionError = MutableStateFlow<ConnectionError?>(null)
    val connectionError: StateFlow<ConnectionError?> = _connectionError.asStateFlow()

    /** Stored when connecting/subscribing so RetryRequested can reuse. */
    @Volatile var pendingRelayUrlsForRetry: List<String> = emptyList()
        private set

    @Volatile private var pendingKind1FilterForRetry: Filter? = null
    @Volatile private var pendingWasSubscribe = false

    private var retryAttempt = 0

    /** Current subscription (relayUrls + kind1Filter) for idempotent feed change and UI. */
    private val _currentSubscription = MutableStateFlow(CurrentSubscription(emptyList(), null))
    val currentSubscription: StateFlow<CurrentSubscription> = _currentSubscription.asStateFlow()

    /** Per-relay status for UI (e.g. "3/5 relays"). Updated when we subscribe (Connecting) and when events arrive (Connected); Failed on ConnectFailed. */
    private val _perRelayState = MutableStateFlow<Map<String, RelayEndpointStatus>>(emptyMap())
    val perRelayState: StateFlow<Map<String, RelayEndpointStatus>> = _perRelayState.asStateFlow()

    private val stateMachine = StateMachine.create<RelayState, RelayEvent, RelaySideEffect> {
        initialState(RelayState.Disconnected)

        state<RelayState.Disconnected> {
            on<RelayEvent.ConnectRequested> {
                transitionTo(RelayState.Connecting, RelaySideEffect.ConnectRelays(it.relayUrls))
            }
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter)
                )
            }
        }

        state<RelayState.Connecting> {
            on<RelayEvent.Connected> {
                transitionTo(RelayState.Connected, RelaySideEffect.OnConnected)
            }
            on<RelayEvent.ConnectFailed> {
                transitionTo(RelayState.ConnectFailed(it.message), RelaySideEffect.ScheduleRetry)
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.ConnectFailed> {
            on<RelayEvent.RetryRequested> {
                val urls = pendingRelayUrlsForRetry
                if (urls.isEmpty()) {
                    transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
                } else if (pendingWasSubscribe) {
                    transitionTo(
                        RelayState.Subscribed,
                        RelaySideEffect.UpdateSubscription(urls, null, null, pendingKind1FilterForRetry)
                    )
                } else {
                    transitionTo(RelayState.Connecting, RelaySideEffect.ConnectRelays(urls))
                }
            }
            on<RelayEvent.FeedChangeRequested> {
                // Retry by going to Subscribed with the requested config
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter)
                )
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.Connected> {
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter)
                )
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        state<RelayState.Subscribed> {
            on<RelayEvent.FeedChangeRequested> {
                transitionTo(
                    RelayState.Subscribed,
                    RelaySideEffect.UpdateSubscription(it.relayUrls, it.customFilter, it.customOnEvent, it.kind1Filter)
                )
            }
            on<RelayEvent.ConnectFailed> {
                transitionTo(RelayState.ConnectFailed(it.message), RelaySideEffect.ScheduleRetry)
            }
            on<RelayEvent.DisconnectRequested> {
                transitionTo(RelayState.Disconnected, RelaySideEffect.DisconnectClient)
            }
        }

        onTransition { transition ->
            if (transition is StateMachine.Transition.Valid) {
                _state.value = transition.toState
                when (val effect = transition.sideEffect) {
                    is RelaySideEffect.ConnectRelays -> {
                        pendingRelayUrlsForRetry = effect.relayUrls
                        pendingWasSubscribe = false
                        executeConnectRelays(effect.relayUrls)
                    }
                    is RelaySideEffect.OnConnected -> {
                        _connectionError.value = null
                        retryAttempt = 0
                    }
                    is RelaySideEffect.ScheduleRetry -> {
                        // Only mark non-Connected relays as Failed; preserve relays that are already working
                        _perRelayState.value = _perRelayState.value.mapValues { (_, status) ->
                            if (status == RelayEndpointStatus.Connected) status else RelayEndpointStatus.Failed
                        }
                        executeScheduleRetry()
                    }
                    is RelaySideEffect.UpdateSubscription -> {
                        pendingRelayUrlsForRetry = effect.relayUrls
                        pendingKind1FilterForRetry = effect.kind1Filter
                        pendingWasSubscribe = true
                        executeUpdateSubscription(
                            effect.relayUrls,
                            effect.customFilter,
                            effect.customOnEvent,
                            effect.kind1Filter
                        )
                    }
                    is RelaySideEffect.DisconnectClient -> {
                        pendingRelayUrlsForRetry = emptyList()
                        retryAttempt = 0
                        _connectionError.value = null
                        _perRelayState.value = emptyMap()
                        executeDisconnect()
                    }
                    null -> { }
                }
            }
        }
    }

    private fun executeConnectRelays(relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        relayUrls.forEach { RelayLogBuffer.logConnecting(it) }
        scope.launch {
            android.os.Trace.beginSection("Relay.connectRelays(${relayUrls.size})")
            try {
                // Bootstrap subscription so relay pool opens connections
                val bootstrapFilter = Filter(kinds = listOf(1), limit = 1)
                mainFeedSubscription?.close()
                mainFeedSubscription = relayPool.subscribe(
                    relayUrls = relayUrls,
                    filters = listOf(bootstrapFilter),
                    onEvent = { _, _ -> }
                )
                relayPool.connect()
                delay(400)
                _connectionError.value = null
                retryAttempt = 0
                relayUrls.forEach { RelayLogBuffer.logConnected(it) }
                stateMachine.transition(RelayEvent.Connected)
            } catch (e: Exception) {
                Log.e(TAG, "Connect relays failed: ${e.message}", e)
                relayUrls.forEach { RelayLogBuffer.logError(it, e.message ?: "Connection failed") }
                _connectionError.value = ConnectionError(e.message, true)
                stateMachine.transition(RelayEvent.ConnectFailed(e.message))
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    private fun executeScheduleRetry() {
        if (pendingRelayUrlsForRetry.isEmpty() || retryAttempt >= Companion.MAX_RETRIES) {
            Log.d(TAG, "No retry: empty pending or max retries ($retryAttempt)")
            return
        }
        retryAttempt++
        val delayMs = when (retryAttempt) {
            1 -> Companion.RETRY_DELAY_MS_FIRST
            2 -> Companion.RETRY_DELAY_MS_SECOND
            else -> Companion.RETRY_DELAY_MS_SECOND
        }
        Log.d(TAG, "Scheduling retry $retryAttempt in ${delayMs}ms")
        scope.launch {
            delay(delayMs)
            stateMachine.transition(RelayEvent.RetryRequested)
        }
    }

    private fun executeUpdateSubscription(
        relayUrls: List<String>,
        customFilter: Filter? = null,
        customOnEvent: ((Event) -> Unit)? = null,
        kind1Filter: Filter? = null
    ) {
        scope.launch {
            android.os.Trace.beginSection("Relay.updateSubscription(${relayUrls.size})")
            try {
                currentSubId?.let { relayPool.closeSubscription(it) }
                currentSubId = null
                mainFeedSubscription?.close()
                mainFeedSubscription = null
                val effectiveRelayUrls = RelayHealthTracker.filterBlocked(relayUrls)
                if (effectiveRelayUrls.isEmpty() && relayUrls.isEmpty()) { android.os.Trace.endSection(); return@launch }
                // Preserve existing relay states (Connected/Failed); only set NEW relays to Connecting.
                // This avoids flashing all orbs back to "connecting" when adding a single relay.
                // Auto-blocked relays stay in perRelayState as Failed so the UI can show a banner.
                val existing = _perRelayState.value
                val blockedUrls = relayUrls.filter { it !in effectiveRelayUrls }
                val newState = effectiveRelayUrls.associateWith { url ->
                    existing[url] ?: RelayEndpointStatus.Connecting
                } + blockedUrls.associateWith { RelayEndpointStatus.Failed }
                // Only emit if the map actually changed — avoids unnecessary recomposition
                // that causes outbox relay orbs to visually flicker when toggling unrelated categories
                if (newState != existing) {
                    _perRelayState.value = newState
                }
                if (effectiveRelayUrls.isEmpty()) {
                    // All relays blocked — state updated above for banner, but nothing to subscribe to
                    Log.w(TAG, "All ${relayUrls.size} relays are blocked, no subscription possible")
                    android.os.Trace.endSection()
                    return@launch
                }
                if (customFilter != null && customOnEvent != null) {
                    val onEvent = customOnEvent
                    mainFeedSubscription = relayPool.subscribe(
                        relayUrls = effectiveRelayUrls,
                        filters = listOf(customFilter),
                        onEvent = { event, _ -> onEvent(event) }
                    )
                    currentSubscriptionRelayUrls = effectiveRelayUrls
                    currentKind1Filter = null
                    _currentSubscription.value = CurrentSubscription(effectiveRelayUrls, null)
                    Log.d(TAG, "Subscription updated for ${effectiveRelayUrls.size} relays (custom filter)")
                } else {
                    val sevenDaysAgo = System.currentTimeMillis() / 1000 - 86400 * 7
                    val filterKind1 = kind1Filter?.let {
                        // Add kind-1068 (NIP-88 polls) alongside kind-1 in Following mode
                        it.copy(kinds = (it.kinds ?: listOf(1)) + 1068)
                    } ?: Filter(
                        kinds = listOf(1, 1068),
                        limit = GLOBAL_FEED_LIMIT,
                        since = sevenDaysAgo
                    )
                    val filterKind6 = if (kind1Filter != null) {
                        Filter(kinds = listOf(6), authors = kind1Filter.authors, limit = kind1Filter.limit ?: GLOBAL_FEED_LIMIT, since = sevenDaysAgo)
                    } else {
                        Filter(kinds = listOf(6), limit = GLOBAL_FEED_LIMIT, since = sevenDaysAgo)
                    }
                    val filterKind11 = Filter(kinds = listOf(11), limit = 500)
                    val filterKind1011 = Filter(kinds = listOf(1011), limit = 200)
                    val filterKind30311 = Filter(kinds = listOf(30311), limit = 20)
                    val filterKind30023 = if (kind1Filter != null) {
                        Filter(kinds = listOf(30023), authors = kind1Filter.authors, limit = 50, since = sevenDaysAgo)
                    } else {
                        Filter(kinds = listOf(30023), limit = 50, since = sevenDaysAgo)
                    }
                    val allFilters = listOf(filterKind1, filterKind6, filterKind11, filterKind1011, filterKind30311, filterKind30023)
                    currentSubId = CybinUtils.randomChars(10)
                    val subId = currentSubId!!
                    val relayFilterMap = effectiveRelayUrls.associateWith { allFilters }
                    relayPool.openSubscription(subId, relayFilterMap) { event, relayUrl ->
                        markEventReceived()
                        // Only update state when it actually changes (Connecting → Connected).
                        // Avoids hundreds of redundant StateFlow emissions per second during event bursts.
                        val current = _perRelayState.value[relayUrl]
                        if (current != RelayEndpointStatus.Connected) {
                            _perRelayState.value = _perRelayState.value + (relayUrl to RelayEndpointStatus.Connected)
                        }
                        // Cross-pollinate to notifications: feed subscription is HIGH priority
                        // and always active; notification subs are BACKGROUND and can be preempted.
                        // seenEventIds dedup inside NotificationsRepository prevents double-processing.
                        social.mycelium.android.repository.NotificationsRepository.ingestEvent(event)
                        when (event.kind) {
                            1 -> {
                                onKind1WithRelay?.invoke(event, relayUrl)
                                social.mycelium.android.repository.NoteCountsRepository.onLiveEvent(event)
                            }
                            1068 -> {
                                // NIP-88 polls: route to kind-1 handler so they appear in feed
                                onKind1WithRelay?.invoke(event, relayUrl)
                            }
                            6 -> onKind6WithRelay?.invoke(event, relayUrl)
                            11 -> onKind11?.invoke(event, relayUrl)
                            1011 -> onKind1011?.invoke(event)
                            30023 -> onKind1WithRelay?.invoke(event, relayUrl)
                            30073 -> onKind30073?.invoke(event)
                            30311 -> onKind30311?.invoke(event, relayUrl)
                            7, 9735 -> social.mycelium.android.repository.NoteCountsRepository.onCountsEvent(event)
                            else -> { }
                        }
                    }
                    val mode = if (kind1Filter != null) "following (authors filter)" else "global"
                    Log.d(TAG, "Subscription updated for ${effectiveRelayUrls.size} relays (kind-1 + kind-6 + kind-11 + kind-30023, $mode)")
                }
                val previousRelayUrls = currentSubscriptionRelayUrls
                currentSubscriptionRelayUrls = effectiveRelayUrls
                currentKind1Filter = kind1Filter
                _currentSubscription.value = CurrentSubscription(effectiveRelayUrls, kind1Filter)
                _connectionError.value = null
                retryAttempt = 0
                relayPool.connect(priorityRelayUrls)
                // Disconnect relays that were in the previous set but not in the new one,
                // UNLESS they are in the persistent set (inbox/outbox/notification relays that
                // should stay connected to avoid connect/disconnect churn).
                val persistent = persistentRelayUrls
                val staleRelays = (previousRelayUrls.toSet() - effectiveRelayUrls.toSet())
                    .filter { it !in persistent }
                    .toSet()
                if (staleRelays.isNotEmpty()) {
                    Log.d(TAG, "Disconnecting ${staleRelays.size} stale relay(s): ${staleRelays.joinToString()}")
                    relayPool.disconnectIdleRelays(staleRelays)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update subscription failed: ${e.message}", e)
                // Only mark non-Connected relays as Failed; preserve relays that are already working
                _perRelayState.value = _perRelayState.value.mapValues { (_, status) ->
                    if (status == RelayEndpointStatus.Connected) status else RelayEndpointStatus.Failed
                }
                val anyConnected = _perRelayState.value.values.any { it == RelayEndpointStatus.Connected }
                if (anyConnected) {
                    // Some relays are working — don't kill the whole subscription
                    Log.d(TAG, "Subscription error but ${_perRelayState.value.values.count { it == RelayEndpointStatus.Connected }} relays still connected; staying Subscribed")
                    _connectionError.value = null
                } else {
                    _connectionError.value = ConnectionError(e.message, false)
                    stateMachine.transition(RelayEvent.ConnectFailed(e.message))
                }
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    /** Last relay set and kind-1 filter used for subscription; preserved so Topics does not overwrite Following mode. */
    @Volatile private var currentSubscriptionRelayUrls: List<String> = emptyList()
    @Volatile private var currentKind1Filter: Filter? = null

    /** Relay URLs that should be connected first (no jitter/cooldown). Typically outbox relays. */
    @Volatile private var priorityRelayUrls: Set<String> = emptySet()

    /** Relay URLs that should stay connected persistently (inbox, outbox, notification relays).
     *  These are NOT disconnected when they become idle (no active subs) because the app will
     *  reuse them shortly for notifications, NIP-65, profile fetches, etc. Avoids churn. */
    @Volatile private var persistentRelayUrls: Set<String> = emptySet()

    /** Set which relay URLs should stay connected persistently (not disconnected when idle).
     *  Call this with the user's inbox + outbox + category relay URLs. */
    fun setPersistentRelayUrls(urls: Set<String>) {
        persistentRelayUrls = urls
        Log.d(TAG, "Persistent relay URLs set: ${urls.size} relays")
    }

    /** Set which relay URLs should be prioritized for connection (connect first, clear cooldown).
     *  Call this with the user's outbox relay URLs so they connect before category relays. */
    fun setPriorityRelayUrls(urls: Set<String>) {
        priorityRelayUrls = urls
    }

    // --- Keepalive health check ---
    /** Timestamp of last event received from any relay. Used by keepalive to detect stale connections. */
    @Volatile private var lastEventReceivedAt: Long = System.currentTimeMillis()
    private var keepaliveJob: kotlinx.coroutines.Job? = null
    private val KEEPALIVE_CHECK_INTERVAL_MS = 120_000L   // Check every 2 minutes
    private val STALE_FALLBACK_THRESHOLD_MS = 1_800_000L // Last-resort: full reconnect if no events in 30 minutes

    /** Call when any event is received to reset the keepalive timer. */
    fun markEventReceived() {
        lastEventReceivedAt = System.currentTimeMillis()
    }

    /**
     * Start periodic keepalive that monitors actual WebSocket connection state.
     *
     * Strategy:
     * 1. Every 2 min: check which subscription relays have dead sockets → reconnect only those.
     *    Ktor CIO's 30s WebSocket ping/pong already detects most dead sockets, but this catches
     *    any that slipped through (e.g. pool-level reconnect attempts exhausted).
     * 2. Every 30 min (no events at all): full reconnect as last-resort fallback for half-open
     *    sockets that ping didn't detect.
     *
     * This avoids the old behavior of tearing down all connections every 5 minutes
     * just because nobody posted (e.g. overnight), which wasted battery and relay goodwill.
     */
    fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (true) {
                delay(KEEPALIVE_CHECK_INTERVAL_MS)
                val currentState = _state.value
                val hasRelays = currentSubscriptionRelayUrls.isNotEmpty()
                if (!hasRelays || currentState !is RelayState.Subscribed) continue

                // --- Check 1: Are any subscription relays disconnected? ---
                val disconnected = relayPool.getDisconnectedSubscriptionRelays()
                val connectedCount = relayPool.getConnectedCount()
                if (disconnected.isNotEmpty()) {
                    Log.d(TAG, "Keepalive: $connectedCount connected, ${disconnected.size} disconnected with active subs — reconnecting dead relays")
                    // Clear reconnect state for dead relays so they get a fresh attempt
                    relayPool.clearReconnectState()
                    relayPool.connect()
                    continue
                }

                // --- Check 2: Last-resort full reconnect if no events for a long time ---
                val elapsed = System.currentTimeMillis() - lastEventReceivedAt
                if (elapsed > STALE_FALLBACK_THRESHOLD_MS) {
                    Log.w(TAG, "Keepalive: no events in ${elapsed / 1000}s with $connectedCount connected relays — forcing full reconnect (possible half-open sockets)")
                    lastEventReceivedAt = System.currentTimeMillis()
                    requestReconnectOnResume()
                } else {
                    Log.d(TAG, "Keepalive: $connectedCount relays connected, last event ${elapsed / 1000}s ago — OK")
                }
            }
        }
    }

    /** Stop the keepalive health check (e.g. when user logs out). */
    fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /** Current kind-1 filter (e.g. authors for Following). Use when setting subscription from Topics to preserve feed mode. */
    fun getCurrentKind1Filter(): Filter? = currentKind1Filter

    @Volatile private var onKind1WithRelay: ((Event, String) -> Unit)? = null
    @Volatile private var onKind6WithRelay: ((Event, String) -> Unit)? = null
    @Volatile private var onKind11: ((Event, String) -> Unit)? = null
    @Volatile private var onKind1011: ((Event) -> Unit)? = null
    @Volatile private var onKind30073: ((Event) -> Unit)? = null
    @Volatile private var onKind30311: ((Event, String) -> Unit)? = null

    fun registerKind1Handler(handler: (Event, String) -> Unit) {
        onKind1WithRelay = handler
    }

    fun registerKind6Handler(handler: (Event, String) -> Unit) {
        onKind6WithRelay = handler
    }

    fun registerKind11Handler(handler: (Event, String) -> Unit) {
        onKind11 = handler
    }

    fun registerKind1011Handler(handler: (Event) -> Unit) {
        onKind1011 = handler
    }

    fun registerKind30073Handler(handler: (Event) -> Unit) {
        onKind30073 = handler
    }

    fun registerKind30311Handler(handler: (Event, String) -> Unit) {
        onKind30311 = handler
    }

    /**
     * Send a signed event to the specified relays.
     */
    fun send(event: Event, relayUrls: Set<String>) {
        relayPool.send(event, relayUrls)
    }

    /** Return a snapshot of per-relay slot utilization for UI dashboards. */
    fun getRelaySlotSnapshots(): List<com.example.cybin.relay.RelaySlotSnapshot> =
        relayPool.getRelaySlotSnapshots()

    /** Dump relay slot usage to logcat for diagnostics. */
    fun dumpRelaySlots(label: String = "") {
        relayPool.dumpRelaySlots(label)
    }

    private fun executeDisconnect() {
        scope.launch {
            try {
                currentSubId?.let { relayPool.closeSubscription(it) }
                currentSubId = null
                mainFeedSubscription?.close()
                mainFeedSubscription = null
                currentSubscriptionRelayUrls = emptyList()
                currentKind1Filter = null
                _currentSubscription.value = CurrentSubscription(emptyList(), null)
                _perRelayState.value = emptyMap()
                relayPool.disconnect()
                Log.d(TAG, "Disconnected from all relays")
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed: ${e.message}", e)
            }
        }
    }

    /** True when the state machine is in a state where subscriptions are active (Connected or Subscribed). */
    fun isSubscriptionActive(): Boolean {
        val state = _state.value
        return state is RelayState.Connected || state is RelayState.Subscribed
    }

    /** Request connection to relays (moves to Connecting then Connected). */
    fun requestConnect(relayUrls: List<String>) {
        stateMachine.transition(RelayEvent.ConnectRequested(relayUrls))
    }

    /** Request feed/subscription with combined kind-1 + kind-11; dispatches to registered handlers. */
    fun requestFeedChange(relayUrls: List<String>) {
        requestFeedChange(relayUrls, null)
    }

    /**
     * Request feed with optional kind-1 filter (e.g. authors=followList for Following).
     * Uses relay-aware subscription so note.relayUrl is set. Pass null for global feed.
     * Skips transition if subscription is unchanged (idempotent).
     */
    fun requestFeedChange(relayUrls: List<String>, kind1Filter: Filter?) {
        if (relayUrls.isEmpty()) return
        val cur = currentSubscriptionRelayUrls
        if (cur.sorted() == relayUrls.sorted() && kind1FiltersEqual(kind1Filter, currentKind1Filter) && isSubscriptionActive()) {
            Log.d(TAG, "Subscription unchanged (${relayUrls.size} relays) and active, skipping")
            return
        }
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, null, null, kind1Filter))
    }

    /**
     * Compares kind-1 feed intent for idempotence. We intentionally do not compare [Filter.since]:
     * returning to Home rebuilds the filter with a new sevenDaysAgo, which would otherwise force
     * a reconnect every time. Same authors + limit = same feed; no need to tear down the subscription.
     */
    private fun kind1FiltersEqual(a: Filter?, b: Filter?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.authors == b.authors && a.limit == b.limit
    }

    /** Request feed with custom filter (e.g. author notes); replaces combined subscription while active. */
    fun requestFeedChange(relayUrls: List<String>, filter: Filter, onEvent: (Event) -> Unit) {
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, filter, onEvent, null))
    }

    /** Request full disconnect (e.g. on screen exit or app shutdown). */
    fun requestDisconnect() {
        stateMachine.transition(RelayEvent.DisconnectRequested)
    }

    /**
     * Full teardown for account switch / logout. Disconnects all relays, clears per-relay
     * state, clears NIP-42 auth status, and resets the NIP-65 repository so the old user's
     * relay connections don't bleed into the new user's session.
     */
    fun disconnectAndClearForAccountSwitch() {
        Log.d(TAG, "Account switch: disconnecting all relays and clearing state")
        stateMachine.transition(RelayEvent.DisconnectRequested)
        // Clear resume provider so requestReconnectOnResume() won't race with stale relay state.
        // The new account's ensureSubscriptionToNotes() will re-register it.
        resumeSubscriptionProvider = null
        currentSubscriptionRelayUrls = emptyList()
        _currentSubscription.value = CurrentSubscription(emptyList(), null)
        // Clear NIP-42 auth tracking immediately (signer will be re-set by caller)
        nip42AuthHandler.setSigner(null)
        // Clear NIP-65 so stale relay lists from the previous user don't persist
        social.mycelium.android.repository.Nip65RelayListRepository.clear()
        // Clear feed notes so the old account's notes don't leak into the new account's UI
        social.mycelium.android.repository.NotesRepository.getInstance().clearNotes()
        // Clear notification subscription handle so it can be re-created after reconnect
        social.mycelium.android.repository.NotificationsRepository.stopSubscription()
        // Clear all multiplexed subscriptions (dedup state, ref-counts, merged subs)
        mux.clear()
    }

    /** User- or pull-to-refresh–triggered retry when in ConnectFailed. Resets backoff and sends RetryRequested. */
    fun requestRetry() {
        if (_state.value is RelayState.ConnectFailed && pendingRelayUrlsForRetry.isNotEmpty()) {
            retryAttempt = 0
            stateMachine.transition(RelayEvent.RetryRequested)
        }
    }

    /**
     * When set, used on resume to get (relayUrls, kind1Filter) from the source of truth (e.g. NotesRepository)
     * so the Following filter is never lost. If null, falls back to stored _currentSubscription.
     */
    @Volatile
    var resumeSubscriptionProvider: (() -> Pair<List<String>, Filter?>)? = null

    /**
     * Re-apply current subscription when app is resumed (e.g. after screen lock or switching apps).
     * Uses resumeSubscriptionProvider when set so the Following filter always comes from the repo (no bleed).
     * Bypasses idempotent check so connection and note aggregation resume even if params are unchanged.
     */
    /**
     * Disconnect all relay WebSockets and pause subscriptions.
     * Used by [ConnectionMode.WHEN_ACTIVE] when the app goes to background.
     */
    fun disconnectAll() {
        Log.d(TAG, "disconnectAll: closing all relay connections (When Active mode)")
        stopKeepalive()
        relayPool.disconnect()
    }

    fun requestReconnectOnResume() {
        // Release any auto-blocks whose cooldown has expired before we decide what to do
        RelayHealthTracker.releaseExpiredAutoBlocks()

        val cur = _currentSubscription.value
        val (relayUrls, kind1Filter) = resumeSubscriptionProvider?.invoke()?.takeIf { it.first.isNotEmpty() }
            ?: (cur.relayUrls to cur.kind1Filter)
        if (relayUrls.isEmpty()) return

        // If already Subscribed and the healthy relay set hasn't changed, don't tear
        // down the subscription — just poke dead sockets back to life via the pool.
        // This avoids the "Connecting to relays…" flash on every resume when a relay
        // is down/blocked but the rest are fine.
        val currentState = _state.value
        val effectiveUrls = RelayHealthTracker.filterBlocked(relayUrls).sorted()
        val activeUrls = currentSubscriptionRelayUrls.sorted()
        if (currentState is RelayState.Subscribed && effectiveUrls == activeUrls) {
            val disconnected = relayPool.getDisconnectedSubscriptionRelays()
            if (disconnected.isNotEmpty()) {
                Log.d(TAG, "App resumed: subscription unchanged, reconnecting ${disconnected.size} dead relays")
                relayPool.clearReconnectState()
                relayPool.connect()
            } else {
                Log.d(TAG, "App resumed: subscription active, ${activeUrls.size} relays healthy — no-op")
            }
            return
        }

        // Relay set changed (e.g. auto-block expired, user added relay) — full resubscribe
        // Reset retry counter so that if this reconnect fails, the state machine
        // can retry from scratch instead of being stuck at MAX_RETRIES from an
        // earlier offline period.
        retryAttempt = 0
        Log.d(TAG, "App resumed: re-applying subscription to ${relayUrls.size} relays (${effectiveUrls.size} healthy, following=${kind1Filter != null})")
        stateMachine.transition(RelayEvent.FeedChangeRequested(relayUrls, null, null, kind1Filter))
    }

    /**
     * Filter out relays that require payment or NIP-42 auth (when no signer is available)
     * based on cached NIP-11 relay information. Relays we haven't checked yet pass through.
     */
    private fun filterUsableRelays(relayUrls: List<String>): List<String> {
        val cache = social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
            ?: return relayUrls // Not initialized yet — let everything through
        return relayUrls.filter { !cache.shouldSkipRelay(it) }
    }

    // ── Temporary subscriptions (all routed through SubscriptionMultiplexer) ──────
    //
    // Every temporary subscription now goes through the multiplexer for:
    // • Global event deduplication across all subscriptions
    // • Ref-counted subscription lifecycle (identical filters share one relay sub)
    // • Centralized clear() on account switch
    // • Debounced REQ batching (50ms)

    private val mux: SubscriptionMultiplexer get() = SubscriptionMultiplexer.getInstance()

    /**
     * One-off subscription using the shared relay pool. Call handle.cancel() when done.
     */
    fun requestTemporarySubscription(
        relayUrls: List<String>,
        filter: Filter,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeCallback(usable, listOf(filter), priority, onEvent)
    }

    /**
     * One-off subscription with multiple filters (e.g. kind-7 + kind-9735 for counts).
     * Optional [onEose] fires once when all relays have sent EOSE for this subscription.
     */
    fun requestTemporarySubscription(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEose: (() -> Unit)? = null,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty() || filters.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeCallback(usable, filters, priority, onEvent, onEose)
    }

    /**
     * One-off subscription that passes the source relay URL to the callback.
     */
    fun requestTemporarySubscriptionWithRelay(
        relayUrls: List<String>,
        filter: Filter,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeCallbackWithRelay(usable, listOf(filter), priority, onEvent)
    }

    /**
     * One-off subscription with multiple filters that passes the source relay URL to the callback.
     */
    fun requestTemporarySubscriptionWithRelay(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty() || filters.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeCallbackWithRelay(usable, filters, priority, onEvent)
    }

    /**
     * One-off subscription with per-relay filter maps (outbox model).
     */
    fun requestTemporarySubscriptionPerRelay(
        relayFilters: Map<String, List<Filter>>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        val filtered = relayFilters.filterKeys { filterUsableRelays(listOf(it)).isNotEmpty() }
        if (filtered.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribePerRelayCallback(filtered, priority, onEvent)
    }

    /**
     * Per-relay filter map subscription with relay URL passthrough.
     */
    fun requestTemporarySubscriptionPerRelayWithRelay(
        relayFilters: Map<String, List<Filter>>,
        priority: SubscriptionPriority = SubscriptionPriority.NORMAL,
        onEvent: (Event, String) -> Unit,
    ): TemporarySubscriptionHandle {
        val filtered = relayFilters.filterKeys { filterUsableRelays(listOf(it)).isNotEmpty() }
        if (filtered.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribePerRelayCallbackWithRelay(filtered, priority, onEvent)
    }

    /**
     * EOSE-based one-shot subscription: opens REQ, collects events until all relays send EOSE,
     * then auto-CLOSEs after a short settle window. **No timers, no slot leaks.**
     */
    fun requestOneShotSubscription(
        relayUrls: List<String>,
        filter: Filter,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeOneShotCallback(usable, listOf(filter), priority, settleMs, maxWaitMs, onEvent)
    }

    /**
     * EOSE-based one-shot with multiple filters.
     */
    fun requestOneShotSubscription(
        relayUrls: List<String>,
        filters: List<Filter>,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event) -> Unit,
    ): TemporarySubscriptionHandle {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty() || filters.isEmpty()) return NoOpTemporaryHandle
        return mux.subscribeOneShotCallback(usable, filters, priority, settleMs, maxWaitMs, onEvent)
    }

    /**
     * Suspending one-shot: opens REQ, collects events via [onEvent], suspends until
     * all relays EOSE (+ settle) or hard timeout, then auto-CLOSEs and returns.
     * Unlike requestOneShotSubscription, the caller awaits completion — no delay needed.
     */
    suspend fun awaitOneShotSubscription(
        relayUrls: List<String>,
        filter: Filter,
        priority: SubscriptionPriority = SubscriptionPriority.LOW,
        settleMs: Long = 500L,
        maxWaitMs: Long = 8_000L,
        onEvent: (Event) -> Unit,
    ) {
        val usable = filterUsableRelays(relayUrls)
        if (usable.isEmpty()) return
        mux.awaitOneShotSubscription(usable, listOf(filter), priority, settleMs, maxWaitMs, onEvent)
    }

    companion object {
        private const val TAG = "RelayConnectionStateMachine"
        /** Default kind-1 limit for global feed; higher = more notes, slightly slower first load. */
        private const val GLOBAL_FEED_LIMIT = 800
        private const val RETRY_DELAY_MS_FIRST = 2_000L
        private const val RETRY_DELAY_MS_SECOND = 5_000L
        private const val MAX_RETRIES = 3

        /** Single shared instance for the app so all feed screens use one NostrClient. */
        @Volatile
        private var instance: RelayConnectionStateMachine? = null
        fun getInstance(): RelayConnectionStateMachine =
            instance ?: synchronized(this) { instance ?: RelayConnectionStateMachine().also { instance = it } }
    }
}
