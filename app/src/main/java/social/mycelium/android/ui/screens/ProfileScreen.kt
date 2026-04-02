package social.mycelium.android.ui.screens

import social.mycelium.android.ui.components.note.NoteCardCallbacks
import social.mycelium.android.ui.components.note.NoteCardOverrides
import social.mycelium.android.ui.components.note.NoteCardConfig
import social.mycelium.android.ui.components.note.NoteCardInteractionState
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.common.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import social.mycelium.android.ui.icons.Nip05Verified
import social.mycelium.android.ui.icons.Nip05VerifiedDark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.cybin.nip19.toNpub
import social.mycelium.android.R
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.data.SampleData
import social.mycelium.android.ui.components.common.ModernSearchBar
import social.mycelium.android.repository.ProfileFeedRepository
import social.mycelium.android.repository.sync.ZapType
import social.mycelium.android.ui.components.note.NoteCard
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import social.mycelium.android.ui.components.zap.ZapCustomDialog

import social.mycelium.android.repository.social.NoteCounts
// ─── Profile tab definitions ────────────────────────────────────────────────

private data class ProfileTab(val label: String, val icon: @Composable () -> Unit)

private val profileTabs = listOf(
    ProfileTab("Notes") { Icon(Icons.AutoMirrored.Outlined.Article, null, modifier = Modifier.size(18.dp)) },
    ProfileTab("Replies") { Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(18.dp)) },
    ProfileTab("Media") { Icon(Icons.Outlined.Image, null, modifier = Modifier.size(18.dp)) },
)

// ─── Main Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    author: Author,
    authorNotes: List<Note>,
    isProfileLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMore: Boolean = true,
    onLoadMore: (Int) -> Unit = {},
    followingCount: Int? = null,
    followerCount: Int? = null,
    isLoadingCounts: Boolean = false,
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onImageTap: (social.mycelium.android.data.Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onReact: (Note, String) -> Unit = { _, _ -> },
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZap: (String, Long) -> Unit = { _, _ -> },
    isZapInProgress: (String) -> Boolean = { false },
    isZapped: (String) -> Boolean = { false },
    myZappedAmountForNote: (String) -> Long? = { null },
    overrideReplyCountForNote: (String) -> Int? = { null },
    countsForNote: (String) -> social.mycelium.android.repository.social.NoteCounts? = { null },
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onRelayClick: (relayUrl: String) -> Unit = {},
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
    accountNpub: String? = null,
    onLoginClick: (() -> Unit)? = null,
    parentNoteForReply: (String) -> Note? = { null },
    timeGapIndex: Int? = null,
    perTabHasMore: Map<Int, Boolean> = emptyMap(),
    onSeeAllReactions: (Note) -> Unit = {},
    onDeleteNote: ((Note) -> Unit)? = null,
    badges: List<social.mycelium.android.repository.social.BadgeRepository.Badge> = emptyList(),
    modifier: Modifier = Modifier
) {
    androidx.activity.compose.BackHandler { onBackClick() }

    val compactMedia by social.mycelium.android.ui.theme.ThemePreferences.compactMedia.collectAsState()
    val profileCurrentUserHex = remember(accountNpub) {
        accountNpub?.let { npub ->
            try {
                (com.example.cybin.nip19.Nip19Parser.uriToRoute(npub)?.entity as? com.example.cybin.nip19.NPub)?.hex?.lowercase()
            } catch (_: Exception) { null }
        }
    }
    // ── Guard: prevent pager from stealing leftward gestures during ThreadSlideBackBox mid-slide ──
    // This NestedScrollConnection sits on the pager. When rightward overscroll passes
    // through (meaning ThreadSlideBackBox is sliding), it locks and consumes ALL
    // horizontal pre-scroll so the pager can't reverse into a tab swipe.
    var slideBackEngaged by remember { mutableStateOf(false) }
    val slideBackGuardConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (slideBackEngaged && available.x < 0f) {
                    // Consume only leftward scroll — pager can't reverse into tab swipe
                    return Offset(available.x, 0f)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // If rightward overscroll is available (pager can't scroll right),
                // it means ThreadSlideBackBox will consume it → engage guard
                if (available.x > 0f) {
                    slideBackEngaged = true
                }
                return Offset.Zero // pass through to ThreadSlideBackBox
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (slideBackEngaged) {
                    slideBackEngaged = false
                    // Consume horizontal fling so pager doesn't animate to next page
                    return Velocity(available.x, 0f)
                }
                return Velocity.Zero
            }
        }
    }

    // Profile zap dialog state
    var showProfileZapDialog by remember { mutableStateOf(false) }

    // Tab state + pager
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = 0) { profileTabs.size }
    val coroutineScope = rememberCoroutineScope()

    // Two-way sync: tab click → pager
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
    }
    // Two-way sync: pager swipe → tab
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != selectedTab) selectedTab = page
        }
    }

    // Per-tab list states — use rememberSaveable to survive lifecycle stops
    // (e.g. navigating to image_viewer/video_viewer and returning)
    var notesScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var notesScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var repliesScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var repliesScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    val notesListState = rememberLazyListState(notesScrollIndex, notesScrollOffset)
    val repliesListState = rememberLazyListState(repliesScrollIndex, repliesScrollOffset)
    // Continuously save scroll position so it survives lifecycle stops
    LaunchedEffect(notesListState) {
        snapshotFlow { notesListState.firstVisibleItemIndex to notesListState.firstVisibleItemScrollOffset }
            .collect { (idx, offset) -> notesScrollIndex = idx; notesScrollOffset = offset }
    }
    LaunchedEffect(repliesListState) {
        snapshotFlow { repliesListState.firstVisibleItemIndex to repliesListState.firstVisibleItemScrollOffset }
            .collect { (idx, offset) -> repliesScrollIndex = idx; repliesScrollOffset = offset }
    }

    // Per-tab filtered notes — single-pass partition (avoids 3× O(n) filter)
    val (notesOnly, repliesOnly, mediaOnly) = remember(authorNotes) {
        val notes = mutableListOf<Note>()
        val replies = mutableListOf<Note>()
        val media = mutableListOf<Note>()
        for (note in authorNotes) {
            if (note.isReply) replies.add(note)
            else notes.add(note)
            if (note.mediaUrls.isNotEmpty()) media.add(note)
        }
        Triple(notes as List<Note>, replies as List<Note>, media as List<Note>)
    }

    // Per-tab hasMore (fallback to global hasMore)
    val notesHasMore = perTabHasMore[ProfileFeedRepository.TAB_NOTES] ?: hasMore
    val repliesHasMore = perTabHasMore[ProfileFeedRepository.TAB_REPLIES] ?: hasMore
    val mediaHasMore = perTabHasMore[ProfileFeedRepository.TAB_MEDIA] ?: hasMore

    // Bio expanded state
    var bioExpanded by rememberSaveable { mutableStateOf(false) }

    // 3-dot menu state
    var showMoreMenu by rememberSaveable { mutableStateOf(false) }

    // ── Collapsing header state ──
    val density = LocalDensity.current
    // Natural heights measured once via onGloballyPositioned
    var headerHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    var tabHeightPx by rememberSaveable { mutableFloatStateOf(0f) }
    // How far the header has been scrolled off screen: 0 = fully visible, -headerHeightPx = fully collapsed
    val headerOffsetPx = rememberSaveable { mutableFloatStateOf(0f) }

    // Custom NestedScrollConnection: collapses header before inner list scrolls
    val collapsingConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling up (finger moves up → negative y): collapse header first
                if (available.y < 0f && headerHeightPx > 0f) {
                    val currentOffset = headerOffsetPx.floatValue
                    val minOffset = -headerHeightPx
                    if (currentOffset > minOffset) {
                        val consumed = available.y.coerceAtLeast(minOffset - currentOffset)
                        headerOffsetPx.floatValue = (currentOffset + consumed).coerceIn(minOffset, 0f)
                        return Offset(0f, consumed)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // Scrolling down (finger moves down → positive y): expand header with leftover
                if (available.y > 0f && headerHeightPx > 0f) {
                    val currentOffset = headerOffsetPx.floatValue
                    if (currentOffset < 0f) {
                        val take = available.y.coerceAtMost(-currentOffset)
                        headerOffsetPx.floatValue = (currentOffset + take).coerceIn(-headerHeightPx, 0f)
                        return Offset(0f, take)
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                // No snap — let the header stay wherever the user left it
                return Velocity.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(collapsingConnection),
        topBar = {
            // Only status-bar inset; the real TopAppBar lives inside the collapsible header
            Spacer(Modifier.statusBarsPadding())
        }
    ) { paddingValues ->
        // Box layout: header+tabs are drawn on top, pager sits below with dynamic top padding
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clipToBounds()
        ) {
            // ═══ Pager: fills area below header+tabs, adjusts as header collapses ═══
            val topOffsetPx = headerHeightPx + tabHeightPx + headerOffsetPx.floatValue
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(slideBackGuardConnection)
                    .layout { measurable, constraints ->
                        val topPx = topOffsetPx.roundToInt().coerceAtLeast(0)
                        val availableHeight = (constraints.maxHeight - topPx).coerceAtLeast(0)
                        val adjustedConstraints = constraints.copy(
                            minHeight = availableHeight,
                            maxHeight = availableHeight
                        )
                        val placeable = measurable.measure(adjustedConstraints)
                        layout(placeable.width, constraints.maxHeight) {
                            placeable.place(0, topPx)
                        }
                    },
                beyondViewportPageCount = 1,
                userScrollEnabled = true,
                key = { it }
            ) { page ->
                val isPageVisible = pagerState.currentPage == page
                when (page) {
                    // ── Notes tab ──
                    0 -> {
                        if (notesOnly.isEmpty() && !isProfileLoading) {
                            EmptyTabPlaceholder(tabIndex = 0)
                        } else {
                            val notesGapIndex = remember(timeGapIndex, notesOnly) {
                                if (timeGapIndex == null) null
                                else detectTimeGapIndex(notesOnly)
                            }
                            var showOlderNotes by rememberSaveable { mutableStateOf(false) }
                            val visibleNotes = remember(notesOnly, notesGapIndex, showOlderNotes) {
                                if (notesGapIndex != null && !showOlderNotes) notesOnly.take(notesGapIndex)
                                else notesOnly
                            }
                            // Pre-fetch: trigger 50 items before the bottom
                            LaunchedEffect(notesListState, isLoadingMore) {
                                snapshotFlow {
                                    val li = notesListState.layoutInfo
                                    val total = li.totalItemsCount
                                    val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    last to total
                                }.collect { (last, total) ->
                                    if (total > 50 && last >= total - 50 && !isLoadingMore && notesHasMore) {
                                        onLoadMore(ProfileFeedRepository.TAB_NOTES)
                                    }
                                }
                            }
                            LazyColumn(
                                state = notesListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 80.dp)
                            ) {
                                items(visibleNotes, key = { "notes_${it.id}" }) { note ->
                                    ProfileNoteCard(
                                        note = note,
                                        onLike = onLike, onShare = onShare, onComment = onComment,
                                        onReact = onReact, onProfileClick = onProfileClick,
                                        onNoteClick = onNoteClick, onImageTap = onImageTap,
                                        onOpenImageViewer = onOpenImageViewer, onVideoClick = onVideoClick,
                                        onCustomZapSend = onCustomZapSend, onZap = onZap,
                                        isZapInProgress = isZapInProgress, isZapped = isZapped,
                                        myZappedAmountForNote = myZappedAmountForNote,
                                        overrideReplyCountForNote = overrideReplyCountForNote,
                                        countsForNote = countsForNote,
                                        onRelayClick = onRelayClick, accountNpub = accountNpub,
                                        onSeeAllReactions = onSeeAllReactions,
                                        onDelete = if (onDeleteNote != null && profileCurrentUserHex != null && social.mycelium.android.utils.normalizeAuthorIdForCache(note.author.id) == profileCurrentUserHex) onDeleteNote else null,
                                        isVisible = isPageVisible,
                                        compactMedia = compactMedia,
                                    )
                                }
                                if (notesGapIndex != null && !showOlderNotes) {
                                    item(key = "time_gap_divider_notes") {
                                        TimeGapDivider(
                                            onShowOlder = { showOlderNotes = true },
                                            onLoadMore = { onLoadMore(ProfileFeedRepository.TAB_NOTES) }
                                        )
                                    }
                                } else {
                                    profileFeedFooter(visibleNotes, notesHasMore, isLoadingMore, isProfileLoading) {
                                        onLoadMore(ProfileFeedRepository.TAB_NOTES)
                                    }
                                }
                            }
                        }
                    }
                    // ── Replies tab ──
                    1 -> {
                        if (repliesOnly.isEmpty() && !isProfileLoading) {
                            EmptyTabPlaceholder(tabIndex = 1)
                        } else {
                            // Pre-fetch: trigger 50 items before the bottom
                            LaunchedEffect(repliesListState, isLoadingMore) {
                                snapshotFlow {
                                    val li = repliesListState.layoutInfo
                                    val total = li.totalItemsCount
                                    val last = li.visibleItemsInfo.lastOrNull()?.index ?: 0
                                    last to total
                                }.collect { (last, total) ->
                                    if (total > 50 && last >= total - 50 && !isLoadingMore && repliesHasMore) {
                                        onLoadMore(ProfileFeedRepository.TAB_REPLIES)
                                    }
                                }
                            }
                            LazyColumn(
                                state = repliesListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 80.dp)
                            ) {
                                items(repliesOnly, key = { "replies_${it.id}" }) { note ->
                                    ReplyWithParentContext(
                                        note = note,
                                        parentNote = (note.replyToId ?: note.rootNoteId)?.let { parentNoteForReply(it) },
                                        onNoteClick = onNoteClick,
                                        onProfileClick = onProfileClick
                                    )
                                    ProfileNoteCard(
                                        note = note,
                                        onLike = onLike, onShare = onShare, onComment = onComment,
                                        onReact = onReact, onProfileClick = onProfileClick,
                                        onNoteClick = onNoteClick, onImageTap = onImageTap,
                                        onOpenImageViewer = onOpenImageViewer, onVideoClick = onVideoClick,
                                        onCustomZapSend = onCustomZapSend, onZap = onZap,
                                        isZapInProgress = isZapInProgress, isZapped = isZapped,
                                        myZappedAmountForNote = myZappedAmountForNote,
                                        overrideReplyCountForNote = overrideReplyCountForNote,
                                        countsForNote = countsForNote,
                                        onRelayClick = onRelayClick, accountNpub = accountNpub,
                                        onSeeAllReactions = onSeeAllReactions,
                                        onDelete = if (onDeleteNote != null && profileCurrentUserHex != null && social.mycelium.android.utils.normalizeAuthorIdForCache(note.author.id) == profileCurrentUserHex) onDeleteNote else null,
                                        isVisible = isPageVisible,
                                        compactMedia = compactMedia,
                                    )
                                }
                                profileFeedFooter(repliesOnly, repliesHasMore, isLoadingMore, isProfileLoading) {
                                    onLoadMore(ProfileFeedRepository.TAB_REPLIES)
                                }
                            }
                        }
                    }
                    // ── Media tab ──
                    2 -> {
                        if (mediaOnly.isEmpty() && !isProfileLoading) {
                            EmptyTabPlaceholder(tabIndex = 2)
                        } else {
                            LazyMediaGrid(
                                notes = mediaOnly,
                                hasMore = mediaHasMore,
                                isLoadingMore = isLoadingMore,
                                isProfileLoading = isProfileLoading,
                                onLoadMore = { onLoadMore(ProfileFeedRepository.TAB_MEDIA) },
                                onNoteClick = onNoteClick,
                                onOpenImageViewer = onOpenImageViewer,
                                onVideoClick = onVideoClick
                            )
                        }
                    }
                }
            }

            // ═══ Header + Tabs: drawn on top, slide upward via offset ═══
            Column(
                modifier = Modifier
                    .offset { IntOffset(0, headerOffsetPx.floatValue.roundToInt()) }
            ) {
                // TopAppBar + Banner + Identity — all collapse together
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            headerHeightPx = coords.size.height.toFloat()
                        }
                ) {
                    // Back / title / menu bar — collapses with the header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = author.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(if (isFollowing) "Unfollow" else "Follow")
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        onFollowClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (isFollowing) Icons.Default.PersonRemove else Icons.Default.PersonAdd,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Filters & Blocks") },
                                    onClick = {
                                        showMoreMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Block,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Report",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Flag,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                    ProfileBanner(
                        author = author,
                        badges = badges,
                        onBannerClick = { url ->
                            if (url != null) onOpenImageViewer(listOf(url), 0)
                        },
                        onAvatarClick = { url ->
                            if (url != null) onOpenImageViewer(listOf(url), 0)
                        }
                    )
                    ProfileIdentity(
                        author = author,
                        notesCount = authorNotes.size,
                        followingCount = followingCount,
                        followerCount = followerCount,
                        isLoadingCounts = isLoadingCounts,
                        isFollowing = isFollowing,
                        onFollowClick = onFollowClick,
                        bioExpanded = bioExpanded,
                        onBioToggle = { bioExpanded = !bioExpanded },
                        onProfileClick = onProfileClick,
                        onZapProfile = { showProfileZapDialog = true }
                    )
                }

                // Tab bar
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            tabHeightPx = coords.size.height.toFloat()
                        }
                ) {
                    TabRow(
                        selectedTabIndex = pagerState.currentPage,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        divider = {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    ) {
                        profileTabs.forEachIndexed { index, tab ->
                            val count = when (index) {
                                0 -> notesOnly.size
                                1 -> repliesOnly.size
                                2 -> mediaOnly.size
                                else -> 0
                            }
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        tab.icon()
                                        Text("${tab.label} ($count)", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Profile zap dialog
    if (showProfileZapDialog) {
        social.mycelium.android.ui.components.zap.ZapCustomDialog(
            onDismiss = { showProfileZapDialog = false },
            onSendZap = { amount, zapType, message ->
                showProfileZapDialog = false
                // Create a synthetic Note representing the profile author for the zap
                val profileNote = Note(
                    id = author.id, // use pubkey as note ID for profile zaps
                    author = author,
                    content = "",
                    timestamp = System.currentTimeMillis(),
                    likes = 0, shares = 0, comments = 0,
                    isLiked = false, hashtags = emptyList(),
                    mediaUrls = emptyList(), isReply = false,
                )
                onCustomZapSend?.invoke(profileNote, amount, zapType, message)
            },
            onZapSettings = { onNavigateTo("zap_settings") }
        )
    }
}

// ─── Time-gap detection ──────────────────────────────────────────────────────

/** Detect the first index where a large time gap (>90 days) exists between consecutive notes. */
internal fun detectTimeGapIndex(
    sortedNotes: List<Note>,
    thresholdMs: Long = 90L * 24 * 3600 * 1000
): Int? {
    for (i in 0 until sortedNotes.size - 1) {
        if (sortedNotes[i].timestamp - sortedNotes[i + 1].timestamp > thresholdMs) return i + 1
    }
    return null
}

/** Divider shown at a temporal discontinuity in the feed. */
@Composable
private fun TimeGapDivider(
    onShowOlder: () -> Unit,
    onLoadMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 32.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Older notes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                onLoadMore()
                onShowOlder()
            },
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("Load more", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shared NoteCard rendering for profile tabs — avoids duplicating all the callback wiring. */
@Composable
private fun ProfileNoteCard(
    note: Note,
    onLike: (String) -> Unit,
    onShare: (String) -> Unit,
    onComment: (String) -> Unit,
    onReact: (Note, String) -> Unit,
    onProfileClick: (String) -> Unit,
    onNoteClick: (Note) -> Unit,
    onImageTap: (Note, List<String>, Int) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit,
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)?,
    onZap: (String, Long) -> Unit,
    isZapInProgress: (String) -> Boolean,
    isZapped: (String) -> Boolean,
    myZappedAmountForNote: (String) -> Long?,
    overrideReplyCountForNote: (String) -> Int?,
    countsForNote: (String) -> social.mycelium.android.repository.social.NoteCounts?,
    onRelayClick: (String) -> Unit,
    accountNpub: String?,
    onSeeAllReactions: (Note) -> Unit,
    onDelete: ((Note) -> Unit)? = null,
    isVisible: Boolean = true,
    compactMedia: Boolean = false,
) {
    val counts = countsForNote(note.originalNoteId ?: note.id) ?: countsForNote(note.id)
    NoteCard(
        note = note,
        callbacks = NoteCardCallbacks(
            onLike = onLike,
            onShare = onShare,
            onComment = onComment,
            onReact = onReact,
            onProfileClick = onProfileClick,
            onNoteClick = onNoteClick,
            onImageTap = onImageTap,
            onOpenImageViewer = onOpenImageViewer,
            onVideoClick = onVideoClick,
            onCustomZapSend = onCustomZapSend,
            onZap = onZap,
            onRelayClick = onRelayClick,
            onDelete = onDelete,
            onSeeAllReactions = { onSeeAllReactions(note) },
        ),
        overrides = NoteCardOverrides(
            replyCount = overrideReplyCountForNote(note.id),
            zapCount = counts?.zapCount,
            zapTotalSats = counts?.zapTotalSats,
            reactions = counts?.reactions,
            reactionAuthors = counts?.reactionAuthors,
            zapAuthors = counts?.zapAuthors,
            zapAmountByAuthor = counts?.zapAmountByAuthor,
            customEmojiUrls = counts?.customEmojiUrls,
        ),
        config = NoteCardConfig(
            isVisible = isVisible,
            compactMedia = compactMedia,
        ),
        interaction = NoteCardInteractionState(
            isZapInProgress = isZapInProgress(note.id),
            isZapped = isZapped(note.id),
            isBoosted = social.mycelium.android.repository.social.NoteCountsRepository.isOwnBoost(note.id),
            myZappedAmount = myZappedAmountForNote(note.id),
        ),
        accountNpub = accountNpub,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Footer items for profile feed lists: persistent loader + end-of-feed.
 *  Pre-fetch is handled by [profileFeedPrefetch] via snapshotFlow, not a bottom-of-list LaunchedEffect. */
private fun LazyListScope.profileFeedFooter(
    tabNotes: List<Note>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isProfileLoading: Boolean,
    onLoadMore: () -> Unit
) {
    // Always show a bottom item when feed has content and not exhausted
    if (tabNotes.isNotEmpty() && hasMore) {
        item(key = "loading_indicator") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isProfileLoading || isLoadingMore) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                } else {
                    // Idle placeholder — keeps scroll area so pre-fetch triggers
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
    if (!hasMore && tabNotes.isNotEmpty()) {
        item(key = "end_of_feed") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "End of feed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/** Empty tab placeholder with icon + message. */
@Composable
private fun EmptyTabPlaceholder(tabIndex: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                when (tabIndex) {
                    1 -> Icons.Outlined.Forum
                    2 -> Icons.Outlined.Image
                    else -> Icons.AutoMirrored.Outlined.Article
                },
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No ${profileTabs[tabIndex].label.lowercase()} yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Compact parent context shown above a reply in the Replies tab. */
@Composable
private fun ReplyWithParentContext(
    note: Note,
    parentNote: Note?,
    onNoteClick: (Note) -> Unit,
    onProfileClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (parentNote != null) {
            // Parent preview card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNoteClick(parentNote) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Thread connector line
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(1.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Replying to @${parentNote.author.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = parentNote.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else if (note.replyToId != null || note.rootNoteId != null) {
            // Placeholder when parent hasn't loaded yet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(20.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(1.dp)
                        )
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Replying to\u2026",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ─── Banner + Avatar ────────────────────────────────────────────────────────

@Composable
private fun ProfileBanner(
    author: Author,
    badges: List<social.mycelium.android.repository.social.BadgeRepository.Badge> = emptyList(),
    onBannerClick: (String?) -> Unit,
    onAvatarClick: (String?) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        // Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onBannerClick(author.banner) }
        ) {
            author.banner?.takeIf { it.isNotBlank() }?.let { bannerUrl ->
                AsyncImage(
                    model = bannerUrl,
                    contentDescription = "Profile banner",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            // Gradient scrim at bottom for avatar readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f))
                        )
                    )
            )
        }

        // Badges — right-aligned in the overlap area between banner and identity
        if (badges.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 4.dp)
                    .clipToBounds(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show as many as fit; limit to ~6 to avoid overflowing into the avatar
                val visibleBadges = badges.take(6)
                visibleBadges.forEach { badge ->
                    BadgeThumbnail(badge = badge, size = 26.dp)
                }
                if (badges.size > 6) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.5f),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "+${badges.size - 6}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Avatar — left-aligned, overlapping banner bottom
        Box(
            modifier = Modifier
                .padding(start = 16.dp)
                .align(Alignment.BottomStart)
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .clickable { onAvatarClick(author.avatarUrl) }
        ) {
            if (author.avatarUrl != null) {
                AsyncImage(
                    model = author.avatarUrl,
                    contentDescription = "Profile picture",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                val hue = remember(author.id) { (author.id.hashCode() and 0x7FFFFFFF) % 360 }
                val bg = remember(hue) { Color.hsl(hue.toFloat(), 0.45f, 0.25f) }
                val fg = remember(hue) { Color.hsl(hue.toFloat(), 0.55f, 0.82f) }
                val isHex = remember(author.displayName) {
                    author.displayName.isBlank() ||
                            author.displayName.all { it.isLetterOrDigit() || it == '.' } &&
                            author.displayName.length >= 8 &&
                            author.displayName.endsWith("...")
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(bg),
                    contentAlignment = Alignment.Center
                ) {
                    if (isHex) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Person,
                            contentDescription = "Unknown user",
                            tint = fg,
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        Text(
                            text = author.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = fg
                        )
                    }
                }
            }
        }
    }
}

// ─── Identity + Actions ─────────────────────────────────────────────────────

@Composable
private fun ProfileIdentity(
    author: Author,
    notesCount: Int,
    followingCount: Int? = null,
    followerCount: Int? = null,
    isLoadingCounts: Boolean = false,
    isFollowing: Boolean,
    onFollowClick: () -> Unit,
    bioExpanded: Boolean,
    onBioToggle: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onZapProfile: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
    ) {
        // ── Row: Name + Actions ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name column
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = author.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (author.isVerified) {
                        Spacer(Modifier.width(4.dp))
                        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
                        Icon(
                            if (isDark) Icons.Outlined.Nip05VerifiedDark else Icons.Outlined.Nip05Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(18.dp),
                            tint = androidx.compose.ui.graphics.Color.Unspecified
                        )
                    }
                    author.pronouns?.takeIf { it.isNotBlank() }?.let { p ->
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "($p)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // NIP-05 or @username
                val nip05Value = author.nip05?.takeIf { it.isNotBlank() }
                if (nip05Value != null) {
                    social.mycelium.android.ui.components.common.Nip05Badge(
                        nip05 = nip05Value,
                        pubkeyHex = author.id,
                        showFullIdentifier = true
                    )
                } else {
                    Text(
                        text = "@${author.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Lightning address just below NIP-05
                author.lud16?.takeIf { it.isNotBlank() }?.let { ln ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ElectricBolt, null, Modifier.size(13.dp), tint = Color(0xFFFFB74D))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            ln,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Action buttons — compact icon-only
            Spacer(Modifier.width(4.dp))

            // Follow/Unfollow
            if (isFollowing) {
                IconButton(
                    onClick = onFollowClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = "Unfollow",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                IconButton(
                    onClick = onFollowClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = "Follow",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Zap
            if (author.lud16?.isNotBlank() == true) {
                IconButton(
                    onClick = onZapProfile,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ElectricBolt,
                        contentDescription = "Zap",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFFFB74D)
                    )
                }
            }

            // Copy npub
            IconButton(
                onClick = {
                    try {
                        val npub = author.id.toNpub()
                        clipboardManager.setText(AnnotatedString(npub))
                        Toast.makeText(context, "npub copied", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {
                        clipboardManager.setText(AnnotatedString(author.id))
                        Toast.makeText(context, "Pubkey copied", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy npub", modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Bio ──
        author.about?.takeIf { it.isNotBlank() }?.let { about ->
            // Extract mentioned pubkeys and request their profiles so display names resolve
            val profileCache = remember { social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance() }
            val bioMentionPubkeys = remember(about) {
                social.mycelium.android.utils.extractPubkeysFromContent(about)
            }
            // Request profiles for uncached mentions
            if (bioMentionPubkeys.isNotEmpty()) {
                LaunchedEffect(bioMentionPubkeys) {
                    val relayUrls = profileCache.getConfiguredRelayUrls().ifEmpty {
                        listOf("wss://relay.damus.io", "wss://relay.nostr.band", "wss://nos.lol")
                    }
                    profileCache.requestProfiles(bioMentionPubkeys, relayUrls)
                }
            }
            // profileVersion ticks when any profile is updated; rebuild bio when mentions resolve
            val profileVersion by profileCache.profileVersion.collectAsState()
            val bioAnnotated = remember(about, profileVersion) { parseBioWithNpubs(about) }
            androidx.compose.foundation.text.ClickableText(
                text = bioAnnotated,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = if (bioExpanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                onClick = { offset ->
                    bioAnnotated.getStringAnnotations("npub_hex", offset, offset)
                        .firstOrNull()?.let { annotation ->
                            onProfileClick(annotation.item)
                            return@ClickableText
                        }
                    onBioToggle()
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (!bioExpanded && (about.length > 200 || about.count { it == '\n' } > 5)) {
                Text(
                    "Show more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onBioToggle() }
                )
            }
        }

        // ── Links row ──
        val hasWebsite = author.website?.isNotBlank() == true
        if (hasWebsite) {
            Spacer(Modifier.height(8.dp))
            author.website?.takeIf { it.isNotBlank() }?.let { url ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        uriHandler.openUri(if (url.startsWith("http")) url else "https://$url")
                    }
                ) {
                    Icon(Icons.Default.Link, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        url.removePrefix("https://").removePrefix("http://").removeSuffix("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Stats row ──
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatItem(value = notesCount.toString(), label = "Notes")
            StatItem(
                value = when {
                    followingCount != null -> formatCount(followingCount)
                    isLoadingCounts -> "\u2026"
                    else -> "\u2013"
                },
                label = "Following"
            )
            StatItem(
                value = when {
                    followerCount != null -> formatCount(followerCount)
                    isLoadingCounts -> "\u2026"
                    else -> "\u2013"
                },
                label = "Followers"
            )
        }

        // Badges are displayed in the banner area (ProfileBanner)

        Spacer(Modifier.height(8.dp))
    }
}

// ─── Badge Row (one-line with overflow expand) ───────────────────────────────

@Composable
private fun ProfileBadgesRow(
    badges: List<social.mycelium.android.repository.social.BadgeRepository.Badge>,
) {
    val badgeSize = 30.dp
    val badgeSpacing = 6.dp
    var expanded by remember { mutableStateOf(false) }

    // Measure available width to determine how many badges fit in one line
    val density = LocalDensity.current
    var rowWidthPx by remember { mutableIntStateOf(0) }
    val badgeSizePx = with(density) { badgeSize.toPx() }
    val spacingPx = with(density) { badgeSpacing.toPx() }
    // Reserve space for the "+N" overflow chip (~40dp)
    val overflowChipWidthPx = with(density) { 40.dp.toPx() }

    val maxVisible = remember(rowWidthPx, badges.size) {
        if (rowWidthPx <= 0) badges.size
        else {
            val available = rowWidthPx.toFloat()
            var count = 0
            var used = 0f
            for (i in badges.indices) {
                val next = if (i == 0) badgeSizePx else spacingPx + badgeSizePx
                // If there are more badges after this one, reserve space for overflow chip
                val needsOverflow = i < badges.size - 1
                val reserveForOverflow = if (needsOverflow) overflowChipWidthPx + spacingPx else 0f
                if (used + next + reserveForOverflow <= available) {
                    used += next
                    count++
                } else break
            }
            count.coerceAtLeast(1)
        }
    }

    val showOverflow = !expanded && badges.size > maxVisible
    val visibleBadges = if (expanded) badges else badges.take(maxVisible)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> rowWidthPx = coords.size.width },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(badgeSpacing)
    ) {
        visibleBadges.forEach { badge ->
            BadgeThumbnail(badge = badge, size = badgeSize)
        }
        if (showOverflow) {
            val remaining = badges.size - maxVisible
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .height(badgeSize)
                    .clickable { expanded = true }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "+$remaining",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BadgeThumbnail(
    badge: social.mycelium.android.repository.social.BadgeRepository.Badge,
    size: androidx.compose.ui.unit.Dp,
) {
    val imageUrl = badge.displayImageUrl
    if (imageUrl != null) {
        AsyncImage(
            model = imageUrl,
            contentDescription = badge.name ?: "Badge",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        // Fallback: show medal icon
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.MilitaryTech,
                    contentDescription = badge.name ?: "Badge",
                    modifier = Modifier.size(size * 0.6f),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─── Media Grid (lazy 2-column square thumbnails) ────────────────────────────

@Composable
private fun LazyMediaGrid(
    notes: List<Note>,
    hasMore: Boolean = true,
    isLoadingMore: Boolean = false,
    isProfileLoading: Boolean = false,
    onLoadMore: () -> Unit = {},
    onNoteClick: (Note) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit,
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> }
) {
    val mediaEntries = remember(notes) {
        notes.flatMap { note ->
            note.mediaUrls.map { url -> url to note }
        }
    }
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 80.dp)
    ) {
        gridItems(mediaEntries, key = { (url, note) -> "media_${note.id}_$url" }) { (url, note) ->
            val isVideo = url.contains(".mp4", true) || url.contains(".webm", true) ||
                url.contains(".mov", true) || url.contains(".m3u8", true)
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onNoteClick(note) },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = if (isVideo) {
                        coil.request.ImageRequest.Builder(context)
                            .data(url)
                            .decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                            .setParameter("videoFrameMillis", 1000L)
                            .crossfade(true)
                            .build()
                    } else url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Video",
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        ) {
                            if (isVideo) {
                                onVideoClick(listOf(url), 0)
                            } else {
                                // Open just this note's media, starting at the tapped image
                                val noteImages = note.mediaUrls.filter { u ->
                                    !u.contains(".mp4", true) && !u.contains(".webm", true) &&
                                    !u.contains(".mov", true) && !u.contains(".m3u8", true)
                                }
                                val idx = noteImages.indexOf(url).coerceAtLeast(0)
                                onOpenImageViewer(noteImages.ifEmpty { listOf(url) }, idx)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = "View fullscreen",
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                }
            }
        }
        // Load-more trigger for media grid
        if (mediaEntries.isNotEmpty() && hasMore && !isLoadingMore) {
            item(key = "media_load_more") {
                LaunchedEffect(Unit) { onLoadMore() }
            }
        }
        if (isProfileLoading || isLoadingMore) {
            item(key = "media_loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ─── NIP-19 Bio Parser ───────────────────────────────────────────────────────

/**
 * Parse bio text, replacing npub1... and nostr:npub1... with display names.
 * Returns an AnnotatedString with "npub_hex" annotations for clickable navigation.
 */
private fun parseBioWithNpubs(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    // Match nostr:npub1... or standalone npub1...
    val npubRegex = Regex("(?:nostr:)?(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{58})", RegexOption.IGNORE_CASE)
    var lastIndex = 0

    for (match in npubRegex.findAll(text)) {
        // Append text before this match
        if (match.range.first > lastIndex) {
            builder.append(text.substring(lastIndex, match.range.first))
        }

        val npubBech32 = match.groupValues[1]
        val hexKey = try {
            val (_, bytes, _) = com.example.cybin.nip19.Bech32.decodeBytes(npubBech32)
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }

        if (hexKey != null) {
            // Resolve display name from cache
            val author = social.mycelium.android.repository.cache.ProfileMetadataCache.getInstance()
                .resolveAuthor(hexKey)
            val displayText = if (author.displayName.endsWith("...") && author.displayName.length <= 12) {
                // Fallback: show truncated npub
                "@${npubBech32.take(12)}…"
            } else {
                "@${author.displayName}"
            }

            builder.pushStringAnnotation("npub_hex", hexKey)
            builder.pushStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = androidx.compose.ui.graphics.Color(0xFF6B8AFF),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            )
            builder.append(displayText)
            builder.pop() // style
            builder.pop() // annotation
        } else {
            // Failed to decode — just append raw text
            builder.append(match.value)
        }

        lastIndex = match.range.last + 1
    }

    // Append remaining text
    if (lastIndex < text.length) {
        builder.append(text.substring(lastIndex))
    }

    return builder.toAnnotatedString()
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Format a count for display: 1234 → "1.2k", 12345 → "12.3k", 1234567 → "1.2M" */
private fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 10_000 -> String.format("%.1fk", count / 1_000.0)
    count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
    else -> count.toString()
}

// ─── Stat chip ──────────────────────────────────────────────────────────────

@Composable
private fun StatItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ProfileScreenPreview() {
    val sampleAuthor = SampleData.sampleNotes[0].author
    val sampleNotes = SampleData.sampleNotes.take(3)

    ProfileScreen(
        author = sampleAuthor,
        authorNotes = sampleNotes,
        onBackClick = {},
        onNoteClick = {},
        onLike = {},
        onShare = {},
        onComment = {},
        onProfileClick = {}
    )
}
