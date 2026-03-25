package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.UserRelay
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.TemporarySubscriptionHandle

/**
 * Syncs user-defined relay categories to/from relays using NIP-51 kind 30002 (Relay Sets).
 *
 * Each [RelayCategory] maps to a parameterized replaceable event with:
 *   - `d` tag = category ID
 *   - `title` tag = display name
 *   - `relay` tags = relay URLs in the category
 *   - `subscribed` tag = whether the category is active
 *
 * ## Publishing
 * Call [publishCategory] after any category mutation (add/remove relay, create/edit category).
 * The event is published to the user's outbox relays.
 *
 * ## Fetching (Cold Start)
 * Call [fetchRelaySets] during Phase 1 startup to restore categories from relays.
 * Fetched categories are merged with local storage — remote wins for matching d-tags,
 * local-only categories are preserved.
 */
object RelayCategorySyncRepository {

    private const val TAG = "RelayCatSync"
    private const val KIND_RELAY_SET = 30002
    private const val KIND_INDEXER_LIST = 10086

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Coroutine failed: ${t.message}", t)
        }
    )

    private var fetchHandle: TemporarySubscriptionHandle? = null
    private var indexerFetchHandle: TemporarySubscriptionHandle? = null

    // ── Publish ──────────────────────────────────────────────────────────────

    /**
     * Publish a single relay category as a kind-30002 event to the user's outbox relays.
     *
     * @param category The category to publish.
     * @param signer The NostrSigner for the current account.
     * @param outboxRelayUrls The user's outbox relay URLs to publish to.
     */
    fun publishCategory(
        category: RelayCategory,
        signer: com.example.cybin.signer.NostrSigner,
        outboxRelayUrls: Set<String>,
    ) {
        if (outboxRelayUrls.isEmpty()) {
            Log.w(TAG, "No outbox relays — skipping publish for '${category.name}'")
            return
        }
        scope.launch {
            try {
                val template = Event.build(KIND_RELAY_SET, "") {
                    add(arrayOf("d", category.id))
                    add(arrayOf("title", category.name))
                    if (category.isSubscribed) {
                        add(arrayOf("subscribed", "true"))
                    }
                    category.relays.forEach { relay ->
                        add(arrayOf("relay", relay.url))
                    }
                }
                val signed = signer.sign(template)
                val normalized = outboxRelayUrls.mapNotNull {
                    com.example.cybin.relay.RelayUrlNormalizer.normalizeOrNull(it)?.url
                }.toSet()
                if (normalized.isEmpty()) return@launch
                RelayConnectionStateMachine.getInstance().send(signed, normalized)
                // Track publish results so delivery is visible in publish results screen
                RelayHealthTracker.storePublishedEvent(signed.id, signed)
                RelayHealthTracker.registerPendingPublish(signed.id, KIND_RELAY_SET, normalized)
                scope.launch {
                    delay(10_000L)
                    RelayHealthTracker.finalizePendingPublish(signed.id)
                }
                Log.d(TAG, "Published category '${category.name}' (d=${category.id.take(8)}) " +
                    "with ${category.relays.size} relays → ${normalized.size} outbox relays")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish category '${category.name}': ${e.message}", e)
            }
        }
    }

    /**
     * Delete a relay category by publishing a kind-5 deletion event and an empty replacement.
     */
    fun deleteCategory(
        categoryId: String,
        userPubkey: String,
        signer: com.example.cybin.signer.NostrSigner,
        outboxRelayUrls: Set<String>,
    ) {
        if (outboxRelayUrls.isEmpty()) return
        scope.launch {
            try {
                val normalized = outboxRelayUrls.mapNotNull {
                    com.example.cybin.relay.RelayUrlNormalizer.normalizeOrNull(it)?.url
                }.toSet()
                if (normalized.isEmpty()) return@launch

                val aTag = "$KIND_RELAY_SET:$userPubkey:$categoryId"

                // Prong 1: Kind-5 deletion
                val deleteTemplate = Event.build(5) {
                    add(arrayOf("a", aTag))
                    add(arrayOf("k", KIND_RELAY_SET.toString()))
                }
                val deleteSigned = signer.sign(deleteTemplate)
                RelayConnectionStateMachine.getInstance().send(deleteSigned, normalized)
                RelayHealthTracker.storePublishedEvent(deleteSigned.id, deleteSigned)
                RelayHealthTracker.registerPendingPublish(deleteSigned.id, 5, normalized)

                // Prong 2: Empty replacement (overwrites on relays that ignore kind-5)
                val emptyTemplate = Event.build(KIND_RELAY_SET, "") {
                    add(arrayOf("d", categoryId))
                    add(arrayOf("deleted", "true"))
                }
                val emptySigned = signer.sign(emptyTemplate)
                RelayConnectionStateMachine.getInstance().send(emptySigned, normalized)
                RelayHealthTracker.storePublishedEvent(emptySigned.id, emptySigned)
                RelayHealthTracker.registerPendingPublish(emptySigned.id, KIND_RELAY_SET, normalized)

                // Finalize both after timeout
                scope.launch {
                    delay(10_000L)
                    RelayHealthTracker.finalizePendingPublish(deleteSigned.id)
                    RelayHealthTracker.finalizePendingPublish(emptySigned.id)
                }

                Log.d(TAG, "Deleted category $categoryId (kind-5 + empty replacement)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete category $categoryId: ${e.message}", e)
            }
        }
    }

    // ── Fetch (Cold Start) ───────────────────────────────────────────────────

    /**
     * Fetch the user's kind-30002 relay sets from relays and merge with local storage.
     *
     * Called during [StartupOrchestrator] Phase 1 alongside people lists.
     *
     * Merge strategy:
     * - Remote categories with matching local d-tag: remote wins (newer timestamp)
     * - Remote categories with no local match: added as new
     * - Local-only categories (no remote match): preserved
     *
     * @param userPubkey The user's hex pubkey.
     * @param relayUrls Relay URLs to fetch from (outbox + inbox).
     * @param storageManager Used to merge and persist the fetched categories.
     */
    fun fetchRelaySets(
        userPubkey: String,
        relayUrls: List<String>,
        context: android.content.Context,
    ) {
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No relays to fetch from — skipping")
            return
        }

        val storageManager = RelayStorageManager(context)

        val filter = Filter(
            kinds = listOf(KIND_RELAY_SET),
            authors = listOf(userPubkey),
            limit = 50
        )

        fetchHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()
        val collected = mutableMapOf<String, Event>()

        // Use blocking await so we process results reliably after EOSE/timeout
        scope.launch {
            try {
                stateMachine.awaitOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 800L, maxWaitMs = 8_000L,
                ) { event ->
                    if (event.kind == KIND_RELAY_SET && event.pubKey.equals(userPubkey, ignoreCase = true)) {
                        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
                            ?: return@awaitOneShotSubscription
                        val isDeleted = event.tags.any { it.size >= 2 && it[0] == "deleted" }
                        if (isDeleted) return@awaitOneShotSubscription
                        val existing = collected[dTag]
                        if (existing == null || event.createdAt > existing.createdAt) {
                            collected[dTag] = event
                        }
                    }
                }

                if (collected.isEmpty()) {
                    Log.d(TAG, "No relay sets found for ${userPubkey.take(8)}")
                    return@launch
                }

                val remoteCategories = collected.values.mapNotNull { parseRelaySet(it) }
                Log.d(TAG, "Fetched ${remoteCategories.size} relay sets for ${userPubkey.take(8)}: " +
                    remoteCategories.joinToString { "'${it.name}'(${it.relays.size})" })

                // Merge with local
                val localCategories = storageManager.loadCategories(userPubkey)
                val merged = mergeCategories(localCategories, remoteCategories)

                if (merged != localCategories) {
                    storageManager.saveCategories(userPubkey, merged)
                    Log.d(TAG, "Merged categories: ${localCategories.size} local + " +
                        "${remoteCategories.size} remote → ${merged.size} merged")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch relay sets: ${e.message}", e)
            }
        }

        Log.d(TAG, "Fetching relay sets for ${userPubkey.take(8)} from ${relayUrls.size} relays")
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    /**
     * Parse a kind-30002 event into a [RelayCategory].
     */
    private fun parseRelaySet(event: Event): RelayCategory? {
        val dTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return null
        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
            ?: event.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
            ?: dTag
        val isSubscribed = event.tags.firstOrNull { it.size >= 2 && it[0] == "subscribed" }
            ?.get(1)?.toBooleanStrictOrNull() ?: true

        val relays = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { tag ->
                val url = social.mycelium.android.utils.normalizeRelayUrl(tag[1])
                UserRelay(url = url, read = true, write = true)
            }
            .distinctBy { it.url }

        return RelayCategory(
            id = dTag,
            name = title,
            relays = relays,
            isDefault = dTag == "default_my_relays",
            isSubscribed = isSubscribed,
            createdAt = event.createdAt * 1000L
        )
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    /**
     * Merge local categories with remote categories fetched from relays.
     *
     * Strategy:
     * 1. For each remote category, check if a local category with the same ID exists.
     *    - If local exists and remote has a newer timestamp → use remote.
     *    - If local exists and is newer → keep local.
     *    - If no local match → add remote as new.
     * 2. Local-only categories (no remote match) → keep as-is.
     */
    private fun mergeCategories(
        local: List<RelayCategory>,
        remote: List<RelayCategory>
    ): List<RelayCategory> {
        val localById = local.associateBy { it.id }.toMutableMap()
        val merged = mutableListOf<RelayCategory>()

        for (remoteCategory in remote) {
            val localCategory = localById.remove(remoteCategory.id)
            if (localCategory == null) {
                // New from remote — add it
                merged.add(remoteCategory)
            } else if (remoteCategory.createdAt >= localCategory.createdAt) {
                // Remote is newer or same age — use remote
                merged.add(remoteCategory)
            } else {
                // Local is newer — keep local
                merged.add(localCategory)
            }
        }

        // Add remaining local-only categories
        merged.addAll(localById.values)

        return merged.sortedBy { it.name.lowercase() }
    }

    /** Clear state on account switch. */
    fun clearAll() {
        fetchHandle?.cancel()
        fetchHandle = null
        indexerFetchHandle?.cancel()
        indexerFetchHandle = null
    }

    // ── Indexer List (Kind 10086) ────────────────────────────────────────────

    /**
     * Fetch the user's indexer relay list (kind 10086) on cold start.
     * Merges with locally stored indexer relays — remote URLs not already local are appended.
     */
    fun fetchIndexerList(
        userPubkey: String,
        relayUrls: List<String>,
        context: android.content.Context,
    ) {
        if (relayUrls.isEmpty()) return

        val storageManager = RelayStorageManager(context)
        val filter = Filter(
            kinds = listOf(KIND_INDEXER_LIST),
            authors = listOf(userPubkey),
            limit = 5
        )

        indexerFetchHandle?.cancel()
        val stateMachine = RelayConnectionStateMachine.getInstance()

        scope.launch {
            try {
                var bestEvent: Event? = null
                stateMachine.awaitOneShotSubscription(
                    relayUrls, filter, priority = SubscriptionPriority.LOW,
                    settleMs = 800L, maxWaitMs = 6_000L,
                ) { event ->
                    if (event.kind == KIND_INDEXER_LIST && event.pubKey.equals(userPubkey, ignoreCase = true)) {
                        val current = bestEvent
                        if (current == null || event.createdAt > current.createdAt) {
                            bestEvent = event
                        }
                    }
                }

                val event = bestEvent
                if (event == null) {
                    Log.d(TAG, "No indexer list (kind $KIND_INDEXER_LIST) found for ${userPubkey.take(8)}")
                    return@launch
                }

                val remoteUrls = event.tags
                    .filter { it.size >= 2 && it[0] == "relay" }
                    .map { social.mycelium.android.utils.normalizeRelayUrl(it[1]) }
                    .distinct()

                if (remoteUrls.isEmpty()) return@launch

                // Published kind-10086 is the user's authoritative indexer list.
                // REPLACE local indexers entirely (discards auto-populated NIP-66 relays
                // from onboarding if user has a published list).
                val remoteRelays = remoteUrls.map { url ->
                    UserRelay(url = url, read = true, write = true)
                }
                val localIndexers = storageManager.loadIndexerRelays(userPubkey)
                storageManager.saveIndexerRelays(userPubkey, remoteRelays)
                Log.d(TAG, "Replaced indexer list: ${localIndexers.size} local → ${remoteRelays.size} from published kind-$KIND_INDEXER_LIST")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch indexer list: ${e.message}", e)
            }
        }

        Log.d(TAG, "Fetching indexer list (kind $KIND_INDEXER_LIST) for ${userPubkey.take(8)}")
    }

    // ── Bulk Publish ─────────────────────────────────────────────────────────

    /**
     * Publish all relay categories at once. Called from the FAB menu.
     */
    fun publishAllCategories(
        categories: List<RelayCategory>,
        signer: com.example.cybin.signer.NostrSigner,
        outboxRelayUrls: Set<String>,
    ) {
        if (categories.isEmpty() || outboxRelayUrls.isEmpty()) return
        categories.forEach { category ->
            publishCategory(category, signer, outboxRelayUrls)
        }
        Log.d(TAG, "Bulk-published ${categories.size} categories")
    }
}
