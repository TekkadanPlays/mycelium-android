package social.mycelium.android.services

import android.content.Context
import android.util.Log
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle
import social.mycelium.android.repository.NwcConfig
import social.mycelium.android.repository.NwcConfigRepository
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.core.hexToByteArray
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.crypto.KeyPair
import com.example.cybin.signer.NostrSignerInternal
import com.example.cybin.nip47.LnZapPaymentRequestEvent
import com.example.cybin.nip47.LnZapPaymentResponseEvent
import com.example.cybin.nip47.PayInvoiceErrorResponse
import com.example.cybin.nip47.PayInvoiceSuccessResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of an NWC payment attempt.
 */
sealed class NwcPaymentResult {
    data class Success(val preimage: String?) : NwcPaymentResult()
    data class Error(val message: String) : NwcPaymentResult()
}

/**
 * Manages NIP-47 Nostr Wallet Connect payments.
 *
 * Flow:
 * 1. Read NWC config (wallet pubkey, relay, secret) from NwcConfigRepository
 * 2. Create a NostrSignerInternal from the NWC secret (this is the "app key" derived from
 *    the nostr+walletconnect:// URI)
 * 3. Build a kind-23194 pay_invoice request, NIP-04 encrypted to the wallet pubkey
 * 4. Send to the NWC relay
 * 5. Subscribe for kind-23195 response, decrypt, and return result
 */
object NwcPaymentManager {
    private const val TAG = "NwcPaymentManager"
    private const val PAYMENT_TIMEOUT_MS = 60_000L

    /**
     * Check if NWC is configured and ready to use.
     */
    fun isConfigured(context: Context): Boolean {
        val config = NwcConfigRepository.getConfig(context)
        return config.pubkey.isNotBlank() && config.relay.isNotBlank() && config.secret.isNotBlank()
    }

    /**
     * Pay a bolt11 invoice via NWC.
     *
     * @param context   Application context (for reading NWC config)
     * @param bolt11    The Lightning invoice to pay
     * @return NwcPaymentResult indicating success or failure
     */
    suspend fun payInvoice(
        context: Context,
        bolt11: String
    ): NwcPaymentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "payInvoice called, bolt11 length=${bolt11.length}")
        val config = NwcConfigRepository.getConfig(context)
        if (config.pubkey.isBlank() || config.relay.isBlank() || config.secret.isBlank()) {
            Log.w(TAG, "payInvoice: NWC not configured (pubkey=${config.pubkey.isNotBlank()}, relay=${config.relay.isNotBlank()}, secret=${config.secret.isNotBlank()})")
            return@withContext NwcPaymentResult.Error("NWC not configured. Set up Wallet Connect in Settings.")
        }

        return@withContext payInvoiceInternal(config, bolt11)
    }

    private suspend fun payInvoiceInternal(
        config: NwcConfig,
        bolt11: String
    ): NwcPaymentResult {
        try {
            // Create signer from NWC secret (the "app" key)
            val secretBytes = config.secret.hexToByteArray()
            val nwcSigner = NostrSignerInternal(KeyPair(privKey = secretBytes))

            Log.d(TAG, "NWC signer pubkey: ${nwcSigner.pubKey.take(8)}...")
            Log.d(TAG, "Wallet service pubkey: ${config.pubkey.take(8)}...")
            Log.d(TAG, "NWC relay: ${config.relay}")

            // Build and sign kind-23194 pay_invoice request
            val paymentRequest = LnZapPaymentRequestEvent.create(
                lnInvoice = bolt11,
                walletServicePubkey = config.pubkey,
                signer = nwcSigner
            )

            Log.d(TAG, "Created payment request: id=${paymentRequest.id.take(8)}, kind=${paymentRequest.kind}")

            // Set up deferred result for the response
            val responseDeferred = CompletableDeferred<NwcPaymentResult>()
            var subscriptionHandle: TemporarySubscriptionHandle? = null

            // Subscribe for kind-23195 response from the wallet service
            val filter = Filter(
                kinds = listOf(LnZapPaymentResponseEvent.KIND),
                tags = mapOf("p" to listOf(nwcSigner.pubKey)),
                since = nowUnixSeconds() - 60
            )

            val rsm = RelayConnectionStateMachine.getInstance()
            subscriptionHandle = rsm.requestTemporarySubscription(
                relayUrls = listOf(config.relay),
                filter = filter,
                priority = SubscriptionPriority.HIGH,
                onEvent = { event: Event ->
                    if (event.kind == LnZapPaymentResponseEvent.KIND) {
                        handleResponseEvent(event, nwcSigner, paymentRequest.id, responseDeferred)
                    }
                }
            )

            // Send the payment request to the NWC relay
            rsm.send(paymentRequest, setOf(config.relay))
            Log.d(TAG, "Sent payment request to NWC relay")

            // Wait for response with timeout
            val result = withTimeoutOrNull(PAYMENT_TIMEOUT_MS) {
                responseDeferred.await()
            }
            if (result == null) {
                Log.w(TAG, "payInvoice: timed out waiting for NWC response after ${PAYMENT_TIMEOUT_MS / 1000}s")
            }
            val finalResult = result ?: NwcPaymentResult.Error("Payment timed out after ${PAYMENT_TIMEOUT_MS / 1000}s")

            // Clean up subscription
            subscriptionHandle.cancel()

            return finalResult
        } catch (e: Exception) {
            Log.e(TAG, "NWC payment failed: ${e.message}", e)
            return NwcPaymentResult.Error("Payment failed: ${e.message?.take(80)}")
        }
    }

    private fun handleResponseEvent(
        event: Event,
        signer: NostrSignerInternal,
        requestId: String,
        deferred: CompletableDeferred<NwcPaymentResult>
    ) {
        try {
            // Check that this response is for our request
            val responseRequestId = LnZapPaymentResponseEvent.requestId(event)
            if (responseRequestId != null && responseRequestId != requestId) {
                Log.d(TAG, "Ignoring response for different request: $responseRequestId")
                return
            }

            // Decrypt and parse the response
            kotlinx.coroutines.runBlocking {
                val response = LnZapPaymentResponseEvent.decrypt(event, signer)
                Log.d(TAG, "Decrypted NWC response: type=${response.resultType}")

                when (response) {
                    is PayInvoiceSuccessResponse -> {
                        val preimage = response.result?.preimage
                        Log.d(TAG, "Payment successful! preimage=${preimage?.take(16)}...")
                        deferred.complete(NwcPaymentResult.Success(preimage))
                    }
                    is PayInvoiceErrorResponse -> {
                        val errorMsg = response.error?.message ?: response.error?.code?.name ?: "Unknown error"
                        Log.w(TAG, "Payment error: $errorMsg")
                        deferred.complete(NwcPaymentResult.Error(errorMsg))
                    }
                    else -> {
                        Log.w(TAG, "Unexpected response type: ${response.resultType}")
                        deferred.complete(NwcPaymentResult.Error("Unexpected response"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling NWC response: ${e.message}", e)
            if (!deferred.isCompleted) {
                deferred.complete(NwcPaymentResult.Error("Failed to process response: ${e.message?.take(60)}"))
            }
        }
    }
}
