package social.mycelium.android.network

import social.mycelium.android.debug.MLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.WebSocketDeflateExtension
import kotlinx.serialization.json.Json

/**
 * Shared Ktor [HttpClient] singleton for all HTTP and WebSocket operations in Mycelium.
 *
 * Uses the CIO (Coroutine I/O) engine — Ktor's native non-blocking engine.
 * No OkHttp dependency. Supports WebSockets with ping/pong keepalive.
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
        HttpClient(CIO) {
            engine {
                requestTimeout = 30_000
                maxConnectionsCount = 100
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }

            install(WebSockets) {
                pingIntervalMillis = 30_000
                extensions {
                    install(WebSocketDeflateExtension) {
                        // Only compress frames above 128 bytes (small REQ/CLOSE messages
                        // don't benefit from compression overhead).
                        compressIfBiggerThan(128)
                    }
                }
            }

            install(ContentNegotiation) {
                json(jsonConfig)
            }

            install(HttpRedirect) {
                checkHttpMethod = false
            }

            install(HttpCache)

            install(HttpRequestRetry) {
                maxRetries = 3
                retryIf { _, response -> response.status.value in listOf(408, 429, 500, 502, 503, 504) }
                retryOnExceptionIf { _, cause -> cause is java.io.IOException }
                delayMillis { retry -> retry * 1500L } // 1.5s, 3s, 4.5s
            }

            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        MLog.d(TAG, message)
                    }
                }
                level = LogLevel.NONE // Set to LogLevel.HEADERS or LogLevel.BODY for debugging
            }
        }
    }

    /** Shared Json instance for manual serialization (e.g. parsing kind-0 content). */
    val json: Json get() = jsonConfig
}
