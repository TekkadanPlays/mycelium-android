package social.mycelium.android.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.cybin.nip19.toNpub
import kotlinx.coroutines.launch
import social.mycelium.android.R
import social.mycelium.android.data.Author
import social.mycelium.android.data.Note
import social.mycelium.android.data.SampleData
import social.mycelium.android.ui.components.ModernSearchBar
import social.mycelium.android.repository.ZapType
import social.mycelium.android.ui.components.NoteCard
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems

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
    onLoadMore: () -> Unit = {},
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
    listState: LazyListState = rememberLazyListState(),
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    onLoginClick: (() -> Unit)? = null,
    onSeeAllReactions: (Note) -> Unit = {},
    modifier: Modifier = Modifier
) {
    androidx.activity.compose.BackHandler { onBackClick() }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    val coroutineScope = rememberCoroutineScope()

    // Tab state
    var selectedTab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(initialPage = 0) { profileTabs.size }
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedTab) selectedTab = pagerState.currentPage
    }

    // Per-tab filtered notes
    val notesOnly = remember(authorNotes) { authorNotes.filter { !it.isReply } }
    val repliesOnly = remember(authorNotes) { authorNotes.filter { it.isReply } }
    val mediaOnly = remember(authorNotes) { authorNotes.filter { it.mediaUrls.isNotEmpty() } }

    // Bio expanded state
    var bioExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
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
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // ═══ Banner + Avatar ═══
            item(key = "profile_banner") {
                ProfileBanner(
                    author = author,
                    onBannerClick = { url ->
                        if (url != null) onOpenImageViewer(listOf(url), 0)
                    },
                    onAvatarClick = { url ->
                        if (url != null) onOpenImageViewer(listOf(url), 0)
                    }
                )
            }

            // ═══ Identity + Actions ═══
            item(key = "profile_identity") {
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

            // ═══ Tab bar (sticky) ═══
            stickyHeader(key = "profile_tabs") {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab,
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
                                selected = selectedTab == index,
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

            // ═══ Tab content ═══
            val tabNotes = when (selectedTab) {
                0 -> notesOnly
                1 -> repliesOnly
                2 -> mediaOnly
                else -> notesOnly
            }

            if (tabNotes.isEmpty()) {
                item(key = "empty_tab") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                when (selectedTab) {
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
                                "No ${profileTabs[selectedTab].label.lowercase()} yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── Media tab: 2-column square grid ──
            if (selectedTab == 2 && mediaOnly.isNotEmpty()) {
                item(key = "media_grid") {
                    MediaGrid(
                        notes = mediaOnly,
                        onNoteClick = onNoteClick,
                        onOpenImageViewer = onOpenImageViewer
                    )
                }
            }

            // ── Notes and Replies tabs: standard NoteCard list ──
            if (selectedTab != 2) {
                items(tabNotes, key = { "tab${selectedTab}_${it.id}" }) { note ->
                    val counts = countsForNote(note.id)
                    NoteCard(
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
            }

            // Load more trigger
            if (tabNotes.isNotEmpty() && hasMore && !isLoadingMore) {
                item(key = "load_more_trigger") {
                    LaunchedEffect(Unit) { onLoadMore() }
                }
            }

            // Loading indicator
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

            // End of feed
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

// ─── Media Grid (2-column square thumbnails) ────────────────────────────────

@Composable
private fun MediaGrid(
    notes: List<Note>,
    onNoteClick: (Note) -> Unit,
    onOpenImageViewer: (List<String>, Int) -> Unit
) {
    // Flatten notes into (url, note) pairs for grid cells
    val mediaEntries = remember(notes) {
        notes.flatMap { note ->
            note.mediaUrls.map { url -> url to note }
        }
    }
    val columns = 2
    val rows = (mediaEntries.size + columns - 1) / columns

    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rows) { rowIdx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                repeat(columns) { colIdx ->
                    val idx = rowIdx * columns + colIdx
                    if (idx < mediaEntries.size) {
                        val (url, note) = mediaEntries[idx]
                        val isVideo = url.contains(".mp4", true) || url.contains(".webm", true) ||
                            url.contains(".mov", true) || url.contains(".m3u8", true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { onNoteClick(note) },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Video badge
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
                            // Fullscreen magnifier icon — bottom-end
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
                                        val allUrls = notes.flatMap { it.mediaUrls }
                                        onOpenImageViewer(allUrls, allUrls.indexOf(url).coerceAtLeast(0))
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
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (rowIdx < rows - 1) Spacer(Modifier.height(2.dp))
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
