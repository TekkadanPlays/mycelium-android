package social.mycelium.android.debug

import android.content.Context
import android.util.Log
import social.mycelium.android.BuildConfig
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/**
 * Structured JSONL event stream for machine-readable diagnostics.
 *
 * Runs alongside [DiagnosticLog] (which keeps the human-readable plain-text log).
 * Every entry is a JSON object on its own line in `diag_logs/events.jsonl`.
 *
 * ## Format
 * ```json
 * {"ts":1712345678123,"ev":"relay.connected","ch":"RELAY","tag":"RelayHealth","sid":"x7f2a1b3","url":"wss://relay.damus.io","latency_ms":118}
 * {"ts":1712345679001,"ev":"startup.phase_end","ch":"STARTUP","tag":"Orchestrator","sid":"def4562a","phase":0,"name":"SETTINGS","elapsed_ms":340}
 * ```
 *
 * Fields:
 * - `ts`  — epoch milliseconds
 * - `ev`  — event type (see [LogEvents] constants)
 * - `ch`  — channel name (mirrors [DiagnosticLog.Channel])
 * - `tag` — source tag (class/subsystem)
 * - `sid` — optional span ID for correlating start→end pairs
 * - additional typed fields per event type
 *
 * ## Retrieval
 * ```bash
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/events.jsonl | python analyze.py startup
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/events.jsonl | python analyze.py relay
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/events.jsonl > events.jsonl
 * python analyze.py --file events.jsonl report --html > report.html
 * ```
 *
 * ## Performance
 * All calls are wait-free (enqueue only). Writes are batched every 500 ms on a
 * daemon thread. The ring buffer rotates at 5,000 events to stay compact.
 */
object EventLog {

    private const val TAG = "EventLog"
    private const val FILENAME = "events.jsonl"
    private const val DIR = "diag_logs"
    private const val FLUSH_INTERVAL_MS = 500L
    private const val MAX_LINES = 5_000

    @Volatile private var logFile: File? = null
    private val initialized = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<String>()
    private var lineCount = 0

    // ── Initialization ─────────────────────────────────────────────────────

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        logFile = File(dir, FILENAME)

        // Session marker so sessions are distinguishable in the JSONL stream
        val sessionLine = buildJson(
            "ev" to LogEvents.SESSION_START,
            "ch" to "SYSTEM",
            "tag" to "EventLog",
            "version" to BuildConfig.VERSION_NAME,
            "version_code" to BuildConfig.VERSION_CODE,
            "sdk" to android.os.Build.VERSION.SDK_INT,
            "device" to android.os.Build.MODEL,
        )
        pending.add(sessionLine)

        Thread({
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flush()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "EventLog-flush").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Initialized: ${logFile?.absolutePath}")
    }

    // ── Span helpers ───────────────────────────────────────────────────────

    /** Generate a short random span ID (8 hex chars). */
    fun spanId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    // ── Emit API ───────────────────────────────────────────────────────────

    /**
     * Emit a structured event.
     *
     * @param event   Event type string — use [LogEvents] constants
     * @param channel Channel name (e.g. "RELAY", "STARTUP") — no enum import needed
     * @param tag     Source tag (class name or subsystem)
     * @param spanId  Optional span ID for correlating related events
     * @param data    Typed payload fields (String, Int, Long, Boolean values)
     */
    fun emit(
        event: String,
        channel: String,
        tag: String,
        spanId: String? = null,
        data: Map<String, Any?> = emptyMap(),
    ) {
        if (!initialized.get()) return
        val allFields = mutableMapOf<String, Any?>(
            "ev" to event,
            "ch" to channel,
            "tag" to tag,
        )
        if (spanId != null) allFields["sid"] = spanId
        allFields.putAll(data)
        pending.add(buildJson(allFields))
    }

    /** Flush pending entries immediately (call before adb pull). */
    fun flush() {
        val file = logFile ?: return
        val lines = mutableListOf<String>()
        while (true) lines.add(pending.poll() ?: break)
        if (lines.isEmpty()) return

        try {
            FileWriter(file, true).use { w ->
                for (line in lines) w.write(line + "\n")
            }
            lineCount += lines.size
            if (lineCount > MAX_LINES * 2) rotate(file)
        } catch (e: Exception) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }

    /** Clear event log (call on account switch). */
    fun clear() {
        val file = logFile ?: return
        pending.clear()
        try {
            file.writeText("")
            lineCount = 0
        } catch (_: Exception) {}
    }

    /** Read all lines (for in-app viewer). Forces a flush first. */
    fun readAll(): List<String> {
        flush()
        val file = logFile ?: return emptyList()
        return try { if (file.exists()) file.readLines() else emptyList() }
        catch (_: Exception) { emptyList() }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private fun rotate(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size <= MAX_LINES) return
            val kept = lines.takeLast(MAX_LINES)
            file.writeText(kept.joinToString("\n") + "\n")
            lineCount = kept.size
        } catch (e: Exception) {
            Log.w(TAG, "Rotate failed: ${e.message}")
        }
    }

    /**
     * Build a minimal JSON object string from ordered key-value pairs.
     * Handles String, Int, Long, Boolean, null. No external library needed.
     */
    private fun buildJson(vararg pairs: Pair<String, Any?>): String =
        buildJson(pairs.toMap())

    private fun buildJson(fields: Map<String, Any?>): String {
        val ts = System.currentTimeMillis()
        val sb = StringBuilder("{\"ts\":").append(ts)
        for ((k, v) in fields) {
            sb.append(",\"").append(k.replace("\"", "\\\"")).append("\":")
            when (v) {
                null          -> sb.append("null")
                is Boolean    -> sb.append(v)
                is Int        -> sb.append(v)
                is Long       -> sb.append(v)
                is Double     -> sb.append(v)
                is Float      -> sb.append(v)
                else          -> sb.append("\"").append(v.toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                ).append("\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }
}
