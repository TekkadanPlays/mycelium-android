package social.mycelium.android.utils

import android.util.LruCache

/**
 * In-memory LRU cache of media aspect ratios keyed by URL.
 * Populated when images/videos load successfully so that subsequent renders
 * can apply Modifier.aspectRatio() immediately, preventing layout jumps.
 * Inspired by Amethyst's MediaAspectRatioCache.
 */
object MediaAspectRatioCache {
    private val cache = LruCache<String, Float>(2000)

    fun get(url: String): Float? = cache.get(url)

    fun add(url: String, width: Int, height: Int) {
        if (height > 1 && width > 1) {
            val ratio = width.toFloat() / height.toFloat()
            if (ratio in 0.1f..10.0f) {
                cache.put(url, ratio)
            }
        }
    }
}
