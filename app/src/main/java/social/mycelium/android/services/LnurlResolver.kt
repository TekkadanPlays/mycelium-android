package social.mycelium.android.services

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves Lightning addresses (LUD-16) to bolt11 invoices via LNURL-pay (LUD-06).
 *
 * Flow:
 * 1. Convert lightning address (user@domain) to LNURL endpoint: https://domain/.well-known/lnurlp/user
 * 2. GET the endpoint → JSON with callback URL, min/max sendable (millisats)
 * 3. GET callback?amount=<millisats> → JSON with pr (bolt11 invoice)
 */
object LnurlResolver {
    private const val TAG = "LnurlResolver"

    sealed class LnurlResult {
        data class Invoice(val bolt11: String) : LnurlResult()
        data class Error(val message: String) : LnurlResult()
    }

    /**
     * Fetch a bolt11 invoice for the given lightning address and amount.
     * @param lightningAddress e.g. "user@walletofsatoshi.com"
     * @param amountSats amount in satoshis
     * @param comment optional comment for the payment
     */
    suspend fun fetchInvoice(
        lightningAddress: String,
        amountSats: Long,
        comment: String = ""
    ): LnurlResult = withContext(Dispatchers.IO) {
        try {
            val parts = lightningAddress.split("@")
            if (parts.size != 2) return@withContext LnurlResult.Error("Invalid lightning address")
            val user = parts[0]
            val domain = parts[1]

            // Step 1: Resolve LNURL endpoint
            val lnurlEndpoint = "https://$domain/.well-known/lnurlp/$user"
            Log.d(TAG, "Resolving LNURL: $lnurlEndpoint")

            val metaJson = httpGet(lnurlEndpoint)
                ?: return@withContext LnurlResult.Error("Failed to reach $domain")
            val meta = JSONObject(metaJson)

            if (meta.optString("status") == "ERROR") {
                return@withContext LnurlResult.Error(meta.optString("reason", "LNURL error"))
            }

            val callback = meta.optString("callback")
            if (callback.isBlank()) return@withContext LnurlResult.Error("No callback URL in LNURL response")

            val minSendable = meta.optLong("minSendable", 1000) / 1000 // millisats → sats
            val maxSendable = meta.optLong("maxSendable", 100_000_000_000) / 1000
            if (amountSats < minSendable || amountSats > maxSendable) {
                return@withContext LnurlResult.Error("Amount must be between $minSendable and $maxSendable sats")
            }

            // Step 2: Request invoice
            val amountMillisats = amountSats * 1000
            val separator = if (callback.contains("?")) "&" else "?"
            val invoiceUrl = "${callback}${separator}amount=$amountMillisats" +
                if (comment.isNotBlank()) "&comment=${java.net.URLEncoder.encode(comment, "UTF-8")}" else ""

            Log.d(TAG, "Fetching invoice: $invoiceUrl")
            val invoiceJson = httpGet(invoiceUrl)
                ?: return@withContext LnurlResult.Error("Failed to fetch invoice")
            val invoiceObj = JSONObject(invoiceJson)

            if (invoiceObj.optString("status") == "ERROR") {
                return@withContext LnurlResult.Error(invoiceObj.optString("reason", "Invoice error"))
            }

            val bolt11 = invoiceObj.optString("pr")
            if (bolt11.isBlank()) return@withContext LnurlResult.Error("No invoice in response")

            Log.d(TAG, "Got invoice: ${bolt11.take(20)}...")
            LnurlResult.Invoice(bolt11)
        } catch (e: Exception) {
            Log.e(TAG, "fetchInvoice failed: ${e.message}", e)
            LnurlResult.Error("Failed: ${e.message?.take(60)}")
        }
    }

    private fun httpGet(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            conn.connect()
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "HTTP ${conn.responseCode} for $urlString")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "httpGet failed for $urlString: ${e.message}")
            null
        }
    }
}
