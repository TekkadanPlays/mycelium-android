package social.mycelium.android.lightning

import android.content.Context
import android.util.Log
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.utils.toByteVector32
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.InvoiceDefaultRoutingFees
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.NodeUri
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.TrampolineFees
import fr.acinq.lightning.WalletParams
import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.blockchain.electrum.ElectrumWatcher
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.PayInvoice
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.io.PaymentNotSent
import fr.acinq.lightning.io.PaymentSent
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Singleton that manages the embedded Phoenix/lightning-kmp Lightning node.
 *
 * Lifecycle:
 * 1. [init] — call once from Application.onCreate or first wallet access
 * 2. [start] — creates KeyManager, NodeParams, ElectrumClient, Peer and connects
 * 3. [payInvoice] — pay a bolt11 invoice directly from the local LN node
 * 4. [stop] — disconnect and release resources
 *
 * The wallet is non-custodial: the user's 12-word mnemonic is stored in
 * EncryptedSharedPreferences and all keys are derived locally.
 */
object PhoenixWalletManager {
    private const val TAG = "PhoenixWalletManager"

    // ── ACINQ testnet trampoline node ────────────────────────────────────
    // TODO: switch to mainnet values for production
    private val TRAMPOLINE_NODE_ID = PublicKey.fromHex(
        "03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"
    )
    private val TRAMPOLINE_NODE_URI = NodeUri(TRAMPOLINE_NODE_ID, "13.248.222.197", 9735)
    private const val REMOTE_SWAP_IN_XPUB =
        "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og"
    private val CHAIN = Chain.Testnet3

    // ── Electrum servers (testnet) ── from Phoenix's ElectrumServers.kt ──
    private val ELECTRUM_SERVERS = listOf(
        ServerAddress("blockstream.info", 993, TcpSocket.TLS.TRUSTED_CERTIFICATES()),
        ServerAddress("testnet.qtornado.com", 51002, TcpSocket.TLS.TRUSTED_CERTIFICATES()),
        ServerAddress("testnet.aranguren.org", 51002, TcpSocket.TLS.TRUSTED_CERTIFICATES()),
    )

    // ── State ────────────────────────────────────────────────────────────

    sealed class WalletState {
        data object NotInitialized : WalletState()
        data object NoWallet : WalletState()
        data object Starting : WalletState()
        data class Running(val peer: Peer) : WalletState()
        data class Error(val message: String) : WalletState()
    }

    private val _state = MutableStateFlow<WalletState>(WalletState.NotInitialized)
    val state: StateFlow<WalletState> = _state.asStateFlow()

    /** Balance in millisatoshis across all channels. */
    private val _balanceMsat = MutableStateFlow(0L)
    val balanceMsat: StateFlow<Long> = _balanceMsat.asStateFlow()

    /** Whether the peer is connected to the trampoline node. */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var scope: CoroutineScope? = null
    private var electrumClient: ElectrumClient? = null
    private var peer: Peer? = null
    private var paymentsDb: InMemoryPaymentsDb? = null
    private var connectionJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Initialize the wallet manager. Call from Application.onCreate().
     * This checks if a seed exists but does NOT start the node.
     */
    fun init(context: Context) {
        if (_state.value != WalletState.NotInitialized) return
        _state.value = if (SeedManager.hasSeed(context)) {
            WalletState.Starting // seed exists, will auto-start
        } else {
            WalletState.NoWallet
        }
        Log.d(TAG, "init: state=${_state.value}")
    }

    /**
     * Create a new wallet (generates mnemonic) and start the node.
     * Returns the 12-word mnemonic for the user to back up.
     */
    suspend fun createWallet(context: Context): List<String> {
        val words = SeedManager.generateAndStore(context)
        start(context)
        return words
    }

    /**
     * Restore a wallet from an existing mnemonic and start the node.
     */
    suspend fun restoreWallet(context: Context, words: List<String>) {
        SeedManager.storeMnemonic(context, words)
        start(context)
    }

    /**
     * Start the Lightning node. Requires a stored seed.
     */
    suspend fun start(context: Context) {
        if (_state.value is WalletState.Running) {
            Log.d(TAG, "start: already running")
            return
        }

        val seedBytes = SeedManager.deriveSeed(context)
        if (seedBytes == null) {
            _state.value = WalletState.NoWallet
            Log.w(TAG, "start: no seed found")
            return
        }

        _state.value = WalletState.Starting
        Log.d(TAG, "start: initializing lightning node...")

        try {
            val walletScope = CoroutineScope(
                CoroutineName("phoenix-wallet") +
                        SupervisorJob() +
                        Dispatchers.Default +
                        CoroutineExceptionHandler { _, e ->
                            Log.e(TAG, "Uncaught error in wallet scope: ${e.message}", e)
                        }
            )
            scope = walletScope

            // 1. Key manager from seed
            val seed = seedBytes.toByteVector32()
            val keyManager = LocalKeyManager(seed, CHAIN, REMOTE_SWAP_IN_XPUB)
            Log.d(TAG, "KeyManager created, nodeId=${keyManager.nodeKeys.nodeKey.publicKey}")

            // 2. Logger factory (routes to Android Log)
            val loggerFactory = AndroidLoggerFactory

            // 3. NodeParams with sensible defaults
            val nodeParams = NodeParams(
                chain = CHAIN,
                loggerFactory = loggerFactory,
                keyManager = keyManager,
            ).copy(
                zeroConfPeers = setOf(TRAMPOLINE_NODE_ID),
                liquidityPolicy = MutableStateFlow(
                    LiquidityPolicy.Auto(
                        inboundLiquidityTarget = null,
                        maxAbsoluteFee = 5_000.sat,
                        maxRelativeFeeBasisPoints = 50_00,
                        skipAbsoluteFeeCheck = false,
                        considerOnlyMiningFeeForAbsoluteFeeCheck = false,
                        maxAllowedFeeCredit = 0.msat
                    )
                ),
            )

            // 4. WalletParams (trampoline fees, swap-in config)
            val walletParams = WalletParams(
                trampolineNode = TRAMPOLINE_NODE_URI,
                trampolineFees = listOf(
                    TrampolineFees(
                        feeBase = 4.sat,
                        feeProportional = 4_000,
                        cltvExpiryDelta = CltvExpiryDelta(576)
                    )
                ),
                invoiceDefaultRoutingFees = InvoiceDefaultRoutingFees(
                    feeBase = 1_000.msat,
                    feeProportional = 100,
                    cltvExpiryDelta = CltvExpiryDelta(144)
                ),
                swapInParams = SwapInParams(
                    minConfirmations = DefaultSwapInParams.MinConfirmations,
                    maxConfirmations = DefaultSwapInParams.MaxConfirmations,
                    refundDelay = DefaultSwapInParams.RefundDelay,
                ),
            )

            // 5. Electrum client
            val client = ElectrumClient(walletScope, loggerFactory)
            electrumClient = client

            // 6. Electrum watcher
            val watcher = ElectrumWatcher(client, walletScope, loggerFactory)

            // 7. Databases
            val channelsDir = java.io.File(context.filesDir, "lightning_channels")
            val channelsDb = FileChannelsDb(channelsDir)
            val payments = InMemoryPaymentsDb()
            paymentsDb = payments
            val databases = LightningDatabases(channelsDb, payments)

            // 8. Create the Peer (the actual Lightning node)
            val lnPeer = Peer(
                nodeParams = nodeParams,
                walletParams = walletParams,
                client = client,
                watcher = watcher,
                db = databases,
                socketBuilder = TcpSocket.Builder(),
                scope = walletScope,
            )
            peer = lnPeer

            // 9. Connect Electrum
            walletScope.launch {
                connectElectrum(client)
            }

            // 10. Monitor connection state
            walletScope.launch {
                lnPeer.connectionState.collect { conn ->
                    _isConnected.value = conn is Connection.ESTABLISHED
                    Log.d(TAG, "Peer connection: $conn")
                }
            }

            // 11. Monitor balance
            walletScope.launch {
                lnPeer.channelsFlow.collect { channels ->
                    val balance = channels.values.sumOf { channel ->
                        when (channel) {
                            is fr.acinq.lightning.channel.states.Normal -> channel.commitments.active.first().localCommit.spec.toLocal.toLong()
                            is fr.acinq.lightning.channel.states.Offline -> {
                                val inner = channel.state
                                if (inner is fr.acinq.lightning.channel.states.Normal) {
                                    inner.commitments.active.first().localCommit.spec.toLocal.toLong()
                                } else 0L
                            }
                            else -> 0L
                        }
                    }
                    _balanceMsat.value = balance
                }
            }

            // 12. Connect to the Lightning peer
            walletScope.launch {
                // Wait for electrum to be connected first
                client.connectionStatus
                    .filter { it is fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus.Connected }
                    .first()
                Log.d(TAG, "Electrum connected, connecting to Lightning peer...")
                connectPeer(lnPeer)
            }

            // 13. Start swap-in watcher
            walletScope.launch {
                lnPeer.connectionState
                    .filter { it is Connection.ESTABLISHED }
                    .first()
                Log.d(TAG, "Peer connected, starting swap-in watcher...")
                lnPeer.startWatchSwapInWallet()
            }

            _state.value = WalletState.Running(lnPeer)
            Log.d(TAG, "Lightning node started successfully")

            // 14. Start NWC service provider
            NwcServiceProvider.start(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start lightning node: ${e.message}", e)
            _state.value = WalletState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Stop the Lightning node and release resources.
     */
    fun stop() {
        Log.d(TAG, "Stopping lightning node...")
        NwcServiceProvider.stop()
        connectionJob?.cancel()
        peer?.disconnect()
        scope?.let { (it as? Job)?.cancel() }
        peer = null
        electrumClient = null
        paymentsDb = null
        scope = null
        _state.value = WalletState.NotInitialized
        _balanceMsat.value = 0L
        _isConnected.value = false
    }

    // ── Payments ─────────────────────────────────────────────────────────

    /**
     * Pay a Bolt11 Lightning invoice. Returns the preimage on success.
     */
    suspend fun payInvoice(bolt11: String): PaymentResult {
        val lnPeer = peer ?: return PaymentResult.Error("Lightning wallet not running")

        return try {
            val invoice = Bolt11Invoice.read(bolt11)
                .let { result ->
                    when (result) {
                        is fr.acinq.bitcoin.utils.Try.Success -> result.result
                        is fr.acinq.bitcoin.utils.Try.Failure -> return PaymentResult.Error("Invalid invoice: ${result.error.message}")
                    }
                }

            val amount = invoice.amount ?: return PaymentResult.Error("Invoice has no amount")

            Log.d(TAG, "Paying invoice: amount=${amount}, paymentHash=${invoice.paymentHash.toHex().take(16)}...")

            val paymentId = fr.acinq.lightning.utils.UUID.randomUUID()
            lnPeer.send(
                PayInvoice(
                    paymentId = paymentId,
                    amount = amount,
                    paymentDetails = fr.acinq.lightning.db.LightningOutgoingPayment.Details.Normal(invoice)
                )
            )

            // Wait for payment result from the peer events flow
            val result = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                lnPeer.eventsFlow
                    .filter { event ->
                        event is PaymentSent && event.request.paymentId == paymentId ||
                        event is PaymentNotSent && event.request.paymentId == paymentId
                    }
                    .first()
            }

            when (result) {
                is PaymentSent -> {
                    val preimage = (result.payment.status as? fr.acinq.lightning.db.LightningOutgoingPayment.Status.Succeeded)?.preimage
                    Log.d(TAG, "Payment successful! preimage=${preimage?.toHex()?.take(16)}")
                    PaymentResult.Success(preimage?.toHex() ?: "")
                }
                is PaymentNotSent -> {
                    Log.w(TAG, "Payment failed: ${result.reason}")
                    PaymentResult.Error("Payment failed: ${result.reason}")
                }
                null -> {
                    Log.w(TAG, "Payment timed out")
                    PaymentResult.Error("Payment timed out after 60s")
                }
                else -> PaymentResult.Error("Unexpected payment result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "payInvoice error: ${e.message}", e)
            PaymentResult.Error("Payment error: ${e.message?.take(80)}")
        }
    }

    /**
     * Generate a Bolt11 invoice for receiving a payment.
     */
    suspend fun createInvoice(amountMsat: Long, description: String = ""): String? {
        val lnPeer = peer ?: return null
        return try {
            val preimage = randomBytes32()
            val invoice = lnPeer.createInvoice(
                paymentPreimage = preimage,
                amount = MilliSatoshi(amountMsat),
                description = Either.Left(description),
            )
            invoice.write()
        } catch (e: Exception) {
            Log.e(TAG, "createInvoice error: ${e.message}", e)
            null
        }
    }

    /** Get the swap-in Bitcoin address for receiving on-chain funds. */
    fun getSwapInAddress(): String? {
        return peer?.swapInWallet?.swapInAddressFlow?.value?.first
    }

    /** Get all completed payments from the in-memory DB. */
    fun getPaymentHistory(): List<fr.acinq.lightning.db.WalletPayment> {
        return paymentsDb?.getAllPayments() ?: emptyList()
    }

    /** Whether the wallet has been created (seed exists). */
    fun hasWallet(context: Context): Boolean = SeedManager.hasSeed(context)

    /** Whether the node is currently running and connected. */
    fun isReady(): Boolean = _state.value is WalletState.Running && _isConnected.value

    // ── Private helpers ──────────────────────────────────────────────────

    private suspend fun connectElectrum(client: ElectrumClient) {
        var serverIndex = 0
        while (true) {
            val server = ELECTRUM_SERVERS[serverIndex % ELECTRUM_SERVERS.size]
            try {
                Log.d(TAG, "Connecting to Electrum: ${server.host}:${server.port}")
                val connected = client.connect(
                    serverAddress = server,
                    socketBuilder = TcpSocket.Builder(),
                )
                if (connected) {
                    Log.d(TAG, "Electrum connected to ${server.host}")
                    // Wait for disconnection then reconnect
                    client.connectionStatus
                        .filter { it is fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus.Closed }
                        .first()
                    Log.w(TAG, "Electrum disconnected from ${server.host}, will reconnect...")
                } else {
                    Log.w(TAG, "Electrum connect returned false for ${server.host}, trying next server")
                    serverIndex++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Electrum connection to ${server.host} failed: ${e.message}")
                serverIndex++
            }
            delay(5000) // retry after 5 seconds
        }
    }

    private suspend fun connectPeer(peer: Peer) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to Lightning peer: ${TRAMPOLINE_NODE_URI.host}:${TRAMPOLINE_NODE_URI.port}")
                peer.connect(connectTimeout = 15.seconds, handshakeTimeout = 10.seconds)
                // Wait for disconnection
                peer.connectionState
                    .filter { it is Connection.CLOSED }
                    .first()
                Log.w(TAG, "Peer disconnected, will reconnect...")
            } catch (e: Exception) {
                Log.e(TAG, "Peer connection failed: ${e.message}")
            }
            delay(5000)
        }
    }
}

/** Result of a payment attempt. */
sealed class PaymentResult {
    data class Success(val preimage: String) : PaymentResult()
    data class Error(val message: String) : PaymentResult()
}
