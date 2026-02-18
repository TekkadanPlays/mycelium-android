package social.mycelium.android.utils

import android.app.ActivityManager
import android.content.Context

/**
 * Helpers for memory-aware behavior. Use before clearly memory-intensive work to avoid
 * large allocations when the device is already low on memory.
 * See: https://developer.android.com/topic/performance/memory#check-how-much-memory-you-need
 *
 * Data structures: For new maps keyed by Int or Long, prefer [android.util.SparseArray],
 * [android.util.SparseBooleanArray], or [android.util.LongSparseArray] over HashMap to reduce
 * allocations and avoid boxing (see Android memory docs: "Use optimized data containers").
 */
object MemoryUtils {

    /**
     * Returns true if the system is in a low-memory state (availMem below threshold).
     * Consider skipping or reducing heavy work when this is true.
     */
    fun isLowMemory(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory
    }

    /**
     * Returns the approximate heap size class in MB (e.g. 256). Use to size caches or batches.
     */
    fun getMemoryClass(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 48
        return activityManager.memoryClass
    }
}
