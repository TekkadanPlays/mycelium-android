package social.mycelium.android.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import social.mycelium.android.BuildConfig
import java.net.URL
import java.net.URLEncoder

/**
 * Simple GIF search using Tenor v2 API.
 * Returns preview URLs (tinygif) for the picker and full URLs (gif) for posting.
 *
 * API key is read from BuildConfig.TENOR_API_KEY (set via local.properties: tenor.api.key=...).
 */
object GifSearchRepository {

    private const val TAG = "GifSearchRepo"
    private val TENOR_API_KEY: String get() = BuildConfig.TENOR_API_KEY
    private const val TENOR_CLIENT_KEY = "mycelium_android"
    private const val BASE_URL = "https://tenor.googleapis.com/v2"
    private const val SEARCH_LIMIT = 30

    private val json = Json { ignoreUnknownKeys = true }

    data class GifResult(
        val previewUrl: String,  // Small preview for picker grid
        val fullUrl: String,     // Full GIF URL for reaction content
        val width: Int = 0,
        val height: Int = 0
    )

    @Serializable
    private data class TenorResponse(val results: List<TenorResult> = emptyList())
    @Serializable
    private data class TenorResult(val media_formats: Map<String, TenorMediaFormat> = emptyMap())
    @Serializable
    private data class TenorMediaFormat(val url: String = "", val dims: List<Int> = emptyList())

    /**
     * Search for GIFs by query. Returns a list of GIF results with preview and full URLs.
     */
    suspend fun search(query: String): List<GifResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$BASE_URL/search?q=$encoded&key=$TENOR_API_KEY&client_key=$TENOR_CLIENT_KEY&limit=$SEARCH_LIMIT&media_filter=tinygif,gif"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Tenor search failed: HTTP $responseCode")
                return@withContext emptyList()
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val response = json.decodeFromString<TenorResponse>(body)
            response.results.mapNotNull { result ->
                val preview = result.media_formats["tinygif"]
                val full = result.media_formats["gif"]
                if (preview != null && full != null) {
                    GifResult(
                        previewUrl = preview.url,
                        fullUrl = full.url,
                        width = full.dims.getOrNull(0) ?: 0,
                        height = full.dims.getOrNull(1) ?: 0
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "GIF search error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch trending GIFs (shown when search is empty).
     */
    suspend fun trending(): List<GifResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/featured?key=$TENOR_API_KEY&client_key=$TENOR_CLIENT_KEY&limit=$SEARCH_LIMIT&media_filter=tinygif,gif"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "Tenor trending failed: HTTP $responseCode")
                return@withContext emptyList()
            }

            val body = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val response = json.decodeFromString<TenorResponse>(body)
            response.results.mapNotNull { result ->
                val preview = result.media_formats["tinygif"]
                val full = result.media_formats["gif"]
                if (preview != null && full != null) {
                    GifResult(
                        previewUrl = preview.url,
                        fullUrl = full.url,
                        width = full.dims.getOrNull(0) ?: 0,
                        height = full.dims.getOrNull(1) ?: 0
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trending GIFs error: ${e.message}", e)
            emptyList()
        }
    }
}
