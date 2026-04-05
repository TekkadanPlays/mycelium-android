package social.mycelium.android.lightning

import android.content.Context
import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.core.hexToByteArray
import com.example.cybin.core.nowUnixSeconds
import com.example.cybin.core.toHexString
import com.example.cybin.crypto.KeyPair
import com.example.cybin.nip47.LnZapPaymentRequestEvent
import com.example.cybin.nip47.LnZapPaymentResponseEvent
import com.example.cybin.relay.SubscriptionPriority
import com.example.cybin.signer.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.TemporarySubscriptionHandle

/**
 * NIP-47 Nostr Wallet Connect **service provider**.
 *
 * This is the *wallet* side of NWC — the app listens for incoming kind-23194
 * requests (pay_invoice, make_invoice, get_balance) from authorized clients
 * and responds with kind-23195 events, backed by the embedded Phoenix Lightning node.
 *
 * ## Connection string
 * The service publishes a `nostr+walletconnect://` URI that external apps or
 * an LNURL proxy can use to talk to this wallet over Nostr relays.
 *
 * ## Supported methods
 * - `pay_invoice`  — Pay a bolt11 invoice via PhoenixWalletManager
 * - `make_invoice` — Create a bolt11 invoice via PhoenixWalletManager
 * - `get_balance`  — Return the current channel balance
 */
object NwcServiceProvider {
    private const val TAG = "NwcServiceProvider"
    private const val PREFS_NAME = "nwc_service_prefs"
    private const val KEY_SERVICE_PRIVKEY = "service_privkey"
    private const val KEY_NWC_RELAY = "nwc_relay"

    // Default relay for NWC traffic (lightweight, widely supported)
    private const val DEFAULT_NWC_RELAY = "wss://relay.damus.io"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── State ──────────────────────────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var serviceSigner: NostrSignerInternal? = null
    private var subscriptionHandle: TemporarySubscriptionHandle? = null
    private var nwcRelay: String = DEFAULT_NWC_RELAY

    // ── Initialization ─────────────────────────────────────────────────────

    /**
     * Start the NWC service provider. Generates a service keypair if needed,
     * then subscribes to kind-23194 requests on the NWC relay.
     */
    fun start(context: Context) {
        if (_isRunning.value) return
        val signer = getOrCreateServiceSigner(context)
        serviceSigner = signer
        nwcRelay = getRelay(context)

        MLog.d(TAG, "Starting NWC service provider, pubkey=${signer.pubKey.take(16)}...")
        MLog.d(TAG, "Listening on relay: $nwcRelay")

        // Subscribe for kind-23194 events tagged to our service pubkey
        val filter = Filter(
            kinds = listOf(LnZapPaymentRequestEvent.KIND),
            tags = mapOf("p" to listOf(signer.pubKey)),
            since = nowUnixSeconds() - 60  // recent events only
        )

        val rsm = RelayConnectionStateMachine.getInstance()
        subscriptionHandle = rsm.requestTemporarySubscription(
            relayUrls = listOf(nwcRelay),
            filter = filter,
            priority = SubscriptionPriority.HIGH,
            onEvent = { event -> handleIncomingRequest(event) }
        )

        _isRunning.value = true
        MLog.d(TAG, "NWC service provider started")
    }

    /**
     * Stop the NWC service provider and cancel the relay subscription.
     */
    fun stop() {
        subscriptionHandle?.cancel()
        subscriptionHandle = null
        serviceSigner = null
        _isRunning.value = false
        MLog.d(TAG, "NWC service provider stopped")
    }

    // ── Connection URI ─────────────────────────────────────────────────────

    /**
     * Generate the `nostr+walletconnect://` connection string.
     *
     * Format: `nostr+walletconnect://<service_pubkey>?relay=<relay>&secret=<client_secret>`
     *
     * The `secret` is a new random keypair generated for the connecting client.
     * In a full implementation each client would get its own secret; here we
     * generate a single shared client secret for simplicity.
     */
    fun getConnectionUri(context: Context): String? {
        val signer = getOrCreateServiceSigner(context)
        val relay = getRelay(context)
        // Generate a client secret (the "app" key that the connecting client uses)
        val clientKeyPair = getOrCreateClientKeyPair(context)
        val clientSecretHex = clientKeyPair.privKey?.toHexString() ?: return null

        return "nostr+walletconnect://${signer.pubKey}?relay=${
            java.net.URLEncoder.encode(relay, "UTF-8")
        }&secret=$clientSecretHex"
    }

    /**
     * Get the service pubkey (hex). Returns null if not initialized.
     */
    fun getServicePubkey(context: Context): String {
        return getOrCreateServiceSigner(context).pubKey
    }

    // ── Request handling ───────────────────────────────────────────────────

    private fun handleIncomingRequest(event: Event) {
        if (event.kind != LnZapPaymentRequestEvent.KIND) return
        val signer = serviceSigner ?: return

        scope.launch {
            try {
                MLog.d(TAG, "Received NWC request: id=${event.id.take(8)}, from=${event.pubKey.take(8)}")

                // Decrypt the request content (NIP-04 encrypted to our service pubkey)
                val plaintext = signer.nip04Decrypt(event.content, event.pubKey)
                val requestJson = JSONObject(plaintext)
                val method = requestJson.optString("method", "")
                val params = requestJson.optJSONObject("params") ?: JSONObject()

                MLog.d(TAG, "NWC method: $method")

                val responseJson = when (method) {
                    "pay_invoice" -> handlePayInvoice(params)
                    "make_invoice" -> handleMakeInvoice(params)
                    "get_balance" -> handleGetBalance()
                    "get_info" -> handleGetInfo()
                    else -> errorResponse(method, "NOT_IMPLEMENTED", "Method '$method' is not supported")
                }

                // Send kind-23195 response
                sendResponse(event, responseJson, signer)

            } catch (e: Exception) {
                MLog.e(TAG, "Error handling NWC request: ${e.message}", e)
                try {
                    val errorResp = errorResponse("unknown", "INTERNAL", "Internal error: ${e.message?.take(60)}")
                    sendResponse(event, errorResp, signer)
                } catch (e2: Exception) {
                    MLog.e(TAG, "Failed to send error response: ${e2.message}")
                }
            }
        }
    }

    private suspend fun handlePayInvoice(params: JSONObject): String {
        val invoice = params.optString("invoice", "")
        if (invoice.isBlank()) {
            return errorResponse("pay_invoice", "OTHER", "Missing 'invoice' parameter")
        }

        if (!PhoenixWalletManager.isReady()) {
            return errorResponse("pay_invoice", "INTERNAL", "Lightning node is not running")
        }

        MLog.d(TAG, "Paying invoice via Phoenix, length=${invoice.length}")
        return when (val result = PhoenixWalletManager.payInvoice(invoice)) {
            is PaymentResult.Success -> {
                MLog.d(TAG, "Payment successful, preimage=${result.preimage.take(16)}")
                JSONObject().apply {
                    put("result_type", "pay_invoice")
                    put("result", JSONObject().apply {
                        put("preimage", result.preimage)
                    })
                }.toString()
            }
            is PaymentResult.Error -> {
                MLog.w(TAG, "Payment failed: ${result.message}")
                errorResponse("pay_invoice", "PAYMENT_FAILED", result.message)
            }
        }
    }

    private suspend fun handleMakeInvoice(params: JSONObject): String {
        // NIP-47 make_invoice params: amount (msats), description, expiry
        val amountMsat = params.optLong("amount", 0)
        val description = params.optString("description", "NWC invoice")

        if (amountMsat <= 0) {
            return errorResponse("make_invoice", "OTHER", "Missing or invalid 'amount' (msats)")
        }

        if (!PhoenixWalletManager.isReady()) {
            return errorResponse("make_invoice", "INTERNAL", "Lightning node is not running")
        }

        MLog.d(TAG, "Creating invoice: ${amountMsat}msat, desc=$description")
        val bolt11 = PhoenixWalletManager.createInvoice(amountMsat, description)

        return if (bolt11 != null) {
            MLog.d(TAG, "Invoice created: ${bolt11.take(20)}...")
            JSONObject().apply {
                put("result_type", "make_invoice")
                put("result", JSONObject().apply {
                    put("type", "incoming")
                    put("invoice", bolt11)
                    put("amount", amountMsat)
                    put("description", description)
                    put("created_at", nowUnixSeconds())
                })
            }.toString()
        } else {
            errorResponse("make_invoice", "INTERNAL", "Failed to create invoice")
        }
    }

    private fun handleGetBalance(): String {
        val balanceMsat = PhoenixWalletManager.balanceMsat.value
        MLog.d(TAG, "get_balance: ${balanceMsat}msat")
        return JSONObject().apply {
            put("result_type", "get_balance")
            put("result", JSONObject().apply {
                put("balance", balanceMsat)
            })
        }.toString()
    }

    private fun handleGetInfo(): String {
        return JSONObject().apply {
            put("result_type", "get_info")
            put("result", JSONObject().apply {
                put("alias", "Mycelium Phoenix Wallet")
                put("network", "testnet")
                put("methods", org.json.JSONArray().apply {
                    put("pay_invoice")
                    put("make_invoice")
                    put("get_balance")
                    put("get_info")
                })
            })
        }.toString()
    }

    private fun errorResponse(resultType: String, code: String, message: String): String {
        return JSONObject().apply {
            put("result_type", resultType)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }.toString()
    }

    private suspend fun sendResponse(requestEvent: Event, responseJson: String, signer: NostrSignerInternal) {
        // Encrypt response to the request author
        val encrypted = signer.nip04Encrypt(responseJson, requestEvent.pubKey)

        // Build kind-23195 response event
        val tags = arrayOf(
            arrayOf("p", requestEvent.pubKey),
            arrayOf("e", requestEvent.id),
        )

        val responseEvent = signer.sign(
            createdAt = nowUnixSeconds(),
            kind = LnZapPaymentResponseEvent.KIND,
            tags = tags,
            content = encrypted,
        )

        // Send to the NWC relay
        val rsm = RelayConnectionStateMachine.getInstance()
        rsm.send(responseEvent, setOf(nwcRelay))
        MLog.d(TAG, "Sent NWC response: id=${responseEvent.id.take(8)}, to=${requestEvent.pubKey.take(8)}")
    }

    // ── Key management ─────────────────────────────────────────────────────

    private fun getOrCreateServiceSigner(context: Context): NostrSignerInternal {
        serviceSigner?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingKey = prefs.getString(KEY_SERVICE_PRIVKEY, null)

        val keyPair = if (existingKey != null) {
            KeyPair(privKey = existingKey.hexToByteArray())
        } else {
            val kp = KeyPair() // generates random keypair
            prefs.edit().putString(KEY_SERVICE_PRIVKEY, kp.privKey!!.toHexString()).apply()
            MLog.d(TAG, "Generated new NWC service keypair")
            kp
        }

        val signer = NostrSignerInternal(keyPair)
        serviceSigner = signer
        return signer
    }

    private fun getOrCreateClientKeyPair(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingKey = prefs.getString("client_privkey", null)

        return if (existingKey != null) {
            KeyPair(privKey = existingKey.hexToByteArray())
        } else {
            val kp = KeyPair()
            prefs.edit().putString("client_privkey", kp.privKey!!.toHexString()).apply()
            MLog.d(TAG, "Generated new NWC client keypair")
            kp
        }
    }

    private fun getRelay(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_NWC_RELAY, DEFAULT_NWC_RELAY) ?: DEFAULT_NWC_RELAY
    }

    /**
     * Update the relay used for NWC traffic. Restarts the subscription if running.
     */
    fun setRelay(context: Context, relay: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_NWC_RELAY, relay).apply()
        if (_isRunning.value) {
            stop()
            start(context)
        }
    }

    /**
     * Reset the NWC service — delete all keys and stop.
     */
    fun reset(context: Context) {
        stop()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        serviceSigner = null
        MLog.d(TAG, "NWC service provider reset")
    }
}
