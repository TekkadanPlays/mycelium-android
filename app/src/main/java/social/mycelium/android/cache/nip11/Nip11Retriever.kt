package social.mycelium.android.cache.nip11

import android.util.Log
import social.mycelium.android.data.RelayInformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Network layer for fetching NIP-11 relay information documents.
 * Based on Amethyst's Nip11Retriever pattern.
 */
class Nip11Retriever(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "Nip11Retriever"
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    enum class ErrorCode {
        FAIL_TO_ASSEMBLE_URL,
        FAIL_TO_REACH_SERVER,
        FAIL_TO_PARSE_RESULT,
        FAIL_WITH_HTTP_STATUS
    }

    /**
     * Load relay information from the network
     *
     * @param relayUrl WebSocket URL of the relay (wss:// or ws://)
     * @param onInfo Callback when information is successfully retrieved
     * @param onError Callback when an error occurs
     */
    suspend fun loadRelayInfo(
        relayUrl: String,
        onInfo: (RelayInformation) -> Unit,
        onError: (String, ErrorCode, String?) -> Unit
    ) {
        try {
            val httpUrl = convertToHttpUrl(relayUrl)

            val request = Request.Builder()
                .url(httpUrl)
                .header("Accept", "application/nostr+json")
                .build()

            withContext(Dispatchers.IO) {
                try {
                    val response = okHttpClient.newCall(request).execute()

                    response.use { resp ->
                        val body = resp.body?.string() ?: ""

                        if (resp.isSuccessful) {
                            if (body.startsWith("{")) {
                                try {
                                    val relayInfo = json.decodeFromString<RelayInformation>(body)
                                    Log.d(TAG, "✅ Successfully fetched NIP-11 info for $relayUrl: ${relayInfo.name}")
                                    onInfo(relayInfo)
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Failed to parse NIP-11 response for $relayUrl: $body", e)
                                    onError(relayUrl, ErrorCode.FAIL_TO_PARSE_RESULT, e.message)
                                }
                            } else {
                                Log.w(TAG, "⚠️ Invalid JSON response from $relayUrl: $body")
                                onError(relayUrl, ErrorCode.FAIL_TO_PARSE_RESULT, "Response is not JSON")
                            }
                        } else {
                            Log.w(TAG, "⚠️ HTTP error ${resp.code} from $relayUrl")
                            onError(relayUrl, ErrorCode.FAIL_WITH_HTTP_STATUS, "HTTP ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Network error fetching $relayUrl", e)
                    onError(relayUrl, ErrorCode.FAIL_TO_REACH_SERVER, e.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Invalid URL $relayUrl", e)
            onError(relayUrl, ErrorCode.FAIL_TO_ASSEMBLE_URL, e.message)
        }
    }

    /**
     * Convert WebSocket URL to HTTP URL for NIP-11 fetching
     */
    private fun convertToHttpUrl(relayUrl: String): String {
        return when {
            relayUrl.startsWith("wss://") -> relayUrl.replace("wss://", "https://")
            relayUrl.startsWith("ws://") -> relayUrl.replace("ws://", "http://")
            relayUrl.startsWith("https://") -> relayUrl
            relayUrl.startsWith("http://") -> relayUrl
            else -> "https://$relayUrl"
        }
    }

    /**
     * Normalize relay URL to WebSocket format
     */
    fun normalizeRelayUrl(url: String): String {
        return when {
            url.startsWith("wss://") || url.startsWith("ws://") -> url
            url.startsWith("https://") -> url.replace("https://", "wss://")
            url.startsWith("http://") -> url.replace("http://", "ws://")
            else -> "wss://$url"
        }
    }

    /**
     * Get display URL (without protocol)
     */
    fun getDisplayUrl(url: String): String {
        return url
            .removePrefix("wss://")
            .removePrefix("ws://")
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
    }

    /**
     * Get favicon URL for a relay
     */
    fun getFaviconUrl(relayUrl: String): String {
        val httpUrl = convertToHttpUrl(relayUrl)
        return if (httpUrl.endsWith("/")) {
            "${httpUrl}favicon.ico"
        } else {
            "$httpUrl/favicon.ico"
        }
    }
}
