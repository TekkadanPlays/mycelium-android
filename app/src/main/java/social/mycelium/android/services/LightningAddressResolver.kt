package social.mycelium.android.services

import android.util.Log
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import social.mycelium.android.network.MyceliumHttpClient

/**
 * Response from the LNURLp endpoint (/.well-known/lnurlp/<user>).
 */
data class LnUrlPayInfo(
    val callback: String,
    val minSendable: Long,   // millisats
    val maxSendable: Long,   // millisats
    val allowsNostr: Boolean,
    val nostrPubkey: String? // hex pubkey of the LN provider for zap receipts
)

/**
 * Resolves Lightning Addresses (LUD-16) to bolt11 invoices.
 *
 * Flow:
 * 1. Parse lud16 (user@domain) → GET https://domain/.well-known/lnurlp/user
 * 2. Parse the JSON response for callback, minSendable, maxSendable, allowsNostr, nostrPubkey
 * 3. Fetch an invoice from the callback URL with amount (and optional zap request)
 */
object LightningAddressResolver {
    private const val TAG = "LnAddressResolver"
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = MyceliumHttpClient.instance

    /**
     * Resolve a lud16 address (user@domain) into the well-known LNURLp URL.
     * @return the URL string, or null if the address is invalid
     */
    fun resolveAddress(lud16: String): String? {
        val parts = lud16.trim().split("@")
        if (parts.size != 2) {
            Log.w(TAG, "Invalid lud16 format: $lud16")
            return null
        }
        val (user, domain) = parts
        return "https://$domain/.well-known/lnurlp/$user"
    }

    /**
     * Fetch the LNURLp metadata from the resolved URL.
     */
    suspend fun fetchPayInfo(lnurlpUrl: String): LnUrlPayInfo? {
        return try {
            val response = httpClient.get(lnurlpUrl)
            if (!response.status.isSuccess()) {
                Log.w(TAG, "LNURLp fetch failed: ${response.status}")
                return null
            }

            val responseBody = response.bodyAsText()
            if (responseBody.isBlank()) return null
            val jsonObj = json.parseToJsonElement(responseBody).jsonObject

            LnUrlPayInfo(
                callback = jsonObj["callback"]?.jsonPrimitive?.content ?: return null,
                minSendable = jsonObj["minSendable"]?.jsonPrimitive?.longOrNull ?: 1000,
                maxSendable = jsonObj["maxSendable"]?.jsonPrimitive?.longOrNull ?: 100_000_000_000,
                allowsNostr = jsonObj["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
                nostrPubkey = jsonObj["nostrPubkey"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching LNURLp info: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch a bolt11 invoice from the callback URL.
     *
     * @param callbackUrl  The callback URL from the LNURLp response
     * @param amountMillisats  The amount to pay in millisats
     * @param zapRequestJson  Optional JSON-encoded zap request event (for NIP-57)
     * @return The bolt11 invoice string, or null on failure
     */
    suspend fun fetchInvoice(
        callbackUrl: String,
        amountMillisats: Long,
        zapRequestJson: String? = null
    ): String? {
        return try {
            val invoiceUrl = buildString {
                append(callbackUrl)
                append(if ("?" in callbackUrl) "&" else "?")
                append("amount=$amountMillisats")
                if (zapRequestJson != null) {
                    append("&nostr=${java.net.URLEncoder.encode(zapRequestJson, "UTF-8")}")
                }
            }
            Log.d(TAG, "Fetching invoice from: $invoiceUrl")

            val response = httpClient.get(invoiceUrl)
            if (!response.status.isSuccess()) {
                Log.w(TAG, "Invoice fetch failed: ${response.status}")
                return null
            }

            val responseBody = response.bodyAsText()
            if (responseBody.isBlank()) return null
            val jsonObj = json.parseToJsonElement(responseBody).jsonObject

            // Check for errors
            val status = jsonObj["status"]?.jsonPrimitive?.content
            if (status == "ERROR") {
                val reason = jsonObj["reason"]?.jsonPrimitive?.content ?: "Unknown error"
                Log.w(TAG, "Invoice fetch error: $reason")
                return null
            }

            jsonObj["pr"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching invoice: ${e.message}", e)
            null
        }
    }

    /**
     * Convenience: resolve lud16 → LnUrlPayInfo in one call.
     */
    suspend fun resolveLud16(lud16: String): LnUrlPayInfo? {
        val url = resolveAddress(lud16) ?: return null
        return fetchPayInfo(url)
    }
}
