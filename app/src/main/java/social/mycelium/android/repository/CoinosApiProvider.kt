package social.mycelium.android.repository

/**
 * Abstraction over the CoinOS REST API.
 *
 * Two implementations:
 * - [RealCoinosApiProvider]: real HTTP calls to coinos.io/api
 * - [MockCoinosApiProvider]: deterministic fake data for offline testing
 *
 * All methods are suspending and return [Result] so the caller can handle
 * success/failure uniformly regardless of which provider is active.
 */
interface CoinosApiProvider {

    /** Fetch a challenge UUID for Nostr auth. */
    suspend fun fetchChallenge(): Result<String>

    /**
     * Authenticate with the signed kind-27235 event JSON + challenge.
     * Returns [AuthResult] containing JWT token and username on success.
     */
    suspend fun authenticate(eventJson: String, challenge: String): Result<AuthResult>

    /** Fetch current user info (balance, username). Requires JWT. */
    suspend fun fetchUserInfo(token: String): Result<UserInfo>

    /** Create a Lightning invoice. Returns the bolt11 invoice string. */
    suspend fun createInvoice(token: String, amountSats: Long, memo: String): Result<String>

    /** Pay a Lightning invoice. Returns true on success. */
    suspend fun payInvoice(token: String, bolt11: String): Result<Boolean>

    /** Fetch recent transactions. */
    suspend fun fetchTransactions(token: String): Result<List<CoinosTransaction>>
}

data class AuthResult(
    val token: String,
    val username: String,
)

data class UserInfo(
    val balance: Long,
    val username: String?,
)
