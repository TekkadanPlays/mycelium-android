package social.mycelium.android.ui.components

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File

/**
 * Singleton video disk cache using Media3 SimpleCache.
 * Caches downloaded video segments so scrolling back to a video
 * or replaying it doesn't re-fetch from the network.
 *
 * LRU eviction keeps the cache bounded at [MAX_CACHE_SIZE_BYTES].
 */
@UnstableApi
object VideoDiskCache {

    private const val MAX_CACHE_SIZE_BYTES = 200L * 1024 * 1024 // 200 MB

    @Volatile
    private var cache: SimpleCache? = null

    private fun getOrCreateCache(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: run {
                val cacheDir = File(context.applicationContext.cacheDir, "feed_video_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE_BYTES)
                val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(cacheDir, evictor, databaseProvider).also { cache = it }
            }
        }
    }

    /**
     * Build a [DefaultMediaSourceFactory] that reads/writes through the disk cache.
     * Pass this to [ExoPlayer.Builder.setMediaSourceFactory].
     */
    fun createCachedMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val simpleCache = getOrCreateCache(context)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(context.applicationContext)
            .setDataSourceFactory(cacheDataSourceFactory)
    }

    /**
     * Release the cache (call on app destruction if needed).
     */
    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
