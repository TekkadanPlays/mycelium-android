package social.mycelium.android.debug

import android.content.Context
import android.util.Log
import social.mycelium.android.BuildConfig
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on, file-backed diagnostic log for all builds.
 *
 * Unlike [DebugVerboseLog] (opt-in, in-memory, lossy, debug-only), this logger:
 *   - Is **always active** — no manual toggle needed
 *   - Writes to a **persistent file** that survives logcat buffer rotation
 *   - Uses **structured channels** for targeted retrieval via `adb shell cat`
 *   - Keeps a **rolling window** (last N lines per file) to stay small
 *   - Batches writes on a background thread to avoid I/O on hot paths
 *
 * ## Retrieval
 *
 * ```
 * # Pull the startup channel (relay sync, phases, category mutations):
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/startup.log
 *
 * # Pull the relay channel (connection lifecycle, subscriptions):
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/relay.log
 *
 * # Pull the state channel (category/profile saves, UI state mutations):
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/state.log
 *
 * # Pull ALL channels in one shot:
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/all.log
 *
 * # Grep for a specific topic (e.g. damus relay):
 * adb shell run-as social.mycelium.android.debug cat files/diag_logs/all.log | Select-String "damus"
 * ```
 *
 * ## Channels
 *
 * Each channel maps to a separate file for targeted pulls. Every entry is also
 * appended to `all.log` for full-timeline analysis.
 *
 * | Channel   | File          | Purpose                                      |
 * |-----------|---------------|----------------------------------------------|
 * | STARTUP   | startup.log   | Orchestrator phases, cold start, sign-in      |
 * | RELAY     | relay.log     | Connection lifecycle, AUTH, subscriptions      |
 * | SYNC      | sync.log      | kind-30002 fetch/publish/delete, NIP-65, merge |
 * | STATE     | state.log     | Category/profile mutations, storage saves      |
 * | FEED      | feed.log      | Note ingestion, subscription changes           |
 * | GENERAL   | general.log   | Anything not fitting the above                 |
 */
object DiagnosticLog {

    private const val TAG = "DiagLog"
    private const val DIR = "diag_logs"
    private const val MAX_LINES_PER_FILE = 2_000
    private const val ALL_FILE = "all.log"
    private const val FLUSH_INTERVAL_MS = 500L

    enum class ChannelGroup(val label: String) {
        NETWORK("Network"),
        DATA("Data"),
        SYSTEM("System"),
        PAYMENTS("Payments"),
    }

    enum class Channel(val filename: String, val group: ChannelGroup) {
        STARTUP("startup.log", ChannelGroup.SYSTEM),
        RELAY("relay.log", ChannelGroup.NETWORK),
        SYNC("sync.log", ChannelGroup.NETWORK),
        STATE("state.log", ChannelGroup.DATA),
        FEED("feed.log", ChannelGroup.DATA),
        AUTH("auth.log", ChannelGroup.NETWORK),
        NOTIFICATION("notification.log", ChannelGroup.DATA),
        WALLET("wallet.log", ChannelGroup.PAYMENTS),
        GENERAL("general.log", ChannelGroup.SYSTEM),
    }

    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    @Volatile
    private var logDir: File? = null
    private val initialized = AtomicBoolean(false)
    private val pending = ConcurrentLinkedQueue<Pair<Channel, String>>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Line counts per file for rotation
    private val lineCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Initialize the diagnostic log system. Call once from Application.onCreate().
     */
    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        logDir = dir

        // Write a session header to all.log
        val header = buildString {
            appendLine()
            appendLine("════════════════════════════════════════════════════════════════")
            appendLine("  SESSION START: ${dateFmt.format(Date())}")
            appendLine("  version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
            appendLine("  sdk=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MODEL}")
            appendLine("════════════════════════════════════════════════════════════════")
        }
        appendToFile(File(dir, ALL_FILE), header)
        Channel.entries.forEach { ch ->
            appendToFile(File(dir, ch.filename), header)
        }

        // Start the flush thread
        Thread({
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flushPending()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "DiagLog-flush").apply {
            isDaemon = true
            start()
        }

        Log.d(TAG, "Initialized: ${dir.absolutePath}")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Log a diagnostic message to a specific channel.
     *
     * @param channel The channel/file to write to
     * @param source The source tag (e.g. class name or subsystem)
     * @param message The log message
     */
    fun log(channel: Channel, source: String, message: String, trace: String? = null) {
        if (!initialized.get()) return
        val ts = timeFmt.format(Date())
        val msg = if (trace != null) "[$trace] $message" else message
        val line = "$ts | ${channel.name.padEnd(12)} | ${source.padEnd(20).take(20)} | $msg"
        pending.add(channel to line)
    }

    fun log(channel: Channel, level: Level, source: String, message: String, trace: String? = null) {
        if (!initialized.get()) return
        val ts = timeFmt.format(Date())
        val lvl = level.name.first()
        val msg = if (trace != null) "[$trace] $message" else message
        val line = "$ts | $lvl | ${channel.name.padEnd(12)} | ${source.padEnd(20).take(20)} | $msg"
        pending.add(channel to line)
    }

    /** Start a measurable span and return its ID. Include the ID in subsequent logs, then call endSpan. */
    fun startSpan(channel: Channel, source: String, operation: String): String {
        if (!initialized.get()) return ""
        val spanId = java.util.UUID.randomUUID().toString().take(8)
        val ts = timeFmt.format(Date())
        val line = "$ts | I | ${channel.name.padEnd(12)} | ${source.padEnd(20).take(20)} | SPAN_START {$spanId} $operation"
        pending.add(channel to line)
        return spanId
    }

    /** End a measurable span previously started. */
    fun endSpan(channel: Channel, source: String, operation: String, spanId: String, data: String? = null) {
        if (!initialized.get() || spanId.isEmpty()) return
        val ts = timeFmt.format(Date())
        val msg = if (data != null) "SPAN_END {$spanId} $operation : $data" else "SPAN_END {$spanId} $operation"
        val line = "$ts | I | ${channel.name.padEnd(12)} | ${source.padEnd(20).take(20)} | $msg"
        pending.add(channel to line)
    }

    /** Convenience: log to STARTUP channel. */
    fun startup(source: String, message: String) = log(Channel.STARTUP, source, message)

    /** Convenience: log to RELAY channel. */
    fun relay(source: String, message: String) = log(Channel.RELAY, source, message)

    /** Convenience: log to SYNC channel. */
    fun sync(source: String, message: String) = log(Channel.SYNC, source, message)

    /** Convenience: log to STATE channel. */
    fun state(source: String, message: String) = log(Channel.STATE, source, message)

    /** Convenience: log to FEED channel. */
    fun feed(source: String, message: String) = log(Channel.FEED, source, message)

    /** Convenience: log to GENERAL channel. */
    fun general(source: String, message: String) = log(Channel.GENERAL, source, message)

    /** Convenience: log to AUTH channel. */
    fun auth(source: String, message: String) = log(Channel.AUTH, source, message)

    /** Convenience: log to NOTIFICATION channel. */
    fun notification(source: String, message: String) = log(Channel.NOTIFICATION, source, message)

    /** Convenience: log to WALLET channel. */
    fun wallet(source: String, message: String) = log(Channel.WALLET, source, message)

    /**
     * Flush all pending entries to disk immediately.
     * Call before pulling logs via adb for real-time completeness.
     */
    fun flush() {
        flushPending()
    }

    /**
     * Rotate (truncate) all log files to the last [MAX_LINES_PER_FILE] lines.
     * Called automatically when a file exceeds 2× the limit.
     */
    fun rotateAll() {
        val dir = logDir ?: return
        (Channel.entries.map { it.filename } + ALL_FILE).forEach { filename ->
            rotateFile(File(dir, filename))
        }
    }

    /**
     * Clear all diagnostic logs. Useful on account switch.
     */
    fun clearAll() {
        val dir = logDir ?: return
        (Channel.entries.map { it.filename } + ALL_FILE).forEach { filename ->
            try {
                File(dir, filename).writeText("")
                lineCounts[filename] = 0
            } catch (_: Exception) {}
        }
        Log.d(TAG, "All diagnostic logs cleared")
    }

    // ── Read API (for DiagnosticLogViewerScreen) ────────────────────────────

    /** Read all lines from a specific channel's log file. */
    fun readChannel(channel: Channel): List<String> {
        val dir = logDir ?: return emptyList()
        flushPending()
        val file = File(dir, channel.filename)
        return try {
            if (file.exists()) file.readLines() else emptyList()
        } catch (e: Exception) {
            listOf("Error reading ${channel.filename}: ${e.message}")
        }
    }

    /** Read all lines from the combined all.log file. */
    fun readAll(): List<String> {
        val dir = logDir ?: return emptyList()
        flushPending()
        val file = File(dir, ALL_FILE)
        return try {
            if (file.exists()) file.readLines() else emptyList()
        } catch (e: Exception) {
            listOf("Error reading all.log: ${e.message}")
        }
    }

    /** Read + merge all channels in a [ChannelGroup], sorted by timestamp. */
    fun readGroup(group: ChannelGroup): List<String> {
        val dir = logDir ?: return emptyList()
        flushPending()
        return Channel.entries
            .filter { it.group == group }
            .flatMap { ch ->
                val file = File(dir, ch.filename)
                try {
                    if (file.exists()) file.readLines() else emptyList()
                } catch (_: Exception) { emptyList() }
            }
            .sortedBy { it.take(12) } // sort by HH:mm:ss.SSS prefix
    }

    /** Build a single export string containing all channel logs for sharing/copying. */
    fun buildExport(): String {
        val dir = logDir ?: return "(DiagnosticLog not initialized)"
        flushPending()
        return buildString {
            appendLine("=== Mycelium Diagnostic Log Export ===")
            appendLine("Exported: ${dateFmt.format(Date())}")
            appendLine()
            for (ch in Channel.entries) {
                val file = File(dir, ch.filename)
                if (file.exists() && file.length() > 0) {
                    appendLine("--- ${ch.name} (${ch.filename}) ---")
                    appendLine(file.readText())
                    appendLine()
                }
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun flushPending() {
        val dir = logDir ?: return
        val batch = mutableListOf<Pair<Channel, String>>()
        while (true) {
            val entry = pending.poll() ?: break
            batch.add(entry)
        }
        if (batch.isEmpty()) return

        // Group by channel file for batch writes
        val byFile = mutableMapOf<String, StringBuilder>()

        for ((channel, line) in batch) {
            // Channel-specific file
            byFile.getOrPut(channel.filename) { StringBuilder() }.appendLine(line)
            // All-channels file
            byFile.getOrPut(ALL_FILE) { StringBuilder() }.appendLine(line)
        }

        for ((filename, content) in byFile) {
            val file = File(dir, filename)
            appendToFile(file, content.toString())

            // Track line count and auto-rotate
            val added = content.lines().size
            val current = lineCounts.getOrDefault(filename, 0) + added
            lineCounts[filename] = current
            if (current > MAX_LINES_PER_FILE * 2) {
                rotateFile(file)
            }
        }
    }

    private fun appendToFile(file: File, text: String) {
        try {
            FileWriter(file, true).use { it.write(text) }
        } catch (e: Exception) {
            Log.w(TAG, "Write failed for ${file.name}: ${e.message}")
        }
    }

    private fun rotateFile(file: File) {
        try {
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.size <= MAX_LINES_PER_FILE) return
            val truncated = lines.takeLast(MAX_LINES_PER_FILE)
            file.writeText(truncated.joinToString("\n") + "\n")
            lineCounts[file.name] = truncated.size
            Log.d(TAG, "Rotated ${file.name}: ${lines.size} → ${truncated.size} lines")
        } catch (e: Exception) {
            Log.w(TAG, "Rotate failed for ${file.name}: ${e.message}")
        }
    }
}
