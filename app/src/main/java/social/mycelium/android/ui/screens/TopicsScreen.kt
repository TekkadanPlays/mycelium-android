package social.mycelium.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Tag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.consumeWindowInsets
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import social.mycelium.android.data.Note
import social.mycelium.android.repository.TopicNote
import social.mycelium.android.ui.components.AdaptiveHeader
import social.mycelium.android.ui.components.BottomNavigationBar
import social.mycelium.android.ui.components.SmartBottomNavigationBar
import social.mycelium.android.ui.components.ScrollAwareBottomNavigationBar
import social.mycelium.android.ui.components.BottomNavDestinations
import social.mycelium.android.ui.components.ModernSearchBar
import social.mycelium.android.ui.components.GlobalSidebar
import social.mycelium.android.ui.components.NoteCard
import social.mycelium.android.ui.components.ActionRowSchema
import social.mycelium.android.ui.components.LiveActivityCard
import social.mycelium.android.ui.components.LiveActivityRow
import social.mycelium.android.ui.components.LoadingAnimation
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.ui.components.NoteCard
import social.mycelium.android.viewmodel.DashboardViewModel
import social.mycelium.android.viewmodel.AuthViewModel
import social.mycelium.android.viewmodel.RelayManagementViewModel
import social.mycelium.android.viewmodel.TopicsViewModel
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.relay.RelayState
import social.mycelium.android.repository.RelayRepository
import social.mycelium.android.repository.RelayStorageManager
import androidx.compose.ui.platform.LocalContext
import social.mycelium.android.ui.performance.animatedYOffset
import social.mycelium.android.repository.HashtagStats
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.foundation.shape.RoundedCornerShape
import social.mycelium.android.ui.icons.ChatBubbleOutline
import social.mycelium.android.repository.ScopedModerationRepository
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter (Thread view pattern)
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Consistent animation specs (Thread view pattern)
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TopicsScreen(
    isSearchMode: Boolean = false,
    onSearchModeChange: (Boolean) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onThreadClick: (Note, List<String>?) -> Unit = { _, _ -> },
    onScrollToTop: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    viewModel: DashboardViewModel = viewModel(),
    topicsViewModel: TopicsViewModel = viewModel(),
    feedStateViewModel: social.mycelium.android.viewmodel.FeedStateViewModel = viewModel(),
    appViewModel: social.mycelium.android.viewmodel.AppViewModel = viewModel(),
    accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel = viewModel(),
    relayRepository: RelayRepository? = null,
    onLoginClick: (() -> Unit)? = null,
    onTopAppBarStateChange: (TopAppBarState) -> Unit = {},
    initialTopAppBarState: TopAppBarState? = null,
    onQrClick: () -> Unit = {},
    onSidebarRelayHealthClick: () -> Unit = {},
    onSidebarRelayDiscoveryClick: () -> Unit = {},
    onNavigateToCreateTopic: (String?) -> Unit = {},
    onRelayClick: (String) -> Unit = {},
    /** Navigate to the full-page zap settings screen. */
    onNavigateToZapSettings: () -> Unit = {},
    onDrawerStateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val topicsUiState by topicsViewModel.uiState.collectAsState()
    val topicsFeedState by feedStateViewModel.topicsFeedState.collectAsState()
    val authState by accountStateViewModel.authState.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val onboardingComplete by accountStateViewModel.onboardingComplete.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Report drawer state to parent (for hiding bottom nav when drawer is open)
    LaunchedEffect(drawerState.currentValue) {
        onDrawerStateChanged(drawerState.isOpen)
    }

    // NIP-22: Observe scoped moderation state for off-topic badges
    val moderationRepo = remember { ScopedModerationRepository.getInstance() }
    val offTopicCounts by moderationRepo.offTopicCounts.collectAsState()

    // NIP-22: Observe anchor subscriptions (kind:30073 favorites)
    val subscribedAnchors by accountStateViewModel.getSubscribedAnchors().collectAsState()
    var showFavoritesOnly by remember { mutableStateOf(false) }

    // NIP-53: Live activities for chips row
    val liveActivities by viewModel.liveActivities.collectAsState()
    val hasFollowedLive by LiveActivityRepository.getInstance().hasFollowedLiveActivity.collectAsState()

    // Real per-relay connection counts from state machine for sidebar "Connected X/Y"
    val perRelayState by social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().perRelayState.collectAsState()
    val relayStateMachineState by social.mycelium.android.relay.RelayConnectionStateMachine.getInstance().state.collectAsState()

    // Relay management
    val storageManager = remember { RelayStorageManager(context) }
    val relayViewModel: RelayManagementViewModel? = relayRepository?.let {
        viewModel { RelayManagementViewModel(it, storageManager) }
    }
    val relayUiState = if (relayViewModel != null) {
        relayViewModel.uiState.collectAsState().value
    } else {
        social.mycelium.android.viewmodel.RelayManagementUiState()
    }

    // Sidebar relay counts: only count user-configured relays (not indexer/outbox temp connections)
    val userRelayUrls = remember(currentAccount, relayUiState) {
        val categoryUrls = relayUiState.relayCategories
            .flatMap { it.relays }.map { it.url.trim().removeSuffix("/").lowercase() }
        val outboxUrls = relayUiState.outboxRelays
            .map { it.url.trim().removeSuffix("/").lowercase() }
        val inboxUrls = relayUiState.inboxRelays
            .map { it.url.trim().removeSuffix("/").lowercase() }
        (categoryUrls + outboxUrls + inboxUrls).toSet()
    }
    val connectedRelayCount = remember(perRelayState, userRelayUrls) {
        perRelayState.count { (url, status) ->
            status == social.mycelium.android.relay.RelayEndpointStatus.Connected &&
                url.trim().removeSuffix("/").lowercase() in userRelayUrls
        }
    }
    val subscribedRelayCount = userRelayUrls.size

    // Indexer relay counts
    val indexerRelayUrls = remember(relayUiState) {
        relayUiState.indexerRelays.map { it.url.trim().removeSuffix("/").lowercase() }.toSet()
    }
    val connectedIndexerCount = remember(perRelayState, indexerRelayUrls) {
        perRelayState.count { (url, status) ->
            status == social.mycelium.android.relay.RelayEndpointStatus.Connected &&
                url.trim().removeSuffix("/").lowercase() in indexerRelayUrls
        }
    }

    // Load user relays when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.loadUserRelays(pubkey)
        }
    }

    // Get active profile for sidebar + feed logic
    val activeProfile = relayUiState.relayProfiles.firstOrNull { it.isActive }
    val relayCategories = activeProfile?.categories ?: emptyList()

    // Synchronous relay URLs from local storage — available instantly before async ViewModel loads.
    // Prevents "No Relays" / loading spinner flash while ViewModel is still loading.
    val savedRelayUrls = remember(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val categories = storageManager.loadCategories(pubkey)
            val subscribed = categories.filter { it.isSubscribed }
                .flatMap { it.relays }.map { it.url }.distinct()
            subscribed.ifEmpty {
                categories.flatMap { it.relays }.map { it.url }.distinct()
            }
        } ?: emptyList()
    }
    val hasSavedRelayConfig = savedRelayUrls.isNotEmpty()

    // Auto-load topics: use ViewModel relay categories when available, fall back to sync storage URLs.
    // This ensures topics load immediately even before the async ViewModel finishes.
    // No one-shot guard — relay-change-driven with debounce (same pattern as DashboardScreen).
    LaunchedEffect(relayCategories, savedRelayUrls, currentAccount, topicsFeedState.isGlobal, topicsFeedState.selectedCategoryId, topicsFeedState.selectedRelayUrl, onboardingComplete, relayUiState.outboxRelays, relayUiState.inboxRelays) {
        if (!onboardingComplete) return@LaunchedEffect

        // Debounce: keys settle in rapid succession; wait briefly so we only fire once.
        delay(150)

        // Prefer ViewModel categories (has sidebar selection info), fall back to sync storage
        val allUserRelayUrls = run {
            val categoryUrls = relayCategories.flatMap { it.relays }.map { it.url }
            val outboxUrls = relayUiState.outboxRelays.map { it.url }
            val inboxUrls = relayUiState.inboxRelays.map { it.url }
            val combined = (categoryUrls + outboxUrls + inboxUrls).distinct()
            combined.ifEmpty { savedRelayUrls }
        }
        if (allUserRelayUrls.isEmpty()) return@LaunchedEffect

        val displayUrls = when {
            topicsFeedState.isGlobal -> allUserRelayUrls
            topicsFeedState.selectedCategoryId != null && relayCategories.isNotEmpty() -> relayCategories
                .firstOrNull { it.id == topicsFeedState.selectedCategoryId }?.relays?.map { it.url } ?: allUserRelayUrls
            topicsFeedState.selectedRelayUrl != null -> listOf(topicsFeedState.selectedRelayUrl!!)
            else -> allUserRelayUrls
        }
        topicsViewModel.loadTopicsFromRelays(allUserRelayUrls, displayUrls)
    }

    // Fetch user's NIP-65 relay list when account changes
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            relayViewModel?.fetchUserRelaysFromNetwork(pubkey)
        }
    }

    // Set cache relay URLs for kind-0 profile fetches when account is available
    LaunchedEffect(currentAccount) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val cacheUrls = storageManager.loadIndexerRelays(pubkey).map { it.url }
            if (cacheUrls.isNotEmpty()) topicsViewModel.setCacheRelayUrls(cacheUrls)
        }
    }

    // Load follow list once when account/relays available; default All (topicsIsFollowing = false)
    LaunchedEffect(currentAccount, relayCategories) {
        currentAccount?.toHexKey()?.let { pubkey ->
            val urls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
            if (urls.isNotEmpty()) {
                topicsViewModel.loadFollowListForTopics(pubkey, urls, topicsFeedState.topicsIsFollowing)
            }
        }
    }
    // Apply All vs Following when user toggles (uses cached follow list)
    LaunchedEffect(topicsFeedState.topicsIsFollowing) {
        topicsViewModel.setFollowFilterForTopics(topicsFeedState.topicsIsFollowing)
    }

    // Search state - using simple String instead of TextFieldValue
    var searchQuery by remember { mutableStateOf("") }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Feed view state
    var currentFeedView by remember { mutableStateOf("Topics") }

    // Selected hashtag state from ViewModel (persists across navigation)
    val selectedHashtag = topicsUiState.selectedHashtag
    val isViewingHashtagFeed = topicsUiState.isViewingHashtagFeed

    // Restore selected topic when returning to Topics tab (persisted in FeedStateViewModel)
    LaunchedEffect(topicsFeedState.selectedHashtag) {
        topicsFeedState.selectedHashtag?.let { hashtag ->
            topicsViewModel.selectHashtag(hashtag)
        }
    }

    // Handle back navigation for hashtag feed
    BackHandler(enabled = isViewingHashtagFeed) {
        feedStateViewModel.clearTopicsSelectedHashtag()
        topicsViewModel.clearSelectedHashtag()
    }

    // Account switcher state
    var showAccountSwitcher by remember { mutableStateOf(false) }

    // Zap menu state - shared across all note cards
    var shouldCloseZapMenus by remember { mutableStateOf(false) }

    var showWalletConnectDialog by remember { mutableStateOf(false) }

    // Relay orb tap navigates to relay log page via onRelayClick callback

    // Close zap menus when feed scroll starts (not during scroll)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }

    // Use Material3's built-in scroll behavior for top app bar
    val topAppBarState = initialTopAppBarState ?: rememberTopAppBarState()
    val scrollBehavior = if (isSearchMode) {
        TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)
    } else {
        TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    }

    // Notify parent of TopAppBarState changes for thread view inheritance
    LaunchedEffect(topAppBarState) {
        onTopAppBarStateChange(topAppBarState)
    }

    // ✅ PERFORMANCE: Optimized search filtering (Thread view pattern)
    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                uiState.notes.filter { note ->
                    note.content.contains(searchQuery, ignoreCase = true) ||
                    note.author.displayName.contains(searchQuery, ignoreCase = true) ||
                    note.author.username.contains(searchQuery, ignoreCase = true) ||
                    note.hashtags.any { it.contains(searchQuery, ignoreCase = true) }
                }
            }
        }
    }

    // ✅ Performance: Cache divider color (don't recreate on every item)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    val uriHandler = LocalUriHandler.current

    // Relay health: trouble count for sidebar badge
    val flaggedRelays by social.mycelium.android.relay.RelayHealthTracker.flaggedRelays.collectAsState()
    val blockedRelays by social.mycelium.android.relay.RelayHealthTracker.blockedRelays.collectAsState()
    val troubleRelayCount = remember(flaggedRelays, blockedRelays) {
        (flaggedRelays + blockedRelays).distinct().size
    }

    GlobalSidebar(
        drawerState = drawerState,
        activeProfile = activeProfile,
        outboxRelays = relayUiState.outboxRelays,
        inboxRelays = relayUiState.inboxRelays,
        feedState = topicsFeedState,
        selectedDisplayName = feedStateViewModel.getTopicsDisplayName(),
        relayState = relayStateMachineState,
        connectedRelayCount = connectedRelayCount,
        subscribedRelayCount = subscribedRelayCount,
        onIndexerClick = { onNavigateTo("relays?tab=indexer") },
        onRelayHealthClick = onSidebarRelayHealthClick,
        onRelayDiscoveryClick = onSidebarRelayDiscoveryClick,
        troubleRelayCount = troubleRelayCount,
        onItemClick = { itemId ->
            when {
                itemId == "global" -> {
                    feedStateViewModel.setTopicsGlobal()
                    feedStateViewModel.setHomeGlobal()
                    val allUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
                    if (allUrls.isNotEmpty()) topicsViewModel.setDisplayFilterOnly(allUrls)
                }
                itemId.startsWith("relay_category:") -> {
                    val categoryId = itemId.removePrefix("relay_category:")
                    val category = activeProfile?.categories?.firstOrNull { it.id == categoryId }
                    val relayUrls = category?.relays?.map { it.url } ?: emptyList()
                    if (relayUrls.isNotEmpty()) {
                        feedStateViewModel.setTopicsSelectedCategory(categoryId, category?.name)
                        feedStateViewModel.setHomeSelectedCategory(categoryId, category?.name)
                        topicsViewModel.setDisplayFilterOnly(relayUrls)
                    }
                }
                itemId.startsWith("relay:") -> {
                    val relayUrl = itemId.removePrefix("relay:")
                    val relay = activeProfile?.categories?.flatMap { it.relays }?.firstOrNull { it.url == relayUrl }
                    feedStateViewModel.setTopicsSelectedRelay(relayUrl, relay?.displayName)
                    feedStateViewModel.setHomeSelectedRelay(relayUrl, relay?.displayName)
                    topicsViewModel.setDisplayFilterOnly(listOf(relayUrl))
                }
                itemId == "user_profile" -> {
                    onNavigateTo("user_profile")
                }
                itemId == "relays" -> {
                    onNavigateTo("relays")
                }
                itemId == "login" -> {
                    onLoginClick?.invoke()
                }
                itemId == "logout" -> {
                    onNavigateTo("settings")
                }
                itemId == "settings" -> {
                    onNavigateTo("settings")
                }
                else -> { /* Other sidebar actions */ }
            }
        },
        onToggleCategory = { categoryId ->
            feedStateViewModel.toggleTopicsExpandedCategory(categoryId)
        },
        modifier = modifier
    ) {
        Scaffold(
            modifier = if (!isSearchMode) {
                Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
            } else {
                Modifier
            },
            topBar = {
                if (isSearchMode) {
                    // Search mode - show docked SearchBar
                    ModernSearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { /* Optional: Handle explicit search submission */ },
                        searchResults = searchResults,
                        onResultClick = { note ->
                            onThreadClick(note, null)
                            onSearchModeChange(false)
                            searchQuery = ""
                        },
                        active = isSearchMode,
                        onActiveChange = { active ->
                            if (!active) {
                                onSearchModeChange(false)
                                searchQuery = ""
                            }
                        },
                        onBackClick = {
                            searchQuery = ""
                            onSearchModeChange(false)
                        },
                        placeholder = { Text("Search notes, users, hashtags...") }
                    )
                } else {
                    // Normal mode - show scrollable header
                    AdaptiveHeader(
                        title = if (selectedHashtag != null) "#$selectedHashtag" else "topics",
                        isSearchMode = false,
                        showBackArrow = selectedHashtag != null,
                        searchQuery = androidx.compose.ui.text.input.TextFieldValue(""),
                        onSearchQueryChange = { },
                        onMenuClick = {
                            if (isViewingHashtagFeed) {
                                feedStateViewModel.clearTopicsSelectedHashtag()
                                topicsViewModel.clearSelectedHashtag()
                            } else {
                                // Hamburger menu when viewing all hashtags
                                scope.launch {
                                    if (drawerState.isClosed) {
                                        drawerState.open()
                                    } else {
                                        drawerState.close()
                                    }
                                }
                            }
                        },
                        onSearchClick = { onSearchModeChange(true) },
                        onFilterClick = { /* TODO: Handle filter/sort */ },
                        onMoreOptionClick = { option ->
                            when (option) {
                                "about" -> onNavigateTo("about")
                                "settings" -> onNavigateTo("settings")
                                else -> viewModel.onMoreOptionClick(option)
                            }
                        },
                        onBackClick = { },
                        onClearSearch = { },
                        onLoginClick = onLoginClick,
                        onProfileClick = {
                            // Navigate to user's own profile
                            onNavigateTo("user_profile")
                        },
                        onAccountsClick = {
                            // Show account switcher
                            showAccountSwitcher = true
                        },
                        onSettingsClick = {
                            // Navigate to settings
                            onNavigateTo("settings")
                        },
                        onRelaysClick = {
                            onNavigateTo("relays")
                        },
                        isGuest = authState.isGuest,
                        userDisplayName = authState.userProfile?.displayName ?: authState.userProfile?.name,
                        userAvatarUrl = authState.userProfile?.picture,
                        scrollBehavior = scrollBehavior,
                        currentFeedView = currentFeedView,
                        onFeedViewChange = { newFeedView -> currentFeedView = newFeedView },
                        isTopicsFollowingFilter = topicsFeedState.topicsIsFollowing,
                        onTopicsFollowingFilterChange = {
                            scope.launch { listState.scrollToItem(0) }
                            feedStateViewModel.setTopicsFollowingFilter(it)
                        },
                        topicsSortOrder = topicsFeedState.topicsSortOrder,
                        onTopicsSortOrderChange = {
                            scope.launch { listState.scrollToItem(0) }
                            feedStateViewModel.setTopicsSortOrder(it)
                        },
                        isTopicsFavoritesFilter = showFavoritesOnly,
                        onTopicsFavoritesFilterChange = {
                            scope.launch { listState.scrollToItem(0) }
                            showFavoritesOnly = it
                        },
                        onNavigateToHome = { onNavigateTo("dashboard") },
                        onNavigateToLive = { onNavigateTo("live_explorer") },
                        hasFollowedLiveActivity = hasFollowedLive
                    )
                }
            },
            floatingActionButton = {
                val fabVisible by remember(topAppBarState) {
                    derivedStateOf { topAppBarState.collapsedFraction < 0.5f }
                }
                AnimatedVisibility(
                    visible = !isSearchMode && fabVisible,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut()
                ) {
                    FloatingActionButton(
                        onClick = { onNavigateToCreateTopic(selectedHashtag) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(bottom = 80.dp)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Create topic")
                    }
                }
            }
        ) { paddingValues ->
            // Main content with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    topicsViewModel.refreshTopics()
                    scope.launch {
                        delay(800)
                        isRefreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Topics/Hashtag Discovery Grid
                // Priority: show cached data first, only fall back to empty/loading states when no data exists.
                val hasRelayConfig = relayCategories.isNotEmpty() || hasSavedRelayConfig
                val hasTopicData = topicsUiState.hashtagStats.isNotEmpty()
                val isStillLoading = topicsUiState.isLoading || topicsUiState.isReceivingEvents ||
                    (topicsUiState.connectedRelays.isEmpty() && hasRelayConfig)

                if (!hasRelayConfig && !hasTopicData) {
                    // No relays configured at all and no cached data
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "No Relays",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Relays Connected",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Open the sidebar and select a relay collection to discover topics",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Sidebar")
                            }
                        }
                    }
                } else if (!hasTopicData && isStillLoading) {
                    // No data yet but loading — simple Material3 spinner
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (!hasTopicData) {
                    // Finished loading but truly no topics found
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tag,
                                contentDescription = "No Topics",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Topics Found",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No topics found from the selected relays. Try selecting different relays or check back later.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Main content area
                    AnimatedContent(
                        targetState = isViewingHashtagFeed,
                        transitionSpec = {
                            // Instant transition — like switching between Home and Topics tabs.
                            // Thread overlays from within a #topic feed use the slide animation.
                            EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "topics_content"
                    ) { viewingFeed ->
                        if (!viewingFeed) {
                            // Hashtag list - new topics counter, then full width cards
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
                            ) {
                                // "x new topics" counter (tap to refresh) — connection status lives in sidebar
                                if (topicsUiState.newTopicsCount > 0) {
                                    item(key = "new_topics_header") {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { topicsViewModel.refreshTopics() }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${topicsUiState.newTopicsCount} new topic${if (topicsUiState.newTopicsCount == 1) "" else "s"}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = "Tap to show",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Filter hashtags by favorites if enabled
                                val displayedHashtags = if (showFavoritesOnly) {
                                    topicsUiState.hashtagStats.filter { stats ->
                                        "#${stats.hashtag.lowercase()}" in subscribedAnchors
                                    }
                                } else {
                                    topicsUiState.hashtagStats
                                }

                                items(
                                    items = displayedHashtags,
                                    key = { it.hashtag }
                                ) { stats ->
                                    val anchor = "#${stats.hashtag.lowercase()}"
                                    val isFavorited = anchor in subscribedAnchors
                                    HashtagCard(
                                        stats = stats,
                                        isFavorited = isFavorited,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        onToggleFavorite = {
                                            if (isFavorited) {
                                                accountStateViewModel.unsubscribeFromAnchor(anchor)
                                            } else {
                                                accountStateViewModel.subscribeToAnchor(anchor)
                                            }
                                        },
                                        onMenuClick = {
                                            // TODO: Show menu
                                        },
                                        onClick = {
                                            feedStateViewModel.setTopicsSelectedHashtag(stats.hashtag)
                                            topicsViewModel.selectHashtag(stats.hashtag)
                                        }
                                    )
                                }
                            }
                        } else {
                            // Kind 11 feed for selected hashtag, or empty state when no topics on this relay
                            if (topicsUiState.topicsForSelectedHashtag.isEmpty() && !topicsUiState.isLoading && selectedHashtag != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tag,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "No topics for #$selectedHashtag on this relay",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Try switching to global or view all topics",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(24.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    feedStateViewModel.clearTopicsSelectedHashtag()
                                                    topicsViewModel.clearSelectedHashtag()
                                                }
                                            ) {
                                                Text("View all topics")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    feedStateViewModel.setTopicsGlobal()
                                                    feedStateViewModel.setHomeGlobal()
                                                    val allUrls = relayCategories.flatMap { it.relays }.map { it.url }.distinct()
                                                    if (allUrls.isNotEmpty()) topicsViewModel.setDisplayFilterOnly(allUrls)
                                                }
                                            ) {
                                                Text("Switch to global")
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
                                ) {
                                    items(
                                        items = topicsUiState.topicsForSelectedHashtag,
                                        key = { it.id }
                                    ) { topic ->
                                        val note = topic.toNote()
                                        // NIP-22: compute anchor from selected hashtag for moderation
                                        val anchor = "#${selectedHashtag?.lowercase() ?: ""}"
                                        val moderationMenuItems = listOf<Pair<String, () -> Unit>>(
                                            "Flag Off-Topic" to {
                                                accountStateViewModel.publishOffTopicModeration(anchor, topic.id)
                                                Unit
                                            },
                                            "Exclude User" to {
                                                accountStateViewModel.publishUserExclusion(anchor, topic.author.id)
                                                Unit
                                            }
                                        )
                                        NoteCard(
                                            note = note,
                                            onNoteClick = {
                                                onThreadClick(note, topic.relayUrls.takeIf { it.isNotEmpty() })
                                            },
                                            onComment = { onThreadClick(note, topic.relayUrls.takeIf { it.isNotEmpty() }) },
                                            onReact = { reactedNote, emoji ->
                                                accountStateViewModel.sendReaction(reactedNote, emoji)
                                            },
                                            onProfileClick = onProfileClick,
                                            onImageTap = { n, urls, idx ->
                                                onThreadClick(note, topic.relayUrls.takeIf { it.isNotEmpty() })
                                            },
                                            onZap = { noteId, amount ->
                                                accountStateViewModel.sendZap(note, amount, social.mycelium.android.repository.ZapType.PUBLIC, "")
                                            },
                                            onCustomZapSend = { n, amount, zapType, msg ->
                                                accountStateViewModel.sendZap(n, amount, zapType, msg)
                                            },
                                            shouldCloseZapMenus = shouldCloseZapMenus,
                                            onZapSettings = { onNavigateToZapSettings() },
                                            onRelayClick = onRelayClick,
                                            accountNpub = currentAccount?.npub,
                                            extraMoreMenuItems = moderationMenuItems,
                                            actionRowSchema = ActionRowSchema.KIND11_FEED,
                                            showHashtagsSection = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ✅ ZAP CONFIGURATION: Dialogs for editing zap amounts
    // Account switcher bottom sheet
    if (showAccountSwitcher) {
        social.mycelium.android.ui.components.AccountSwitchBottomSheet(
            accountStateViewModel = accountStateViewModel,
            onDismiss = { showAccountSwitcher = false },
            onAddAccount = {
                showAccountSwitcher = false
                onLoginClick?.invoke()
            }
        )
    }

    // Zap configuration: now navigates to zap_settings page via onNavigateToZapSettings

    // Wallet Connect dialog
    if (showWalletConnectDialog) {
        social.mycelium.android.ui.components.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }

}



/**
 * Hashtag card displaying statistics - true edge-to-edge like NoteCard feed cards
 */
@Composable
private fun HashtagCard(
    stats: HashtagStats,
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onMenuClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        shape = RectangleShape
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tag icon
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))

                // Left: hashtag name + count + timestamp
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "#${stats.hashtag}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stats.topicCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Outlined.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatHashtagTimestamp(stats.latestActivity),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Middle: relay orbs (expand to fill available space)
                if (stats.relayUrls.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    social.mycelium.android.ui.components.RelayOrbs(
                        relayUrls = stats.relayUrls
                    )
                }

                // Right: star + menu aligned to end
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorited) "Unfavorite" else "Favorite",
                        tint = if (isFavorited) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Divider
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
        }
    }
}

/**
 * Format timestamp to relative time for hashtag cards
 */
private fun formatHashtagTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        diff < 604800_000 -> "${diff / 86400_000}d ago"
        else -> "${diff / 604800_000}w ago"
    }
}

/**
 * Kind 11 Topic card displaying topic info - custom design with karma and styled body.
 * Includes NIP-22 scoped moderation: off-topic badge and flag action in overflow menu.
 */
@Composable
private fun Kind11TopicCard(
    topic: social.mycelium.android.repository.TopicNote,
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onMenuClick: () -> Unit,
    onClick: () -> Unit,
    offTopicCount: Int = 0,
    onFlagOffTopic: (() -> Unit)? = null,
    onExcludeUser: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RectangleShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header: Profile pic + Author + Time + Relay orbs + overflow menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                social.mycelium.android.ui.components.ProfilePicture(
                    author = topic.author,
                    size = 36.dp,
                    onClick = { /* profile click */ }
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = topic.author.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatHashtagTimestamp(topic.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        if (topic.replyCount > 0) {
                            Text(
                                text = "\u2022",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = "${topic.replyCount} ${if (topic.replyCount == 1) "reply" else "replies"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        // NIP-22: off-topic flag badge
                        if (offTopicCount > 0) {
                            Text(
                                text = "\u2022",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "$offTopicCount flag${if (offTopicCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                // Relay orbs
                val displayRelayUrls = run {
                    val raw = topic.relayUrls.ifEmpty { listOfNotNull(topic.relayUrl).filter { it.isNotEmpty() } }
                    val seen = mutableSetOf<String>()
                    raw.filter { url -> seen.add(url.trimEnd('/').lowercase()) }.take(4)
                }
                if (displayRelayUrls.isNotEmpty()) {
                    social.mycelium.android.ui.components.RelayOrbs(
                        relayUrls = displayRelayUrls,
                        onRelayClick = { /* relay info */ }
                    )
                }
                // NIP-22: overflow menu with moderation actions
                if (onFlagOffTopic != null || onExcludeUser != null) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (onFlagOffTopic != null) {
                                DropdownMenuItem(
                                    text = { Text("Flag as off-topic") },
                                    onClick = {
                                        showMenu = false
                                        onFlagOffTopic()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Flag,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                            if (onExcludeUser != null) {
                                DropdownMenuItem(
                                    text = { Text("Exclude user from topic") },
                                    onClick = {
                                        showMenu = false
                                        onExcludeUser()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.PersonOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Title
            if (topic.title.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = topic.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Body text with subtle background
            if (topic.content.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = topic.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(12.dp),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp
                    )
                }
            }

            // Hashtags — inline text style, not chips
            if (topic.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = topic.hashtags.take(4).joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8FBC8F)
                )
            }

        }

        // Divider
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            thickness = 1.dp
        )
    }
}


@Preview(showBackground = true)
@Composable
fun TopicsScreenPreview() {
    MaterialTheme {
        TopicsScreen()
    }
}
