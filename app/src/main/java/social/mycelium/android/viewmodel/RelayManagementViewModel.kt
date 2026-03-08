package social.mycelium.android.viewmodel

import android.content.Context
import android.util.Log
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
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RelayManagementUiState(
    val relays: List<UserRelay> = emptyList(),
    val connectionStatus: Map<String, RelayConnectionStatus> = emptyMap(),
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

    // Expose categories separately for easy access from other screens
    val relayCategories: StateFlow<List<RelayCategory>> = _uiState
        .map { it.relayCategories }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultRelayCategories.getAllDefaultCategories())

    // Expose profiles separately
    val relayProfiles: StateFlow<List<RelayProfile>> = _uiState
        .map { it.relayProfiles }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(DefaultRelayProfiles.getDefaultProfile()))

    init {
        // Collect relay updates from repository
        viewModelScope.launch {
            relayRepository.relays.collect { relays ->
                _uiState.update { it.copy(relays = relays) }
            }
        }

        // Collect connection status updates from repository
        viewModelScope.launch {
            relayRepository.connectionStatus.collect { connectionStatus ->
                _uiState.update { it.copy(connectionStatus = connectionStatus) }
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
            val categories = storageManager.loadCategories(pubkey)
            val profiles = storageManager.loadProfiles(pubkey)

            // Load personal relays
            val outbox = storageManager.loadOutboxRelays(pubkey)
            val inbox = storageManager.loadInboxRelays(pubkey)
            val cache = storageManager.loadIndexerRelays(pubkey)

            // Load system relays
            val announcements = storageManager.loadAnnouncementRelays(pubkey)
            val drafts = storageManager.loadDraftsRelays(pubkey)
            val blossom = storageManager.loadBlossomServers(pubkey)
            val nip96 = storageManager.loadNip96Servers(pubkey)

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
            ) return@launch

            _uiState.update { it.copy(relayCategories = categories, relayProfiles = profiles, outboxRelays = outbox, inboxRelays = inbox, indexerRelays = cache, announcementRelays = announcements, draftsRelays = drafts, blossomServers = blossom, nip96Servers = nip96) }

            // Fetch NIP-11 info in background for all relays (personal + category)
            val allCategoryUrls = categories.flatMap { it.relays }.map { it.url }
            val allPersonalUrls = (outbox + inbox + cache).map { it.url }
            val allSystemUrls = (announcements + drafts).map { it.url }
            val allUrls = (allCategoryUrls + allPersonalUrls + allSystemUrls).distinct()
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
        Log.d("RelayMgmtVM", "Refreshing active subscription with ${relayUrls.size} relays")
        // Invalidate the idempotency guard so the feed re-subscribes with the new relay set
        social.mycelium.android.repository.NotesRepository.getInstance().invalidateSubscriptionGuard()
        // Preserve the current kind-1 filter (e.g. Following authors) so relay changes
        // don't accidentally replace a Following subscription with a global one.
        val currentFilter = RelayConnectionStateMachine.getInstance().getCurrentKind1Filter()
        RelayConnectionStateMachine.getInstance().requestFeedChange(relayUrls, currentFilter)
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
        _uiState.update { it.copy(relayCategories = it.relayCategories.filter { cat -> cat.id != categoryId }) }
        saveToStorage()
    }

    fun addRelayToCategory(categoryId: String, relay: UserRelay) {
        _uiState.update { state ->
            state.copy(relayCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(relays = category.relays + relay) else category
            })
        }
        saveToStorage()
        refreshActiveSubscription()

        // Fetch NIP-11 info for the newly added relay
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val freshInfo = nip11Cache.getRelayInfo(relay.url)
                if (freshInfo != null) {
                    _uiState.update { state ->
                        state.copy(relayCategories = state.relayCategories.map { cat ->
                            cat.copy(relays = cat.relays.updateRelayInfo(relay.url, freshInfo))
                        })
                    }
                    saveToStorage()
                }
            } catch (_: Exception) { /* ignore */ }
        }
    }

    fun removeRelayFromCategory(categoryId: String, relayUrl: String) {
        _uiState.update { state ->
            state.copy(relayCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(relays = category.relays.filter { it.url != relayUrl }) else category
            })
        }
        saveToStorage()
        // Re-apply subscription so the removed relay gets disconnected
        refreshActiveSubscription()
    }

    // Personal relay management methods
    fun addOutboxRelay(relay: UserRelay) {
        _uiState.update { it.copy(outboxRelays = it.outboxRelays + relay) }
        saveToStorage()
        refreshActiveSubscription()
    }

    fun removeOutboxRelay(url: String) {
        _uiState.update { it.copy(outboxRelays = it.outboxRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addInboxRelay(relay: UserRelay) {
        _uiState.update { it.copy(inboxRelays = it.inboxRelays + relay) }
        saveToStorage()
        refreshActiveSubscription()
    }

    fun removeInboxRelay(url: String) {
        _uiState.update { it.copy(inboxRelays = it.inboxRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addIndexerRelay(relay: UserRelay) {
        _uiState.update { it.copy(indexerRelays = it.indexerRelays + relay) }
        saveToStorage()
        refreshActiveSubscription()
    }

    fun removeIndexerRelay(url: String) {
        _uiState.update { it.copy(indexerRelays = it.indexerRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    // System relay management methods
    fun addAnnouncementRelay(relay: UserRelay) {
        _uiState.update { it.copy(announcementRelays = it.announcementRelays + relay) }
        saveToStorage()
    }

    fun removeAnnouncementRelay(url: String) {
        _uiState.update { it.copy(announcementRelays = it.announcementRelays.filter { r -> r.url != url }) }
        saveToStorage()
    }

    fun addDraftsRelay(relay: UserRelay) {
        _uiState.update { it.copy(draftsRelays = it.draftsRelays + relay) }
        saveToStorage()
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
            state.copy(relayCategories = state.relayCategories.map { category ->
                if (category.id == categoryId) category.copy(isSubscribed = !category.isSubscribed) else category
            })
        }
        saveToStorage()
        refreshActiveSubscription()
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
        saveToStorage()
        refreshActiveSubscription()
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
        saveToStorage()
        refreshActiveSubscription()
    }

    fun fetchUserRelaysFromNetwork(pubkey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

                relayRepository.fetchUserRelayList(pubkey)
                .onSuccess { relays ->
                    // Normalize URLs (no trailing slash) then categorize by NIP-65
                    val normalized = relays.map { it.copy(url = RelayStorageManager.normalizeRelayUrl(it.url)) }
                    val outbox = normalized.filter { it.write }
                    val inbox = normalized.filter { it.read }
                    val current = _uiState.value
                    // Only populate when outbox and inbox are empty so we don't overwrite user edits
                    if (current.outboxRelays.isEmpty() && current.inboxRelays.isEmpty() && (outbox.isNotEmpty() || inbox.isNotEmpty())) {
                        // Add outbox relays to the default (empty) category so user sees notes from outbox on first sign-in
                        val defaultId = DefaultRelayCategories.getDefaultCategory().id
                        val updatedCategories = current.relayCategories.map { cat ->
                            if (cat.id == defaultId && cat.relays.isEmpty() && outbox.isNotEmpty()) {
                                cat.copy(relays = outbox)
                            } else {
                                cat
                            }
                        }
                        _uiState.update {
                            it.copy(relayCategories = updatedCategories, outboxRelays = outbox, inboxRelays = inbox, isLoading = false)
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
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
                .onFailure { exception ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = exception.message ?: "Failed to fetch relay list from network") }
                }
        }
    }
}
