package social.mycelium.android.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel to manage separate feed states for Home and Topics feeds
 * Preserves sidebar state, scroll position, and selected relays per feed
 */
class FeedStateViewModel : ViewModel() {

    /** True once the initial scroll-to-top has fired for the home feed.
     *  Survives LaunchedEffect lifecycle restarts (ViewModel > composable lifecycle).
     *  Prevents scroll-to-0 from re-firing when returning from image_viewer/video_viewer. */
    @Volatile var hasInitializedHomeScroll = false

    // Home feed state
    private val _homeFeedState = MutableStateFlow(FeedState())
    val homeFeedState: StateFlow<FeedState> = _homeFeedState.asStateFlow()

    // Topics feed state
    private val _topicsFeedState = MutableStateFlow(FeedState())
    val topicsFeedState: StateFlow<FeedState> = _topicsFeedState.asStateFlow()

    /**
     * Update home feed state
     */
    fun updateHomeFeedState(update: FeedState.() -> FeedState) {
        _homeFeedState.value = update(_homeFeedState.value)
    }

    /**
     * Update topics feed state
     */
    fun updateTopicsFeedState(update: FeedState.() -> FeedState) {
        _topicsFeedState.value = update(_topicsFeedState.value)
    }

    /**
     * Set global feed for home (all relays). Does not change the Following feed filter.
     */
    fun setHomeGlobal() {
        _homeFeedState.update { it.copy(isGlobal = true, selectedCategoryId = null, selectedCategoryName = null, selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set or clear the Following feed filter (independent of relay selection).
     * When true, only notes from followed users are shown; when false, all notes from selected relay(s) are shown.
     */
    fun setHomeFollowingFilter(enabled: Boolean) {
        _homeFeedState.update { it.copy(isFollowing = enabled) }
    }

    /**
     * Set home feed sort order (Latest = by time, Popular = by engagement).
     */
    fun setHomeSortOrder(sortOrder: HomeSortOrder) {
        _homeFeedState.update { it.copy(homeSortOrder = sortOrder) }
    }

    /**
     * Set selected relay category for home feed (does not change isFollowing).
     */
    fun setHomeSelectedCategory(categoryId: String?, categoryName: String?) {
        _homeFeedState.update { it.copy(isGlobal = false, selectedCategoryId = categoryId, selectedCategoryName = categoryName, selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set selected relay for home feed
     */
    fun setHomeSelectedRelay(relayUrl: String?, relayName: String?) {
        _homeFeedState.update { it.copy(isGlobal = false, selectedCategoryId = null, selectedCategoryName = null, selectedRelayUrl = relayUrl, selectedRelayName = relayName) }
    }

    /**
     * Set home feed to filter by all outbox relays.
     */
    fun setHomeSelectedOutbox() {
        _homeFeedState.update { it.copy(isGlobal = false, selectedCategoryId = "outbox", selectedCategoryName = "Outbox", selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set or clear the Following filter for topics feed (same idea as home).
     */
    fun setTopicsFollowingFilter(enabled: Boolean) {
        _topicsFeedState.update { it.copy(topicsIsFollowing = enabled) }
    }

    /**
     * Set topics feed sort order (Latest / Popular).
     */
    fun setTopicsSortOrder(sortOrder: TopicsSortOrder) {
        _topicsFeedState.update { it.copy(topicsSortOrder = sortOrder) }
    }

    /**
     * Set global feed for topics (all relays)
     */
    fun setTopicsGlobal() {
        _topicsFeedState.update { it.copy(isGlobal = true, selectedCategoryId = null, selectedCategoryName = null, selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set selected relay category for topics feed
     */
    fun setTopicsSelectedCategory(categoryId: String?, categoryName: String?) {
        _topicsFeedState.update { it.copy(isGlobal = false, selectedCategoryId = categoryId, selectedCategoryName = categoryName, selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set selected relay for topics feed
     */
    fun setTopicsSelectedRelay(relayUrl: String?, relayName: String?) {
        _topicsFeedState.update { it.copy(isGlobal = false, selectedCategoryId = null, selectedCategoryName = null, selectedRelayUrl = relayUrl, selectedRelayName = relayName) }
    }

    /**
     * Set topics feed to filter by all outbox relays.
     */
    fun setTopicsSelectedOutbox() {
        _topicsFeedState.update { it.copy(isGlobal = false, selectedCategoryId = "outbox", selectedCategoryName = "Outbox", selectedRelayUrl = null, selectedRelayName = null) }
    }

    /**
     * Set selected topic (hashtag) for topics feed so it persists across navigation.
     */
    fun setTopicsSelectedHashtag(hashtag: String?) {
        _topicsFeedState.update { it.copy(selectedHashtag = hashtag, isViewingHashtagFeed = hashtag != null) }
    }

    /**
     * Clear selected topic and return to topics explorer list.
     */
    fun clearTopicsSelectedHashtag() {
        _topicsFeedState.update { it.copy(selectedHashtag = null, isViewingHashtagFeed = false) }
    }

    /**
     * Initialize expanded categories so all sections start open on first load.
     * Only applies when expandedCategories is still empty (user hasn't toggled anything yet).
     */
    fun initializeExpandedCategories(categoryIds: List<String>) {
        _homeFeedState.update { state ->
            if (state.expandedCategories.isEmpty()) {
                state.copy(expandedCategories = (categoryIds + "outbox").toSet())
            } else state
        }
    }

    /**
     * Toggle expanded state for a category in home feed
     */
    fun toggleHomeExpandedCategory(categoryId: String) {
        _homeFeedState.update { it.copy(expandedCategories = if (categoryId in it.expandedCategories) it.expandedCategories - categoryId else it.expandedCategories + categoryId) }
    }

    /**
     * Toggle expanded state for a category in topics feed
     */
    fun toggleTopicsExpandedCategory(categoryId: String) {
        _topicsFeedState.update { it.copy(expandedCategories = if (categoryId in it.expandedCategories) it.expandedCategories - categoryId else it.expandedCategories + categoryId) }
    }

    /**
     * Save scroll position for home feed
     */
    fun saveHomeScrollPosition(firstVisibleItem: Int, scrollOffset: Int) {
        _homeFeedState.update { it.copy(scrollPosition = ScrollPosition(firstVisibleItem, scrollOffset)) }
    }

    /**
     * Save scroll position for topics feed
     */
    fun saveTopicsScrollPosition(firstVisibleItem: Int, scrollOffset: Int) {
        _topicsFeedState.update { it.copy(scrollPosition = ScrollPosition(firstVisibleItem, scrollOffset)) }
    }

    /**
     * Get display name for current relay selection only (Global vs category vs relay). Following filter is separate.
     */
    /**
     * Set engagement filter for home feed (null = all, "replies" / "likes" / "zaps").
     */
    fun setHomeEngagementFilter(filter: String?) {
        _homeFeedState.update { it.copy(engagementFilter = filter) }
    }

    fun getHomeDisplayName(): String {
        val state = _homeFeedState.value
        return when {
            state.isGlobal -> "Global"
            state.selectedCategoryName != null -> state.selectedCategoryName!!
            state.selectedRelayName != null -> state.selectedRelayName!!
            else -> "Global"
        }
    }

    /**
     * Get display name for current topics feed selection
     */
    fun getTopicsDisplayName(): String {
        val state = _topicsFeedState.value
        return when {
            state.isGlobal -> "Global"
            state.selectedCategoryName != null -> state.selectedCategoryName!!
            state.selectedRelayName != null -> state.selectedRelayName!!
            else -> "Global"
        }
    }
}

/** Home feed sort order: Latest (by time) or Popular (by engagement). */
enum class HomeSortOrder { Latest, Popular }

/** Topics feed sort order: Latest or Popular. */
enum class TopicsSortOrder { Latest, Popular }

/**
 * State for a single feed (Home or Topics)
 */
data class FeedState(
    // Global feed = all relays from all categories (default on launch)
    val isGlobal: Boolean = true,

    // When true, home feed shows only notes from followed users (kind-3); when false, shows all (Global).
    val isFollowing: Boolean = true,

    // Home feed sort: Latest (default) or Popular
    val homeSortOrder: HomeSortOrder = HomeSortOrder.Latest,

    // Topics: When true, only topics from followed users; when false, all. Default All (inverted from Home).
    val topicsIsFollowing: Boolean = false,

    // Topics feed sort: Latest (default) or Popular
    val topicsSortOrder: TopicsSortOrder = TopicsSortOrder.Latest,

    // Selected relay category (when not global)
    val selectedCategoryId: String? = null,
    val selectedCategoryName: String? = null,

    // Selected individual relay (when not global)
    val selectedRelayUrl: String? = null,
    val selectedRelayName: String? = null,

    // Expanded categories in sidebar (UI only; do not trigger feed reload)
    val expandedCategories: Set<String> = emptySet(),

    // Scroll position
    val scrollPosition: ScrollPosition = ScrollPosition(),

    // Sidebar drawer state
    val isSidebarOpen: Boolean = false,

    // Engagement filter: null = all, "replies" / "likes" / "zaps" (persists across navigations)
    val engagementFilter: String? = null,

    // Topics: selected hashtag and whether viewing that hashtag's feed (persists across tab switch)
    val selectedHashtag: String? = null,
    val isViewingHashtagFeed: Boolean = false
)

/**
 * Scroll position for feed list
 */
data class ScrollPosition(
    val firstVisibleItem: Int = 0,
    val scrollOffset: Int = 0
)
