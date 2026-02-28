package social.mycelium.android.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import social.mycelium.android.ui.components.cutoutPadding
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
import social.mycelium.android.ui.components.ModernSearchBar
import social.mycelium.android.repository.ProfileFeedRepository
import social.mycelium.android.repository.ZapType
import social.mycelium.android.ui.components.NoteCard
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

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
    countsForNote: (String) -> social.mycelium.android.repository.NoteCounts? = { null },
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    onRelayClick: (relayUrl: String) -> Unit = {},
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    accountNpub: String? = null,
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    onLoginClick: (() -> Unit)? = null,
    parentNoteForReply: (String) -> Note? = { null },
    timeGapIndex: Int? = null,
    perTabHasMore: Map<Int, Boolean> = emptyMap(),
    onSeeAllReactions: (Note) -> Unit = {},
    modifier: Modifier = Modifier
) {
    androidx.activity.compose.BackHandler { onBackClick() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topAppBarState)

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

    // Per-tab list states (preserve scroll per tab)
    val notesListState = rememberLazyListState()
    val repliesListState = rememberLazyListState()

    // Per-tab filtered notes
    val notesOnly = remember(authorNotes) { authorNotes.filter { !it.isReply } }
    val repliesOnly = remember(authorNotes) { authorNotes.filter { it.isReply } }
    val mediaOnly = remember(authorNotes) { authorNotes.filter { it.mediaUrls.isNotEmpty() } }

    // Per-tab hasMore (fallback to global hasMore)
    val notesHasMore = perTabHasMore[ProfileFeedRepository.TAB_NOTES] ?: hasMore
    val repliesHasMore = perTabHasMore[ProfileFeedRepository.TAB_REPLIES] ?: hasMore
    val mediaHasMore = perTabHasMore[ProfileFeedRepository.TAB_MEDIA] ?: hasMore

    // Bio expanded state
    var bioExpanded by rememberSaveable { mutableStateOf(false) }

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
                if (headerHeightPx <= 0f) return Velocity.Zero
                val current = headerOffsetPx.floatValue
                val mid = -headerHeightPx / 2f
                val target = if (current < mid) -headerHeightPx else 0f
                if (current != target) {
                    val anim = Animatable(current)
                    anim.animateTo(target, tween(200)) {
                        headerOffsetPx.floatValue = value
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(collapsingConnection)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Text(
                            text = author.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* TODO: more options */ }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
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
                            var showOlderNotes by remember { mutableStateOf(false) }
                            val visibleNotes = remember(notesOnly, notesGapIndex, showOlderNotes) {
                                if (notesGapIndex != null && !showOlderNotes) notesOnly.take(notesGapIndex)
                                else notesOnly
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
                                        isVisible = isPageVisible
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
                            LazyColumn(
                                state = repliesListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 80.dp)
                            ) {
                                items(repliesOnly, key = { "replies_${it.id}" }) { note ->
                                    ReplyWithParentContext(
                                        note = note,
                                        parentNote = note.replyToId?.let { parentNoteForReply(it) },
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
                                        isVisible = isPageVisible
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
                // Banner + Identity — draggable so touches on the header collapse it
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { coords ->
                            headerHeightPx = coords.size.height.toFloat()
                        }
                        .nestedScroll(collapsingConnection)
                        .verticalScroll(rememberScrollState())
                ) {
                    ProfileBanner(
                        author = author,
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
                        onMessageClick = onMessageClick,
                        bioExpanded = bioExpanded,
                        onBioToggle = { bioExpanded = !bioExpanded }
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
}

// ─── Time-gap detection ──────────────────────────────────────────────────────

/** Detect the first index where a large time gap (>7 days) exists between consecutive notes. */
internal fun detectTimeGapIndex(
    sortedNotes: List<Note>,
    thresholdMs: Long = 7L * 24 * 3600 * 1000
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
    countsForNote: (String) -> social.mycelium.android.repository.NoteCounts?,
    onRelayClick: (String) -> Unit,
    accountNpub: String?,
    onSeeAllReactions: (Note) -> Unit,
    isVisible: Boolean = true,
) {
    val counts = countsForNote(note.id)
    NoteCard(
        isVisible = isVisible,
        note = note,
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
        isZapInProgress = isZapInProgress(note.id),
        isZapped = isZapped(note.id),
        myZappedAmount = myZappedAmountForNote(note.id),
        overrideReplyCount = overrideReplyCountForNote(note.id),
        overrideZapCount = counts?.zapCount,
        overrideZapTotalSats = counts?.zapTotalSats,
        overrideReactions = counts?.reactions,
        overrideReactionAuthors = counts?.reactionAuthors,
        overrideZapAuthors = counts?.zapAuthors,
        overrideZapAmountByAuthor = counts?.zapAmountByAuthor,
        overrideCustomEmojiUrls = counts?.customEmojiUrls,
        onRelayClick = onRelayClick,
        accountNpub = accountNpub,
        onSeeAllReactions = { onSeeAllReactions(note) },
        modifier = Modifier.fillMaxWidth()
    )
}

/** Footer items for profile feed lists: load-more trigger, loading spinner, end-of-feed. */
private fun LazyListScope.profileFeedFooter(
    tabNotes: List<Note>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isProfileLoading: Boolean,
    onLoadMore: () -> Unit
) {
    if (tabNotes.isNotEmpty() && hasMore && !isLoadingMore) {
        item(key = "load_more_trigger") {
            LaunchedEffect(Unit) { onLoadMore() }
        }
    }
    if (isProfileLoading || isLoadingMore) {
        item(key = "loading_indicator") {
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
        } else if (note.replyToId != null) {
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = author.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
    onMessageClick: () -> Unit,
    bioExpanded: Boolean,
    onBioToggle: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = "Verified",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
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
                val subtitle = author.nip05?.takeIf { it.isNotBlank() } ?: "@${author.username}"
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (author.nip05 != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action buttons
            Spacer(Modifier.width(8.dp))
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

        // ── Action row: Follow / Zap / Message ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isFollowing) {
                OutlinedButton(
                    onClick = onFollowClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Following", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Button(
                    onClick = onFollowClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Follow", style = MaterialTheme.typography.labelMedium)
                }
            }
            // Zap button
            if (author.lud16?.isNotBlank() == true) {
                OutlinedButton(
                    onClick = { /* TODO: zap profile */ },
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.ElectricBolt,
                        null,
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFFB74D)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Zap", style = MaterialTheme.typography.labelMedium)
                }
            }
            OutlinedButton(
                onClick = onMessageClick,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Outlined.Mail, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("DM", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ── Bio ──
        author.about?.takeIf { it.isNotBlank() }?.let { about ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBioToggle() }
            )
            if (!bioExpanded && about.length > 150) {
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
        val hasLn = author.lud16?.isNotBlank() == true
        if (hasWebsite || hasLn) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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
                author.lud16?.takeIf { it.isNotBlank() }?.let { ln ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ElectricBolt, null, Modifier.size(14.dp), tint = Color(0xFFFFB74D))
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
        Spacer(Modifier.height(8.dp))
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
