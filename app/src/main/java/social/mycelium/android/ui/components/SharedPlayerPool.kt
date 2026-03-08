package social.mycelium.android.ui.components

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import social.mycelium.android.utils.MediaAspectRatioCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton pool of ExoPlayer instances keyed by instance ID.
 * Each video container (NoteCard, quoted note, etc.) gets a unique instance key
 * so the same URL in multiple containers doesn't share one ExoPlayer.
 * The feed↔fullscreen transition shares the same instance key for seamless handoff.
 *
 * Flow:
 * 1. Feed player calls [acquire] with a unique instanceKey — creates or returns player.
 * 2. On fullscreen, feed calls [detach] (keeps player alive but marks it unowned).
 * 3. Fullscreen calls [acquire] with the SAME instanceKey — gets the same player.
 * 4. On exit fullscreen, fullscreen calls [detach], feed calls [acquire] again.
 * 5. When the composable truly disposes (leaves composition), call [release].
 */
object SharedPlayerPool {

    /** Max concurrent ExoPlayer instances to prevent exhausting codec resources. */
    private const val MAX_POOL_SIZE = 3

    private data class Entry(
        val player: ExoPlayer,
        val url: String,
        var ownerCount: Int = 0,
        var lastAccessTime: Long = System.nanoTime()
    )

    private val pool = ConcurrentHashMap<String, Entry>()

    /**
     * Acquire a player for [instanceKey]. If one already exists in the pool, return it.
     * Otherwise create a new one for [url]. Increments the owner count.
     * When the pool is full, the least-recently-used unowned player is evicted.
     */
    fun acquire(context: Context, instanceKey: String, url: String): ExoPlayer? {
        // If PiP owns this instance, refuse to create a duplicate player.
        // The caller should check isPipActive and skip rendering.
        if (PipStreamManager.isInstanceActive(instanceKey)) return null

        val existing = pool[instanceKey]
        if (existing != null) {
            existing.ownerCount++
            existing.lastAccessTime = System.nanoTime()
            // Clear video surface so the previous PlayerView loses rendering.
            // The new caller's PlayerView will re-attach in its update block.
            existing.player.clearVideoSurface()
            return existing.player
        }

        // Evict LRU unowned entries if at capacity
        while (pool.size >= MAX_POOL_SIZE) {
            val lru = pool.entries
                .filter { it.value.ownerCount <= 0 }
                .minByOrNull { it.value.lastAccessTime }
            if (lru != null) {
                pool.remove(lru.key)
                VideoPositionCache.set(lru.value.url, lru.value.player.currentPosition)
                lru.value.player.release()
            } else {
                // All entries are owned — allow over-limit rather than deadlock
                break
            }
        }

        val player = ExoPlayer.Builder(context.applicationContext).build().apply {
            // Cache video aspect ratio on size change (like Amethyst's AspectRatioCacher)
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        MediaAspectRatioCache.add(url, videoSize.width, videoSize.height)
                    }
                }
            })
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = false
        }
        val entry = Entry(player, url, 1)
        pool[instanceKey] = entry
        return entry.player
    }

    /**
     * Detach from a player without releasing it.
     * Decrements owner count. Player stays in pool for the next [acquire].
     */
    fun detach(instanceKey: String) {
        val entry = pool[instanceKey] ?: return
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
        // Pause when no owners — prevents audio leaking from orphaned pool entries
        if (entry.ownerCount <= 0) {
            entry.player.pause()
        }
    }

    /**
     * Release a player and remove it from the pool.
     * Only releases if owner count is zero (no other view is using it).
     * Returns true if the player was actually released.
     */
    fun release(instanceKey: String): Boolean {
        val entry = pool[instanceKey] ?: return false
        entry.ownerCount = (entry.ownerCount - 1).coerceAtLeast(0)
        if (entry.ownerCount <= 0) {
            pool.remove(instanceKey)
            VideoPositionCache.set(entry.url, entry.player.currentPosition)
            entry.player.release()
            return true
        }
        return false
    }

    /**
     * Force-release a player regardless of owner count.
     */
    fun forceRelease(instanceKey: String) {
        val entry = pool.remove(instanceKey) ?: return
        VideoPositionCache.set(entry.url, entry.player.currentPosition)
        entry.player.release()
    }

    /**
     * Remove a player from the pool WITHOUT releasing it.
     * The caller takes ownership of the ExoPlayer instance.
     * Used by PipStreamManager to orphan the player so feed can't reacquire it.
     */
    fun steal(instanceKey: String): ExoPlayer? {
        val entry = pool.remove(instanceKey) ?: return null
        VideoPositionCache.set(entry.url, entry.player.currentPosition)
        return entry.player
    }

    /**
     * Return a previously stolen/reclaimed player back to the pool.
     * Used when PiP hands a player back so the next screen can [acquire] it
     * instead of creating a duplicate.
     */
    fun returnToPool(instanceKey: String, url: String, player: ExoPlayer) {
        // If there's already a different player for this key, release the incoming one
        val existing = pool[instanceKey]
        if (existing != null && existing.player !== player) {
            player.stop()
            player.release()
            return
        }
        pool[instanceKey] = Entry(player, url, ownerCount = 0)
    }

    /**
     * Check if a player exists in the pool for [instanceKey].
     */
    fun has(instanceKey: String): Boolean = pool.containsKey(instanceKey)

    /**
     * Check if a pooled player for [instanceKey] exists and has no current owners.
     * Used by FeedVideoPlayer to detect when FullVideoPlayer has released ownership
     * so it can safely re-acquire without dual-ownership.
     */
    fun isUnowned(instanceKey: String): Boolean {
        val entry = pool[instanceKey] ?: return false
        return entry.ownerCount <= 0
    }

    /**
     * Get the player for [instanceKey] without changing ownership. Returns null if not pooled.
     */
    fun peek(instanceKey: String): ExoPlayer? = pool[instanceKey]?.player

    /**
     * Pause ALL pooled players (e.g. when app goes to background).
     * Saves current positions so they can resume cleanly.
     */
    fun pauseAll() {
        for ((_, entry) in pool) {
            if (entry.player.isPlaying) {
                VideoPositionCache.set(entry.url, entry.player.currentPosition)
                entry.player.pause()
            }
        }
    }

    /**
     * Release ALL pooled players (e.g. on app destruction).
     */
    fun releaseAll() {
        for ((_, entry) in pool) {
            VideoPositionCache.set(entry.url, entry.player.currentPosition)
            entry.player.release()
        }
        pool.clear()
    }
}
