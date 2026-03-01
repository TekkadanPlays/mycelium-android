package social.mycelium.android.repository

import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Mock CoinOS API provider for offline testing.
 *
 * Returns deterministic fake data with realistic delays to simulate network
 * latency. No network calls are made. Balance adjusts on send/receive.
 *
 * Active only when `BuildConfig.WALLET_DEV_MODE` is true (debug builds).
 */
class MockCoinosApiProvider : CoinosApiProvider {

    private var mockBalance = 250_000L
    private val mockTransactions = mutableListOf(
        CoinosTransaction(id = "mock-tx-1", amount = 21_000L, memo = "Welcome bonus", createdAt = "2025-12-01T10:00:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-2", amount = -5_000L, memo = "Coffee at Bitcoin Cafe", createdAt = "2025-12-15T14:30:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-3", amount = 100_000L, memo = "Zap from fiatjaf", createdAt = "2026-01-05T08:15:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-4", amount = -1_000L, memo = "Nostr relay subscription", createdAt = "2026-01-20T16:45:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-5", amount = 50_000L, memo = "Payout from stacker.news", createdAt = "2026-02-10T12:00:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-6", amount = -2_100L, memo = null, createdAt = "2026-02-20T09:30:00Z", confirmed = true, type = "lightning"),
        CoinosTransaction(id = "mock-tx-7", amount = 10_000L, memo = "Tip for relay maintenance", createdAt = "2026-02-28T18:00:00Z", confirmed = true, type = "lightning"),
    )

    override suspend fun fetchChallenge(): Result<String> {
        delay(300) // simulate network
        return Result.success(UUID.randomUUID().toString())
    }

    override suspend fun authenticate(eventJson: String, challenge: String): Result<AuthResult> {
        delay(800) // simulate auth round-trip
        return Result.success(
            AuthResult(
                token = "mock-jwt-${UUID.randomUUID().toString().take(8)}",
                username = "mockuser",
            )
        )
    }

    override suspend fun fetchUserInfo(token: String): Result<UserInfo> {
        delay(200)
        return Result.success(
            UserInfo(balance = mockBalance, username = "mockuser")
        )
    }

    override suspend fun createInvoice(token: String, amountSats: Long, memo: String): Result<String> {
        delay(600) // simulate invoice creation
        // Generate a realistic-looking (but invalid) lnbc invoice
        val fakeHash = UUID.randomUUID().toString().replace("-", "")
        val invoice = "lnbc${amountSats}n1p${fakeHash.take(20)}mock${fakeHash.takeLast(12)}"
        // Credit the mock balance as if someone paid immediately
        mockBalance += amountSats
        mockTransactions.add(
            0,
            CoinosTransaction(
                id = "mock-tx-${UUID.randomUUID().toString().take(8)}",
                amount = amountSats,
                memo = memo.ifBlank { null },
                createdAt = java.time.Instant.now().toString(),
                confirmed = true,
                type = "lightning",
            )
        )
        return Result.success(invoice)
    }

    override suspend fun payInvoice(token: String, bolt11: String): Result<Boolean> {
        delay(1_500) // simulate payment propagation
        // Parse a rough amount from the bolt11 prefix for the mock deduction
        val amount = Regex("""lnbc(\d+)""").find(bolt11.lowercase())
            ?.groupValues?.get(1)?.toLongOrNull() ?: 1_000L
        mockBalance -= amount
        mockTransactions.add(
            0,
            CoinosTransaction(
                id = "mock-tx-${UUID.randomUUID().toString().take(8)}",
                amount = -amount,
                memo = "Payment: ${bolt11.take(20)}...",
                createdAt = java.time.Instant.now().toString(),
                confirmed = true,
                type = "lightning",
            )
        )
        return Result.success(true)
    }

    override suspend fun fetchTransactions(token: String): Result<List<CoinosTransaction>> {
        delay(300)
        return Result.success(mockTransactions.toList())
    }
}
