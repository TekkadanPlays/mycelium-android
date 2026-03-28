package social.mycelium.android.repository.relay

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import social.mycelium.android.network.MyceliumHttpClient

/**
 * NIP-86 Relay Management API client.
 *
 * Provides JSON-RPC over HTTP access to relay management endpoints.
 * The relay exposes these on the same URI as its WebSocket endpoint,
 * distinguished by `Content-Type: application/nostr+json+rpc`.
 *
 * Authorization uses NIP-98 HTTP Auth events. The caller must provide
 * a signed NIP-98 event via [setAuthProvider].
 *
 * Usage:
 * ```
 * val client = Nip86RelayManagementClient("wss://my-relay.example.com")
 * client.setAuthProvider { relayUrl, method, payload ->
 *     // Return base64-encoded signed kind-27235 NIP-98 event
 *     signNip98Event(relayUrl, method, payload)
 * }
 * val methods = client.supportedMethods()
 * ```
 */
class Nip86RelayManagementClient(private val relayWsUrl: String) {

    companion object {
        private const val TAG = "Nip86Client"
        private val NIP86_CONTENT_TYPE = ContentType.parse("application/nostr+json+rpc")
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    private val httpUrl: String = relayWsUrl
        .replace("wss://", "https://")
        .replace("ws://", "http://")
        .removeSuffix("/")

    private val httpClient = MyceliumHttpClient.instance

    /**
     * Provider for NIP-98 authorization headers.
     * Parameters: (relayUrl: String, method: String, payloadHash: String) -> base64 NIP-98 event
     */
    private var authProvider: (suspend (String, String, String) -> String)? = null

    fun setAuthProvider(provider: suspend (relayUrl: String, method: String, payloadHash: String) -> String) {
        authProvider = provider
    }

    // ΓöÇΓöÇ Public API methods ΓöÇΓöÇ

    /**
     * Query which management methods this relay supports.
     * This is the entry point ΓÇö call this first to discover capabilities.
     */
    suspend fun supportedMethods(): Result<List<String>> {
        return callMethod("supportedmethods").map { result ->
            (result as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
        }
    }

    suspend fun banPubkey(pubkeyHex: String, reason: String? = null): Result<Boolean> {
        val params = buildList {
            add(JsonPrimitive(pubkeyHex))
            if (reason != null) add(JsonPrimitive(reason))
        }
        return callMethod("banpubkey", params).map { true }
    }

    suspend fun listBannedPubkeys(): Result<List<BannedEntry>> {
        return callMethod("listbannedpubkeys").map { result ->
            (result as? JsonArray)?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.content
                BannedEntry(pubkey, reason)
            } ?: emptyList()
        }
    }

    suspend fun allowPubkey(pubkeyHex: String, reason: String? = null): Result<Boolean> {
        val params = buildList {
            add(JsonPrimitive(pubkeyHex))
            if (reason != null) add(JsonPrimitive(reason))
        }
        return callMethod("allowpubkey", params).map { true }
    }

    suspend fun listAllowedPubkeys(): Result<List<BannedEntry>> {
        return callMethod("listallowedpubkeys").map { result ->
            (result as? JsonArray)?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.content
                BannedEntry(pubkey, reason)
            } ?: emptyList()
        }
    }

    suspend fun banEvent(eventIdHex: String, reason: String? = null): Result<Boolean> {
        val params = buildList {
            add(JsonPrimitive(eventIdHex))
            if (reason != null) add(JsonPrimitive(reason))
        }
        return callMethod("banevent", params).map { true }
    }

    suspend fun listBannedEvents(): Result<List<BannedEntry>> {
        return callMethod("listbannedevents").map { result ->
            (result as? JsonArray)?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.content
                BannedEntry(id, reason)
            } ?: emptyList()
        }
    }

    suspend fun allowEvent(eventIdHex: String, reason: String? = null): Result<Boolean> {
        val params = buildList {
            add(JsonPrimitive(eventIdHex))
            if (reason != null) add(JsonPrimitive(reason))
        }
        return callMethod("allowevent", params).map { true }
    }

    suspend fun listEventsNeedingModeration(): Result<List<BannedEntry>> {
        return callMethod("listeventsneedingmoderation").map { result ->
            (result as? JsonArray)?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.content
                BannedEntry(id, reason)
            } ?: emptyList()
        }
    }

    suspend fun changeRelayName(newName: String): Result<Boolean> {
        return callMethod("changerelayname", listOf(JsonPrimitive(newName))).map { true }
    }

    suspend fun changeRelayDescription(newDescription: String): Result<Boolean> {
        return callMethod("changerelaydescription", listOf(JsonPrimitive(newDescription))).map { true }
    }

    suspend fun changeRelayIcon(newIconUrl: String): Result<Boolean> {
        return callMethod("changerelayicon", listOf(JsonPrimitive(newIconUrl))).map { true }
    }

    suspend fun allowKind(kind: Int): Result<Boolean> {
        return callMethod("allowkind", listOf(JsonPrimitive(kind))).map { true }
    }

    suspend fun disallowKind(kind: Int): Result<Boolean> {
        return callMethod("disallowkind", listOf(JsonPrimitive(kind))).map { true }
    }

    suspend fun listAllowedKinds(): Result<List<Int>> {
        return callMethod("listallowedkinds").map { result ->
            (result as? JsonArray)?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() } ?: emptyList()
        }
    }

    suspend fun blockIp(ip: String, reason: String? = null): Result<Boolean> {
        val params = buildList {
            add(JsonPrimitive(ip))
            if (reason != null) add(JsonPrimitive(reason))
        }
        return callMethod("blockip", params).map { true }
    }

    suspend fun unblockIp(ip: String): Result<Boolean> {
        return callMethod("unblockip", listOf(JsonPrimitive(ip))).map { true }
    }

    suspend fun listBlockedIps(): Result<List<BlockedIp>> {
        return callMethod("listblockedips").map { result ->
            (result as? JsonArray)?.mapNotNull { elem ->
                val obj = elem.jsonObject
                val ip = obj["ip"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.content
                BlockedIp(ip, reason)
            } ?: emptyList()
        }
    }

    // ΓöÇΓöÇ Core RPC ΓöÇΓöÇ

    /**
     * Execute a JSON-RPC method call against the relay's management API.
     */
    private suspend fun callMethod(
        method: String,
        params: List<JsonElement> = emptyList()
    ): Result<JsonElement?> = withContext(Dispatchers.IO) {
        try {
            val rpcPayload = JsonObject(
                mapOf(
                    "method" to JsonPrimitive(method),
                    "params" to JsonArray(params)
                )
            ).toString()

            // Build NIP-98 authorization header if provider is set
            var nip98AuthHeader: String? = null
            val auth = authProvider
            if (auth != null) {
                try {
                    val payloadHash = sha256Hex(rpcPayload)
                    nip98AuthHeader = "Nostr ${auth(relayWsUrl, method, payloadHash)}"
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to generate NIP-98 auth for $method: ${e.message}")
                }
            }

            val response = httpClient.post(httpUrl) {
                setBody(TextContent(rpcPayload, NIP86_CONTENT_TYPE))
                nip98AuthHeader?.let { header("Authorization", it) }
            }

            if (response.status.value == 401) {
                return@withContext Result.failure(Nip86AuthRequiredException("Authorization required for $method"))
            }

            if (response.status.value !in 200..299) {
                return@withContext Result.failure(
                    Nip86ApiException("HTTP ${response.status} for $method")
                )
            }

            val responseBody = response.bodyAsText()
            if (responseBody.isBlank()) {
                return@withContext Result.failure(Nip86ApiException("Empty response for $method"))
            }

            val json = JSON.parseToJsonElement(responseBody).jsonObject
            val error = json["error"]?.jsonPrimitive?.content
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(Nip86ApiException(error))
            }

            Result.success(json["result"])
        } catch (e: Nip86AuthRequiredException) {
            Result.failure(e)
        } catch (e: Nip86ApiException) {
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "RPC call $method failed: ${e.message}", e)
            Result.failure(Nip86ApiException("Network error: ${e.message}"))
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ΓöÇΓöÇ Data classes ΓöÇΓöÇ

    data class BannedEntry(val id: String, val reason: String?)
    data class BlockedIp(val ip: String, val reason: String?)

    // ΓöÇΓöÇ Exceptions ΓöÇΓöÇ

    class Nip86AuthRequiredException(message: String) : Exception(message)
    class Nip86ApiException(message: String) : Exception(message)
}