package social.mycelium.android.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Shared Ktor [HttpClient] singleton for all HTTP and WebSocket operations in Mycelium.
 *
 * Replaces the 13+ scattered [okhttp3.OkHttpClient] instances with a single connection pool.
 * Uses the OkHttp engine for Android compatibility and connection reuse.
 *
 * Usage:
 * ```
 * // HTTP GET with JSON deserialization
 * val info: Nip11Info = MyceliumHttpClient.instance.get("https://relay.example.com") {
 *     header("Accept", "application/nostr+json")
 * }.body()
 *
 * // WebSocket session
 * MyceliumHttpClient.instance.webSocket("wss://relay.example.com") {
 *     send(Frame.Text(reqJson))
 *     for (frame in incoming) { ... }
 * }
 * ```
 */
object MyceliumHttpClient {

    private const val TAG = "MyceliumHttpClient"

    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        coerceInputValues = true
    }

    val instance: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(10, TimeUnit.SECONDS)
                    retryOnConnectionFailure(true)
                }
            }

            install(WebSockets) {
                pingIntervalMillis = 30_000
            }

            install(ContentNegotiation) {
                json(jsonConfig)
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Log.d(TAG, message)
                    }
                }
                level = LogLevel.NONE // Set to LogLevel.HEADERS or LogLevel.BODY for debugging
            }
        }
    }

    /** Shared Json instance for manual serialization (e.g. parsing kind-0 content). */
    val json: Json get() = jsonConfig
}
