package social.mycelium.android.repository

import android.util.Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import social.mycelium.android.network.MyceliumHttpClient

/**
 * Real CoinOS API provider — issues HTTP requests to coinos.io/api.
 */
class RealCoinosApiProvider : CoinosApiProvider {

    private companion object {
        const val TAG = "RealCoinosApi"
        const val BASE_URL = "https://coinos.io/api"
        const val USER_AGENT = "Mycelium"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = MyceliumHttpClient.instance

    override suspend fun fetchChallenge(): Result<String> = runCatching {
        val resp = httpClient.get("$BASE_URL/challenge")
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess() || body.isBlank()) {
            error("Failed to get challenge: ${resp.status}")
        }
        val obj = json.decodeFromString<JsonObject>(body)
        obj["challenge"]?.jsonPrimitive?.content
            ?: error("No challenge in response")
    }

    override suspend fun authenticate(eventJson: String, challenge: String): Result<AuthResult> = runCatching {
        val payload = """{"event":$eventJson,"challenge":"$challenge"}"""
        Log.d(TAG, "POST /nostrAuth body=${payload.take(200)}...")
        val resp = httpClient.post("$BASE_URL/nostrAuth") {
            header("User-Agent", USER_AGENT)
            setBody(TextContent(payload, ContentType.Application.Json))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess() || body.isBlank()) {
            error("Auth failed: ${resp.status}")
        }
        val obj = json.decodeFromString<JsonObject>(body)
        val jwt = obj["token"]?.jsonPrimitive?.content ?: error("No token in response")
        val username = try {
            obj["user"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            try {
                val userMap = json.decodeFromString<JsonObject>(obj["user"].toString())
                userMap["username"]?.jsonPrimitive?.content
            } catch (_: Exception) { null }
        } ?: "unknown"
        AuthResult(token = jwt, username = username)
    }

    override suspend fun fetchUserInfo(token: String): Result<UserInfo> = runCatching {
        val resp = httpClient.get("$BASE_URL/me") {
            header("Authorization", "Bearer $token")
        }
        val body = resp.bodyAsText()
        if (resp.status.value == 401) error("Session expired")
        if (!resp.status.isSuccess() || body.isBlank()) error("Fetch failed: ${resp.status}")
        val obj = json.decodeFromString<JsonObject>(body)
        UserInfo(
            balance = obj["balance"]?.jsonPrimitive?.longOrNull ?: 0L,
            username = obj["username"]?.jsonPrimitive?.content,
        )
    }

    override suspend fun createInvoice(token: String, amountSats: Long, memo: String): Result<String> = runCatching {
        val payload = if (memo.isNotBlank()) {
            """{"invoice":{"amount":$amountSats,"memo":"$memo","type":"lightning"}}"""
        } else {
            """{"invoice":{"amount":$amountSats,"type":"lightning"}}"""
        }
        val resp = httpClient.post("$BASE_URL/invoice") {
            header("Authorization", "Bearer $token")
            setBody(TextContent(payload, ContentType.Application.Json))
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess() || body.isBlank()) error("Invoice creation failed: ${resp.status}")
        val obj = json.decodeFromString<JsonObject>(body)
        obj["text"]?.jsonPrimitive?.content
            ?: obj["address"]?.jsonPrimitive?.content
            ?: obj["hash"]?.jsonPrimitive?.content
            ?: error("No invoice in response")
    }

    override suspend fun payInvoice(token: String, bolt11: String): Result<Boolean> = runCatching {
        val payload = """{"payreq":"$bolt11"}"""
        val resp = httpClient.post("$BASE_URL/payments") {
            header("Authorization", "Bearer $token")
            setBody(TextContent(payload, ContentType.Application.Json))
        }
        if (!resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            error("Payment failed: ${resp.status} $body")
        }
        true
    }

    override suspend fun fetchTransactions(token: String): Result<List<CoinosTransaction>> = runCatching {
        val resp = httpClient.get("$BASE_URL/payments") {
            header("Authorization", "Bearer $token")
        }
        val body = resp.bodyAsText()
        if (!resp.status.isSuccess() || body.isBlank()) error("Tx fetch failed: ${resp.status}")
        val arr = json.decodeFromString<List<JsonObject>>(body)
        arr.mapNotNull { obj ->
            try {
                CoinosTransaction(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    amount = obj["amount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    memo = obj["memo"]?.jsonPrimitive?.content,
                    createdAt = obj["created_at"]?.jsonPrimitive?.content,
                    confirmed = obj["confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                    type = obj["type"]?.jsonPrimitive?.content ?: "lightning",
                )
            } catch (_: Exception) { null }
        }
    }
}
