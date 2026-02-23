package social.mycelium.android.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
    /** Average connection latency in ms (rolling window of last 10). */
    val avgLatencyMs: Long = 0,
    /** Last successful connection timestamp (epoch ms), 0 if never. */
    val lastConnectedAt: Long = 0,
    /** Last failure timestamp (epoch ms), 0 if never. */
    val lastFailedAt: Long = 0,
    /** Last error message, null if last attempt succeeded. */
    val lastError: String? = null,
    /** True when consecutive failures exceed threshold — relay is unreliable. */
    val isFlagged: Boolean = false,
    /** True when user has explicitly blocked this relay. */
    val isBlocked: Boolean = false
) {
    val failureRate: Float
        get() = if (connectionAttempts > 0) connectionFailures.toFloat() / connectionAttempts else 0f
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

    /** Consecutive failures before a relay is flagged. */
    private const val FLAG_CONSECUTIVE_FAILURES = 5

    /** Consecutive failures before a relay is auto-blocked. */
    private const val AUTO_BLOCK_CONSECUTIVE_FAILURES = 5

    /** Duration of auto-block cooldown (6 hours). */
    private const val AUTO_BLOCK_DURATION_MS = 6 * 60 * 60 * 1000L

    /** Max latency samples to keep per relay for rolling average. */
    private const val MAX_LATENCY_SAMPLES = 10

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
        var latencySamples: MutableList<Long> = mutableListOf(),
        var lastConnectedAt: Long = 0,
        var lastFailedAt: Long = 0,
        var lastError: String? = null,
        var isFlagged: Boolean = false,
        var isBlocked: Boolean = false,
        /** Timestamp when connection attempt started (for latency measurement). */
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

    /** Recent publish reports (last 50). Observable by UI. */
    private val _publishReports = MutableStateFlow<List<PublishReport>>(emptyList())
    val publishReports: StateFlow<List<PublishReport>> = _publishReports.asStateFlow()

    /** One-shot flow for publish failure notifications (UI shows snackbar). */
    private val _publishFailure = MutableSharedFlow<PublishReport>(extraBufferCapacity = 8)
    val publishFailure: SharedFlow<PublishReport> = _publishFailure.asSharedFlow()

    /**
     * Register a pending publish so we can track OK responses from relays.
     * Call immediately after sending the event to relays.
     */
    fun registerPendingPublish(eventId: String, kind: Int, relayUrls: Set<String>) {
        synchronized(lock) {
            pendingPublishes[eventId] = PendingPublish(kind, relayUrls)
        }
        Log.d(TAG, "Registered pending publish ${eventId.take(8)} kind-$kind → ${relayUrls.size} relays")
    }

    /**
     * Called when a relay responds with OK to a published event.
     * Tracks per-relay success/failure and finalizes the report when all relays respond
     * or when [finalizePendingPublish] is called after timeout.
     */
    fun recordPublishOk(relayUrl: String, eventId: String, success: Boolean, message: String) {
        val url = normalize(relayUrl)
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
        // Add to recent reports (keep last 50)
        val current = _publishReports.value.toMutableList()
        current.add(0, report)
        if (current.size > 50) current.subList(50, current.size).clear()
        _publishReports.value = current

        if (report.hasFailures) {
            _publishFailure.tryEmit(report)
            Log.w(TAG, "Publish ${report.eventId.take(8)} kind-${report.kind}: " +
                "${report.successCount}/${report.targetRelayCount} OK, ${report.failureCount} failed")
            // Record failures in per-relay health
            report.results.filter { !it.success }.forEach { result ->
                recordPublishFailure(result.relayUrl, result.message)
            }
        } else {
            Log.d(TAG, "Publish ${report.eventId.take(8)} kind-${report.kind}: " +
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
        loadBlockedRelays()
    }

    // --- Recording events ---

    /** Call when a connection attempt starts (for latency tracking). */
    fun recordConnectionAttempt(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.connectionAttempts++
            data.connectStartedAt = System.currentTimeMillis()
        }
        emitState()
        RelayLogBuffer.logConnecting(url)
    }

    /** Call when a connection succeeds. */
    fun recordConnectionSuccess(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.consecutiveFailures = 0
            data.lastConnectedAt = System.currentTimeMillis()
            data.lastError = null
            // Latency
            if (data.connectStartedAt > 0) {
                val latency = System.currentTimeMillis() - data.connectStartedAt
                data.latencySamples.add(latency)
                if (data.latencySamples.size > MAX_LATENCY_SAMPLES) {
                    data.latencySamples.removeAt(0)
                }
                data.connectStartedAt = 0
            }
            // Unflag if it was flagged and now succeeds
            if (data.isFlagged) {
                data.isFlagged = false
                Log.i(TAG, "Relay $url unflagged after successful connection")
            }
        }
        emitState()
        RelayLogBuffer.logConnected(url)
    }

    /** Call when a connection fails. */
    fun recordConnectionFailure(relayUrl: String, error: String?) {
        val url = normalize(relayUrl)
        var nowFlagged = false
        var nowAutoBlocked = false
        synchronized(lock) {
            val data = getOrCreate(url)
            data.connectionFailures++
            data.consecutiveFailures++
            data.lastFailedAt = System.currentTimeMillis()
            data.lastError = error
            data.connectStartedAt = 0
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
        }
        if (nowFlagged) {
            Log.w(TAG, "Relay $url FLAGGED after $FLAG_CONSECUTIVE_FAILURES consecutive failures: $error")
        }
        if (nowAutoBlocked) {
            _blockedRelays.value = _blockedRelays.value + url
            _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
            persistBlockedRelays()
            persistAutoBlockExpiry()
            Log.w(TAG, "Relay $url AUTO-BLOCKED for ${AUTO_BLOCK_DURATION_MS / 3600000}h after $AUTO_BLOCK_CONSECUTIVE_FAILURES consecutive failures")
        }
        emitState()
        RelayLogBuffer.logError(url, error ?: "Connection failed")
    }

    /** Call when an event is received from a relay (any kind). */
    fun recordEventReceived(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            val data = getOrCreate(url)
            data.eventsReceived++
        }
        // Don't emit on every event — too noisy. Batch via periodic or threshold.
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

    /** Block a relay manually (no expiry). Persisted immediately. */
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
        Log.i(TAG, "Relay BLOCKED (manual): $url")
    }

    /** Unblock a relay. Persisted immediately. */
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
        Log.i(TAG, "Relay UNBLOCKED: $url")
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
            expired.forEach { Log.i(TAG, "Relay auto-block EXPIRED, unblocked: $it") }
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
        Log.i(TAG, "Relay manually unflagged: $url")
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
        avgLatencyMs = if (latencySamples.isNotEmpty()) latencySamples.average().toLong() else 0,
        lastConnectedAt = lastConnectedAt,
        lastFailedAt = lastFailedAt,
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
                Log.d(TAG, "Loaded ${list.size} blocked relays")
            }
            // Load auto-block expiry times
            val expiryRaw = prefs?.getString(KEY_AUTO_BLOCK_EXPIRY, null)
            if (expiryRaw != null) {
                val map = json.decodeFromString<Map<String, Long>>(expiryRaw)
                autoBlockExpiry.putAll(map)
                _autoBlockExpiryMap.value = autoBlockExpiry.toMap()
                Log.d(TAG, "Loaded ${map.size} auto-block expiry entries")
            }
            // Release any already-expired auto-blocks on startup
            releaseExpiredAutoBlocks()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocked relays: ${e.message}", e)
        }
    }

    private fun persistBlockedRelays() {
        try {
            val list = _blockedRelays.value.toList()
            prefs?.edit()?.putString(KEY_BLOCKED_RELAYS, json.encodeToString(list))?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist blocked relays: ${e.message}", e)
        }
    }

    private fun persistAutoBlockExpiry() {
        try {
            prefs?.edit()?.putString(KEY_AUTO_BLOCK_EXPIRY, json.encodeToString(autoBlockExpiry.toMap()))?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist auto-block expiry: ${e.message}", e)
        }
    }
}
