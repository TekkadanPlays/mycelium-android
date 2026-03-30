package social.mycelium.android.services

import android.content.Context
import android.util.Log
import social.mycelium.android.repository.sync.NwcConfig
import social.mycelium.android.repository.sync.NwcConfigRepository
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.core.hexToByteArray
import com.example.cybin.crypto.KeyPair
import com.example.cybin.relay.NostrProtocol
import com.example.cybin.signer.NostrSignerInternal
import com.example.cybin.nip47.LnZapPaymentRequestEvent
import com.example.cybin.nip47.LnZapPaymentResponseEvent
import com.example.cybin.nip47.PayInvoiceErrorResponse
import com.example.cybin.nip47.PayInvoiceSuccessResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed class NwcPaymentResult {
    data class Success(val preimage: String?) : NwcPaymentResult()
    data class Error(val message: String) : NwcPaymentResult()
}

/**
 * Manages NIP-47 Nostr Wallet Connect payments via dedicated Ktor CIO WebSocket.
 *
 * Direct WebSocket gives full wire-level control for responsive feedback:
 * - OK: true from relay   → event accepted, wallet is processing
 * - OK: false from relay  → relay rejected event, fail immediately
 * - CLOSED message        → subscription killed, fail immediately
 * - kind-23195 EVENT      → wallet responded, complete immediately
 * - No OK within 5s       → relay unresponsive, fail fast
 * - No response within 10s after OK → wallet offline, timeout
 */
object NwcPaymentManager {
    private const val TAG = "NwcPaymentManager"
    private const val RELAY_OK_TIMEOUT_MS = 5_000L
    private const val WALLET_RESPONSE_TIMEOUT_MS = 10_000L

    private val wsClient by lazy {
        HttpClient(CIO) {
            install(WebSockets) { pingIntervalMillis = 20_000 }
        }
    }

    fun isConfigured(context: Context): Boolean {
        val config = NwcConfigRepository.getConfig(context)
        return config.pubkey.isNotBlank() && config.relay.isNotBlank() && config.secret.isNotBlank()
    }

    suspend fun payInvoice(
        context: Context,
        bolt11: String
    ): NwcPaymentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "payInvoice called, bolt11 length=${bolt11.length}")
        val config = NwcConfigRepository.getConfig(context)
        if (config.pubkey.isBlank() || config.relay.isBlank() || config.secret.isBlank()) {
            return@withContext NwcPaymentResult.Error("NWC not configured. Set up Wallet Connect in Settings.")
        }
        return@withContext payInvoiceInternal(config, bolt11)
    }

    private suspend fun payInvoiceInternal(
        config: NwcConfig,
        bolt11: String,
    ): NwcPaymentResult {
        val secretBytes = config.secret.hexToByteArray()
        val nwcSigner = NostrSignerInternal(KeyPair(privKey = secretBytes))

        Log.d(TAG, "NWC signer=${nwcSigner.pubKey.take(8)}, wallet=${config.pubkey.take(8)}, relay=${config.relay}")

        val paymentRequest = LnZapPaymentRequestEvent.create(
            lnInvoice = bolt11,
            walletServicePubkey = config.pubkey,
            signer = nwcSigner,
            useNip44 = false,
        )
        Log.d(TAG, "Request id=${paymentRequest.id.take(12)}")

        val responseDeferred = CompletableDeferred<NwcPaymentResult>()
        val relayAccepted = CompletableDeferred<Boolean>()

        try {
            var finalResult: NwcPaymentResult? = null

            wsClient.webSocket(config.relay) {
                Log.d(TAG, "Connected to ${config.relay}")

                val subId = "nwc_${System.currentTimeMillis()}"

                val readerJob = launch {
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val raw = frame.readText()
                                handleMessage(raw, subId, config.pubkey, nwcSigner, paymentRequest.id, responseDeferred, relayAccepted)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Read error: ${e.message}")
                        if (!relayAccepted.isCompleted) relayAccepted.complete(false)
                        if (!responseDeferred.isCompleted) {
                            responseDeferred.complete(NwcPaymentResult.Error("Connection lost"))
                        }
                    }
                }

                // Subscribe for kind-23195 responses
                val filter = Filter(
                    kinds = listOf(LnZapPaymentResponseEvent.KIND),
                    authors = listOf(config.pubkey),
                    tags = mapOf(
                        "e" to listOf(paymentRequest.id),
                        "p" to listOf(nwcSigner.pubKey),
                    ),
                )
                send(Frame.Text(NostrProtocol.buildReq(subId, filter)))

                // Publish the payment request
                send(Frame.Text(NostrProtocol.buildEvent(paymentRequest)))

                // Phase 1: Wait for relay to acknowledge (fast fail if rejected)
                val accepted = withTimeoutOrNull(RELAY_OK_TIMEOUT_MS) {
                    relayAccepted.await()
                }
                if (accepted == null) {
                    Log.w(TAG, "Relay did not acknowledge event within ${RELAY_OK_TIMEOUT_MS / 1000}s")
                    readerJob.cancel()
                    finalResult = NwcPaymentResult.Error("Relay unresponsive")
                } else if (accepted == false) {
                    readerJob.cancel()
                    finalResult = if (responseDeferred.isCompleted) responseDeferred.getCompleted()
                        else NwcPaymentResult.Error("Relay rejected payment request")
                } else if (responseDeferred.isCompleted) {
                    // Wallet already responded before we checked (fast path)
                    readerJob.cancel()
                    finalResult = responseDeferred.getCompleted()
                } else {
                    // Phase 2: Relay accepted — wait for wallet response
                    Log.d(TAG, "Relay accepted, waiting for wallet response...")
                    val result = withTimeoutOrNull(WALLET_RESPONSE_TIMEOUT_MS) {
                        responseDeferred.await()
                    }
                    try { send(Frame.Text(NostrProtocol.buildClose(subId))) } catch (_: Exception) {}
                    readerJob.cancel()
                    finalResult = result
                }
            }

            if (finalResult != null) return finalResult!!

            Log.w(TAG, "Wallet did not respond within ${WALLET_RESPONSE_TIMEOUT_MS / 1000}s")
            return NwcPaymentResult.Error("Wallet did not respond — may be offline")

        } catch (e: Exception) {
            Log.e(TAG, "NWC payment failed: ${e.message}", e)
            return NwcPaymentResult.Error("Payment failed: ${e.message?.take(80)}")
        }
    }

    private suspend fun handleMessage(
        raw: String,
        subId: String,
        walletPubkey: String,
        signer: NostrSignerInternal,
        requestId: String,
        responseDeferred: CompletableDeferred<NwcPaymentResult>,
        relayAccepted: CompletableDeferred<Boolean>,
    ) {
        try {
            when (val msg = NostrProtocol.parseRelayMessage(raw)) {
                is NostrProtocol.RelayMessage.EventMsg -> {
                    if (msg.subscriptionId == subId && msg.event.kind == LnZapPaymentResponseEvent.KIND) {
                        Log.d(TAG, "Got kind-23195 id=${msg.event.id.take(12)}")
                        processResponse(msg.event, signer, requestId, responseDeferred)
                    }
                }
                is NostrProtocol.RelayMessage.Ok -> {
                    if (msg.eventId == requestId) {
                        Log.d(TAG, "OK: accepted=${msg.success}${if (msg.message.isNotEmpty()) " msg=${msg.message}" else ""}")
                        if (msg.success) {
                            if (!relayAccepted.isCompleted) relayAccepted.complete(true)
                        } else {
                            if (!responseDeferred.isCompleted) {
                                responseDeferred.complete(NwcPaymentResult.Error("Relay rejected: ${msg.message.take(80).ifEmpty { "unknown reason" }}"))
                            }
                            if (!relayAccepted.isCompleted) relayAccepted.complete(false)
                        }
                    }
                }
                is NostrProtocol.RelayMessage.EndOfStoredEvents -> {}
                is NostrProtocol.RelayMessage.Closed -> {
                    Log.w(TAG, "CLOSED: ${msg.message}")
                    if (!responseDeferred.isCompleted) {
                        responseDeferred.complete(NwcPaymentResult.Error("Subscription closed: ${msg.message.take(60)}"))
                    }
                    if (!relayAccepted.isCompleted) relayAccepted.complete(false)
                }
                is NostrProtocol.RelayMessage.Notice -> {
                    Log.w(TAG, "NOTICE: ${msg.message}")
                }
                is NostrProtocol.RelayMessage.Auth -> {
                    Log.w(TAG, "AUTH required by NWC relay")
                }
                else -> {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${e.message}")
        }
    }

    private suspend fun processResponse(
        event: Event,
        signer: NostrSignerInternal,
        requestId: String,
        deferred: CompletableDeferred<NwcPaymentResult>,
    ) {
        if (deferred.isCompleted) return
        try {
            val responseRequestId = LnZapPaymentResponseEvent.requestId(event)
            if (responseRequestId != null && responseRequestId != requestId) {
                Log.d(TAG, "Skipping stale response (${responseRequestId.take(12)} != ${requestId.take(12)})")
                return
            }

            val response = LnZapPaymentResponseEvent.decrypt(event, signer)
            when (response) {
                is PayInvoiceSuccessResponse -> {
                    Log.d(TAG, "SUCCESS preimage=${response.result?.preimage?.take(16)}")
                    deferred.complete(NwcPaymentResult.Success(response.result?.preimage))
                }
                is PayInvoiceErrorResponse -> {
                    val msg = response.error?.message ?: response.error?.code?.name ?: "Unknown error"
                    Log.w(TAG, "PAYMENT ERROR: $msg")
                    deferred.complete(NwcPaymentResult.Error(msg))
                }
                else -> {
                    deferred.complete(NwcPaymentResult.Error("Unexpected response"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decrypt error: ${e.message}")
            if (!deferred.isCompleted) {
                deferred.complete(NwcPaymentResult.Error("Failed to decrypt response"))
            }
        }
    }
}
