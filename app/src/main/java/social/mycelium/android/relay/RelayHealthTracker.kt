package social.mycelium.android.relay

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-relay health snapshot exposed to UI and decision-making code.
 */
data class RelayHealthInfo(
    val url: String,
    /** Total connection attempts (successful + failed). */
    val connectionAttempts: Int = 0,
    /** Total connection failures (timeout, refused, error). */
    val connectionFailures: Int = 0,
    /** Consecutive failures without a successful connection in between. */
    val consecutiveFailures: Int = 0,
    /** Total events received from this relay across all subscriptions. */
    val eventsReceived: Long = 0,
    /** Average WebSocket handshake time in ms (rolling window of last 10).
     *  This is NOT message round-trip time — it includes DNS, TCP, TLS, and WS upgrade. */
    val connectTimeMs: Long = 0,
    /** Last successful connection timestamp (epoch ms), 0 if never. */
    val lastConnectedAt: Long = 0,
    /** Last failure timestamp (epoch ms), 0 if never. */
    val lastFailedAt: Long = 0,
    /** Last event received timestamp (epoch ms), 0 if never. */
    val lastEventAt: Long = 0,
    /** First time this relay was seen (epoch ms). */
    val firstSeenAt: Long = 0,
    /** Last error message, null if last attempt succeeded. */
    val lastError: String? = null,
    /** True when consecutive failures exceed threshold — relay is unreliable. */
    val isFlagged: Boolean = false,
    /** True when user has explicitly blocked this relay. */
    val isBlocked: Boolean = false
) {
    val failureRate: Float
        get() = if (connectionAttempts > 0) connectionFailures.toFloat() / connectionAttempts else 0f
    /** Uptime ratio: successful connections / total attempts. */
    val uptimeRatio: Float
        get() = if (connectionAttempts > 0) 1f - failureRate else 0f
}

/**
 * Singleton that tracks per-relay health metrics across all connection paths
 * (RelayConnectionStateMachine, Kind1RepliesRepository direct WS, profile fetches, etc.).
 *
 * ## Flagging
 * A relay is flagged when it accumulates [FLAG_CONSECUTIVE_FAILURES] consecutive failures
 * without a successful connection in between. Flagged relays surface a warning in the UI
 * so the user can review and optionally block them.
 *
 * ## Blocking
 * Blocked relays are persisted via SharedPreferences and excluded from all connection
 * attempts. All connection paths should call [isBlocked] before opening a WebSocket.
 *
 * ## Integration points
 * - `RelayConnectionStateMachine.IRelayClientListener` → onConnected / onCannotConnect
 * - `Kind1RepliesRepository` direct WebSocket → onOpen / onFailure
 * - `ProfileMetadataCache` profile fetches → success / failure
 * - Any future direct WebSocket usage
 */
object RelayHealthTracker {

    private const val TAG = "RelayHealthTracker"

    /** Consecutive failures before a relay is flagged (warning state). */
    private const val FLAG_CONSECUTIVE_FAILURES = 3

    /** Consecutive failures before a relay is auto-blocked (must be > FLAG threshold). */
    private const val AUTO_BLOCK_CONSECUTIVE_FAILURES = 8

    /** Duration of auto-block cooldown (6 hours). */
    private const val AUTO_BLOCK_DURATION_MS = 6 * 60 * 60 * 1000L

    /** Max connect-time samples to keep per relay for rolling average. */
    private const val MAX_CONNECT_TIME_SAMPLES = 10

    /** SharedPreferences key for blocked relay list. */
    private const val PREFS_NAME = "relay_health"
    private const val KEY_BLOCKED_RELAYS = "blocked_relays"
    private const val KEY_AUTO_BLOCK_EXPIRY = "auto_block_expiry"

    private val json = Json { ignoreUnknownKeys = true }

    // --- Internal mutable state ---

    /** Per-relay mutable health data (not exposed directly). */
    private data class MutableHealthData(
        var connectionAttempts: Int = 0,
        var connectionFailures: Int = 0,
        var consecutiveFailures: Int = 0,
        var eventsReceived: Long = 0,
        var connectTimeSamples: MutableList<Long> = mutableListOf(),
        var lastConnectedAt: Long = 0,
        var lastFailedAt: Long = 0,
        var lastEventAt: Long = 0,
        var firstSeenAt: Long = 0,
        var lastError: String? = null,
        var isFlagged: Boolean = false,
        var isBlocked: Boolean = false,
        /** Timestamp when connection attempt started (for connect-time measurement). */
        var connectStartedAt: Long = 0
    )

    private val healthData = mutableMapOf<String, MutableHealthData>()
    private val lock = Any()

    /** Auto-block expiry timestamps: relay URL → epoch ms when auto-block expires. */
    private val autoBlockExpiry = mutableMapOf<String, Long>()

    /** Observable map of auto-block expiry times for UI display. */
    private val _autoBlockExpiryMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val autoBlockExpiryMap: StateFlow<Map<String, Long>> = _autoBlockExpiryMap.asStateFlow()

    // --- Public observable state ---

    private val _healthByRelay = MutableStateFlow<Map<String, RelayHealthInfo>>(emptyMap())
    /** Per-relay health info, keyed by normalized URL. Observable by UI. */
    val healthByRelay: StateFlow<Map<String, RelayHealthInfo>> = _healthByRelay.asStateFlow()

    private val _flaggedRelays = MutableStateFlow<Set<String>>(emptySet())
    /** Set of relay URLs that are flagged as unreliable. */
    val flaggedRelays: StateFlow<Set<String>> = _flaggedRelays.asStateFlow()

    private val _blockedRelays = MutableStateFlow<Set<String>>(emptySet())
    /** Set of relay URLs that the user has explicitly blocked. */
    val blockedRelays: StateFlow<Set<String>> = _blockedRelays.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var connectivityManager: ConnectivityManager? = null

    /** Check if the device currently has validated internet connectivity. */
    private fun hasNetwork(): Boolean {
        val cm = connectivityManager ?: return true // assume online if not initialized
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // --- Publish failure tracking ---

    /** A single relay's response to a published event. */
    data class PublishRelayResult(
        val relayUrl: String,
        val success: Boolean,
        val message: String = ""
    )

    /** Summary of a publish attempt across all targeted relays. */
    data class PublishReport(
        val eventId: String,
        val kind: Int,
        val timestamp: Long = System.currentTimeMillis(),
        val targetRelayCount: Int,
        val results: List<PublishRelayResult> = emptyList()
    ) {
        val successCount get() = results.count { it.success }
        val failureCount get() = results.count { !it.success }
        val pendingCount get() = targetRelayCount - results.size
        val hasFailures get() = failureCount > 0
    }

    /** Pending publishes awaiting OK responses, keyed by eventId. */
    private data class PendingPublish(
        val kind: Int,
        val targetRelays: Set<String>,
        val results: MutableList<PublishRelayResult> = mutableListOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val pendingPublishes = mutableMapOf<String, PendingPublish>()

    /** LRU cache of signed events for retry capability. Keyed by eventId, capped at 50. */
    private val publishedEventCache = object : LinkedHashMap<String, Event>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Event>?) = size > 50
    }

    private val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val RETRY_OK_TIMEOUT_MS = 10_000L

    /** Recent publish reports (last 50). Observable by UI. */
    private val _publishReports = MutableStateFlow<List<PublishReport>>(emptyList())
    val publishReports: StateFlow<List<PublishReport>> = _publishReports.asStateFlow()

    /** One-shot flow for publish failure notifications (UI shows snackbar). */
    private val _publishFailure = MutableSharedFlow<PublishReport>(extraBufferCapacity = 8)
    val publishFailure: SharedFlow<PublishReport> = _publishFailure.asSharedFlow()

    /** Broadcast: emits (eventId, relayUrl) when a relay confirms a published event.
     *  Thread reply repos observe this to update relay orbs in real-time. */
    data class PublishRelayConfirmation(val eventId: String, val relayUrl: String)
    private val _publishRelayConfirmed = MutableSharedFlow<PublishRelayConfirmation>(extraBufferCapacity = 32)
    val publishRelayConfirmed: SharedFlow<PublishRelayConfirmation> = _publishRelayConfirmed.asSharedFlow()

    /**
     * Register a pending publish so we can track OK responses from relays.
     * Call immediately after sending the event to relays.
     */
    fun registerPendingPublish(eventId: String, kind: Int, relayUrls: Set<String>) {
        synchronized(lock) {
            pendingPublishes[eventId] = PendingPublish(kind, relayUrls)
        }
        MLog.d(TAG, "Registered pending publish ${eventId.take(8)} kind-$kind → ${relayUrls.size} relays")
    }

    /**
     * Store a signed event so it can be retried later if relays fail.
     * Call immediately after signing (before send).
     */
    fun storePublishedEvent(eventId: String, event: Event) {
        synchronized(lock) {
            publishedEventCache[eventId] = event
        }
    }

    /**
     * Retry publishing a previously signed event to specific relay URLs.
     * Returns true if the event was found in cache and re-sent, false otherwise.
     */
    fun retryPublish(eventId: String, relayUrls: Set<String>): Boolean {
        val event: Event
        val kind: Int
        synchronized(lock) {
            event = publishedEventCache[eventId] ?: return false
            kind = event.kind
        }
        if (relayUrls.isEmpty()) return false

        // Re-send to the specified relays
        try {
            val rcsm = RelayConnectionStateMachine.getInstance()
            rcsm.send(event, relayUrls)
            rcsm.nip42AuthHandler.trackPublishedEvent(event, relayUrls)
            MLog.d(TAG, "Retry publish ${eventId.take(8)} kind-$kind → ${relayUrls.size} relays")

            // Register fresh tracking for the retry
            registerPendingPublish(eventId, kind, relayUrls)
            retryScope.launch {
                delay(RETRY_OK_TIMEOUT_MS)
                finalizePendingPublish(eventId)
            }
            return true
        } catch (e: Exception) {
            MLog.e(TAG, "Retry publish failed for ${eventId.take(8)}: ${e.message}", e)
            return false
        }
    }

    /** Check if a signed event is available for retry. */
    fun hasPublishedEvent(eventId: String): Boolean {
        return synchronized(lock) { eventId in publishedEventCache }
    }

    /**
     * Called when a relay responds with OK to a published event.
     * Tracks per-relay success/failure and finalizes the report when all relays respond
     * or when [finalizePendingPublish] is called after timeout.
     */
    fun recordPublishOk(relayUrl: String, eventId: String, success: Boolean, message: String) {
        val url = normalize(relayUrl)
        // Merge relay URL into feed note so relay orbs update in real-time
        if (success) {
            try {
                social.mycelium.android.repository.feed.NotesRepository.getInstance().mergePublishRelayUrl(eventId, url)
            } catch (_: Exception) { /* NotesRepository may not be initialized yet */ }
            // Broadcast so thread reply repositories can also update relay orbs
            _publishRelayConfirmed.tryEmit(PublishRelayConfirmation(eventId, url))
        }
        var report: PublishReport? = null
        synchronized(lock) {
            val pending = pendingPublishes[eventId] ?: return
            pending.results.add(PublishRelayResult(url, success, message))
            // If all relays responded, finalize
            if (pending.results.size >= pending.targetRelays.size) {
                report = finalizeReport(eventId, pending)
            }
        }
        report?.let { handleCompletedReport(it) }
    }

    /**
     * Finalize any pending publish after a timeout. Relays that haven't responded
     * are treated as failures with "No response (timeout)".
     */
    fun finalizePendingPublish(eventId: String) {
        var report: PublishReport? = null
        synchronized(lock) {
            val pending = pendingPublishes[eventId] ?: return
            // Add timeout entries for relays that didn't respond
            val respondedUrls = pending.results.map { it.relayUrl }.toSet()
            for (relay in pending.targetRelays) {
                val normalizedRelay = normalize(relay)
                if (normalizedRelay !in respondedUrls) {
                    pending.results.add(PublishRelayResult(normalizedRelay, false, "No response (timeout)"))
                }
            }
            report = finalizeReport(eventId, pending)
        }
        report?.let { handleCompletedReport(it) }
    }

    private fun finalizeReport(eventId: String, pending: PendingPublish): PublishReport {
        pendingPublishes.remove(eventId)
        return PublishReport(
            eventId = eventId,
            kind = pending.kind,
            timestamp = pending.createdAt,
            targetRelayCount = pending.targetRelays.size,
            results = pending.results.toList()
        )
    }

    private fun handleCompletedReport(report: PublishReport) {
        // Add to recent reports (keep last 50).
        // If a report with the same eventId already exists (retry), replace it
        // instead of adding a duplicate — duplicate keys crash LazyColumn.
        val current = _publishReports.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.eventId == report.eventId }
        if (existingIndex >= 0) {
            // Merge: combine old + new results, keeping the latest per relay
            val oldReport = current[existingIndex]
            val mergedResults = (report.results + oldReport.results)
                .groupBy { it.relayUrl }
                .map { (_, results) -> results.maxByOrNull { if (it.success) 1 else 0 } ?: results.first() }
            val merged = report.copy(
                targetRelayCount = mergedResults.size,
                results = mergedResults
            )
            current[existingIndex] = merged
        } else {
            current.add(0, report)
        }
        if (current.size > 50) current.subList(50, current.size).clear()
        _publishReports.value = current

        if (report.hasFailures) {
            _publishFailure.tryEmit(report)
            MLog.w(TAG, "Publish ${report.eventId.take(8)} kind-${report.kind}: " +
                "${report.successCount}/${report.targetRelayCount} OK, ${report.failureCount} failed")
            // Record failures in per-relay health
            report.results.filter { !it.success }.forEach { result ->
                recordPublishFailure(result.relayUrl, result.message)
            }
        } else {
            MLog.d(TAG, "Publish ${report.eventId.take(8)} kind-${report.kind}: " +
                "${report.successCount}/${report.targetRelayCount} OK")
        }
    }

    /** Record a publish failure for a specific relay (updates health metrics). */
    private fun recordPublishFailure(relayUrl: String, error: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.lastError = "Publish failed: $error"
            data.lastFailedAt = System.currentTimeMillis()
        }
        emitState()
    }

    // --- Initialization ---

    /**
     * Initialize with Context to load persisted blocklist. Call once from Application/MainActivity.
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        loadBlockedRelays()
    }

    // --- Recording events ---

    /** Per-relay span IDs for connect attempt tracking. */
    private val connectSpans = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Call when a connection attempt starts (for connect-time tracking). */
    fun recordConnectionAttempt(relayUrl: String) {
        val url = normalize(relayUrl)
        var attempt = 0
        synchronized(lock) {
            val data = getOrCreate(url)
            data.connectionAttempts++
            attempt = data.connectionAttempts
            val now = System.currentTimeMillis()
            data.connectStartedAt = now
            if (data.firstSeenAt == 0L) data.firstSeenAt = now
        }
        emitState()
        RelayLogBuffer.logConnecting(url)
        val sid = social.mycelium.android.debug.EventLog.startRelayConnect(url, attempt)
        connectSpans[url] = sid
    }

    /** Call when a connection succeeds. */
    fun recordConnectionSuccess(relayUrl: String) {
        val url = normalize(relayUrl)
        var latencyMs = 0L
        var wasUnflagged = false
        synchronized(lock) {
            val data = getOrCreate(url)
            data.consecutiveFailures = 0
            data.lastConnectedAt = System.currentTimeMillis()
            data.lastError = null
            // WS handshake time (NOT RTT — includes DNS, TCP, TLS, WS upgrade)
            if (data.connectStartedAt > 0) {
                latencyMs = System.currentTimeMillis() - data.connectStartedAt
                data.connectTimeSamples.add(latencyMs)
                if (data.connectTimeSamples.size > MAX_CONNECT_TIME_SAMPLES) {
                    data.connectTimeSamples.removeAt(0)
                }
                data.connectStartedAt = 0
            }
            // Unflag if it was flagged and now succeeds
            if (data.isFlagged) {
                data.isFlagged = false
                wasUnflagged = true
                MLog.i(TAG, "Relay $url unflagged after successful connection")
            }
        }
        emitState()
        RelayLogBuffer.logConnected(url)
        val sid = connectSpans.remove(url)
        social.mycelium.android.debug.EventLog.endRelayConnect(url, sid ?: "", latencyMs)
        if (wasUnflagged) {
            social.mycelium.android.debug.EventLog.emit(
                social.mycelium.android.debug.LogEvents.RELAY_UNFLAGGED,
                "RELAY", TAG, data = mapOf("url" to url)
            )
        }
    }

    /** Call when a connection fails. */
    fun recordConnectionFailure(relayUrl: String, error: String?) {
        val url = normalize(relayUrl)
        val deviceOnline = hasNetwork()
        var nowFlagged = false
        var nowAutoBlocked = false
        synchronized(lock) {
            val data = getOrCreate(url)
            data.connectionFailures++
            data.lastFailedAt = System.currentTimeMillis()
            data.lastError = error
            data.connectStartedAt = 0
            // Only count consecutive failures when device has network.
            // Offline failures are the device's fault, not the relay's.
            if (deviceOnline) {
                data.consecutiveFailures++
                // Flag check
                if (!data.isFlagged && data.consecutiveFailures >= FLAG_CONSECUTIVE_FAILURES) {
                    data.isFlagged = true
                    nowFlagged = true
                }
                // Auto-block: block after threshold consecutive failures (with timed cooldown)
                if (!data.isBlocked && data.consecutiveFailures >= AUTO_BLOCK_CONSECUTIVE_FAILURES) {
                    data.isBlocked = true
                    nowAutoBlocked = true
                    val expiry = System.currentTimeMillis() + AUTO_BLOCK_DURATION_MS
                    autoBlockExpiry[url] = expiry
                }
            } else {
                MLog.d(TAG, "Relay $url failure while OFFLINE — not counting toward consecutive failures")
            }
        }
        connectSpans.remove(url)  // discard span — connection never succeeded
        social.mycelium.android.debug.EventLog.emit(
            social.mycelium.android.debug.LogEvents.RELAY_FAILED,
            "RELAY", TAG, data = mapOf(
                "url" to url,
                "error" to (error ?: "unknown"),
                "consecutive_failures" to synchronized(lock) { healthData[url]?.consecutiveFailures ?: 0 },
                "online" to deviceOnline,
            )
        )
        if (nowFlagged) {
            MLog.w(TAG, "Relay $url FLAGGED after $FLAG_CONSECUTIVE_FAILURES consecutive failures: $error")
            social.mycelium.android.debug.EventLog.emit(
                social.mycelium.android.debug.LogEvents.RELAY_FLAGGED,
                "RELAY", TAG, data = mapOf(
                    "url" to url,
                    "consecutive_failures" to FLAG_CONSECUTIVE_FAILURES,
                )
            )
        }
        if (nowAutoBlocked) {
            _blockedRelays.value = _blockedRelays.value + url
            _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
            persistBlockedRelays()
            persistAutoBlockExpiry()
            MLog.w(TAG, "Relay $url AUTO-BLOCKED for ${AUTO_BLOCK_DURATION_MS / 3600000}h after $AUTO_BLOCK_CONSECUTIVE_FAILURES consecutive failures")
            social.mycelium.android.debug.EventLog.emit(
                social.mycelium.android.debug.LogEvents.RELAY_BLOCKED,
                "RELAY", TAG, data = mapOf(
                    "url" to url,
                    "consecutive_failures" to AUTO_BLOCK_CONSECUTIVE_FAILURES,
                    "duration_hours" to (AUTO_BLOCK_DURATION_MS / 3600000).toInt(),
                )
            )
            // Cancel any pending reconnect and ensure socket stays closed
            try {
                RelayConnectionStateMachine.getInstance().relayPool.disconnectRelay(url)
            } catch (e: Exception) {
                MLog.w(TAG, "Failed to cancel reconnect for auto-blocked relay: ${e.message}")
            }
        }
        emitState()
        RelayLogBuffer.logError(url, error ?: "Connection failed")
    }

    /** Debounce job for batching event-count emissions (avoids thousands of StateFlow updates). */
    private val eventFlushScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var eventFlushScheduled = false

    /** Call when an event is received from a relay (any kind). */
    fun recordEventReceived(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.eventsReceived++
            data.lastEventAt = System.currentTimeMillis()
        }
        // Debounced flush: schedule a single delayed emit that coalesces rapid event bursts
        if (!eventFlushScheduled) {
            eventFlushScheduled = true
            eventFlushScope.launch {
                delay(2_000) // emit at most every 2 seconds
                eventFlushScheduled = false
                emitState()
            }
        }
    }

    /** Batch-emit after a burst of events (call periodically or after EOSE). */
    fun flushEventCounts() {
        emitState()
    }

    // --- Blocking ---

    /** Check if a relay is blocked. All connection paths should check this before connecting. */
    fun isBlocked(relayUrl: String): Boolean {
        return _blockedRelays.value.contains(normalize(relayUrl))
    }

    /** Block a relay manually (no expiry). Persisted immediately.
     *  Immediately disconnects any open socket to this relay. */
    fun blockRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            getOrCreate(url).isBlocked = true
            autoBlockExpiry.remove(url) // manual block has no expiry
        }
        _blockedRelays.value = _blockedRelays.value + url
        _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
        persistBlockedRelays()
        persistAutoBlockExpiry()
        emitState()
        MLog.i(TAG, "Relay BLOCKED (manual): $url")
        // Immediately disconnect any open socket to this relay
        try {
            RelayConnectionStateMachine.getInstance().relayPool.disconnectRelay(url)
        } catch (e: Exception) {
            MLog.w(TAG, "Failed to disconnect blocked relay: ${e.message}")
        }
    }

    /** Unblock a relay. Persisted immediately. Triggers reconnect + resubscribe
     *  so the relay immediately rejoins the feed. */
    fun unblockRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            getOrCreate(url).isBlocked = false
            getOrCreate(url).isFlagged = false
            getOrCreate(url).consecutiveFailures = 0
            autoBlockExpiry.remove(url)
        }
        _blockedRelays.value = _blockedRelays.value - url
        _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
        persistBlockedRelays()
        persistAutoBlockExpiry()
        emitState()
        MLog.i(TAG, "Relay UNBLOCKED: $url — triggering reconnect")
        social.mycelium.android.debug.EventLog.emit(
            social.mycelium.android.debug.LogEvents.RELAY_UNBLOCKED,
            "RELAY", TAG, data = mapOf("url" to url, "reason" to "manual")
        )
        // Clear pool-level blacklist + reconnect state for this relay, then
        // re-apply subscriptions so the relay immediately rejoins the feed.
        try {
            val rcsm = RelayConnectionStateMachine.getInstance()
            rcsm.relayPool.forceReconnect(url)
            rcsm.requestReconnectOnResume()
        } catch (e: Exception) {
            MLog.w(TAG, "Failed to trigger reconnect after unblock: ${e.message}")
        }
    }

    /**
     * Check and release any auto-blocked relays whose cooldown has expired.
     * Call periodically (e.g. every 60s from a LaunchedEffect on the health screen).
     */
    fun releaseExpiredAutoBlocks() {
        val now = System.currentTimeMillis()
        val expired = mutableListOf<String>()
        synchronized(lock) {
            val iter = autoBlockExpiry.iterator()
            while (iter.hasNext()) {
                val (url, expiry) = iter.next()
                if (now >= expiry) {
                    iter.remove()
                    val data = healthData[url]
                    if (data != null && data.isBlocked) {
                        data.isBlocked = false
                        data.isFlagged = false
                        data.consecutiveFailures = 0
                        expired.add(url)
                    }
                }
            }
        }
        if (expired.isNotEmpty()) {
            _blockedRelays.value = _blockedRelays.value - expired.toSet()
            _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
            persistBlockedRelays()
            persistAutoBlockExpiry()
            emitState()
            expired.forEach { url ->
                MLog.i(TAG, "Relay auto-block EXPIRED, unblocked: $url")
                social.mycelium.android.debug.EventLog.emit(
                    social.mycelium.android.debug.LogEvents.RELAY_UNBLOCKED,
                    "RELAY", TAG, data = mapOf("url" to url, "reason" to "expiry")
                )
            }
        }
    }

    /** Check if a relay is auto-blocked (vs manually blocked). */
    fun isAutoBlocked(relayUrl: String): Boolean {
        return autoBlockExpiry.containsKey(normalize(relayUrl))
    }

    /** Unflag a relay (user reviewed it and decided it's fine). */
    fun unflagRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.isFlagged = false
            data.consecutiveFailures = 0
        }
        emitState()
        MLog.i(TAG, "Relay manually unflagged: $url")
    }

    /**
     * Grant amnesty to all relays after the device regains network connectivity.
     * Resets consecutive failure counts, removes flags, and releases auto-blocks that
     * were caused by device-offline failures rather than genuine relay problems.
     * Call from NetworkConnectivityMonitor when network is restored after a loss.
     */
    fun grantOfflineAmnesty() {
        var releasedAutoBlocks = 0
        var unflaggedCount = 0
        synchronized(lock) {
            for ((url, data) in healthData) {
                if (data.consecutiveFailures > 0) {
                    data.consecutiveFailures = 0
                }
                if (data.isFlagged) {
                    data.isFlagged = false
                    unflaggedCount++
                }
                // Release auto-blocks (but not manual blocks)
                if (data.isBlocked && autoBlockExpiry.containsKey(url)) {
                    data.isBlocked = false
                    autoBlockExpiry.remove(url)
                    releasedAutoBlocks++
                }
            }
        }
        if (releasedAutoBlocks > 0 || unflaggedCount > 0) {
            _blockedRelays.value = synchronized(lock) {
                healthData.filter { it.value.isBlocked }.keys.toSet()
            }
            _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
            persistBlockedRelays()
            persistAutoBlockExpiry()
            emitState()
            MLog.i(TAG, "Offline amnesty: unflagged $unflaggedCount relays, released $releasedAutoBlocks auto-blocks")
            social.mycelium.android.debug.EventLog.emit(
                social.mycelium.android.debug.LogEvents.NETWORK_REGAINED,
                "RELAY", TAG, data = mapOf("flagged_count" to unflaggedCount, "auto_blocks_released" to releasedAutoBlocks)
            )
        } else {
            emitState()
            MLog.d(TAG, "Offline amnesty: no relays needed recovery")
        }
    }

    /** Reset all health data for a relay (e.g. after user reconfigures it). */
    fun resetRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            healthData.remove(url)
        }
        emitState()
    }

    /** Filter a list of relay URLs, removing blocked ones. Convenience for connection paths. */
    fun filterBlocked(relayUrls: List<String>): List<String> {
        val blocked = _blockedRelays.value
        return if (blocked.isEmpty()) relayUrls
        else relayUrls.filter { normalize(it) !in blocked }
    }

    // --- Snapshot for UI ---

    /** Get health info for a single relay. */
    fun getHealth(relayUrl: String): RelayHealthInfo? {
        val url = normalize(relayUrl)
        return synchronized(lock) { healthData[url]?.toInfo(url) }
    }

    /** Get all relay health data as a sorted list (flagged first, then by failure rate). */
    fun getAllHealthSorted(): List<RelayHealthInfo> {
        return synchronized(lock) {
            healthData.map { (url, data) -> data.toInfo(url) }
                .sortedWith(compareByDescending<RelayHealthInfo> { it.isFlagged || it.isBlocked }
                    .thenByDescending { it.consecutiveFailures }
                    .thenByDescending { it.failureRate })
        }
    }

    // --- Internal ---

    private fun normalize(url: String): String = url.trim().removeSuffix("/")

    private fun getOrCreate(url: String): MutableHealthData {
        return healthData.getOrPut(url) { MutableHealthData() }
    }

    private fun MutableHealthData.toInfo(url: String) = RelayHealthInfo(
        url = url,
        connectionAttempts = connectionAttempts,
        connectionFailures = connectionFailures,
        consecutiveFailures = consecutiveFailures,
        eventsReceived = eventsReceived,
        connectTimeMs = if (connectTimeSamples.isNotEmpty()) connectTimeSamples.average().toLong() else 0,
        lastConnectedAt = lastConnectedAt,
        lastFailedAt = lastFailedAt,
        lastEventAt = lastEventAt,
        firstSeenAt = firstSeenAt,
        lastError = lastError,
        isFlagged = isFlagged,
        isBlocked = isBlocked
    )

    private fun emitState() {
        synchronized(lock) {
            _healthByRelay.value = healthData.map { (url, data) -> url to data.toInfo(url) }.toMap()
            _flaggedRelays.value = healthData.filter { it.value.isFlagged }.keys.toSet()
        }
    }

    private fun loadBlockedRelays() {
        try {
            val raw = prefs?.getString(KEY_BLOCKED_RELAYS, null)
            if (raw != null) {
                val list = json.decodeFromString<List<String>>(raw)
                _blockedRelays.value = list.toSet()
                list.forEach { url ->
                    synchronized(lock) { getOrCreate(url).isBlocked = true }
                }
                MLog.d(TAG, "Loaded ${list.size} blocked relays")
            }
            // Load auto-block expiry times
            val expiryRaw = prefs?.getString(KEY_AUTO_BLOCK_EXPIRY, null)
            if (expiryRaw != null) {
                val map = json.decodeFromString<Map<String, Long>>(expiryRaw)
                autoBlockExpiry.putAll(map)
                _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
                MLog.d(TAG, "Loaded ${map.size} auto-block expiry entries")
            }
            // Release any already-expired auto-blocks on startup
            releaseExpiredAutoBlocks()
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to load blocked relays: ${e.message}", e)
        }
    }

    private fun persistBlockedRelays() {
        try {
            val list = _blockedRelays.value.toList()
            prefs?.edit()?.putString(KEY_BLOCKED_RELAYS, json.encodeToString(list))?.apply()
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to persist blocked relays: ${e.message}", e)
        }
    }

    private fun persistAutoBlockExpiry() {
        try {
            prefs?.edit()?.putString(KEY_AUTO_BLOCK_EXPIRY, json.encodeToString(autoBlockExpiry.toMap()))?.apply()
        } catch (e: Exception) {
            MLog.e(TAG, "Failed to persist auto-block expiry: ${e.message}", e)
        }
    }
}
