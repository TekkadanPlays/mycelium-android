package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.BuildConfig

/**
 * Repository for CoinOS Bitcoin/Lightning wallet.
 *
 * Delegates all API calls to a [CoinosApiProvider]:
 * - **Debug builds** (`WALLET_DEV_MODE = true`): [MockCoinosApiProvider] — fake data, no network.
 * - **Release builds**: [RealCoinosApiProvider] — real HTTP calls to coinos.io/api.
 *
 * The wallet UI is only accessible in debug builds; release shows the
 * "Coming soon" lander via `BuildConfig.WALLET_DEV_MODE`.
 */
object CoinosRepository {

    private const val TAG = "CoinosRepo"
    private const val PREFS_NAME = "coinos_wallet"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USERNAME = "username"

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) }
    )

    private val provider: CoinosApiProvider =
        if (BuildConfig.WALLET_DEV_MODE) MockCoinosApiProvider() else RealCoinosApiProvider()

    val isMockMode: Boolean get() = provider is MockCoinosApiProvider

    private var prefs: SharedPreferences? = null
    private var token: String? = null

    // ── State ──

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _balanceSats = MutableStateFlow(0L)
    val balanceSats: StateFlow<Long> = _balanceSats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _transactions = MutableStateFlow<List<CoinosTransaction>>(emptyList())
    val transactions: StateFlow<List<CoinosTransaction>> = _transactions.asStateFlow()

    private val _lastInvoice = MutableStateFlow<String?>(null)
    val lastInvoice: StateFlow<String?> = _lastInvoice.asStateFlow()

    // ── Init ──

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            token = prefs?.getString(KEY_TOKEN, null)
            val savedUser = prefs?.getString(KEY_USERNAME, null)
            if (token != null && savedUser != null) {
                _isLoggedIn.value = true
                _username.value = savedUser
                refreshBalance()
            }
            Log.d(TAG, "init: mock=${isMockMode}, loggedIn=${_isLoggedIn.value}")
        }
    }

    // ── Nostr Auth ──

    fun loginWithNostr(signer: NostrSigner, pubkey: String) {
        _isLoading.value = true
        _error.value = null
        scope.launch {
            try {
                Log.d(TAG, "loginWithNostr: starting auth (mock=$isMockMode) for ${pubkey.take(8)}...")

                // Step 1: Get challenge
                val challenge = provider.fetchChallenge().getOrThrow()
                Log.d(TAG, "Got challenge: ${challenge.take(8)}...")

                // Step 2: Sign kind-27235 event
                val template = Event.build(27235, "") {
                    add(arrayOf("challenge", challenge))
                }
                val signedEvent = signer.sign(template)
                val eventJson = signedEvent.toJson()
                Log.d(TAG, "Signed auth event: kind=${signedEvent.kind}, id=${signedEvent.id.take(8)}")

                // Step 3: Authenticate
                val authResult = provider.authenticate(eventJson, challenge).getOrThrow()
                token = authResult.token
                _username.value = authResult.username
                _isLoggedIn.value = true
                prefs?.edit()
                    ?.putString(KEY_TOKEN, authResult.token)
                    ?.putString(KEY_USERNAME, authResult.username)
                    ?.apply()
                Log.d(TAG, "Auth successful: ${authResult.username}")

                refreshBalanceInternal()
                fetchTransactionsInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Auth error: ${e.message}", e)
                _error.value = "Auth error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        token = null
        _isLoggedIn.value = false
        _username.value = null
        _balanceSats.value = 0L
        _transactions.value = emptyList()
        _lastInvoice.value = null
        _error.value = null
        prefs?.edit()?.remove(KEY_TOKEN)?.remove(KEY_USERNAME)?.apply()
    }

    // ── Balance ──

    fun refreshBalance() {
        scope.launch { refreshBalanceInternal() }
    }

    private suspend fun refreshBalanceInternal() {
        val jwt = token ?: return
        provider.fetchUserInfo(jwt).onSuccess { info ->
            _balanceSats.value = info.balance
            Log.d(TAG, "Balance: ${info.balance} sats")
        }.onFailure { e ->
            if (e.message?.contains("expired", ignoreCase = true) == true) {
                _error.value = "Session expired. Please log in again."
                logout()
            } else {
                Log.e(TAG, "Balance fetch error: ${e.message}", e)
            }
        }
    }

    // ── Invoices (Receive) ──

    fun createInvoice(amountSats: Long, memo: String = "") {
        _isLoading.value = true
        _error.value = null
        scope.launch {
            try {
                val jwt = token ?: run {
                    _error.value = "Not logged in"
                    _isLoading.value = false
                    return@launch
                }
                val invoice = provider.createInvoice(jwt, amountSats, memo).getOrThrow()
                _lastInvoice.value = invoice
                Log.d(TAG, "Invoice created: ${invoice.take(30)}...")
                refreshBalanceInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Invoice error: ${e.message}", e)
                _error.value = "Invoice error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Payments (Send) ──

    fun payInvoice(bolt11: String) {
        _isLoading.value = true
        _error.value = null
        scope.launch {
            try {
                val jwt = token ?: run {
                    _error.value = "Not logged in"
                    _isLoading.value = false
                    return@launch
                }
                provider.payInvoice(jwt, bolt11).getOrThrow()
                Log.d(TAG, "Payment sent successfully")
                refreshBalanceInternal()
                fetchTransactionsInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Payment error: ${e.message}", e)
                _error.value = "Payment error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ── Transactions ──

    fun fetchTransactions() {
        scope.launch { fetchTransactionsInternal() }
    }

    private suspend fun fetchTransactionsInternal() {
        val jwt = token ?: return
        provider.fetchTransactions(jwt).onSuccess { txs ->
            _transactions.value = txs
            Log.d(TAG, "Fetched ${txs.size} transactions")
        }.onFailure { e ->
            Log.e(TAG, "Transactions fetch error: ${e.message}", e)
        }
    }

    fun clearError() { _error.value = null }
}

data class CoinosTransaction(
    val id: String,
    val amount: Long,
    val memo: String?,
    val createdAt: String?,
    val confirmed: Boolean,
    val type: String
)
