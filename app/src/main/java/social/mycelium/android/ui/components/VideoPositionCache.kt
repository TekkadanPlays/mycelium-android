package social.mycelium.android.ui.components

import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton cache for video playback positions (in milliseconds) keyed by URL.
 * Allows inline feed players and fullscreen viewers to share playback state
 * so videos resume where the user left off.
 */
object VideoPositionCache {
    private val positions = ConcurrentHashMap<String, Long>()

    fun get(url: String): Long = positions[url] ?: 0L

    fun set(url: String, positionMs: Long) {
        positions[url] = positionMs
    }

    fun remove(url: String) {
        positions.remove(url)
    }

    fun clear() {
        positions.clear()
    }
}
