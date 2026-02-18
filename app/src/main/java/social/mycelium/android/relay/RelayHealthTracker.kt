package social.mycelium.android.relay

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /** Max latency samples to keep per relay for rolling average. */
    private const val MAX_LATENCY_SAMPLES = 10

    /** SharedPreferences key for blocked relay list. */
    private const val PREFS_NAME = "relay_health"
    private const val KEY_BLOCKED_RELAYS = "blocked_relays"

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
        }
        if (nowFlagged) {
            Log.w(TAG, "Relay $url FLAGGED after $FLAG_CONSECUTIVE_FAILURES consecutive failures: $error")
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

    /** Block a relay. Persisted immediately. */
    fun blockRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            getOrCreate(url).isBlocked = true
        }
        _blockedRelays.value = _blockedRelays.value + url
        persistBlockedRelays()
        emitState()
        Log.i(TAG, "Relay BLOCKED: $url")
    }

    /** Unblock a relay. Persisted immediately. */
    fun unblockRelay(relayUrl: String) {
        val url = normalize(relayUrl)
        synchronized(lock) {
            getOrCreate(url).isBlocked = false
        }
        _blockedRelays.value = _blockedRelays.value - url
        persistBlockedRelays()
        emitState()
        Log.i(TAG, "Relay UNBLOCKED: $url")
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
}
