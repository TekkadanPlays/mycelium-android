package social.mycelium.android.viewmodel

import android.content.Context
import android.util.Log
import social.mycelium.android.debug.DiagnosticLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.data.UserRelay
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.DefaultRelayCategories
import social.mycelium.android.data.DefaultRelayProfiles
import social.mycelium.android.cache.Nip11CacheManager
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayHealthInfo
import social.mycelium.android.relay.RelayHealthTracker
import social.mycelium.android.relay.RelayDeliveryTracker
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RelayManagementUiState(
    val relays: List<UserRelay> = emptyList(),
    val showAddRelayDialog: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val relayCategories: List<RelayCategory> = DefaultRelayCategories.getAllDefaultCategories(),
    val relayProfiles: List<RelayProfile> = listOf(DefaultRelayProfiles.getDefaultProfile()),
    val outboxRelays: List<UserRelay> = emptyList(),
    val inboxRelays: List<UserRelay> = emptyList(),
    val indexerRelays: List<UserRelay> = emptyList(),
    val announcementRelays: List<UserRelay> = emptyList(),
    val draftsRelays: List<UserRelay> = emptyList(),
    val blossomServers: List<social.mycelium.android.data.MediaServer> = social.mycelium.android.data.DefaultMediaServers.BLOSSOM_SERVERS,
    val nip96Servers: List<social.mycelium.android.data.MediaServer> = social.mycelium.android.data.DefaultMediaServers.NIP96_SERVERS
)

class RelayManagementViewModel(
    private val relayRepository: RelayRepository,
    private val storageManager: RelayStorageManager
) : ViewModel() {

    private val nip11Cache by lazy { relayRepository.getNip11Cache() }

    private val _uiState = MutableStateFlow(RelayManagementUiState())
    val uiState: StateFlow<RelayManagementUiState> = _uiState.asStateFlow()

    // Current user's pubkey - set when loading data
    private var currentPubkey: String? = null

    /** True once [loadUserRelays] has populated UI state from disk for the current pubkey.
     *  Guards [fetchUserRelaysFromNetwork] against racing with the storage load. */
    @Volatile
    private var storageLoaded = false

    /** Signer cached from the active account — set via [setSigner]. */
    private var cachedSigner: com.example.cybin.signer.NostrSigner? = null

    /** Set the NostrSigner for publishing relay sets. Call whenever the account changes. */
    fun setSigner(signer: com.example.cybin.signer.NostrSigner?) {
        cachedSigner = signer
    }

    /**
     * Publish a relay category as a kind-30002 event to the user's outbox relays.
     * Fire-and-forget — errors are logged but don't affect UI.
     */
    private fun publishCategoryToRelays(category: social.mycelium.android.data.RelayCategory) {
        val signer = cachedSigner ?: return
        val outboxUrls = _uiState.value.outboxRelays.map {
            social.mycelium.android.utils.normalizeRelayUrl(it.url)
        }.toSet()
        social.mycelium.android.repository.RelayCategorySyncRepository.publishCategory(
            category, signer, outboxUrls
        )
    }

    /**
     * Publish a kind-5 + empty replacement to delete a category from relays.
     */
    private fun deleteCategoryFromRelays(categoryId: String) {
        val signer = cachedSigner ?: return
        val pubkey = currentPubkey ?: return
        val outboxUrls = _uiState.value.outboxRelays.map {
            social.mycelium.android.utils.normalizeRelayUrl(it.url)
        }.toSet()
        social.mycelium.android.repository.RelayCategorySyncRepository.deleteCategory(
            categoryId, pubkey, signer, outboxUrls
        )
    }

    /**
     * Publish a single category from a relay profile as a kind-30002 event.
     * Called from the Edit Category dialog's "Publish" action.
     */
    fun publishProfileCategory(profileId: String, categoryId: String) {
        val signer = cachedSigner ?: return
        val profile = _uiState.value.relayProfiles.find { it.id == profileId } ?: return
        val category = profile.categories.find { it.id == categoryId } ?: return
        publishCategoryToRelays(category)
        Log.d("RelayMgmtVM", "Publishing profile category '${category.name}' (${category.relays.size} relays)")
    }

    // Expose categories separately for easy access from other screens
    val relayCategories: StateFlow<List<RelayCategory>> = _uiState
        .map { it.relayCategories }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultRelayCategories.getAllDefaultCategories())

    // Expose profiles separately
    val relayProfiles: StateFlow<List<RelayProfile>> = _uiState
        .map { it.relayProfiles }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(DefaultRelayProfiles.getDefaultProfile()))

    // Live health data from RelayHealthTracker — exposed for inline display on relay rows
    val healthByRelay: StateFlow<Map<String, RelayHealthInfo>> = RelayHealthTracker.healthByRelay
    val flaggedRelays: StateFlow<Set<String>> = RelayHealthTracker.flaggedRelays
    val blockedRelays: StateFlow<Set<String>> = RelayHealthTracker.blockedRelays

    // Delivery stats from Thompson Sampling tracker
    private val _deliveryStats = MutableStateFlow<Map<String, RelayDeliveryTracker.RelayStats>>(emptyMap())
    val deliveryStats: StateFlow<Map<String, RelayDeliveryTracker.RelayStats>> = _deliveryStats.asStateFlow()

    // Health actions — delegate to RelayHealthTracker
    fun blockRelay(url: String) = RelayHealthTracker.blockRelay(url)
    fun unblockRelay(url: String) = RelayHealthTracker.unblockRelay(url)
    fun unflagRelay(url: String) = RelayHealthTracker.unflagRelay(url)
    fun resetRelayHealth(url: String) = RelayHealthTracker.resetRelay(url)

    init {
        // Collect relay updates from repository
        viewModelScope.launch {
            relayRepository.relays.collect { relays ->
                _uiState.update { it.copy(relays = relays) }
            }
        }

        // Periodically refresh delivery stats (not a StateFlow in the tracker, so poll)
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                _deliveryStats.value = RelayDeliveryTracker.getStats()
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    /**
     * Load relay data for a specific user (pubkey)
     * Call this when user logs in or switches accounts
     */
    fun loadUserRelays(pubkey: String) {
        val isSwitching = pubkey != currentPubkey
        // If switching to a different user, clear stale state immediately so downstream
        // consumers (DashboardScreen, TopicsScreen) don't see the old account's relays.
        if (isSwitching) {
            storageLoaded = false
            _uiState.update {
                it.copy(
                    relayCategories = emptyList(),
                    relayProfiles = emptyList(),
                    outboxRelays = emptyList(),
                    inboxRelays = emptyList(),
                    indexerRelays = emptyList(),
                    announcementRelays = emptyList(),
                    draftsRelays = emptyList(),
                    blossomServers = emptyList(),
                    nip96Servers = emptyList()
                )
            }
        }
        currentPubkey = pubkey

        viewModelScope.launch {
            // Load categories + profiles
            var categories = storageManager.loadCategories(pubkey)
            var profiles = storageManager.loadProfiles(pubkey)

            DiagnosticLog.state("RelayMgmtVM", "loadUserRelays(${ pubkey.take(8) }): " +
                "${categories.size} categories from storage: " +
                categories.joinToString { "'${it.name}'(${it.relays.size} relays, id=${it.id.take(8)})" })

            // Load personal relays
            val outbox = storageManager.loadOutboxRelays(pubkey)
            val inbox = storageManager.loadInboxRelays(pubkey)
            val cache = storageManager.loadIndexerRelays(pubkey)

            // Load system relays
            val announcements = storageManager.loadAnnouncementRelays(pubkey)
            val drafts = storageManager.loadDraftsRelays(pubkey)
            val blossom = storageManager.loadBlossomServers(pubkey)
            val nip96 = storageManager.loadNip96Servers(pubkey)

            // ── Merge fetched remote categories into active profile ──
            // On first sign-in (or when local profile categories are empty), remote
            // kind-30002 categories fetched by RelayCategorySyncRepository should
            // replace the default placeholder. For returning users, pending diffs
            // are auto-merged into the active profile's category list.
            val pendingDiff = social.mycelium.android.repository.RelayCategorySyncRepository.pendingCategoryDiff.value
            val activeProfile = profiles.find { it.isActive } ?: profiles.firstOrNull()
            if (activeProfile != null) {
                val profileHasSubstantive = activeProfile.categories.any { it.relays.isNotEmpty() }
                if (!profileHasSubstantive && categories.any { it.relays.isNotEmpty() }) {
                    // First sign-in: standalone categories have data (from fetchRelaySets auto-apply)
                    // but the active profile is empty — populate profile with the fetched categories
                    val substantiveCategories = categories.filter { it.relays.isNotEmpty() }
                    profiles = profiles.map { p ->
                        if (p.id == activeProfile.id) p.copy(categories = substantiveCategories) else p
                    }
                    storageManager.saveProfiles(pubkey, profiles)
                    DiagnosticLog.state("RelayMgmtVM", "First sign-in: populated active profile with ${substantiveCategories.size} fetched categories")
                    Log.d("RelayMgmtVM", "First sign-in: populated active profile with ${substantiveCategories.size} fetched categories")
                } else if (pendingDiff != null && pendingDiff.newFromRemote.isNotEmpty()) {
                    // Returning user with pending remote diff — merge new-from-remote into profile
                    val existingIds = activeProfile.categories.map { it.id }.toSet()
                    val newCategories = pendingDiff.newFromRemote.filter { it.id !in existingIds && it.relays.isNotEmpty() }
                    if (newCategories.isNotEmpty()) {
                        profiles = profiles.map { p ->
                            if (p.id == activeProfile.id) p.copy(categories = p.categories + newCategories) else p
                        }
                        // Also merge into standalone categories for consistency
                        val standalonIds = categories.map { it.id }.toSet()
                        categories = categories + newCategories.filter { it.id !in standalonIds }
                        storageManager.saveProfiles(pubkey, profiles)
                        storageManager.saveCategories(pubkey, categories)
                        Log.d("RelayMgmtVM", "Returning user: merged ${newCategories.size} new remote categories into active profile")
                    }
                    // Clear the diff since we auto-merged
                    social.mycelium.android.repository.RelayCategorySyncRepository.dismissPendingCategoryDiff()
                }
            }

            // For same-user reload (e.g. returning from onboarding), only update if
            // the data actually changed — avoids triggering downstream recomposition
            // that causes feed flicker.
            val current = _uiState.value
            if (!isSwitching &&
                current.relayCategories == categories &&
                current.relayProfiles == profiles &&
                current.outboxRelays == outbox &&
                current.inboxRelays == inbox &&
                current.indexerRelays == cache &&
                current.announcementRelays == announcements &&
                current.draftsRelays == drafts &&
                current.blossomServers == blossom &&
                current.nip96Servers == nip96
            ) {
                storageLoaded = true
                return@launch
            }

            _uiState.update { it.copy(relayCategories = categories, relayProfiles = profiles, outboxRelays = outbox, inboxRelays = inbox, indexerRelays = cache, announcementRelays = announcements, draftsRelays = drafts, blossomServers = blossom, nip96Servers = nip96) }
            storageLoaded = true

            // Mark outbox relays as priority for connection (connect first, no jitter/cooldown)
            val outboxUrls = outbox.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }.toSet()
            if (outboxUrls.isNotEmpty()) {
                RelayConnectionStateMachine.getInstance().setPriorityRelayUrls(outboxUrls)
            }

            // Mark inbox + outbox + subscribed category relays as persistent so they stay
            // connected even when temporarily idle (avoids connect/disconnect churn).
            val inboxUrls = inbox.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }.toSet()
            val subscribedCategoryUrls = categories.filter { it.isSubscribed }
                .flatMap { it.relays }.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }.toSet()
            val persistentUrls = outboxUrls + inboxUrls + subscribedCategoryUrls
            if (persistentUrls.isNotEmpty()) {
                RelayConnectionStateMachine.getInstance().setPersistentRelayUrls(persistentUrls)
            }

            // Fetch NIP-11 info in background for all relays (personal + category + profile categories)
            val allCategoryUrls = categories.flatMap { it.relays }.map { it.url }
            val allProfileCategoryUrls = profiles.flatMap { it.categories }.flatMap { it.relays }.map { it.url }
            val allPersonalUrls = (outbox + inbox + cache).map { it.url }
            val allSystemUrls = (announcements + drafts).map { it.url }
            val allUrls = (allCategoryUrls + allProfileCategoryUrls + allPersonalUrls + allSystemUrls).distinct()
            allUrls.forEach { url ->
                launch(Dispatchers.IO) {
                    try {
                        val freshInfo = nip11Cache.getRelayInfo(url)
                        if (freshInfo != null) {
                            _uiState.update { state ->
                                state.copy(
                                    relayCategories = state.relayCategories.map { cat ->
                                        cat.copy(relays = cat.relays.updateRelayInfo(url, freshInfo))
                                    },
                                    relayProfiles = state.relayProfiles.map { profile ->
                                        profile.copy(categories = profile.categories.map { cat ->
                                            cat.copy(relays = cat.relays.updateRelayInfo(url, freshInfo))
                                        })
                                    },
                                    outboxRelays = state.outboxRelays.updateRelayInfo(url, freshInfo),
                                    inboxRelays = state.inboxRelays.updateRelayInfo(url, freshInfo),
                                    indexerRelays = state.indexerRelays.updateRelayInfo(url, freshInfo),
                                    announcementRelays = state.announcementRelays.updateRelayInfo(url, freshInfo),
                                    draftsRelays = state.draftsRelays.updateRelayInfo(url, freshInfo)
                                )
                            }
                            saveToStorage()
                        }
                    } catch (_: Exception) { /* ignore — keep existing info */ }
                }
            }
        }
    }

    /** Update NIP-11 info for a relay in a list by URL. */
    private fun List<UserRelay>.updateRelayInfo(url: String, info: social.mycelium.android.data.RelayInformation): List<UserRelay> {
        return map { relay ->
            if (relay.url == url) relay.copy(info = info, isOnline = true, lastChecked = System.currentTimeMillis())
            else relay
        }
    }

    /** Force-fetch NIP-11 info for a newly added relay and update all UI state lists that contain it. */
    private fun fetchAndApplyNip11(relayUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val freshInfo = nip11Cache.getRelayInfo(relayUrl, forceRefresh = true) ?: return@launch
                _uiState.update { state ->
                    state.copy(
                        relayCategories = state.relayCategories.map { cat ->
                            cat.copy(relays = cat.relays.updateRelayInfo(relayUrl, freshInfo))
                        },
                        relayProfiles = state.relayProfiles.map { profile ->
                            profile.copy(categories = profile.categories.map { cat ->
                                cat.copy(relays = cat.relays.updateRelayInfo(relayUrl, freshInfo))
                            })
                        },
                        outboxRelays = state.outboxRelays.updateRelayInfo(relayUrl, freshInfo),
                        inboxRelays = state.inboxRelays.updateRelayInfo(relayUrl, freshInfo),
                        indexerRelays = state.indexerRelays.updateRelayInfo(relayUrl, freshInfo),
                        announcementRelays = state.announcementRelays.updateRelayInfo(relayUrl, freshInfo),
                        draftsRelays = state.draftsRelays.updateRelayInfo(relayUrl, freshInfo)
                    )
                }
                saveToStorage()
            } catch (_: Exception) { /* ignore — keep existing info */ }
        }
    }

    /**
     * Save current relay data to storage
     */
    private fun saveToStorage() {
        currentPubkey?.let { pubkey ->
            storageManager.saveCategories(pubkey, _uiState.value.relayCategories)
            storageManager.saveProfiles(pubkey, _uiState.value.relayProfiles)
            storageManager.saveOutboxRelays(pubkey, _uiState.value.outboxRelays)
            storageManager.saveInboxRelays(pubkey, _uiState.value.inboxRelays)
            storageManager.saveIndexerRelays(pubkey, _uiState.value.indexerRelays)
            storageManager.saveAnnouncementRelays(pubkey, _uiState.value.announcementRelays)
            storageManager.saveDraftsRelays(pubkey, _uiState.value.draftsRelays)
            storageManager.saveBlossomServers(pubkey, _uiState.value.blossomServers)
            storageManager.saveNip96Servers(pubkey, _uiState.value.nip96Servers)
        }
    }

    /**
     * Re-apply the active relay subscription so newly added relays start
     * receiving kind-1 notes and kind-0 profiles immediately, even before
     * the user navigates back to the feed.
     *
     * Invalidates NotesRepository's idempotency guard so the next
     * ensureSubscriptionToNotes call (when user returns to feed) re-applies
     * the subscription with the updated relay set.
     */
    private fun refreshActiveSubscription() {
        val state = _uiState.value
        val subscribedRelayUrls = state.relayCategories
            .filter { it.isSubscribed }
            .flatMap { it.relays }
            .map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
        // Include relays from active profiles
        val profileRelayUrls = state.relayProfiles
            .filter { it.isActive }
            .flatMap { it.categories }
            .filter { it.isSubscribed }
            .flatMap { it.relays }
            .map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
        // Merge outbox relays so adding a relay to a category doesn't drop outbox notes
        val outboxUrls = state.outboxRelays.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
        val relayUrls = (subscribedRelayUrls + profileRelayUrls + outboxUrls).distinct()
        if (relayUrls.isEmpty()) return
        Log.d("RelayMgmtVM", "Refreshing active subscription with ${relayUrls.size} relays (${outboxUrls.size} outbox priority)")
        // Clear NIP-42 auth state for all relays in the new set so re-enabled relays
        // get a clean AUTH handshake (no stale cooldowns or consumed challenges)
        val authHandler = RelayConnectionStateMachine.getInstance().nip42AuthHandler
        relayUrls.forEach { authHandler.clearAuthStateForRelay(it) }
        // Update the NIP-42 allowed relay set so newly added relays can authenticate.
        // Without this, AUTH challenges from new relays are silently ignored.
        val allRelayUrls = (subscribedRelayUrls + profileRelayUrls + outboxUrls +
            state.inboxRelays.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) } +
            state.indexerRelays.map { social.mycelium.android.utils.normalizeRelayUrl(it.url) } +
            // Include NIP-17 DM relays so auth challenges from DM relays are accepted
            social.mycelium.android.repository.DirectMessageRepository.dmRelayUrls.value
                .map { social.mycelium.android.utils.normalizeRelayUrl(it) }
        ).toSet()
        authHandler.setAllowedRelayUrls(allRelayUrls)
        // Invalidate the idempotency guard so the feed re-subscribes with the new relay set
        social.mycelium.android.repository.NotesRepository.getInstance().invalidateSubscriptionGuard()
        // Mark outbox relays as priority so they connect first (no jitter, cooldown cleared)
        RelayConnectionStateMachine.getInstance().setPriorityRelayUrls(outboxUrls.toSet())
        // Preserve the current kind-1 filter (e.g. Following authors) so relay changes
        // don't accidentally replace a Following subscription with a global one.
        val currentFilter = RelayConnectionStateMachine.getInstance().getCurrentKind1Filter()
        RelayConnectionStateMachine.getInstance().requestFeedChange(relayUrls, currentFilter)
        // Also refresh TopicsRepository so kind-11 topics arrive from the new relay
        social.mycelium.android.repository.TopicsRepository.getInstanceOrNull()?.setSubscriptionRelays(relayUrls)
        // Update NotesRepository relay set so NoteCountsRepository (kind-30011 votes,
        // kind-1111 replies) picks up the new relay for existing subscriptions
        social.mycelium.android.repository.NotesRepository.getInstance().setSubscriptionRelays(relayUrls)
        // Update NotificationsRepository so target-note fetches, badge resolution, and
        // poll enrichment use the updated relay set (not just the sign-in set).
        social.mycelium.android.repository.NotificationsRepository.updateSubscriptionRelayUrls(relayUrls)
        // Preload NIP-11 info for all relay URLs so relay orbs get icons immediately
        social.mycelium.android.cache.Nip11CacheManager.getInstanceOrNull()
            ?.preloadRelayInfo(relayUrls, viewModelScope)
    }

    fun showAddRelayDialog() {
        _uiState.update { it.copy(showAddRelayDialog = true) }
    }

    fun hideAddRelayDialog() {
        _uiState.update { it.copy(showAddRelayDialog = false, errorMessage = null) }
    }

    fun addRelay(url: String, read: Boolean, write: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            relayRepository.addRelay(url, read, write)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, showAddRelayDialog = false) }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to add relay") }
                }
        }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            relayRepository.removeRelay(url)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to remove relay") }
                }
        }
    }

    fun refreshRelayInfo(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            relayRepository.refreshRelayInfo(url)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to refresh relay info") }
                }
        }
    }

    fun testRelayConnection(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            relayRepository.testRelayConnection(url)
                .onSuccess { isConnected ->
                    _uiState.update { it.copy(isLoading = false) }
                    // Connection status will be updated via the repository's StateFlow
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to test connection") }
                }
        }
    }

    fun updateRelaySettings(url: String, read: Boolean, write: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            relayRepository.updateRelaySettings(url, read, write)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to update relay settings") }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // Category management methods
    fun addCategory(category: RelayCategory) {
        _uiState.update { it.copy(relayCategories = it.relayCategories + category) }
        saveToStorage()
    }

    fun updateCategory(categoryId: String, updatedCategory: RelayCategory) {
        _uiState.update { state ->
            state.copy(relayCategories = state.relayCategories.map { if (it.id == categoryId) updatedCategory else it })
        }
        saveToStorage()
    }

    fun deleteCategory(categoryId: String) {
        val deleted = _uiState.value.relayCategories.find { it.id == categoryId }
        _uiState.update { it.copy(relayCategories = it.relayCategories.filter { cat -> cat.id != categoryId }) }
        saveToStorage()
        deleteCategoryFromRelays(categoryId)
        DiagnosticLog.state("RelayMgmtVM", "deleteCategory id=${categoryId.take(8)} name='${deleted?.name}' " +
            "(had ${deleted?.relays?.size ?: 0} relays) — removed from UI + storage + relays")
    }

    fun addRelayToCategory(categoryId: String, relay: UserRelay) {
        _uiState.update { state ->
            state.copy(relayCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(relays = category.relays + relay) else category
            })
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
        fetchAndApplyNip11(relay.url)
    }

    fun removeRelayFromCategory(categoryId: String, relayUrl: String) {
        _uiState.update { state ->
            state.copy(relayCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(relays = category.relays.filter { it.url != relayUrl }) else category
            })
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
    }

    // Personal relay management methods
    fun addOutboxRelay(relay: UserRelay) {
        _uiState.update { it.copy(outboxRelays = it.outboxRelays + relay) }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
        fetchAndApplyNip11(relay.url)
    }

    fun removeOutboxRelay(url: String) {
        _uiState.update { it.copy(outboxRelays = it.outboxRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addInboxRelay(relay: UserRelay) {
        _uiState.update { it.copy(inboxRelays = it.inboxRelays + relay) }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
        fetchAndApplyNip11(relay.url)
    }

    fun removeInboxRelay(url: String) {
        _uiState.update { it.copy(inboxRelays = it.inboxRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addIndexerRelay(relay: UserRelay) {
        _uiState.update { it.copy(indexerRelays = it.indexerRelays + relay) }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            currentPubkey?.let { storageManager.setIndexersConfirmed(it, true) }
            refreshActiveSubscription()
        }
        fetchAndApplyNip11(relay.url)
    }

    fun removeIndexerRelay(url: String) {
        _uiState.update { it.copy(indexerRelays = it.indexerRelays.filter { r -> r.url != url }) }
        saveToStorage()
        currentPubkey?.let { storageManager.setIndexersConfirmed(it, true) }
    }

    // System relay management methods
    fun addAnnouncementRelay(relay: UserRelay) {
        _uiState.update { it.copy(announcementRelays = it.announcementRelays + relay) }
        saveToStorage()
        fetchAndApplyNip11(relay.url)
    }

    fun removeAnnouncementRelay(url: String) {
        _uiState.update { it.copy(announcementRelays = it.announcementRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addDraftsRelay(relay: UserRelay) {
        _uiState.update { it.copy(draftsRelays = it.draftsRelays + relay) }
        saveToStorage()
        fetchAndApplyNip11(relay.url)
    }

    fun removeDraftsRelay(url: String) {
        _uiState.update { it.copy(draftsRelays = it.draftsRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addBlossomServer(server: social.mycelium.android.data.MediaServer) {
        _uiState.update { it.copy(blossomServers = it.blossomServers + server) }
        saveToStorage()
    }

    fun removeBlossomServer(baseUrl: String) {
        _uiState.update { it.copy(blossomServers = it.blossomServers.filter { s -> s.baseUrl != baseUrl }) }
        saveToStorage()
    }

    fun addNip96Server(server: social.mycelium.android.data.MediaServer) {
        _uiState.update { it.copy(nip96Servers = it.nip96Servers + server) }
        saveToStorage()
    }

    fun removeNip96Server(baseUrl: String) {
        _uiState.update { it.copy(nip96Servers = it.nip96Servers.filter { s -> s.baseUrl != baseUrl }) }
        saveToStorage()
    }

    /**
     * Toggle a category's subscription status.
     * Subscribed categories show in sidebar, connect relays, and are used for feeds.
     * Unsubscribed categories are dormant.
     */
    fun toggleCategorySubscription(categoryId: String) {
        _uiState.update { state ->
            val updatedCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(isSubscribed = !category.isSubscribed) else category
            }
            // Also update the categories inside each relay profile so that
            // DashboardScreen (which reads activeProfile.categories) sees the change.
            val updatedProfiles = state.relayProfiles.map { profile ->
                profile.copy(categories = profile.categories.map { category ->
                    if (category.id == categoryId) category.copy(isSubscribed = !category.isSubscribed) else category
                })
            }
            state.copy(relayCategories = updatedCategories, relayProfiles = updatedProfiles)
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
    }

    /**
     * Get all subscribed categories
     */
    fun getSubscribedCategories(): List<RelayCategory> {
        return _uiState.value.relayCategories.filter { it.isSubscribed }
    }

    /**
     * Get all relay URLs from subscribed categories
     */
    fun getSubscribedRelayUrls(): List<String> {
        return getSubscribedCategories()
            .flatMap { it.relays }
            .map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
            .distinct()
    }

    /**
     * Get all relay URLs from all General categories
     */
    fun getAllGeneralRelayUrls(): List<String> {
        return _uiState.value.relayCategories
            .filter { it.name.contains("General", ignoreCase = true) }
            .flatMap { category -> category.relays.map { it.url } }
            .distinct()
    }

    // ====== Profile management ======

    fun addProfile(profile: RelayProfile) {
        _uiState.update { it.copy(relayProfiles = it.relayProfiles + profile) }
        saveToStorage()
    }

    fun updateProfile(profileId: String, updatedProfile: RelayProfile) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map { if (it.id == profileId) updatedProfile else it })
        }
        saveToStorage()
    }

    fun deleteProfile(profileId: String) {
        _uiState.update { it.copy(relayProfiles = it.relayProfiles.filter { p -> p.id != profileId }) }
        saveToStorage()
    }

    fun activateProfile(profileId: String) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map {
                if (it.id == profileId) it.copy(isActive = !it.isActive) else it
            })
        }
        saveToStorage()
        refreshActiveSubscription()
    }

    /**
     * Publish a specific category from a profile after it has been updated
     * (e.g. name change, subscription toggle). This ensures the remote
     * kind-30002 event reflects the latest local state.
     */
    fun publishProfileCategoryById(profileId: String, categoryId: String) {
        val profile = _uiState.value.relayProfiles.find { it.id == profileId } ?: return
        val category = profile.categories.find { it.id == categoryId } ?: return
        publishCategoryToRelays(category)
        Log.d("RelayMgmtVM", "Publishing updated profile category '${category.name}' (${category.relays.size} relays)")
    }

    fun addCategoryToProfile(profileId: String, category: RelayCategory) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map { profile ->
                if (profile.id == profileId) profile.copy(categories = profile.categories + category) else profile
            })
        }
        saveToStorage()
    }

    fun removeCategoryFromProfile(profileId: String, categoryId: String) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map { profile ->
                if (profile.id == profileId) profile.copy(categories = profile.categories.filter { it.id != categoryId }) else profile
            })
        }
        saveToStorage()
        // Also publish the deletion to relays so remote state stays in sync
        deleteCategoryFromRelays(categoryId)
    }

    fun addRelayToProfileCategory(profileId: String, categoryId: String, relay: UserRelay) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(categories = profile.categories.map { cat ->
                        if (cat.id == categoryId) cat.copy(relays = cat.relays + relay) else cat
                    })
                } else profile
            })
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
        fetchAndApplyNip11(relay.url)
    }

    fun removeRelayFromProfileCategory(profileId: String, categoryId: String, relayUrl: String) {
        _uiState.update { state ->
            state.copy(relayProfiles = state.relayProfiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(categories = profile.categories.map { cat ->
                        if (cat.id == categoryId) cat.copy(relays = cat.relays.filter { it.url != relayUrl }) else cat
                    })
                } else profile
            })
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveToStorage()
            refreshActiveSubscription()
        }
    }

    fun fetchUserRelaysFromNetwork(pubkey: String) {
        viewModelScope.launch {
            // Wait for loadUserRelays to finish populating state from disk so we
            // don't mistake an in-flight disk read for "empty outbox/inbox".
            var waited = 0
            while (!storageLoaded && waited < 3_000) {
                kotlinx.coroutines.delay(50)
                waited += 50
            }

            _uiState.update { it.copy(isLoading = true) }

                relayRepository.fetchUserRelayList(pubkey)
                .onSuccess { relays ->
                    // Normalize URLs (no trailing slash) then categorize by NIP-65
                    val normalized = relays.map { it.copy(url = RelayStorageManager.normalizeRelayUrl(it.url)) }
                    val outbox = normalized.filter { it.write }
                    val inbox = normalized.filter { it.read }
                    val current = _uiState.value
                    // Only populate outbox/inbox when they are empty so we don't overwrite user edits.
                    // NOTE: We intentionally do NOT inject outbox relays into the Home Relays
                    // category here. That seeding is handled by the onboarding flow
                    // (OnboardingScreen) and kind-30002 sync (RelayCategorySyncRepository).
                    // Injecting NIP-65 outbox relays into categories here would bypass
                    // kind-30002 deletion state and resurrect relays the user deleted.
                    if (current.outboxRelays.isEmpty() && current.inboxRelays.isEmpty() && (outbox.isNotEmpty() || inbox.isNotEmpty())) {
                        DiagnosticLog.state("RelayMgmtVM", "fetchUserRelaysFromNetwork: populating outbox=${outbox.size} inbox=${inbox.size} " +
                            "(outbox was empty, inbox was empty) — NOT touching categories")
                        _uiState.update {
                            it.copy(outboxRelays = outbox, inboxRelays = inbox, isLoading = false)
                        }
                        saveToStorage()

                        // Eagerly fetch NIP-11 info for all newly added relays so the
                        // Relay Management screen shows info immediately
                        viewModelScope.launch(Dispatchers.IO) {
                            val nip11 = Nip11CacheManager.getInstance(storageManager.context)
                            (outbox + inbox).map { it.url }.distinct().forEach { url ->
                                try {
                                    nip11.getRelayInfo(url, forceRefresh = true)
                                } catch (e: Exception) {
                                    Log.w("RelayMgmtVM", "Eager NIP-11 fetch failed for $url: ${e.message}")
                                }
                            }
                        }
                    } else {
                        DiagnosticLog.state("RelayMgmtVM", "fetchUserRelaysFromNetwork: SKIPPED — " +
                            "outbox=${current.outboxRelays.size} inbox=${current.inboxRelays.size} already populated")
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to fetch relay list from network") }
                }
        }
    }
}
