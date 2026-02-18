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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Repository for coinos.io Bitcoin/Lightning wallet API.
 * Uses Nostr auth (kind-27235 challenge signing) for captcha-free authentication.
 * Server auto-registers new users from their Nostr profile.
 * API base: https://coinos.io/api
 */
object CoinosRepository {

    private const val TAG = "CoinosRepo"
    private const val BASE_URL = "https://coinos.io/api"
    private const val PREFS_NAME = "coinos_wallet"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USERNAME = "username"
    private const val USER_AGENT = "Mycelium"

    private val JSON_MEDIA = "application/json".toMediaType()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) }
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

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
        }
    }

    // ── Nostr Auth ──

    /**
     * Authenticate with coinos using a signed Nostr event (kind 27235).
     * Flow: GET /challenge -> sign event with challenge tag -> POST /nostr/auth.
     * Server auto-registers new users from their Nostr profile (kind-0).
     */
    fun loginWithNostr(signer: NostrSigner, pubkey: String) {
        _isLoading.value = true
        _error.value = null
        scope.launch {
            try {
                // Step 1: Get challenge UUID from server
                Log.d(TAG, "loginWithNostr: starting auth for pubkey=${pubkey.take(8)}...")
                val challengeReq = Request.Builder()
                    .url("$BASE_URL/challenge")
                    .get()
                    .build()
                val challengeResp = client.newCall(challengeReq).execute()
                val challengeBody = challengeResp.body?.string() ?: ""
                if (!challengeResp.isSuccessful || challengeBody.isBlank()) {
                    _error.value = "Failed to get challenge: ${challengeResp.code}"
                    _isLoading.value = false
                    return@launch
                }
                val challengeObj = json.decodeFromString<JsonObject>(challengeBody)
                val challenge = challengeObj["challenge"]?.jsonPrimitive?.content
                    ?: throw Exception("No challenge in response")
                Log.d(TAG, "Got challenge: ${challenge.take(8)}...")

                // Step 2: Build and sign kind-27235 event with challenge tag
                val template = Event.build(27235, "") {
                    add(arrayOf("challenge", challenge))
                }
                val signedEvent = signer.sign(template)
                val eventJson = signedEvent.toJson()
                Log.d(TAG, "Signed auth event: kind=${signedEvent.kind}, id=${signedEvent.id.take(8)}")

                // Step 3: POST signed event to /nostrAuth
                val authBody = """{"event":$eventJson,"challenge":"$challenge"}"""
                Log.d(TAG, "loginWithNostr: POST /nostrAuth body=${authBody.take(200)}...")
                val authReq = Request.Builder()
                    .url("$BASE_URL/nostrAuth")
                    .header("User-Agent", USER_AGENT)
                    .post(authBody.toRequestBody(JSON_MEDIA))
                    .build()
                val authResp = client.newCall(authReq).execute()
                val authRespBody = authResp.body?.string() ?: ""

                Log.d(TAG, "loginWithNostr: response code=${authResp.code}, body=${authRespBody.take(300)}")
                if (authResp.isSuccessful && authRespBody.isNotBlank()) {
                    handleAuthSuccess(authRespBody, pubkey)
                } else {
                    Log.e(TAG, "Nostr auth failed: ${authResp.code} $authRespBody")
                    _error.value = "Auth failed: ${authResp.code}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Nostr auth error: ${e.message}", e)
                _error.value = "Auth error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun handleAuthSuccess(responseBody: String, pubkey: String) {
        val obj = json.decodeFromString<JsonObject>(responseBody)
        val jwt = obj["token"]?.jsonPrimitive?.content
        val userObj = obj["user"]
        val coinosUsername = try {
            userObj?.jsonPrimitive?.content
        } catch (_: Exception) {
            try {
                val userMap = json.decodeFromString<JsonObject>(userObj.toString())
                userMap["username"]?.jsonPrimitive?.content
            } catch (_: Exception) { null }
        } ?: pubkey.take(16)

        if (jwt != null) {
            token = jwt
            _username.value = coinosUsername
            _isLoggedIn.value = true
            prefs?.edit()
                ?.putString(KEY_TOKEN, jwt)
                ?.putString(KEY_USERNAME, coinosUsername)
                ?.apply()
            Log.d(TAG, "Nostr auth successful: $coinosUsername")
            refreshBalanceInternal()
            fetchTransactionsInternal()
        } else {
            _error.value = "Auth failed: no token in response"
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
        try {
            val request = Request.Builder()
                .url("$BASE_URL/me")
                .get()
                .header("Authorization", "Bearer $jwt")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotBlank()) {
                val obj = json.decodeFromString<JsonObject>(body)
                val balance = obj["balance"]?.jsonPrimitive?.longOrNull ?: 0L
                _balanceSats.value = balance
                Log.d(TAG, "Balance: $balance sats")
            } else if (response.code == 401) {
                _error.value = "Session expired. Please log in again."
                logout()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Balance fetch error: ${e.message}", e)
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
                val body = if (memo.isNotBlank()) {
                    """{"invoice":{"amount":$amountSats,"memo":"$memo","type":"lightning"}}"""
                } else {
                    """{"invoice":{"amount":$amountSats,"type":"lightning"}}"""
                }
                val request = Request.Builder()
                    .url("$BASE_URL/invoice")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .header("Authorization", "Bearer $jwt")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful && responseBody.isNotBlank()) {
                    val obj = json.decodeFromString<JsonObject>(responseBody)
                    val invoice = obj["text"]?.jsonPrimitive?.content
                        ?: obj["address"]?.jsonPrimitive?.content
                        ?: obj["hash"]?.jsonPrimitive?.content
                    _lastInvoice.value = invoice
                    Log.d(TAG, "Invoice created: ${invoice?.take(30)}...")
                } else {
                    _error.value = "Invoice creation failed: ${response.code}"
                }
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
                val body = """{"payreq":"$bolt11"}"""
                val request = Request.Builder()
                    .url("$BASE_URL/payments")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .header("Authorization", "Bearer $jwt")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    Log.d(TAG, "Payment sent successfully")
                    refreshBalanceInternal()
                    fetchTransactionsInternal()
                } else {
                    _error.value = "Payment failed: ${response.code} $responseBody"
                }
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
        try {
            val request = Request.Builder()
                .url("$BASE_URL/payments")
                .get()
                .header("Authorization", "Bearer $jwt")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotBlank()) {
                val arr = json.decodeFromString<List<JsonObject>>(body)
                val txs = arr.mapNotNull { obj ->
                    try {
                        CoinosTransaction(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            amount = obj["amount"]?.jsonPrimitive?.longOrNull ?: 0L,
                            memo = obj["memo"]?.jsonPrimitive?.content,
                            createdAt = obj["created_at"]?.jsonPrimitive?.content,
                            confirmed = obj["confirmed"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                            type = obj["type"]?.jsonPrimitive?.content ?: "lightning"
                        )
                    } catch (_: Exception) { null }
                }
                _transactions.value = txs
                Log.d(TAG, "Fetched ${txs.size} transactions")
            }
        } catch (e: Exception) {
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
