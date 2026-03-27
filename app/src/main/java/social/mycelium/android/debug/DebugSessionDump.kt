package social.mycelium.android.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import social.mycelium.android.BuildConfig
import social.mycelium.android.relay.RelayConnectionStateMachine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Assembles the full text blob for debug export (session buffer + relay pool snapshot).
 * Separated from [DebugVerboseLog] to avoid a dependency cycle with [RelayConnectionStateMachine].
 *
 * Format is plain UTF-8 text with stable columns (`time | LAYER | tag | message`) — easy for humans
 * and for LLMs. Saving to `.txt` on disk avoids clipboard size limits and is better for large captures.
 */
object DebugSessionDump {

    private const val TAG = "DebugSessionDump"

    fun buildFull(context: Context): String {
        if (!BuildConfig.DEBUG) return ""
        val sb = StringBuilder(DebugVerboseLog.buildSessionExport())
        sb.appendLine()
        sb.appendLine("--- Relay slot snapshot (pool) ---")
        try {
            val snaps = RelayConnectionStateMachine.getInstance().getRelaySlotSnapshots()
            if (snaps.isEmpty()) {
                sb.appendLine("(no relay scheduler entries)")
            } else {
                for (s in snaps) sb.appendLine(s.toString())
            }
        } catch (e: Exception) {
            sb.appendLine("(snapshot error: ${e.message})")
        }
        sb.appendLine()
        sb.appendLine("=== end ===")
        return sb.toString()
    }

    /**
     * Writes [buildFull] to app-accessible storage. Prefers [Context.getExternalFilesDir] so the file
     * is visible under Android/data/.../files/Documents/debug_logs/ over USB or Files app.
     * @return absolute path, or null on failure
     */
    fun saveToFile(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        return try {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: File(context.filesDir, "Documents").also { it.mkdirs() }
            val dir = File(base, "debug_logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "mycelium_debug_$stamp.txt")
            val text = buildFull(context)
            file.writeText(text, Charsets.UTF_8)
            Log.i(TAG, "Saved debug dump (${text.length} chars) to ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "saveToFile failed: ${e.message}", e)
            null
        }
    }

    /** @return number of characters copied, or 0 if not a debug build */
    fun copyToClipboard(context: Context, label: String = "Mycelium debug log"): Int {
        if (!BuildConfig.DEBUG) return 0
        val text = buildFull(context)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Log.i(TAG, "Copied debug dump to clipboard (${text.length} chars)")
        return text.length
    }

    fun share(context: Context) {
        if (!BuildConfig.DEBUG) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mycelium debug log ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, buildFull(context))
        }
        context.startActivity(Intent.createChooser(send, "Share debug log"))
    }
}
