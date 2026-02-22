package social.mycelium.android.ui.components

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton pool of ExoPlayer instances keyed by URL.
 * Allows the same player to be shared between inline feed and fullscreen views
 * so video doesn't stutter or restart on fullscreen toggle.
 *
 * Flow:
 * 1. Feed player calls [acquire] — creates a new player or returns existing one.
 * 2. On fullscreen, feed calls [detach] (keeps player alive but marks it unowned).
 * 3. Fullscreen calls [acquire] — gets the same player, no re-buffer.
 * 4. On exit fullscreen, fullscreen calls [detach], feed calls [acquire] again.
 * 5. When the composable truly disposes (leaves composition), call [release].
 */
object SharedPlayerPool {

    /** Max concurrent ExoPlayer instances to prevent exhausting codec resources. */
    private const val MAX_POOL_SIZE = 3

    private data class Entry(
        val player: ExoPlayer,
        var ownerCount: Int = 0,
        var lastAccessTime: Long = System.nanoTime()
    )

    private val pool = ConcurrentHashMap<String, Entry>()

    /**
     * Acquire a player for [url]. If one already exists in the pool, return it.
     * Otherwise create a new one. Increments the owner count.
     * When the pool is full, the least-recently-used unowned player is evicted.
     */
    fun acquire(context: Context, url: String): ExoPlayer {
        val existing = pool[url]
        if (existing != null) {
            existing.ownerCount++
            existing.lastAccessTime = System.nanoTime()
            return existing.player
        }

        // Evict LRU unowned entries if at capacity
        while (pool.size >= MAX_POOL_SIZE) {
            val lru = pool.entries
                .filter { it.value.ownerCount <= 0 }
                .minByOrNull { it.value.lastAccessTime }
            if (lru != null) {
                pool.remove(lru.key)
                VideoPositionCache.set(lru.key, lru.value.player.currentPosition)
                lru.value.player.release()
            } else {
                // All entries are owned — allow over-limit rather than deadlock
                break
            }
        }

        val player = ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
        }
        val entry = Entry(player, 1)
        pool[url] = entry
        return entry.player
    }

    /**
     * Detach from a player without releasing it.
     * Decrements owner count. Player stays in pool for the next [acquire].
     */
    fun detach(url: String) {
        val entry = pool[url] ?: return
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
    }

    /**
     * Release a player and remove it from the pool.
     * Only releases if owner count is zero (no other view is using it).
     * Returns true if the player was actually released.
     */
    fun release(url: String): Boolean {
        val entry = pool[url] ?: return false
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
        if (entry.ownerCount <= 0) {
            pool.remove(url)
            VideoPositionCache.set(url, entry.player.currentPosition)
            entry.player.release()
            return true
        }
        return false
    }

    /**
     * Force-release a player regardless of owner count.
     */
    fun forceRelease(url: String) {
        val entry = pool.remove(url) ?: return
        VideoPositionCache.set(url, entry.player.currentPosition)
        entry.player.release()
    }

    /**
     * Check if a player exists in the pool for [url].
     */
    fun has(url: String): Boolean = pool.containsKey(url)

    /**
     * Get the player for [url] without changing ownership. Returns null if not pooled.
     */
    fun peek(url: String): ExoPlayer? = pool[url]?.player

    /**
     * Pause ALL pooled players (e.g. when app goes to background).
     * Saves current positions so they can resume cleanly.
     */
    fun pauseAll() {
        for ((url, entry) in pool) {
            if (entry.player.isPlaying) {
                VideoPositionCache.set(url, entry.player.currentPosition)
                entry.player.pause()
            }
        }
    }

    /**
     * Release ALL pooled players (e.g. on app destruction).
     */
    fun releaseAll() {
        for ((url, entry) in pool) {
            VideoPositionCache.set(url, entry.player.currentPosition)
            entry.player.release()
        }
        pool.clear()
    }
}
