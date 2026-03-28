package social.mycelium.android.ui.components.media

/**
 * Ephemeral cache that maps a video URL to the pool instance key of the
 * FeedVideoPlayer that last triggered fullscreen for that URL.
 *
 * When FeedVideoPlayer goes fullscreen, it stores its instanceKey here.
 * FullVideoPlayer retrieves it so it acquires the same pool entry.
 * Cleared when the fullscreen viewer closes.
 */
object VideoInstanceKeyCache {
    private val map = mutableMapOf<String, String>()

    fun set(url: String, instanceKey: String) { map[url] = instanceKey }
    fun get(url: String): String? = map[url]
    fun remove(url: String) { map.remove(url) }
    fun clear() { map.clear() }
}
