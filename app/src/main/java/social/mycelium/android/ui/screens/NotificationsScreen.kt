package social.mycelium.android.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import social.mycelium.android.data.Author
import social.mycelium.android.data.NotificationData
import social.mycelium.android.data.NotificationType
import social.mycelium.android.data.Note
import social.mycelium.android.repository.NotificationsRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.ui.components.ProfilePicture
import social.mycelium.android.utils.normalizeAuthorIdForCache
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import java.util.Calendar

// ─── Time group labels ───────────────────────────────────────────────────────

private enum class TimeGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    OLDER("Older")
}

private fun timeGroupFor(timestampMs: Long): TimeGroup {
    val cal = Calendar.getInstance()
    val nowDay = cal.get(Calendar.DAY_OF_YEAR)
    val nowYear = cal.get(Calendar.YEAR)
    cal.timeInMillis = timestampMs
    val day = cal.get(Calendar.DAY_OF_YEAR)
    val year = cal.get(Calendar.YEAR)
    if (year == nowYear) {
        val diff = nowDay - day
        return when {
            diff == 0 -> TimeGroup.TODAY
            diff == 1 -> TimeGroup.YESTERDAY
            diff in 2..6 -> TimeGroup.THIS_WEEK
            else -> TimeGroup.OLDER
        }
    }
    return TimeGroup.OLDER
}

// ─── Live timestamp formatting ───────────────────────────────────────────────

/**
 * Shared screen-level time tick. Call once per screen, pass the value to items.
 * Avoids N LaunchedEffect coroutines for N visible notification items.
 */
@Composable
private fun rememberTimeTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun formatTimeAgo(timestampMs: Long, now: Long): String {
    val diff = now - timestampMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        diff < 604_800_000 -> "${diff / 86_400_000}d"
        else -> "${diff / 604_800_000}w"
    }
}

// ─── Tab definitions ─────────────────────────────────────────────────────────

private data class NotifTab(
    val label: String,
    val icon: @Composable () -> Unit,
    val filter: (NotificationData) -> Boolean
)

// ─── Main Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onOpenThreadForRootId: (rootNoteId: String, replyKind: Int, replyNoteId: String?, targetNote: Note?) -> Unit = { _, _, _, _ -> },
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Per-tab scroll states so each tab remembers its own position
    val tabListStates = remember { List(13) { LazyListState() } }
    val currentListState = tabListStates.getOrElse(selectedTabIndex) { tabListStates[0] }
    val coroutineScope = rememberCoroutineScope()
    val allNotifications by NotificationsRepository.notifications.collectAsState()
    val seenIds by NotificationsRepository.seenIds.collectAsState()
    val myPubkeyHex = NotificationsRepository.getMyPubkeyHex()
    val timeTick = rememberTimeTick()

    // Batch-request profiles for all notification and note authors
    val profileCache = ProfileMetadataCache.getInstance()
    val cacheRelayUrls = NotificationsRepository.getCacheRelayUrls()
    LaunchedEffect(allNotifications, cacheRelayUrls) {
        if (cacheRelayUrls.isEmpty()) return@LaunchedEffect
        val authorIds = allNotifications.flatMap { n ->
            buildList {
                n.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
                n.actorPubkeys.forEach { add(normalizeAuthorIdForCache(it)) }
                (n.targetNote ?: n.note)?.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
            }
        }.distinct().filter { it.isNotBlank() }
        if (authorIds.isNotEmpty()) profileCache.requestProfiles(authorIds, cacheRelayUrls)
    }

    // Tab definitions (keyed on myPubkeyHex so Threads filter captures correct value)
    val tabs = remember(myPubkeyHex) {
        listOf(
            NotifTab("All", { Icon(Icons.Default.Notifications, null, modifier = Modifier.size(18.dp)) }) { true },
            NotifTab("Replies", { Icon(Icons.AutoMirrored.Outlined.Reply, null, modifier = Modifier.size(18.dp)) }) {
                it.type == NotificationType.REPLY && (it.replyKind == null || it.replyKind == 1)
            },
            NotifTab("Threads", { Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(18.dp)) }) {
                // After enrichment, replyKind is set to 11 for kind-1111 replies targeting our kind-11 threads
                it.type == NotificationType.REPLY && it.replyKind == 11
            },
            NotifTab("Comments", { Icon(Icons.Outlined.ChatBubble, null, modifier = Modifier.size(18.dp)) }) {
                it.type == NotificationType.REPLY && (it.replyKind == 1111 || it.replyKind == 11)
            },
            NotifTab("Likes", { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.LIKE },
            NotifTab("Zaps", { Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.ZAP },
            NotifTab("Reposts", { Icon(Icons.Default.Repeat, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.REPOST },
            NotifTab("Mentions", { Icon(Icons.Outlined.AlternateEmail, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.MENTION },
            NotifTab("Polls", { Icon(Icons.Outlined.HowToVote, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.POLL_VOTE },
            NotifTab("Quotes", { Icon(Icons.Default.FormatQuote, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.QUOTE },
            NotifTab("Highlights", { Icon(Icons.Outlined.FormatQuote, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.HIGHLIGHT },
            NotifTab("Reports", { Icon(Icons.Outlined.Flag, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.REPORT },
            NotifTab("Badges", { Icon(Icons.Default.MilitaryTech, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.BADGE_AWARD }
        )
    }

    // Pre-compute unseen counts per tab once per data change (not per frame).
    // Previously each tab row recomputed O(notifications) per tab per recomposition.
    val tabUnseenCounts = remember(allNotifications, seenIds) {
        IntArray(tabs.size) { tabIdx ->
            val f = tabs[tabIdx].filter
            allNotifications.count { f(it) && it.id !in seenIds }
        }
    }

    // Pager state synced with external selectedTabIndex
    val pagerState = rememberPagerState(initialPage = selectedTabIndex) { tabs.size }
    // Sync: external tab selection -> pager
    LaunchedEffect(selectedTabIndex) {
        if (pagerState.currentPage != selectedTabIndex) pagerState.animateScrollToPage(selectedTabIndex)
    }
    // Sync: pager swipe -> external tab selection (use settledPage to avoid firing every animation frame)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != selectedTabIndex) onTabSelected(page)
        }
    }
    // Scroll to top when pager settles on a new page (after animation completes)
    LaunchedEffect(pagerState) {
        var previousPage = pagerState.settledPage
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != previousPage) {
                tabListStates.getOrElse(page) { tabListStates[0] }.scrollToItem(0)
                previousPage = page
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    BackHandler { onBackClick() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Text(
                            text = "Notifications",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val unseenInTab = tabUnseenCounts.getOrElse(selectedTabIndex) { 0 }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "Notification settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                if (unseenInTab > 0) {
                                    if (selectedTabIndex == 0) {
                                        NotificationsRepository.markAllAsSeen()
                                    } else {
                                        val tabFilter = tabs[selectedTabIndex].filter
                                        allNotifications.filter(tabFilter).forEach { NotificationsRepository.markAsSeen(it.id) }
                                    }
                                }
                            },
                            enabled = unseenInTab > 0
                        ) {
                            Icon(
                                Icons.Outlined.DoneAll,
                                contentDescription = "Mark all as read",
                                tint = if (unseenInTab > 0) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Scrollable tab row
                @Suppress("DEPRECATION")
                ScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    divider = {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val unseenForTab = tabUnseenCounts.getOrElse(index) { 0 }
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        tab.icon()
                                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                                        // Reserve space for dot so layout doesn't shift
                                        Spacer(modifier = Modifier.width(if (unseenForTab > 0) 8.dp else 0.dp))
                                    }
                                    if (unseenForTab > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 2.dp, y = (-2).dp)
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // Pre-compute filtered + grouped lists per tab outside the pager.
        // This avoids re-filtering inside each page lambda on every recomposition.
        val filteredByTab = remember(allNotifications) {
            Array(tabs.size) { tabIdx -> allNotifications.filter(tabs[tabIdx].filter) }
        }
        val groupedByTab = remember(filteredByTab) {
            Array(tabs.size) { tabIdx -> filteredByTab[tabIdx].groupBy { timeGroupFor(it.sortTimestamp) } }
        }

        // Threads tab (index 2): condensed per-thread grouping
        val threadsTabIndex = 2
        val threadGroups = remember(filteredByTab) {
            val threadsNotifs = filteredByTab.getOrElse(threadsTabIndex) { emptyList() }
            threadsNotifs
                .groupBy { it.rootNoteId ?: it.id }
                .map { (rootId, notifs) ->
                    val sorted = notifs.sortedByDescending { it.sortTimestamp }
                    val uniqueAuthors = notifs.mapNotNull { it.author }.distinctBy { it.id }
                    val latestTs = sorted.first().sortTimestamp
                    val targetNote = notifs.firstNotNullOfOrNull { it.targetNote }
                    ThreadSummary(
                        rootNoteId = rootId,
                        replyCount = notifs.size,
                        latestTimestamp = latestTs,
                        replierAuthors = uniqueAuthors.take(5),
                        latestReply = sorted.first(),
                        targetNote = targetNote,
                        allNotifIds = notifs.map { it.id },
                        replyKind = sorted.first().replyKind ?: 11,
                        targetNoteFromAny = notifs.firstNotNullOfOrNull { it.targetNote }
                    )
                }
                .sortedByDescending { it.latestTimestamp }
        }

        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(paddingValues),
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            val filteredNotifications = filteredByTab[page]
            val grouped = groupedByTab[page]
            val pageListState = tabListStates.getOrElse(page) { tabListStates[0] }

            LazyColumn(
                state = pageListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (filteredNotifications.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = if (allNotifications.isEmpty()) "No notifications yet" else "No ${tabs[page].label.lowercase()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (page == threadsTabIndex) {
                    // ── Condensed per-thread view ──
                    items(
                        items = threadGroups,
                        key = { "thread:${it.rootNoteId}" }
                    ) { summary ->
                        ThreadSummaryCard(
                            summary = summary,
                            seenIds = seenIds,
                            timeTick = timeTick,
                            onProfileClick = onProfileClick,
                            onClick = {
                                summary.allNotifIds.forEach { NotificationsRepository.markAsSeen(it) }
                                onOpenThreadForRootId(
                                    summary.rootNoteId,
                                    summary.replyKind,
                                    summary.latestReply.replyNoteId,
                                    summary.targetNote
                                )
                            }
                        )
                    }
                } else {
                    // ── Default time-grouped view for all other tabs ──
                    for (group in TimeGroup.entries) {
                        val items = grouped[group] ?: continue
                        stickyHeader(key = "header_${group.name}") {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            ) {
                                Text(
                                    text = group.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        }

                        items(
                            items = items,
                            key = { it.id }
                        ) { notification ->
                            val isSeen = notification.id in seenIds
                            when {
                                notification.type == NotificationType.POLL_VOTE -> {
                                    PollVoteNotificationCard(
                                        notification = notification,
                                        isSeen = isSeen,
                                        timeTick = timeTick,
                                        onProfileClick = onProfileClick,
                                        onClick = {
                                            NotificationsRepository.markAsSeen(notification.id)
                                            val target = notification.targetNote
                                            if (target != null) onNoteClick(target)
                                        }
                                    )
                                }
                                notification.type in listOf(
                                    NotificationType.LIKE,
                                    NotificationType.REPOST,
                                    NotificationType.ZAP,
                                    NotificationType.BADGE_AWARD
                                ) -> {
                                    CompactNotificationRow(
                                        notification = notification,
                                        isSeen = isSeen,
                                        timeTick = timeTick,
                                        onProfileClick = onProfileClick,
                                        onClick = {
                                            NotificationsRepository.markAsSeen(notification.id)
                                            val target = notification.targetNote ?: notification.note
                                            if (target != null && target.rootNoteId != null) {
                                                onOpenThreadForRootId(target.rootNoteId!!, 1, null, target)
                                            } else if (target != null) {
                                                onNoteClick(target)
                                            }
                                        }
                                    )
                                }
                                else -> {
                                    FullNotificationCard(
                                        notification = notification,
                                        isSeen = isSeen,
                                        timeTick = timeTick,
                                        onProfileClick = onProfileClick,
                                        onClick = {
                                            NotificationsRepository.markAsSeen(notification.id)
                                            when {
                                                notification.type == NotificationType.REPLY && notification.rootNoteId != null ->
                                                    onOpenThreadForRootId(notification.rootNoteId!!, notification.replyKind ?: 1, notification.replyNoteId, notification.targetNote)
                                                notification.type == NotificationType.MENTION && notification.note != null ->
                                                    onNoteClick(notification.note!!)
                                                notification.targetNote != null -> onNoteClick(notification.targetNote!!)
                                                notification.note != null -> onNoteClick(notification.note!!)
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
    }
}

// ─── Shared card shell (unseen accent bar, background, divider) ──────────────

@Composable
private fun NotificationCardShell(
    isSeen: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val accentColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (!isSeen) Modifier.drawBehind {
                    drawRect(
                        color = accentColor,
                        topLeft = androidx.compose.ui.geometry.Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                    )
                } else Modifier
            ),
        color = if (!isSeen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.10f) else Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            content()
        }
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}

// ─── Horizontal avatar row (shared across all card types) ────────────────────

private val placeholderAuthor = Author(
    id = "",
    username = "...",
    displayName = "…",
    avatarUrl = null,
    isVerified = false
)

@Composable
private fun AvatarRow(
    authors: List<Author>,
    totalCount: Int,
    onProfileClick: (String) -> Unit,
    avatarSize: Int = 28
) {
    if (authors.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-6).dp)
    ) {
        authors.take(6).forEach { author ->
            ProfilePicture(
                author = author,
                size = avatarSize.dp,
                onClick = { onProfileClick(author.id) }
            )
        }
        if (totalCount > 6) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "+${totalCount - 6}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Compact row for likes / zaps / reposts ──────────────────────────────────

@Composable
private fun CompactNotificationRow(
    notification: NotificationData,
    isSeen: Boolean,
    timeTick: Long,
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit
) {
    val profileCache = ProfileMetadataCache.getInstance()
    val timeAgo = formatTimeAgo(notification.sortTimestamp, timeTick)

    // Live profile resolution for single author
    val authorId = notification.author?.id ?: ""
    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
    var displayAuthor by remember(authorId) {
        mutableStateOf(profileCache.getAuthor(cacheKey) ?: notification.author ?: placeholderAuthor)
    }
    LaunchedEffect(cacheKey) {
        if (cacheKey.isBlank()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it == cacheKey }
            .collect { displayAuthor = profileCache.getAuthor(cacheKey) ?: displayAuthor }
    }

    val emoji = notification.reactionEmoji
    val isCustomEmoji = emoji != null && emoji != "❤️" && emoji != "+"
    val typeIcon = when (notification.type) {
        NotificationType.LIKE -> Icons.Default.Favorite
        NotificationType.REPOST -> Icons.Default.Repeat
        NotificationType.ZAP -> Icons.Default.Bolt
        NotificationType.BADGE_AWARD -> Icons.Default.MilitaryTech
        else -> Icons.Default.Notifications
    }
    val typeColor = when (notification.type) {
        NotificationType.LIKE -> Color(0xFFE91E63)
        NotificationType.REPOST -> Color(0xFF4CAF50)
        NotificationType.ZAP -> Color(0xFFF59E0B)
        NotificationType.BADGE_AWARD -> Color(0xFF9C27B0)
        else -> MaterialTheme.colorScheme.primary
    }

    val actionText = remember(notification.type, notification.reactionEmoji, notification.zapAmountSats, notification.badgeName) {
        when (notification.type) {
            NotificationType.LIKE -> when {
                emoji == "❤️" || emoji == "+" || emoji == null -> "liked your post"
                else -> "reacted to your post"
            }
            NotificationType.REPOST -> "reposted your post"
            NotificationType.ZAP -> {
                if (notification.zapAmountSats > 0) "zapped ${formatSatsCompact(notification.zapAmountSats)} sats"
                else "zapped your post"
            }
            NotificationType.BADGE_AWARD -> {
                val bn = notification.badgeName
                if (bn != null) "awarded \"$bn\" badge" else "awarded a badge"
            }
            else -> "interacted"
        }
    }

    NotificationCardShell(isSeen = isSeen, onClick = onClick) {
        // Single row: avatar + name + action icon/emoji + action text + zap badge + timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                author = displayAuthor,
                size = 32.dp,
                onClick = { notification.author?.id?.let { onProfileClick(it) } }
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Name + action
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayAuthor.displayName.ifBlank { displayAuthor.id.take(8) + "…" },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(4.dp))
                    // Type icon or emoji
                    if (isCustomEmoji && emoji != null) {
                        val emojiUrls = remember(notification.customEmojiUrls, notification.customEmojiUrl, emoji) {
                            val urls = notification.customEmojiUrls.toMutableMap()
                            if (notification.customEmojiUrl != null) urls[emoji] = notification.customEmojiUrl
                            urls.toMap()
                        }
                        social.mycelium.android.ui.components.ReactionEmoji(
                            emoji = emoji,
                            customEmojiUrls = emojiUrls,
                            fontSize = 14.sp,
                            imageSize = 16.dp,
                            modifier = Modifier.widthIn(min = 16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = typeIcon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = typeColor
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = actionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                // Note preview snippet
                val rawPreview = notification.targetNote?.content ?: notification.note?.content
                val previewText = remember(rawPreview) {
                    rawPreview?.replace(Regex("nostr:(nevent1|note1|nprofile1|npub1|naddr1)[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+", RegexOption.IGNORE_CASE), "")
                        ?.replace(Regex("\\s+"), " ")?.trim()
                }
                if (!previewText.isNullOrBlank()) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            // Zap amount badge
            if (notification.type == NotificationType.ZAP && notification.zapAmountSats > 0) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, null, Modifier.size(12.dp), tint = Color(0xFFF59E0B))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatSatsCompact(notification.zapAmountSats),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF59E0B)
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            // Badge thumbnail
            if (notification.type == NotificationType.BADGE_AWARD && notification.badgeImageUrl != null) {
                coil.compose.AsyncImage(
                    model = notification.badgeImageUrl,
                    contentDescription = notification.badgeName ?: "Badge",
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ─── Full card for replies / mentions ────────────────────────────────────────

@Composable
private fun FullNotificationCard(
    notification: NotificationData,
    isSeen: Boolean,
    timeTick: Long,
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit
) {
    val profileCache = ProfileMetadataCache.getInstance()
    val authorId = notification.author?.id ?: ""
    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
    var displayAuthor by remember(authorId) {
        mutableStateOf(profileCache.getAuthor(cacheKey) ?: notification.author ?: placeholderAuthor)
    }

    // Build the set of all relevant pubkeys for this card (author + target note author)
    val relevantPubkeys = remember(cacheKey, notification.targetNote?.author?.id, notification.note?.author?.id) {
        buildSet {
            if (cacheKey.isNotBlank()) add(cacheKey)
            notification.targetNote?.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
            notification.note?.author?.id?.let { add(normalizeAuthorIdForCache(it)) }
        }
    }

    // Single collector for all relevant profiles instead of two separate collectors
    var profileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(relevantPubkeys) {
        if (relevantPubkeys.isEmpty()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it in relevantPubkeys }
            .collect {
                if (it == cacheKey) displayAuthor = profileCache.getAuthor(cacheKey) ?: displayAuthor
                profileRevision++
            }
    }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    val timeAgo = formatTimeAgo(notification.sortTimestamp, timeTick)
    val typeColor = when (notification.type) {
        NotificationType.REPLY -> MaterialTheme.colorScheme.secondary
        NotificationType.MENTION -> MaterialTheme.colorScheme.tertiary
        NotificationType.HIGHLIGHT -> Color(0xFF9C27B0)
        NotificationType.REPORT -> Color(0xFFF44336)
        else -> MaterialTheme.colorScheme.primary
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = remember(linkColor) { SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }

    val typeLabel = when (notification.type) {
        NotificationType.REPLY -> when (notification.replyKind) {
            11 -> "replied to your thread"
            1111 -> "commented"
            else -> "replied"
        }
        NotificationType.QUOTE -> "quoted your note"
        NotificationType.HIGHLIGHT -> "highlighted"
        NotificationType.REPORT -> "reported"
        else -> "mentioned you"
    }
    val typeIcon = when (notification.type) {
        NotificationType.REPLY -> Icons.AutoMirrored.Outlined.Reply
        NotificationType.QUOTE -> Icons.Default.FormatQuote
        NotificationType.HIGHLIGHT -> Icons.Outlined.FormatQuote
        NotificationType.REPORT -> Icons.Outlined.Flag
        else -> Icons.Outlined.AlternateEmail
    }

    NotificationCardShell(isSeen = isSeen, onClick = onClick) {
        // Row 1: type icon + action text + timestamp (same pattern as compact)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = typeIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = typeColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(6.dp))

        // Row 2: author avatar + name (full width, no indent on content below)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(
                author = displayAuthor,
                size = 24.dp,
                onClick = { notification.author?.id?.let { onProfileClick(it) } }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayAuthor.displayName.ifBlank { displayAuthor.id.take(8) + "…" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Row 3: reply/quote content — FULL WIDTH, no indentation
        notification.note?.let { replyNote ->
            NotificationReplyContent(
                replyNote = replyNote,
                profileCache = profileCache,
                onProfileClick = onProfileClick,
                onClick = onClick,
            )
        }

        // Row 4: target note context — FULL WIDTH
        notification.targetNote?.let { target ->
            Spacer(Modifier.height(6.dp))
            NotificationTargetPreview(
                target = target,
                profileCache = profileCache,
                profileRevision = profileRevision,
                linkStyle = linkStyle,
                onProfileClick = onProfileClick,
            )
        }
    }
}

/**
 * Rich inline reply/quote content: uses the same buildNoteContentWithInlinePreviews pipeline
 * as the feed, producing interleaved text, media groups, quoted note references, and link previews.
 * Supports markdown rendering and preserves line breaks.
 */
@Composable
private fun NotificationReplyContent(
    replyNote: Note,
    profileCache: ProfileMetadataCache,
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    if (replyNote.content.isBlank() && replyNote.mediaUrls.isEmpty()) return

    val uriHandler = LocalUriHandler.current
    val mediaUrlSet = remember(replyNote.mediaUrls) { replyNote.mediaUrls.toSet() }
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = remember(linkColor) { SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) }

    // Use the full rich content pipeline (same as feed cards)
    val contentIsMarkdown = remember(replyNote.content) { social.mycelium.android.ui.components.isMarkdown(replyNote.content) }
    val contentBlocks = remember(replyNote.content, mediaUrlSet, linkStyle) {
        social.mycelium.android.utils.buildNoteContentWithInlinePreviews(
            content = replyNote.content,
            mediaUrls = mediaUrlSet,
            urlPreviews = replyNote.urlPreviews,
            linkStyle = linkStyle,
            profileCache = profileCache,
            emojiUrls = social.mycelium.android.utils.extractEmojiUrls(replyNote.tags)
        )
    }

    Spacer(Modifier.height(4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        contentBlocks.forEach { block ->
            when (block) {
                is social.mycelium.android.utils.NoteContentBlock.Content -> {
                    val annotated = block.annotated
                    if (annotated.isNotEmpty()) {
                        if (contentIsMarkdown) {
                            social.mycelium.android.ui.components.MarkdownNoteContent(
                                content = annotated.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                ),
                                onProfileClick = onProfileClick,
                                onNoteClick = { onClick() },
                                onUrlClick = { url -> uriHandler.openUri(url) }
                            )
                        } else {
                            social.mycelium.android.ui.components.ClickableNoteContent(
                                text = annotated,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 20.sp
                                ),
                                maxLines = 12,
                                modifier = Modifier,
                                emojiUrls = block.emojiUrls,
                                onClick = { offset ->
                                    val profile = annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                                    val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                                    val naddr = annotated.getStringAnnotations(tag = "NADDR", start = offset, end = offset).firstOrNull()
                                    when {
                                        profile != null -> onProfileClick(profile.item)
                                        url != null -> uriHandler.openUri(url.item)
                                        naddr != null -> uriHandler.openUri(naddr.item)
                                        else -> onClick()
                                    }
                                }
                            )
                        }
                    }
                }
                is social.mycelium.android.utils.NoteContentBlock.MediaGroup -> {
                    val mediaList = block.urls.take(4)
                    if (mediaList.isNotEmpty()) {
                        mediaList.forEach { url ->
                            if (social.mycelium.android.utils.UrlDetector.isVideoUrl(url)) {
                                // Video thumbnail placeholder
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Video",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                val ratio = remember(url) {
                                    social.mycelium.android.utils.MediaAspectRatioCache.get(url) ?: (16f / 9f)
                                }
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(ratio.coerceIn(0.5f, 2.5f))
                                        .heightIn(max = 220.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                    }
                }
                is social.mycelium.android.utils.NoteContentBlock.Preview -> {
                    social.mycelium.android.ui.components.UrlPreviewCard(
                        previewInfo = block.previewInfo,
                        onUrlClick = { url -> uriHandler.openUri(url) },
                        onUrlLongClick = { }
                    )
                }
                is social.mycelium.android.utils.NoteContentBlock.QuotedNote -> {
                    // Inline quoted note reference — compact clickable link
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClick() }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FormatQuote,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "View quoted note \u203A",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                is social.mycelium.android.utils.NoteContentBlock.EmojiPack -> {
                    social.mycelium.android.ui.components.EmojiPackGrid(
                        author = block.author,
                        dTag = block.dTag,
                        relayHints = block.relayHints
                    )
                }
                is social.mycelium.android.utils.NoteContentBlock.Article -> {
                    social.mycelium.android.ui.components.EmbeddedArticlePreview(
                        author = block.author,
                        dTag = block.dTag,
                        relayHints = block.relayHints,
                        onNoteClick = { onClick() }
                    )
                }
                is social.mycelium.android.utils.NoteContentBlock.LiveEventReference -> {
                    Row(
                        modifier = Modifier.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "Live event \u203A",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        // Hashtags (if not already captured in content annotations)
        if (replyNote.hashtags.isNotEmpty()) {
            Text(
                text = replyNote.hashtags.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8FBC8F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Extracted target note preview: shows what was replied to with author context.
 * Splits ~70 lines from FullNotificationCard to reduce 6.5MB JIT.
 */
@Composable
private fun NotificationTargetPreview(
    target: Note,
    profileCache: ProfileMetadataCache,
    profileRevision: Int,
    linkStyle: SpanStyle,
    onProfileClick: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val targetAuthorId = remember(target.author.id) { normalizeAuthorIdForCache(target.author.id) }
    val targetAuthor = remember(targetAuthorId, profileRevision) {
        profileCache.getAuthor(targetAuthorId) ?: target.author
    }
    val targetMediaSet = remember(target.mediaUrls) { target.mediaUrls.toSet() }

    // Rich content pipeline — same as feed
    val contentIsMarkdown = remember(target.content) { social.mycelium.android.ui.components.isMarkdown(target.content) }
    val contentBlocks = remember(target.content, targetMediaSet, linkStyle) {
        social.mycelium.android.utils.buildNoteContentWithInlinePreviews(
            content = target.content,
            mediaUrls = targetMediaSet,
            urlPreviews = target.urlPreviews,
            linkStyle = linkStyle,
            profileCache = profileCache,
            emojiUrls = social.mycelium.android.utils.extractEmojiUrls(target.tags)
        )
    }

    // Subtle surface with left accent bar — visually distinct "referenced note"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    ) {
        Row(
            modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)
        ) {
            // Quote accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                    )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // Author row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfilePicture(
                        author = targetAuthor,
                        size = 16.dp,
                        onClick = { onProfileClick(target.author.id) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = targetAuthor.displayName.ifBlank { targetAuthor.id.take(8) + "…" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Rich content blocks
                val contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                contentBlocks.forEach { block ->
                    when (block) {
                        is social.mycelium.android.utils.NoteContentBlock.Content -> {
                            val annotated = block.annotated
                            if (annotated.isNotEmpty()) {
                                if (contentIsMarkdown) {
                                    social.mycelium.android.ui.components.MarkdownNoteContent(
                                        content = annotated.text,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = contentColor,
                                            lineHeight = 17.sp
                                        ),
                                        onProfileClick = onProfileClick,
                                        onNoteClick = { },
                                        onUrlClick = { url -> uriHandler.openUri(url) }
                                    )
                                } else {
                                    social.mycelium.android.ui.components.ClickableNoteContent(
                                        text = annotated,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = contentColor,
                                            lineHeight = 17.sp
                                        ),
                                        maxLines = 4,
                                        emojiUrls = block.emojiUrls,
                                        onClick = { offset ->
                                            val profile = annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                                            val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                                            when {
                                                profile != null -> onProfileClick(profile.item)
                                                url != null -> uriHandler.openUri(url.item)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        is social.mycelium.android.utils.NoteContentBlock.MediaGroup -> {
                            val mediaList = block.urls.take(3)
                            if (mediaList.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    mediaList.forEach { url ->
                                        if (social.mycelium.android.utils.UrlDetector.isVideoUrl(url)) {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Video",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        } else {
                                            AsyncImage(
                                                model = url,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        is social.mycelium.android.utils.NoteContentBlock.Preview -> {
                            social.mycelium.android.ui.components.UrlPreviewCard(
                                previewInfo = block.previewInfo,
                                onUrlClick = { url -> uriHandler.openUri(url) },
                                onUrlLongClick = { }
                            )
                        }
                        is social.mycelium.android.utils.NoteContentBlock.QuotedNote -> {
                            Row(
                                modifier = Modifier.padding(vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.FormatQuote,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "Quoted note",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                )
                            }
                        }
                        is social.mycelium.android.utils.NoteContentBlock.EmojiPack -> {
                            social.mycelium.android.ui.components.EmojiPackGrid(
                                author = block.author,
                                dTag = block.dTag,
                                relayHints = block.relayHints
                            )
                        }
                        is social.mycelium.android.utils.NoteContentBlock.Article -> {
                            social.mycelium.android.ui.components.EmbeddedArticlePreview(
                                author = block.author,
                                dTag = block.dTag,
                                relayHints = block.relayHints
                            )
                        }
                        is social.mycelium.android.utils.NoteContentBlock.LiveEventReference -> {
                            Text(
                                text = "Live event",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Poll vote notification card ─────────────────────────────────────────────

@Composable
private fun PollVoteNotificationCard(
    notification: NotificationData,
    isSeen: Boolean,
    timeTick: Long,
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit
) {
    val profileCache = ProfileMetadataCache.getInstance()
    val authorId = notification.author?.id ?: ""
    val cacheKey = remember(authorId) { normalizeAuthorIdForCache(authorId) }
    var displayAuthor by remember(authorId) {
        mutableStateOf(profileCache.getAuthor(cacheKey) ?: notification.author ?: placeholderAuthor)
    }
    LaunchedEffect(cacheKey) {
        if (cacheKey.isBlank()) return@LaunchedEffect
        profileCache.profileUpdated
            .filter { it == cacheKey }
            .collect { displayAuthor = profileCache.getAuthor(cacheKey) ?: displayAuthor }
    }

    val timeAgo = formatTimeAgo(notification.sortTimestamp, timeTick)
    val pollGreen = Color(0xFF8FBC8F)

    NotificationCardShell(isSeen = isSeen, onClick = onClick) {
        // Row 1: type icon + action + timestamp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.HowToVote,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = pollGreen
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "voted on your poll",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(6.dp))

        // Row 2: voter avatar + name + what they voted
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfilePicture(
                author = displayAuthor,
                size = 28.dp,
                onClick = { notification.author?.id?.let { onProfileClick(it) } }
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayAuthor.displayName.ifBlank { displayAuthor.id.take(8) + "…" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (notification.pollOptionLabels.isNotEmpty()) {
                    Text(
                        text = "Chose: ${notification.pollOptionLabels.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = pollGreen,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Row 3: poll question preview (if enriched)
        if (!notification.pollQuestion.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(IntrinsicSize.Min)
                            .background(pollGreen.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = notification.pollQuestion!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        // Mini option list
                        if (notification.pollAllOptions.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            notification.pollAllOptions.forEach { optLabel ->
                                val isChosen = optLabel in notification.pollOptionLabels
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                ) {
                                    if (isChosen) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = pollGreen
                                        )
                                    } else {
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = optLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isChosen) pollGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        fontWeight = if (isChosen) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun formatSatsCompact(sats: Long): String {
    return when {
        sats >= 1_000_000 -> "${sats / 1_000_000}.${(sats % 1_000_000) / 100_000}M"
        sats >= 1_000 -> "${sats / 1_000}.${(sats % 1_000) / 100}K"
        else -> "$sats"
    }
}

@Composable
private fun SummaryChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(tint.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(14.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatSummaryZap(sats: Long): String = when {
    sats >= 1_000_000 -> "${sats / 1_000_000}.${(sats % 1_000_000) / 100_000}M ⚡"
    sats >= 1_000 -> "${sats / 1_000}.${(sats % 1_000) / 100}K ⚡"
    else -> "$sats ⚡"
}

// ─── Threads tab: condensed per-thread grouping ──────────────────────────────

private data class ThreadSummary(
    val rootNoteId: String,
    val replyCount: Int,
    val latestTimestamp: Long,
    val replierAuthors: List<Author>,
    val latestReply: NotificationData,
    val targetNote: Note?,
    val allNotifIds: List<String>,
    val replyKind: Int,
    val targetNoteFromAny: Note?,
)

@Composable
private fun ThreadSummaryCard(
    summary: ThreadSummary,
    seenIds: Set<String>,
    timeTick: Long,
    onProfileClick: (String) -> Unit,
    onClick: () -> Unit,
) {
    val hasUnseen = summary.allNotifIds.any { it !in seenIds }

    NotificationCardShell(isSeen = !hasUnseen, onClick = onClick) {
        // Row 1: type icon + reply count + timestamp (same pattern as all cards)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Forum,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${summary.replyCount} ${if (summary.replyCount == 1) "reply" else "replies"} in thread",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTimeAgo(summary.latestTimestamp, timeTick),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(6.dp))

        // Row 2: avatar row + names
        val names = summary.replierAuthors.take(3).joinToString(", ") {
            it.displayName.ifBlank { it.username.ifBlank { it.id.take(8) + "…" } }
        }
        val extra = if (summary.replierAuthors.size > 3) " +${summary.replierAuthors.size - 3}" else ""
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarRow(
                authors = summary.replierAuthors,
                totalCount = summary.replierAuthors.size,
                onProfileClick = onProfileClick,
                avatarSize = 24
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = names + extra,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Row 3: thread root preview (full width)
        val threadTitle = summary.targetNote?.content
            ?: summary.targetNoteFromAny?.content
        if (threadTitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = threadTitle.take(120).replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
        }

        // Row 4: latest reply preview (full width)
        val latestContent = summary.latestReply.note?.content
        if (latestContent != null && latestContent != threadTitle) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = latestContent.take(160).replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                lineHeight = 18.sp
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NotificationsScreenPreview() {
    MaterialTheme {
        NotificationsScreen(
            onBackClick = {}
        )
    }
}
