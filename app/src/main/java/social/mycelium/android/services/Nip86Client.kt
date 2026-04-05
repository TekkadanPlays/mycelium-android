package social.mycelium.android.services

import android.util.Base64
import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.signer.NostrSigner
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import social.mycelium.android.network.MyceliumHttpClient
import java.security.MessageDigest

/**
 * NIP-86 Relay Management API client.
 *
 * Sends JSON-RPC-like requests over HTTP to relay management endpoints,
 * authenticated with NIP-98 (kind 27235) signed events.
 *
 * Usage:
 * ```
 * val client = Nip86Client(signer)
 * val methods = client.supportedMethods("wss://relay.example.com")
 * if ("changerelayname" in methods) {
 *     client.changeRelayName("wss://relay.example.com", "My Relay")
 * }
 * ```
 */
class Nip86Client(private val signer: NostrSigner) {

    companion object {
        private const val TAG = "Nip86Client"
        private const val NIP98_KIND = 27235
        private const val CONTENT_TYPE = "application/nostr+json+rpc"
    }

    sealed class Nip86Result<T> {
        data class Success<T>(val data: T) : Nip86Result<T>()
        data class Error<T>(val message: String) : Nip86Result<T>()
    }

    /**
     * Query which NIP-86 methods the relay supports.
     * This is the entry point — call this first to discover capabilities.
     */
    suspend fun supportedMethods(relayUrl: String): Nip86Result<List<String>> {
        return call(relayUrl, "supportedmethods", JSONArray()) { result ->
            val arr = result as? JSONArray ?: return@call emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        }
    }

    suspend fun changeRelayName(relayUrl: String, newName: String): Nip86Result<Boolean> {
        return call(relayUrl, "changerelayname", JSONArray().put(newName)) { true }
    }

    suspend fun changeRelayDescription(relayUrl: String, newDescription: String): Nip86Result<Boolean> {
        return call(relayUrl, "changerelaydescription", JSONArray().put(newDescription)) { true }
    }

    suspend fun changeRelayIcon(relayUrl: String, newIconUrl: String): Nip86Result<Boolean> {
        return call(relayUrl, "changerelayicon", JSONArray().put(newIconUrl)) { true }
    }

    suspend fun banPubkey(relayUrl: String, pubkey: String, reason: String = ""): Nip86Result<Boolean> {
        val params = JSONArray().put(pubkey)
        if (reason.isNotBlank()) params.put(reason)
        return call(relayUrl, "banpubkey", params) { true }
    }

    suspend fun unbanPubkey(relayUrl: String, pubkey: String, reason: String = ""): Nip86Result<Boolean> {
        val params = JSONArray().put(pubkey)
        if (reason.isNotBlank()) params.put(reason)
        return call(relayUrl, "unbanpubkey", params) { true }
    }

    data class BannedEntry(val pubkey: String, val reason: String?)

    suspend fun listBannedPubkeys(relayUrl: String): Nip86Result<List<BannedEntry>> {
        return call(relayUrl, "listbannedpubkeys", JSONArray()) { result ->
            val arr = result as? JSONArray ?: return@call emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                BannedEntry(
                    pubkey = obj.getString("pubkey"),
                    reason = if (obj.has("reason")) obj.optString("reason") else null
                )
            }
        }
    }

    // ── Allow list management ──

    data class AllowedEntry(val pubkey: String, val reason: String?)

    suspend fun listAllowedPubkeys(relayUrl: String): Nip86Result<List<AllowedEntry>> {
        return call(relayUrl, "listallowedpubkeys", JSONArray()) { result ->
            val arr = result as? JSONArray ?: return@call emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AllowedEntry(
                    pubkey = obj.getString("pubkey"),
                    reason = if (obj.has("reason")) obj.optString("reason") else null
                )
            }
        }
    }

    suspend fun allowPubkey(relayUrl: String, pubkey: String, reason: String = ""): Nip86Result<Boolean> {
        val params = JSONArray().put(pubkey)
        if (reason.isNotBlank()) params.put(reason)
        return call(relayUrl, "allowpubkey", params) { true }
    }

    suspend fun disallowPubkey(relayUrl: String, pubkey: String, reason: String = ""): Nip86Result<Boolean> {
        val params = JSONArray().put(pubkey)
        if (reason.isNotBlank()) params.put(reason)
        return call(relayUrl, "disallowpubkey", params) { true }
    }

    // ── Event management ──

    suspend fun deleteEvent(relayUrl: String, eventId: String, reason: String = ""): Nip86Result<Boolean> {
        val params = JSONArray().put(eventId)
        if (reason.isNotBlank()) params.put(reason)
        return call(relayUrl, "banevent", params) { true }
    }

    // ── Internal ──

    /**
     * Execute a NIP-86 JSON-RPC call with NIP-98 authentication.
     */
    private suspend fun <T> call(
        relayUrl: String,
        method: String,
        params: JSONArray,
        parseResult: (Any?) -> T
    ): Nip86Result<T> = withContext(Dispatchers.IO) {
        try {
            val httpUrl = relayWsToHttp(relayUrl)

            // Build JSON-RPC body
            val body = JSONObject().apply {
                put("method", method)
                put("params", params)
            }.toString()

            // Create NIP-98 auth event (kind 27235)
            val payloadHash = sha256Hex(body.toByteArray())
            val authEvent = try {
                buildNip98AuthEvent(httpUrl, "POST", payloadHash)
            } catch (e: Exception) {
                MLog.e(TAG, "Failed to sign NIP-98 auth event (kind $NIP98_KIND) — Amber may lack permission: ${e.message}", e)
                return@withContext Nip86Result.Error("Signing failed (kind $NIP98_KIND) — re-login via Amber to grant permission")
            }

            // Base64-encode the signed event JSON for the Authorization header
            val authHeader = "Nostr ${Base64.encodeToString(authEvent.toJson().toByteArray(), Base64.NO_WRAP)}"

            val response = MyceliumHttpClient.instance.post(httpUrl) {
                contentType(ContentType.parse(CONTENT_TYPE))
                header("Accept", CONTENT_TYPE)
                header("Authorization", authHeader)
                setBody(body)
            }

            val responseText = response.bodyAsText()
            val status = response.status.value

            if (status == 401) {
                return@withContext Nip86Result.Error("Unauthorized — you may not be the relay operator")
            }
            if (status !in 200..299) {
                return@withContext Nip86Result.Error("HTTP $status: ${responseText.take(200)}")
            }

            // Guard against HTML responses (relay web frontend intercepting the POST)
            val trimmed = responseText.trimStart()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                MLog.w(TAG, "NIP-86 response is not JSON (starts with '${trimmed.take(20)}')")
                return@withContext Nip86Result.Error("Relay returned HTML instead of JSON — NIP-86 management endpoint may not be configured")
            }

            val json = JSONObject(responseText)
            val error = json.optString("error", "")
            if (error.isNotBlank()) {
                return@withContext Nip86Result.Error(error)
            }

            val result = json.opt("result")
            Nip86Result.Success(parseResult(result))

        } catch (e: Exception) {
            MLog.e(TAG, "NIP-86 call '$method' to $relayUrl failed: ${e.message}", e)
            Nip86Result.Error(e.message?.take(120) ?: "Unknown error")
        }
    }

    /**
     * Build and sign a NIP-98 HTTP Auth event (kind 27235).
     * Tags: ["u", <url>], ["method", "POST"], ["payload", <sha256-hex-of-body>]
     */
    private suspend fun buildNip98AuthEvent(url: String, method: String, payloadHash: String): Event {
        val template = Event.build(NIP98_KIND, "") {
            add(arrayOf("u", url))
            add(arrayOf("method", method))
            add(arrayOf("payload", payloadHash))
        }
        return signer.sign(template)
    }

    /**
     * Convert relay WebSocket URL to HTTP(S) URL.
     * wss://relay.example.com → https://relay.example.com
     * ws://relay.example.com → http://relay.example.com
     */
    private fun relayWsToHttp(relayUrl: String): String {
        return relayUrl
            .replace("^wss://".toRegex(), "https://")
            .replace("^ws://".toRegex(), "http://")
            .trimEnd('/')
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
