package social.mycelium.android.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.BuildConfig
import social.mycelium.android.debug.DebugVerboseLog
import social.mycelium.android.utils.normalizeRelayUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Types of relay log entries.
 */
enum class LogType {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    SENT,
    RECEIVED,
    ERROR,
    NOTICE,
    EOSE,
    /** Pool / scheduler / reconnect / app-layer diagnostics (control panel). */
    DIAG
}

/**
 * Semantic categories for relay log entries. Used for UI filtering.
 */
enum class LogCategory {
    /** Connect / disconnect / reconnect lifecycle. */
    LIFECYCLE,
    /** Raw outbound / inbound WebSocket messages. */
    WIRE,
    /** NIP-42 AUTH challenges and responses. */
    AUTH,
    /** REQ / CLOSE / EOSE subscription management. */
    SUBSCRIPTION,
    /** Errors, NOTICEs, and failures. */
    ERROR,
    /** Pool / scheduler / keepalive / app-layer diagnostics. */
    DIAGNOSTIC,
}

/**
 * A single relay log entry.
 */
data class RelayLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val type: LogType,
    val message: String,
    val relayUrl: String,
    val category: LogCategory = LogCategory.DIAGNOSTIC,
) {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun formattedTime(): String = dateFormat.format(Date(timestamp))

    fun typeLabel(): String = when (type) {
        LogType.CONNECTING -> "CONN"
        LogType.CONNECTED -> "OK"
        LogType.DISCONNECTED -> "DISC"
        LogType.SENT -> "SEND"
        LogType.RECEIVED -> "RECV"
        LogType.ERROR -> "ERR"
        LogType.NOTICE -> "NOTE"
        LogType.EOSE -> "EOSE"
        LogType.DIAG -> "DIAG"
    }
}

/**
 * In-memory ring buffer for relay activity logs. One buffer per relay URL.
 * Logs are stored in a fixed-size ring buffer (max 500 entries per relay).
 * Exposes a [StateFlow] so the UI can observe live updates.
 */
object RelayLogBuffer {
    private const val MAX_ENTRIES_PER_RELAY = 500

    // Per-relay log flows
    private val buffers = ConcurrentHashMap<String, MutableStateFlow<List<RelayLogEntry>>>()

    /**
     * Get the log flow for a specific relay URL.
     */
    fun getLogsForRelay(relayUrl: String): StateFlow<List<RelayLogEntry>> {
        return getOrCreateBuffer(normalizeRelayUrl(relayUrl)).asStateFlow()
    }

    /**
     * Get combined logs from all relays, sorted by timestamp.
     */
    fun getAllLogs(): StateFlow<List<RelayLogEntry>> {
        // For simplicity, return a combined flow — but for real-time updates,
        // the UI should observe per-relay flows
        return _allLogs.asStateFlow()
    }

    private val _allLogs = MutableStateFlow<List<RelayLogEntry>>(emptyList())

    /**
     * Log a new entry with auto-categorization based on LogType.
     */
    fun log(relayUrl: String, type: LogType, message: String, category: LogCategory? = null) {
        val flow = getOrCreateBuffer(relayUrl)
        val key = normalizeRelayUrl(relayUrl)
        // Auto-derive category from LogType if not specified
        val effectiveCategory = category ?: when (type) {
            LogType.CONNECTING, LogType.CONNECTED, LogType.DISCONNECTED -> LogCategory.LIFECYCLE
            LogType.SENT, LogType.RECEIVED -> LogCategory.WIRE
            LogType.ERROR, LogType.NOTICE -> LogCategory.ERROR
            LogType.EOSE -> LogCategory.SUBSCRIPTION
            LogType.DIAG -> LogCategory.DIAGNOSTIC
        }
        val entry = RelayLogEntry(
            type = type,
            message = message,
            relayUrl = key,
            category = effectiveCategory,
        )
        val current = flow.value.toMutableList()
        current.add(entry)

        // Trim to max size (ring buffer)
        if (current.size > MAX_ENTRIES_PER_RELAY) {
            val trimmed = current.takeLast(MAX_ENTRIES_PER_RELAY)
            flow.value = trimmed
        } else {
            flow.value = current
        }

        // Update all-logs
        val all = _allLogs.value.toMutableList()
        all.add(entry)
        if (all.size > MAX_ENTRIES_PER_RELAY * 2) {
            _allLogs.value = all.takeLast(MAX_ENTRIES_PER_RELAY)
        } else {
            _allLogs.value = all
        }

        if (BuildConfig.DEBUG) {
            DebugVerboseLog.mirrorRelayLog(key, type, message)
        }
    }

    // Convenience methods
    fun logConnecting(relayUrl: String) = log(relayUrl, LogType.CONNECTING, "Connecting...")
    fun logConnected(relayUrl: String) = log(relayUrl, LogType.CONNECTED, "Connected")
    fun logDisconnected(relayUrl: String, reason: String = "") =
        log(relayUrl, LogType.DISCONNECTED, if (reason.isNotBlank()) reason else "Disconnected (reason unknown)")
    fun logSent(relayUrl: String, message: String) = log(relayUrl, LogType.SENT, message)
    fun logReceived(relayUrl: String, message: String) = log(relayUrl, LogType.RECEIVED, message)
    fun logError(relayUrl: String, error: String) = log(relayUrl, LogType.ERROR, error)
    fun logNotice(relayUrl: String, message: String) = log(relayUrl, LogType.NOTICE, message)
    fun logEose(relayUrl: String, subId: String) = log(relayUrl, LogType.EOSE, "EOSE: $subId", LogCategory.SUBSCRIPTION)

    /** Structured diagnostic line: `[category] detail` for the relay control panel. */
    fun logDiagnostic(relayUrl: String, category: String, detail: String) {
        val cat = category.trim().ifBlank { "app" }
        // Detect auth-related diagnostics and categorize them separately
        val logCat = if (cat == "auth" || cat == "nip42") LogCategory.AUTH else LogCategory.DIAGNOSTIC
        log(relayUrl, LogType.DIAG, "[$cat] $detail", logCat)
    }

    /**
     * Clear logs for a specific relay.
     */
    fun clearLogsForRelay(relayUrl: String) {
        buffers[normalizeRelayUrl(relayUrl)]?.value = emptyList()
    }

    /**
     * Clear all logs.
     */
    fun clearAll() {
        buffers.values.forEach { it.value = emptyList() }
        _allLogs.value = emptyList()
    }

    private fun getOrCreateBuffer(relayUrl: String): MutableStateFlow<List<RelayLogEntry>> {
        val key = normalizeRelayUrl(relayUrl)
        return buffers.getOrPut(key) { MutableStateFlow(emptyList()) }
    }
}
