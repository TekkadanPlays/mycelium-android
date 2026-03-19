package social.mycelium.android.network

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
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
                maxConnectionsCount = 1000
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }

            install(WebSockets) {
                pingIntervalMillis = 30_000
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
