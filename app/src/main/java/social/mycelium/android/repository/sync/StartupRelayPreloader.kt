package social.mycelium.android.repository.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.utils.normalizeRelayUrl

/**
 * Silently preloads NIP-11 relay information and connects indexer relays
 * during the sign-in startup sequence. This ensures that:
 *
 * 1. Relay orbs in the Relay Manager have icons/names on first render
 *    (no flash of generic Router icons).
 * 2. Indexer relays are connected early so profile resolution and NIP-50
 *    search are available before the user navigates to the Relay Manager.
 * 3. DM relay orbs and category relay orbs are similarly warm-cached.
 *
 * ## Separation of Concerns
 * - This class owns ONLY the preload/connect side-effects.
 * - [StartupOrchestrator] decides WHEN to call these methods.
 * - [RelayCategorySyncRepository] fetches relay lists (unchanged).
 * - [Nip11CacheManager] handles HTTP+caching (unchanged).
 */
object StartupRelayPreloader {

    private const val TAG = "StartupRelayPreloader"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── NIP-11 Preload ──────────────────────────────────────────────────────

    /**
     * Preload NIP-11 info for a batch of relay URLs.
     * Safe to call multiple times — [Nip11CacheManager] deduplicates.
     *
     * @param relayUrls Relay URLs to preload (wss:// or ws://).
     * @param context   Application context for [Nip11CacheManager] singleton.
     * @param label     Human-readable label for logging (e.g. "indexers", "dm", "categories").
     */
    fun preloadNip11(relayUrls: List<String>, context: Context, label: String) {
        if (relayUrls.isEmpty()) return
        val nip11 = Nip11CacheManager.getInstance(context)
        val normalized = relayUrls.map { normalizeRelayUrl(it) }.distinct()
        Log.d(TAG, "Preloading NIP-11 for $label: ${normalized.size} relays")
        nip11.preloadRelayInfo(normalized, scope)
    }

    // ── Indexer Pool Connection ─────────────────────────────────────────────

    /**
     * Connect indexer relays to the relay pool and add them to the persistent
     * set so they stay connected across feed changes. Also registers them in
     * [perRelayState] so their orbs show live connection status.
     *
     * This enables early profile fetching (kind-0) for feed event authors
     * and NIP-50 search readiness during sign-in.
     *
     * @param indexerUrls The user's indexer relay URLs.
     */
    fun connectIndexerRelays(indexerUrls: List<String>) {
        if (indexerUrls.isEmpty()) return
        val normalized = indexerUrls.map { normalizeRelayUrl(it) }.distinct().toSet()
        val stateMachine = RelayConnectionStateMachine.getInstance()

        // Add indexers to the persistent set so they survive feed subscription changes
        // (they won't be disconnected when idle — we need them for profile lookups)
        val currentPersistent = stateMachine.getPersistentRelayUrls()
        stateMachine.setPersistentRelayUrls(currentPersistent + normalized)

        // Register indexers in perRelayState so the UI shows their orbs immediately.
        // Actual WebSocket connections are created on-demand when subscriptions
        // (profile fetches, NIP-50 search) target these relay URLs.
        stateMachine.registerExternalRelays(normalized)
        Log.d(TAG, "Registered ${normalized.size} indexer relays as persistent + trackable")
    }

    // ── Early Profile Fetch ─────────────────────────────────────────────────

    /**
     * Begin fetching profiles for a batch of pubkeys using the connected
     * indexer relays. Call after [connectIndexerRelays] so the pool has
     * active connections.
     *
     * @param pubkeys      Pubkeys to resolve profiles for.
     * @param indexerUrls  Relay URLs to query for kind-0 events.
     */
    fun startEarlyProfileFetch(pubkeys: List<String>, indexerUrls: List<String>) {
        if (pubkeys.isEmpty() || indexerUrls.isEmpty()) return
        scope.launch {
            try {
                val cache = social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
                cache.requestProfiles(pubkeys, indexerUrls)
                Log.d(TAG, "Early profile fetch started: ${pubkeys.size} pubkeys via ${indexerUrls.size} indexers")
            } catch (e: Exception) {
                Log.w(TAG, "Early profile fetch failed: ${e.message}")
            }
        }
    }
}
