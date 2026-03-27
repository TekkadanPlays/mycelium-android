package social.mycelium.android.debug

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.mycelium.android.BuildConfig
import social.mycelium.android.relay.LogType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * Debug-build-only session log: layered, in-memory ring buffer + optional logcat mirror.
 * **Release:** [isAvailable] is false; all public methods return immediately (R8 may strip bodies).
 *
 * Intended as the primary capture surface for bug audits (relay, startup, lifecycle, state machine).
 * Enable from Settings → Debug, then Copy or Share the dump.
 */
object DebugVerboseLog {

    private const val PREFS = "mycelium_debug_verbose_log"
    private const val KEY_CAPTURE = "verbose_capture_enabled"
    private const val MAX_LINES = 20_000
    private const val OUT_TAG = "MyceliumVerbose"

    /** Layer prefix for each line (audit columns). */
    object Layer {
        const val SYSTEM = "SYSTEM"
        const val RELAY = "RELAY"
        const val RELAY_STATE = "RELAY_STATE"
        const val STARTUP = "STARTUP"
        const val NETWORK = "NETWORK"
        const val UI = "UI"
    }

    val isAvailable: Boolean get() = BuildConfig.DEBUG

    @Volatile
    private var initialized = false

    @Volatile
    private var captureEnabledInternal = false

    private val _lineCountFlow = MutableStateFlow(0)
    val lineCountFlow: StateFlow<Int> = _lineCountFlow.asStateFlow()

    private val _captureEnabledFlow = MutableStateFlow(false)
    val captureEnabledFlow: StateFlow<Boolean> = _captureEnabledFlow.asStateFlow()

    val captureEnabled: Boolean get() = BuildConfig.DEBUG && captureEnabledInternal

    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES + 1)
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            captureEnabledInternal = sp.getBoolean(KEY_CAPTURE, false)
            _captureEnabledFlow.value = captureEnabledInternal
        }
        if (captureEnabledInternal) {
            record(Layer.SYSTEM, "DebugVerboseLog", "Cold start: capture was left ON — logging resumed")
        }
    }

    fun setCaptureEnabled(context: Context, enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        captureEnabledInternal = enabled
        _captureEnabledFlow.value = enabled
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CAPTURE, enabled)
            .apply()
        record(Layer.SYSTEM, "DebugVerboseLog", if (enabled) "Capture ON" else "Capture OFF")
    }

    fun lineCount(): Int =
        if (!BuildConfig.DEBUG) 0 else synchronized(lock) { lines.size }

    /**
     * Append one line: `time | LAYER | tag | message`.
     * No-op in release or when capture is off.
     */
    fun record(layer: String, tag: String, message: String) {
        if (!BuildConfig.DEBUG || !captureEnabledInternal) return
        val ts = timeFmt.format(Date())
        val safeMsg = message.replace('\n', '↵').take(4_000)
        val line = "$ts | $layer | $tag | $safeMsg"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            _lineCountFlow.value = lines.size
        }
        Log.v(OUT_TAG, line)
    }

    /** Mirror of [RelayLogBuffer] entries when capture is on. */
    fun mirrorRelayLog(relayUrl: String, type: LogType, message: String) {
        if (!BuildConfig.DEBUG || !captureEnabledInternal) return
        val label = when (type) {
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
        val shortRelay = relayUrl.takeLast(56)
        record(Layer.RELAY, shortRelay, "[$label] $message")
    }

    fun clearBuffer() {
        if (!BuildConfig.DEBUG) return
        synchronized(lock) {
            lines.clear()
            _lineCountFlow.value = 0
        }
        record(Layer.SYSTEM, "DebugVerboseLog", "Buffer cleared")
    }

    /**
     * Header + in-memory session lines only (no relay pool snapshot — see [DebugSessionDump]).
     */
    fun buildSessionExport(): String {
        if (!BuildConfig.DEBUG) return ""
        val sb = StringBuilder(256 + lines.size * 120)
        sb.appendLine("=== Mycelium verbose debug dump ===")
        sb.appendLine("version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
        sb.appendLine("sdk=${Build.VERSION.SDK_INT} device=${Build.MODEL} manufacturer=${Build.MANUFACTURER}")
        sb.appendLine("captureEnabled=$captureEnabledInternal bufferedLines=${lineCount()}")
        sb.appendLine()
        sb.appendLine("--- Session log ---")
        synchronized(lock) {
            for (line in lines) sb.appendLine(line)
        }
        return sb.toString()
    }
}
