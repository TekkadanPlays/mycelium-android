package social.mycelium.android.ui.components

import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton cache for video mute state keyed by URL.
 * Allows inline feed players and fullscreen viewers to share mute state
 * so toggling mute in one view persists to the other.
 */
object VideoMuteCache {
    private val states = ConcurrentHashMap<String, Boolean>()

    fun get(url: String): Boolean? = states[url]

    fun set(url: String, muted: Boolean) {
        states[url] = muted
    }

    fun remove(url: String) {
        states.remove(url)
    }

    fun clear() {
        states.clear()
    }
}
