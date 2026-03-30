package social.mycelium.android.repository.relay

import android.util.Log
import social.mycelium.android.debug.DiagnosticLog
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
import social.mycelium.android.repository.sync.StartupOrchestrator

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

    /**
     * When a remote kind-10086 differs from the user's confirmed local indexers,
     * the diff is held here for the UI to present as a non-blocking confirmation.
     * Null = no pending change. Consumed by DashboardScreen / Relay Manager.
     */
    data class PendingIndexerDiff(
        val remoteUrls: List<String>,
        val localUrls: List<String>,
        val added: Set<String>,
        val removed: Set<String>,
    )

    private val _pendingIndexerDiff = kotlinx.coroutines.flow.MutableStateFlow<PendingIndexerDiff?>(null)
    val pendingIndexerDiff: kotlinx.coroutines.flow.StateFlow<PendingIndexerDiff?> = _pendingIndexerDiff

    /** Accept a pending indexer diff — applies the remote list and clears the diff. */
    fun acceptPendingIndexerDiff(userPubkey: String, context: android.content.Context) {
        val diff = _pendingIndexerDiff.value ?: return
        val storageManager = RelayStorageManager(context)
        val remoteRelays = diff.remoteUrls.map { UserRelay(url = it, read = true, write = true, source = social.mycelium.android.data.RelaySource.NIP66_DISCOVERY) }
        storageManager.saveIndexerRelays(userPubkey, remoteRelays)
        storageManager.setIndexersConfirmed(userPubkey, true)
        _pendingIndexerDiff.value = null
        Log.d(TAG, "Accepted pending indexer diff: ${remoteRelays.size} relays applied")
    }

    /** Dismiss a pending indexer diff — keeps local list, clears the banner. */
    fun dismissPendingIndexerDiff() {
        _pendingIndexerDiff.value = null
        Log.d(TAG, "Dismissed pending indexer diff")
    }

    // ── Relay Category Diff ──────────────────────────────────────────────────

    /**
     * When remote kind-30002 relay sets differ from local categories, the diff
     * is held here for the user to review. This prevents orphaned events from
     * prior builds/sessions from silently polluting the relay configuration.
     */
    data class PendingRelayCategoryDiff(
        val remoteCategories: List<social.mycelium.android.data.RelayCategory>,
        val localCategories: List<social.mycelium.android.data.RelayCategory>,
        val newFromRemote: List<social.mycelium.android.data.RelayCategory>,
        val updatedFromRemote: List<social.mycelium.android.data.RelayCategory>,
    )

    private val _pendingCategoryDiff = kotlinx.coroutines.flow.MutableStateFlow<PendingRelayCategoryDiff?>(null)
    val pendingCategoryDiff: kotlinx.coroutines.flow.StateFlow<PendingRelayCategoryDiff?> = _pendingCategoryDiff

    /** Accept a pending category diff — merges remote categories into local storage. */
    fun acceptPendingCategoryDiff(userPubkey: String, context: android.content.Context) {
        val diff = _pendingCategoryDiff.value ?: return
        val storageManager = RelayStorageManager(context)
        val merged = mergeCategories(diff.localCategories, diff.remoteCategories)
        storageManager.saveCategories(userPubkey, merged)
        _pendingCategoryDiff.value = null
        Log.d(TAG, "Accepted pending category diff: ${merged.size} categories merged")
    }

    /** Dismiss a pending category diff — keeps local categories, clears the banner. */
    fun dismissPendingCategoryDiff() {
        _pendingCategoryDiff.value = null
        Log.d(TAG, "Dismissed pending category diff")
    }

    private var fetchHandle: TemporarySubscriptionHandle? = null
    private var indexerFetchHandle: TemporarySubscriptionHandle? = null

    /**
     * Monotonic counter incremented whenever [fetchRelaySets] writes categories
     * to disk. The ViewModel observes this to trigger [reloadFromStorage] even
     * when no [pendingCategoryDiff] is emitted (e.g. first-sign-in auto-apply).
     */
    private val _categoriesWrittenVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    val categoriesWrittenVersion: kotlinx.coroutines.flow.StateFlow<Int> = _categoriesWrittenVersion

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
                val msg = "Published category '${category.name}' (d=${category.id.take(8)}) " +
                    "with ${category.relays.size} relays → ${normalized.size} outbox relays"
                Log.d(TAG, msg)
                DiagnosticLog.sync(TAG, msg)
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
                DiagnosticLog.sync(TAG, "DELETE category=$categoryId (kind-5 + empty replacement) → ${normalized.size} relays")
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
        val deletedDTags = mutableSetOf<String>() // Track d-tags explicitly deleted remotely

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
                        val relayCount = event.tags.count { it.size >= 2 && it[0] == "relay" }
                        val title = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1) ?: dTag.take(8)
                        DiagnosticLog.sync(TAG, "RECV kind-30002 d=${dTag.take(8)} title='$title' relays=$relayCount deleted=$isDeleted ts=${event.createdAt}")
                        if (isDeleted) {
                            deletedDTags.add(dTag)
                            return@awaitOneShotSubscription
                        }
                        val existing = collected[dTag]
                        if (existing == null || event.createdAt > existing.createdAt) {
                            collected[dTag] = event
                        }
                    }
                }

                // Purge local categories whose remote counterpart was explicitly deleted.
                // Without this, a local category persists forever after the user deletes
                // it remotely — the deleted event is skipped in collection, but the local
                // copy is never cleaned up.
                if (deletedDTags.isNotEmpty()) {
                    val localCategories = storageManager.loadCategories(userPubkey)
                    val purged = localCategories.filter { it.id !in deletedDTags }
                    if (purged.size < localCategories.size) {
                        val removedNames = localCategories.filter { it.id in deletedDTags }.joinToString { "'${it.name}'" }
                        storageManager.saveCategories(userPubkey, purged)
                        _categoriesWrittenVersion.value++
                        Log.d(TAG, "Purged ${localCategories.size - purged.size} locally-cached categories that were deleted remotely: $removedNames")
                        DiagnosticLog.sync(TAG, "PURGE: removed ${localCategories.size - purged.size} deleted categories from local: $removedNames")
                    }
                }

                if (collected.isEmpty()) {
                    Log.d(TAG, "No relay sets found for ${userPubkey.take(8)}")
                    DiagnosticLog.sync(TAG, "FETCH RESULT: 0 relay sets for ${userPubkey.take(8)}")
                    return@launch
                }

                val remoteCategories = collected.values.mapNotNull { parseRelaySet(it) }
                    .filter { it.relays.isNotEmpty() } // Skip empty/pruned categories
                val fetchSummary = "Fetched ${remoteCategories.size} relay sets for ${userPubkey.take(8)}: " +
                    remoteCategories.joinToString { "'${it.name}'(${it.relays.size} relays: ${it.relays.joinToString(",") { r -> r.url.removePrefix("wss://").removeSuffix("/") }})" }
                Log.d(TAG, fetchSummary)
                DiagnosticLog.sync(TAG, "FETCH RESULT: $fetchSummary")

                if (remoteCategories.isEmpty()) {
                    Log.d(TAG, "All fetched relay sets were empty after filtering — nothing to merge")
                    return@launch
                }

                val localCategories = storageManager.loadCategories(userPubkey)
                DiagnosticLog.sync(TAG, "LOCAL categories: ${localCategories.size} total, " +
                    localCategories.joinToString { "'${it.name}'(${it.relays.size} relays, id=${it.id.take(8)})" })

                // On first sign-in (no local categories or only the empty default),
                // auto-apply remote categories since there's nothing to conflict with.
                val hasSubstantiveLocal = localCategories.any { it.relays.isNotEmpty() }
                if (!hasSubstantiveLocal) {
                    val merged = mergeCategories(localCategories, remoteCategories)
                    storageManager.saveCategories(userPubkey, merged)
                    _categoriesWrittenVersion.value++
                    Log.d(TAG, "First sign-in: auto-applied ${remoteCategories.size} remote categories")
                    DiagnosticLog.sync(TAG, "MERGE (first sign-in): auto-applied ${remoteCategories.size} remote → ${merged.size} total")
                    return@launch
                }

                // Returning user: check if remote differs from local. If so, hold for
                // user confirmation instead of silently merging (prevents orphaned events
                // from prior builds/sessions from polluting the relay config).
                val localById = localCategories.associateBy { it.id }
                val newFromRemote = remoteCategories.filter { it.id !in localById }
                val updatedFromRemote = remoteCategories.filter { rc ->
                    val lc = localById[rc.id]
                    lc != null && rc.createdAt >= lc.createdAt && rc.relays != lc.relays
                }

                if (newFromRemote.isEmpty() && updatedFromRemote.isEmpty()) {
                    Log.d(TAG, "Remote relay sets match local — no merge needed")
                    DiagnosticLog.sync(TAG, "MERGE: remote matches local — no changes")
                    return@launch
                }

                // Emit diff for user confirmation
                _pendingCategoryDiff.value = PendingRelayCategoryDiff(
                    remoteCategories = remoteCategories,
                    localCategories = localCategories,
                    newFromRemote = newFromRemote,
                    updatedFromRemote = updatedFromRemote,
                )
                val diffMsg = "DIFF: ${newFromRemote.size} new [${newFromRemote.joinToString { it.name }}], " +
                    "${updatedFromRemote.size} updated [${updatedFromRemote.joinToString { it.name }}] — pending user confirmation"
                Log.d(TAG, diffMsg)
                DiagnosticLog.sync(TAG, diffMsg)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch relay sets: ${e.message}", e)
            }
        }

        Log.d(TAG, "Fetching relay sets for ${userPubkey.take(8)} from ${relayUrls.size} relays")
        DiagnosticLog.sync(TAG, "FETCH START: kind-30002 for ${userPubkey.take(8)} from ${relayUrls.size} relays: ${relayUrls.joinToString(",") { it.removePrefix("wss://").removeSuffix("/") }}")
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

        // Build NIP-66 liveness lookup once for this parse
        val nip66Relays = Nip66RelayDiscoveryRepository.discoveredRelays.value
        val sevenDaysAgoSecs = (System.currentTimeMillis() / 1000) - (7 * 24 * 60 * 60)

        val relays = event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { tag ->
                val url = social.mycelium.android.utils.normalizeRelayUrl(tag[1])
                UserRelay(url = url, read = true, write = true, source = social.mycelium.android.data.RelaySource.NIP65_IMPORT)
            }
            .distinctBy { it.url }
            // Filter out blocked/dead relays so ghost entries from permanently
            // offline relays (e.g. tekkadan.mycelium.social) don't re-enter
            // the connection pool via synced relay categories.
            .filter { relay ->
                if (RelayHealthTracker.isBlocked(relay.url)) return@filter false
                // Cross-check NIP-66 liveness: if monitors track this relay but
                // haven't seen it alive in 7+ days, exclude it.
                val normalized = relay.url.trim().removeSuffix("/").lowercase()
                val discovered = nip66Relays[normalized]
                // Unmonitored relays (private, niche) pass through — benefit of the doubt
                if (discovered == null) return@filter true
                val alive = discovered.lastSeen >= sevenDaysAgoSecs
                if (!alive) Log.d(TAG, "Pruned dead relay from category '$title': ${relay.url}")
                alive
            }

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
        _pendingCategoryDiff.value = null
        _pendingIndexerDiff.value = null
    }

    // ── Indexer List (Kind 10086) ────────────────────────────────────────────

    /**
     * Fetch the user's indexer relay list (kind 10086) on cold start.
     *
     * Behavior depends on whether the user has confirmed their indexer list:
     * - **Not confirmed:** Replaces local indexers with the remote list (first-time
     *   setup or onboarding prefetch — the user hasn't curated yet).
     * - **Confirmed:** Compares remote vs. local and, if they differ, emits a
     *   [PendingIndexerDiff] for the UI to show as a non-blocking banner instead
     *   of silently overwriting the user's curated list.
     *
     * @param forceReplace When true, always replace local indexers regardless of
     *   the confirmed flag. Used during onboarding prefetch where the user is
     *   actively reviewing and will re-confirm on the next screen.
     */
    fun fetchIndexerList(
        userPubkey: String,
        relayUrls: List<String>,
        context: android.content.Context,
        forceReplace: Boolean = false,
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

                val localIndexers = storageManager.loadIndexerRelays(userPubkey)
                val localUrls = localIndexers.map { it.url }
                val localNormalized = localUrls.map { it.trim().removeSuffix("/").lowercase() }.toSet()
                val remoteNormalized = remoteUrls.map { it.trim().removeSuffix("/").lowercase() }.toSet()

                val listsMatch = localNormalized == remoteNormalized

                if (forceReplace || !storageManager.areIndexersConfirmed(userPubkey)) {
                    // User hasn't confirmed yet — safe to replace silently
                    val remoteRelays = remoteUrls.map { url ->
                        UserRelay(url = url, read = true, write = true, source = social.mycelium.android.data.RelaySource.NIP66_DISCOVERY)
                    }
                    storageManager.saveIndexerRelays(userPubkey, remoteRelays)
                    Log.d(TAG, "Replaced indexer list: ${localIndexers.size} local → ${remoteRelays.size} from published kind-$KIND_INDEXER_LIST")
                } else if (!listsMatch) {
                    // User has confirmed their list — don't overwrite, emit a diff for the UI
                    val added = remoteNormalized - localNormalized
                    val removed = localNormalized - remoteNormalized
                    _pendingIndexerDiff.value = PendingIndexerDiff(
                        remoteUrls = remoteUrls,
                        localUrls = localUrls,
                        added = added,
                        removed = removed,
                    )
                    Log.d(TAG, "Indexer list differs from confirmed: +${added.size} -${removed.size} — pending user approval")
                } else {
                    Log.d(TAG, "Indexer list matches confirmed local list — no changes needed")
                }
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
