package social.mycelium.android.repository.social

import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import social.mycelium.android.network.MyceliumHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-05 DNS-based verification, modeled after Amethyst's approach.
 *
 * Fetches `https://<domain>/.well-known/nostr.json?name=<name>` and checks that
 * the returned `names[name]` matches the expected hex pubkey. Results are cached
 * with TTL: 1 hour for verified, 5 minutes for failed/error.
 *
 * UI observes [verificationStates] (a Compose snapshot-state map) which triggers
 * recomposition when a verification result arrives.
 */
object Nip05Verifier {

    private const val TAG = "Nip05Verifier"

    /** Verification result for a single pubkey's nip05 identifier. */
    enum class VerificationStatus {
        UNKNOWN,
        VERIFYING,
        VERIFIED,
        FAILED
    }

    private data class CacheEntry(
        val status: VerificationStatus,
        val expiresAtMs: Long
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = MyceliumHttpClient.instance

    private val json = Json { ignoreUnknownKeys = true }

    /** Cache keyed by hex pubkey. Thread-safe for writes from IO, reads from UI. */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Snapshot-state map observed by Compose. Keyed by hex pubkey. */
    val verificationStates = mutableStateMapOf<String, VerificationStatus>()

    /** In-flight dedup: pubkeys currently being verified. */
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Request verification for a pubkey's NIP-05 identifier.
     * If already cached and not expired, returns immediately.
     * Otherwise kicks off a background HTTP fetch.
     */
    fun verify(pubkeyHex: String, nip05: String) {
        // Check cache first
        val cached = cache[pubkeyHex]
        if (cached != null && System.currentTimeMillis() < cached.expiresAtMs) {
            // Ensure snapshot state is in sync
            if (verificationStates[pubkeyHex] != cached.status) {
                verificationStates[pubkeyHex] = cached.status
            }
            return
        }

        // Dedup in-flight
        if (!inflight.add(pubkeyHex)) return

        verificationStates[pubkeyHex] = VerificationStatus.VERIFYING

        scope.launch {
            try {
                val result = checkNip05(pubkeyHex, nip05)
                val ttl = if (result) 60 * 60 * 1000L else 5 * 60 * 1000L // 1h / 5min
                val status = if (result) VerificationStatus.VERIFIED else VerificationStatus.FAILED
                cache[pubkeyHex] = CacheEntry(status, System.currentTimeMillis() + ttl)
                verificationStates[pubkeyHex] = status
            } catch (e: Exception) {
                Log.w(TAG, "NIP-05 check failed for $nip05: ${e.message}")
                cache[pubkeyHex] = CacheEntry(VerificationStatus.FAILED, System.currentTimeMillis() + 5 * 60 * 1000L)
                verificationStates[pubkeyHex] = VerificationStatus.FAILED
            } finally {
                inflight.remove(pubkeyHex)
            }
        }
    }

    /**
     * Get current status for a pubkey. Returns UNKNOWN if never checked.
     */
    fun getStatus(pubkeyHex: String): VerificationStatus {
        return verificationStates[pubkeyHex] ?: VerificationStatus.UNKNOWN
    }

    /**
     * Perform the actual NIP-05 HTTP check.
     * Parses `name@domain` → fetches `https://domain/.well-known/nostr.json?name=name`
     * → checks `names[name] == pubkeyHex`.
     */
    private suspend fun checkNip05(pubkeyHex: String, nip05: String): Boolean {
        val parts = nip05.trim().split("@")
        val name: String
        val domain: String
        when (parts.size) {
            2 -> { name = parts[0].lowercase(); domain = parts[1].lowercase() }
            1 -> { name = "_"; domain = parts[0].lowercase() }
            else -> return false
        }
        if (domain.isBlank()) return false

        val url = "https://$domain/.well-known/nostr.json?name=$name"
        val response = client.get(url) {
            header("Accept", "application/json")
        }

        if (!response.status.isSuccess()) return false

        val body = response.bodyAsText()
        val root = json.parseToJsonElement(body)
        val hex = root.jsonObject["names"]
            ?.jsonObject?.get(name)
            ?.jsonPrimitive?.content
            ?: return false

        return hex.equals(pubkeyHex, ignoreCase = true)
    }
}
