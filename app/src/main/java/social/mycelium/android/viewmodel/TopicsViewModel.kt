package social.mycelium.android.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayState
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.TopicsRepository
import social.mycelium.android.repository.TopicNote
import social.mycelium.android.repository.HashtagStats
import social.mycelium.android.repository.ScopedModerationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TopicsUiState(
    val hashtagStats: List<HashtagStats> = emptyList(),
    val allTopics: List<TopicNote> = emptyList(),
    val selectedHashtag: String? = null,
    val topicsForSelectedHashtag: List<TopicNote> = emptyList(),
    val isLoading: Boolean = false,
    val isReceivingEvents: Boolean = false,
    val error: String? = null,
    val connectedRelays: List<String> = emptyList(),
    val sortOrder: HashtagSortOrder = HashtagSortOrder.MOST_TOPICS,
    val isViewingHashtagFeed: Boolean = false,
    val relayState: RelayState = RelayState.Disconnected,
    val relayCountSummary: String? = null,
    val newTopicsCount: Int = 0
)

enum class HashtagSortOrder {
    MOST_TOPICS,      // Sort by topic count
    MOST_ACTIVE,      // Sort by latest activity
    MOST_REPLIES,     // Sort by total reply count
    MOST_POPULAR,     // Sort by aggregate vote score (upvotes - downvotes)
    ALPHABETICAL      // Sort alphabetically
}

/**
 * ViewModel for managing topics and hashtag discovery
 * Handles Kind 11 topic fetching and hashtag statistics
 */
class TopicsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TopicsRepository.getInstance(application.applicationContext)

    private val _uiState = MutableStateFlow(TopicsUiState())
    val uiState: StateFlow<TopicsUiState> = _uiState.asStateFlow()

    /** Cached follow list so toggling All/Following doesn't require refetch. */
    private var followListCache = emptySet<String>()

    companion object {
        private const val TAG = "TopicsViewModel"
    }

    init {
        observeRepositoryFlows()
        observeRelayState()
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey -> repository.updateAuthorInTopics(pubkey) }
            .launchIn(viewModelScope)
    }

    private fun observeRelayState() {
        val stateMachine = RelayConnectionStateMachine.getInstance()
        viewModelScope.launch {
            combine(
                stateMachine.state,
                stateMachine.perRelayState
            ) { state, perRelay ->
                val total = perRelay.size
                val connected = perRelay.values.count { it == social.mycelium.android.relay.RelayEndpointStatus.Connected }
                val summary = if (total > 0) "$connected/$total relays" else null
                state to summary
            }.collect { (state, summary) ->
                _uiState.update { it.copy(relayState = state, relayCountSummary = summary) }
            }
        }
        viewModelScope.launch {
            repository.newTopicsCount.collect { count ->
                _uiState.update { it.copy(newTopicsCount = count) }
            }
        }
    }

    /**
     * Set cache relay URLs for kind-0 profile fetches. Call from UI when account is available.
     */
    fun setCacheRelayUrls(urls: List<String>) {
        repository.setCacheRelayUrls(urls)
    }

    /**
     * Observe repository state flows
     */
    private fun observeRepositoryFlows() {
        viewModelScope.launch {
            repository.hashtagStats.collect { stats ->
                val sortedStats = sortHashtagStats(stats, _uiState.value.sortOrder)
                _uiState.update { it.copy(hashtagStats = sortedStats) }
            }
        }

        viewModelScope.launch {
            repository.topics.collect { _ ->
                val allTopics = repository.getAllTopics()
                _uiState.update { it.copy(allTopics = allTopics) }

                val selectedHashtag = _uiState.value.selectedHashtag
                if (selectedHashtag != null) {
                    val topicsForHashtag = repository.getTopicsForHashtag(selectedHashtag)
                    _uiState.update { it.copy(topicsForSelectedHashtag = topicsForHashtag) }
                }
            }
        }

        viewModelScope.launch {
            repository.isLoading.collect { loading ->
                _uiState.update { it.copy(isLoading = loading) }
            }
        }

        viewModelScope.launch {
            repository.isReceivingEvents.collect { receiving ->
                _uiState.update { it.copy(isReceivingEvents = receiving) }
            }
        }

        viewModelScope.launch {
            repository.error.collect { error ->
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                }
            }
        }
    }

    /**
     * Set subscription to all user relays and display filter to sidebar selection.
     * allUserRelayUrls = all relays we stay connected to; displayUrls = what to show (sidebar selection).
     */
    fun loadTopicsFromRelays(allUserRelayUrls: List<String>, displayUrls: List<String>) {
        Log.d(TAG, "🔄 Loading topics: subscription=${allUserRelayUrls.size} relays, display=${displayUrls.size} relay(s)")

        val displayRelays = if (displayUrls.isEmpty()) allUserRelayUrls else displayUrls
        val currentRelays = _uiState.value.connectedRelays.sorted()
        val newRelays = displayRelays.sorted()
        val hasData = _uiState.value.hashtagStats.isNotEmpty()
        val stuckLoading = _uiState.value.isLoading && _uiState.value.hashtagStats.isEmpty()

        if (currentRelays == newRelays && currentRelays.isNotEmpty() && (hasData || !stuckLoading) && allUserRelayUrls.isNotEmpty()) {
            Log.d(TAG, "Topics relays unchanged and have data or not loading, skipping")
            _uiState.update { it.copy(isLoading = false) }  // Always clear loading on skip
            return
        }

        _uiState.update { it.copy(connectedRelays = displayRelays, isLoading = true, isReceivingEvents = false, error = null) }

        viewModelScope.launch {
            try {
                if (allUserRelayUrls.isNotEmpty()) repository.setSubscriptionRelays(allUserRelayUrls)
                repository.connectToRelays(displayRelays)
                // Sync loading state: connectToRelays clears repo loading; ensure UI doesn't stay on "Connecting to relays..."
                _uiState.update { it.copy(isLoading = repository.isLoadingTopics(), isReceivingEvents = repository.isReceivingEvents.value) }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading topics: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to load topics: ${e.message}", isLoading = false, isReceivingEvents = false) }
            }
        }
    }

    /**
     * Update display filter only (sidebar selection). Does NOT change subscription.
     */
    fun setDisplayFilterOnly(displayUrls: List<String>) {
        repository.connectToRelays(displayUrls)
        _uiState.update { it.copy(connectedRelays = displayUrls) }
    }

    /**
     * Set follow filter for topics (All vs Following). Call when user toggles; uses cached follow list.
     */
    fun setFollowFilterForTopics(enabled: Boolean) {
        repository.setFollowFilter(followListCache, enabled)
    }

    /**
     * Load follow list for Topics and apply [isFollowing] (default All = false). Call when account is available.
     */
    fun loadFollowListForTopics(pubkey: String, relayUrls: List<String>, isFollowing: Boolean) {
        viewModelScope.launch {
            val list = ContactListRepository.fetchFollowList(pubkey, relayUrls, forceRefresh = false)
            followListCache = list
            repository.setFollowFilter(list, isFollowing)
            // Wire WoT follow set into moderation repository for trust-based filtering
            ScopedModerationRepository.getInstance().setWotFollowSet(list)
        }
    }

    /**
     * Refresh topics: merge pending new topics into the list, then optionally refetch recent.
     */
    fun refreshTopics() {
        repository.applyPendingTopics()
        val currentRelays = _uiState.value.connectedRelays
        if (currentRelays.isEmpty()) {
            Log.w(TAG, "No relays configured for refresh")
            return
        }

        viewModelScope.launch {
            try {
                repository.refresh(currentRelays)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing topics: ${e.message}", e)
                _uiState.update { it.copy(error = "Failed to refresh topics: ${e.message}") }
            }
        }
    }

    /**
     * Select a hashtag to view its topics
     */
    fun selectHashtag(hashtag: String?) {
        val topicsForHashtag = if (hashtag != null) {
            repository.getTopicsForHashtag(hashtag)
        } else {
            emptyList()
        }

        _uiState.update { it.copy(selectedHashtag = hashtag, topicsForSelectedHashtag = topicsForHashtag, isViewingHashtagFeed = hashtag != null) }
    }

    /**
     * Clear selected hashtag and return to hashtag list
     */
    fun clearSelectedHashtag() {
        _uiState.update { it.copy(selectedHashtag = null, topicsForSelectedHashtag = emptyList(), isViewingHashtagFeed = false) }
    }

    /**
     * Change sort order for hashtags
     */
    fun setSortOrder(sortOrder: HashtagSortOrder) {
        if (_uiState.value.sortOrder != sortOrder) {
            val sortedStats = sortHashtagStats(_uiState.value.hashtagStats, sortOrder)
            _uiState.update { it.copy(sortOrder = sortOrder, hashtagStats = sortedStats) }
        }
    }

    /**
     * Sort hashtag statistics based on sort order
     */
    private fun sortHashtagStats(
        stats: List<HashtagStats>,
        sortOrder: HashtagSortOrder
    ): List<HashtagStats> {
        return when (sortOrder) {
            HashtagSortOrder.MOST_TOPICS -> stats.sortedByDescending { it.topicCount }
            HashtagSortOrder.MOST_ACTIVE -> stats.sortedByDescending { it.latestActivity }
            HashtagSortOrder.MOST_REPLIES -> stats.sortedByDescending { it.totalReplies }
            HashtagSortOrder.MOST_POPULAR -> stats.sortedByDescending { social.mycelium.android.repository.VoteRepository.getTotalScore(it.topicIds) }
            HashtagSortOrder.ALPHABETICAL -> stats.sortedBy { it.hashtag.lowercase() }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear all topics and reset state
     */
    fun clearTopics() {
        repository.clearAllTopics()
        _uiState.value = TopicsUiState()
    }

    override fun onCleared() {
        super.onCleared()
        // Do not call repository.disconnectAll() - shared connection and topics outlive this screen
    }
}
