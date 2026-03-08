package social.mycelium.android.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.offset
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import androidx.compose.ui.platform.LocalContext
import social.mycelium.android.viewmodel.HomeSortOrder
import social.mycelium.android.viewmodel.TopicsSortOrder
import social.mycelium.android.viewmodel.HashtagSortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveHeader(
    title: String = "mycelium",
    isSearchMode: Boolean = false,
    showBackArrow: Boolean = false,
    searchQuery: TextFieldValue = TextFieldValue(""),
    onSearchQueryChange: (TextFieldValue) -> Unit = {},
    onMenuClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onFilterClick: () -> Unit = {},
    onMoreOptionClick: (String) -> Unit = {},
    onBackClick: () -> Unit = {},
    onClearSearch: () -> Unit = {},
    onLoginClick: (() -> Unit)? = null,
    onProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    onQrCodeClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRelaysClick: () -> Unit = {},
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    currentFeedView: String = "Home",
    onFeedViewChange: (String) -> Unit = {},
    // Home feed filter: All vs Following (slide-out under title); Edit for future custom feeds
    isFollowingFilter: Boolean = true,
    onFollowingFilterChange: ((Boolean) -> Unit)? = null,
    onEditFeedClick: (() -> Unit)? = null,
    // Home sort: Latest (default) / Popular
    homeSortOrder: HomeSortOrder = HomeSortOrder.Latest,
    onHomeSortOrderChange: ((HomeSortOrder) -> Unit)? = null,
    // Topics feed: All (default) / Following + Latest/Popular (when non-null)
    isTopicsFollowingFilter: Boolean = false,
    onTopicsFollowingFilterChange: ((Boolean) -> Unit)? = null,
    topicsSortOrder: TopicsSortOrder = TopicsSortOrder.Latest,
    onTopicsSortOrderChange: ((TopicsSortOrder) -> Unit)? = null,
    // Topics explorer sort (hashtag list): Most Topics, Most Active, Most Popular, Alphabetical
    explorerSortOrder: HashtagSortOrder = HashtagSortOrder.MOST_TOPICS,
    onExplorerSortOrderChange: ((HashtagSortOrder) -> Unit)? = null,
    // True when viewing a #hashtag feed (not the explorer list)
    isViewingHashtagFeed: Boolean = false,
    // Topics favorites filter (kind:30073 anchor subscriptions)
    isTopicsFavoritesFilter: Boolean = false,
    onTopicsFavoritesFilterChange: ((Boolean) -> Unit)? = null,
    activeEngagementFilter: String? = null,
    onEngagementFilterChange: (String?) -> Unit = {},
    /** Navigate to Topics screen from the Mycelium logo menu. */
    onNavigateToTopics: (() -> Unit)? = null,
    /** Navigate to Home feed from the Topics logo menu. */
    onNavigateToHome: (() -> Unit)? = null,
    /** Navigate to Live broadcast explorer. */
    onNavigateToLive: (() -> Unit)? = null,
    /** True when a followed user is currently hosting a NIP-53 live event. Turns the Live icon red. */
    hasFollowedLiveActivity: Boolean = false,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Mycelium logo dropdown menu state
    var logoMenuExpanded by remember { mutableStateOf(false) }

    // Show keyboard when search mode is activated
    LaunchedEffect(isSearchMode) {
        if (isSearchMode) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Auto-collapse menu when header begins to hide from scroll
    val collapsedFraction = scrollBehavior?.state?.collapsedFraction ?: 0f
    LaunchedEffect(collapsedFraction) {
        if (collapsedFraction > 0.05f && logoMenuExpanded) {
            logoMenuExpanded = false
        }
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
            ),
            windowInsets = WindowInsets(0),
            title = {
                if (isSearchMode) {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = {
                            Text(
                                "Search notes...",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.text.isNotEmpty()) {
                                IconButton(onClick = onClearSearch) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )
                } else {
                    // Title: just text, or clickable title + arrow when filter slide-out is below
                    if (showBackArrow) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Box {
                            Row(
                                modifier = Modifier
                                    .clickable { logoMenuExpanded = !logoMenuExpanded }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                val caretRotation by animateFloatAsState(
                                    targetValue = if (logoMenuExpanded) 180f else 0f,
                                    animationSpec = tween(250),
                                    label = "caret_rotation"
                                )
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Feed options",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp).rotate(caretRotation)
                                )
                            }
                            // Adaptive logo dropdown menu
                            val isMessagesScreen = title == "messages" || title == "requests"
                            DropdownMenu(
                                expanded = logoMenuExpanded,
                                onDismissRequest = { logoMenuExpanded = false },
                                offset = DpOffset(x = 0.dp, y = 4.dp)
                            ) {
                                if (onTopicsFollowingFilterChange != null) {
                                    // ── Topics screen menu ──
                                    if (onNavigateToHome != null) {
                                        DropdownMenuItem(
                                            text = { Text("Home") },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToHome()
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Home, contentDescription = null) }
                                        )
                                    }
                                    if (onNavigateToLive != null) {
                                        val liveTint = if (hasFollowedLiveActivity) Color.Red else LocalContentColor.current
                                        DropdownMenuItem(
                                            text = { Text("Live", color = if (hasFollowedLiveActivity) Color.Red else Color.Unspecified) },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToLive()
                                            },
                                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null, tint = liveTint) }
                                        )
                                    }
                                    if (onNavigateToHome != null || onNavigateToLive != null) {
                                        HorizontalDivider()
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text("All", color = if (!isTopicsFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            logoMenuExpanded = false
                                            onTopicsFollowingFilterChange(false)
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null, tint = if (!isTopicsFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Following", color = if (isTopicsFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            logoMenuExpanded = false
                                            onTopicsFollowingFilterChange(true)
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.People, contentDescription = null, tint = if (isTopicsFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                    )
                                } else if (isMessagesScreen) {
                                    // ── Messages screen menu ──
                                    if (onNavigateToHome != null) {
                                        DropdownMenuItem(
                                            text = { Text("Home") },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToHome()
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Home, contentDescription = null) }
                                        )
                                    }
                                    if (onNavigateToTopics != null) {
                                        DropdownMenuItem(
                                            text = { Text("Topics") },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToTopics()
                                            },
                                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) }
                                        )
                                    }
                                    if (onNavigateToLive != null) {
                                        val liveTint = if (hasFollowedLiveActivity) Color.Red else LocalContentColor.current
                                        DropdownMenuItem(
                                            text = { Text("Live", color = if (hasFollowedLiveActivity) Color.Red else Color.Unspecified) },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToLive()
                                            },
                                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null, tint = liveTint) }
                                        )
                                    }
                                    if (onNavigateToHome != null || onNavigateToTopics != null || onNavigateToLive != null) {
                                        HorizontalDivider()
                                    }
                                    // Single toggle between conversations and requests
                                    val showingRequests = title == "requests"
                                    DropdownMenuItem(
                                        text = { Text(if (showingRequests) "Conversations" else "Requests") },
                                        onClick = {
                                            logoMenuExpanded = false
                                            onMoreOptionClick(if (showingRequests) "conversations" else "requests")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (showingRequests) Icons.Outlined.Email else Icons.Outlined.MarkEmailUnread,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                } else {
                                    // ── Dashboard / Home screen menu ──
                                    if (onNavigateToTopics != null) {
                                        DropdownMenuItem(
                                            text = { Text("Topics") },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToTopics()
                                            },
                                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null) }
                                        )
                                    }
                                    if (onNavigateToLive != null) {
                                        val liveTint = if (hasFollowedLiveActivity) Color.Red else LocalContentColor.current
                                        DropdownMenuItem(
                                            text = { Text("Live", color = if (hasFollowedLiveActivity) Color.Red else Color.Unspecified) },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onNavigateToLive()
                                            },
                                            leadingIcon = { Icon(Icons.Filled.Videocam, contentDescription = null, tint = liveTint) }
                                        )
                                    }
                                    if (onNavigateToTopics != null || onNavigateToLive != null) {
                                        HorizontalDivider()
                                    }
                                    if (onFollowingFilterChange != null) {
                                        DropdownMenuItem(
                                            text = {
                                                Text("Global", color = if (!isFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onFollowingFilterChange(false)
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null, tint = if (!isFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text("Following", color = if (isFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            },
                                            onClick = {
                                                logoMenuExpanded = false
                                                onFollowingFilterChange(true)
                                            },
                                            leadingIcon = { Icon(Icons.Outlined.People, contentDescription = null, tint = if (isFollowingFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        navigationIcon = {
            if (isSearchMode || showBackArrow) {
                // Search mode or back arrow mode - show back button
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                // Normal mode - show hamburger menu
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (isSearchMode) {
                // Search mode - no actions, just the clear button in the text field
            } else {
                // Normal mode - show search, filter, and profile avatar
                Row {
                    // Search button
                    IconButton(onClick = onSearchClick) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Feed engagement filter / sort
                    if (!showBackArrow || onTopicsSortOrderChange != null) {
                        var filterMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            val filterActive = if (onTopicsSortOrderChange != null) {
                                if (isViewingHashtagFeed) {
                                    topicsSortOrder != TopicsSortOrder.Latest
                                } else {
                                    explorerSortOrder != HashtagSortOrder.MOST_TOPICS || isTopicsFavoritesFilter
                                }
                            } else {
                                activeEngagementFilter != null || homeSortOrder != HomeSortOrder.Latest
                            }
                            IconButton(onClick = { filterMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filter feed",
                                    tint = if (filterActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = filterMenuExpanded,
                                onDismissRequest = { filterMenuExpanded = false },
                                offset = DpOffset(x = (-8).dp, y = 8.dp)
                            ) {
                                if (onTopicsSortOrderChange != null && isViewingHashtagFeed) {
                                    // ── Topic feed filter menu (Latest / Popular) ──
                                    DropdownMenuItem(
                                        text = {
                                            Text("Latest", color = if (topicsSortOrder == TopicsSortOrder.Latest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onTopicsSortOrderChange(TopicsSortOrder.Latest)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = if (topicsSortOrder == TopicsSortOrder.Latest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Popular", color = if (topicsSortOrder == TopicsSortOrder.Popular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onTopicsSortOrderChange(TopicsSortOrder.Popular)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = if (topicsSortOrder == TopicsSortOrder.Popular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                } else if (onExplorerSortOrderChange != null && !isViewingHashtagFeed) {
                                    // ── Topics explorer filter menu (hashtag list sort) ──
                                    DropdownMenuItem(
                                        text = {
                                            Text("Most Topics", color = if (explorerSortOrder == HashtagSortOrder.MOST_TOPICS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onExplorerSortOrderChange(HashtagSortOrder.MOST_TOPICS)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Tag, contentDescription = null, tint = if (explorerSortOrder == HashtagSortOrder.MOST_TOPICS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Most Active", color = if (explorerSortOrder == HashtagSortOrder.MOST_ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onExplorerSortOrderChange(HashtagSortOrder.MOST_ACTIVE)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = if (explorerSortOrder == HashtagSortOrder.MOST_ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Most Popular", color = if (explorerSortOrder == HashtagSortOrder.MOST_POPULAR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onExplorerSortOrderChange(HashtagSortOrder.MOST_POPULAR)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = if (explorerSortOrder == HashtagSortOrder.MOST_POPULAR) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Alphabetical", color = if (explorerSortOrder == HashtagSortOrder.ALPHABETICAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onExplorerSortOrderChange(HashtagSortOrder.ALPHABETICAL)
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.SortByAlpha, contentDescription = null, tint = if (explorerSortOrder == HashtagSortOrder.ALPHABETICAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    if (onTopicsFavoritesFilterChange != null) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text("Favorites", color = if (isTopicsFavoritesFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            },
                                            onClick = {
                                                filterMenuExpanded = false
                                                onTopicsFavoritesFilterChange(!isTopicsFavoritesFilter)
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (isTopicsFavoritesFilter) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                                    contentDescription = null,
                                                    tint = if (isTopicsFavoritesFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        )
                                    }
                                } else {
                                    // ── Dashboard / Home filter menu ──
                                    if (onHomeSortOrderChange != null) {
                                        val isLatestActive = activeEngagementFilter == null && homeSortOrder == HomeSortOrder.Latest
                                        val isPopularActive = activeEngagementFilter == null && homeSortOrder == HomeSortOrder.Popular
                                        DropdownMenuItem(
                                            text = {
                                                Text("Latest", color = if (isLatestActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            },
                                            onClick = {
                                                filterMenuExpanded = false
                                                onEngagementFilterChange(null)
                                                onHomeSortOrderChange(HomeSortOrder.Latest)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = if (isLatestActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text("Popular", color = if (isPopularActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            },
                                            onClick = {
                                                filterMenuExpanded = false
                                                onEngagementFilterChange(null)
                                                onHomeSortOrderChange(HomeSortOrder.Popular)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Outlined.TrendingUp, contentDescription = null, tint = if (isPopularActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        )
                                        HorizontalDivider()
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text("Replies", color = if (activeEngagementFilter == "replies") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onEngagementFilterChange("replies")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Outlined.Reply, contentDescription = null, tint = if (activeEngagementFilter == "replies") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Likes", color = if (activeEngagementFilter == "likes") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onEngagementFilterChange("likes")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = if (activeEngagementFilter == "likes") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text("Zaps", color = if (activeEngagementFilter == "zaps") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                        },
                                        onClick = {
                                            filterMenuExpanded = false
                                            onEngagementFilterChange("zaps")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.ElectricBolt, contentDescription = null, tint = if (activeEngagementFilter == "zaps") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Profile avatar or login button
                    if (isGuest) {
                        // Guest mode - show login icon
                        IconButton(onClick = onLoginClick ?: {}) {
                            Icon(
                                imageVector = Icons.Outlined.Login,
                                contentDescription = "Login with Amber",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        // Signed in - show user avatar with dropdown menu
                        var showMenu by remember { mutableStateOf(false) }
                        // Persist the last known avatar URL across lifecycle stops so that
                        // navigating back never flashes the letter placeholder while Coil
                        // loads from memory cache.
                        var savedAvatarUrl by rememberSaveable { mutableStateOf(userAvatarUrl) }
                        if (userAvatarUrl != null && userAvatarUrl.isNotBlank()) {
                            savedAvatarUrl = userAvatarUrl
                        }
                        val avatarModel = savedAvatarUrl?.takeIf { it.isNotBlank() }

                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (avatarModel != null) {
                                        val context = LocalContext.current
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(avatarModel)
                                                .memoryCachePolicy(CachePolicy.ENABLED)
                                                .diskCachePolicy(CachePolicy.ENABLED)
                                                .crossfade(false)
                                                .build(),
                                            contentDescription = "Profile",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = userDisplayName?.take(1)?.uppercase() ?: "U",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                offset = DpOffset(x = (-8).dp, y = 8.dp)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Profile") },
                                    onClick = {
                                        showMenu = false
                                        onProfileClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Person,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Accounts") },
                                    onClick = {
                                        showMenu = false
                                        onAccountsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.AccountCircle,
                                            contentDescription = null
                                        )
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Relays") },
                                    onClick = {
                                        showMenu = false
                                        onRelaysClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Hub,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = {
                                        showMenu = false
                                        onSettingsClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Settings,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
            scrollBehavior = scrollBehavior
        )
        // Filter chip row removed — Topics options now live in the adaptive dropdown menus
    }
}
