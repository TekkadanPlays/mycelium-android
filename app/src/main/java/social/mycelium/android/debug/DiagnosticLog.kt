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
 * Always-on, file-backed diagnostic log for debug builds.
 *
 * Unlike [DebugVerboseLog] (opt-in, in-memory, lossy), this logger:
 *   - Is **always active** in debug builds — no manual toggle
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

    enum class Channel(val filename: String) {
        STARTUP("startup.log"),
        RELAY("relay.log"),
        SYNC("sync.log"),
        STATE("state.log"),
        FEED("feed.log"),
        GENERAL("general.log"),
    }

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
     * No-op in release builds.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
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
    fun log(channel: Channel, source: String, message: String) {
        if (!BuildConfig.DEBUG || !initialized.get()) return
        val ts = timeFmt.format(Date())
        val line = "$ts | ${channel.name.padEnd(7)} | ${source.padEnd(20).take(20)} | $message"
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
