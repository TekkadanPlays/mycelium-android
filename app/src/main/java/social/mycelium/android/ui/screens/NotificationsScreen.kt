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
import social.mycelium.android.utils.buildNoteContentAnnotatedString
import social.mycelium.android.utils.NoteContentBlock
import social.mycelium.android.utils.buildNoteContentWithInlinePreviews
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
    val tabListStates = remember { List(10) { LazyListState() } }
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
                it.type == NotificationType.REPLY && it.replyKind == 1111 &&
                    it.targetNote?.kind == 11 && myPubkeyHex != null &&
                    normalizeAuthorIdForCache(it.targetNote!!.author.id) == myPubkeyHex
            },
            NotifTab("Comments", { Icon(Icons.Outlined.ChatBubble, null, modifier = Modifier.size(18.dp)) }) {
                it.type == NotificationType.REPLY && it.replyKind == 1111
            },
            NotifTab("Likes", { Icon(Icons.Default.Favorite, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.LIKE },
            NotifTab("Zaps", { Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.ZAP },
            NotifTab("Reposts", { Icon(Icons.Default.Repeat, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.REPOST },
            NotifTab("Mentions", { Icon(Icons.Outlined.AlternateEmail, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.MENTION },
            NotifTab("Highlights", { Icon(Icons.Outlined.FormatQuote, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.HIGHLIGHT },
            NotifTab("Reports", { Icon(Icons.Outlined.Flag, null, modifier = Modifier.size(18.dp)) }) { it.type == NotificationType.REPORT }
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
    // Sync: pager swipe -> external tab selection
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectedTabIndex) onTabSelected(pagerState.currentPage)
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
                        if (unseenInTab > 0) {
                            IconButton(onClick = {
                                if (selectedTabIndex == 0) {
                                    NotificationsRepository.markAllAsSeen()
                                } else {
                                    val tabFilter = tabs[selectedTabIndex].filter
                                    allNotifications.filter(tabFilter).forEach { NotificationsRepository.markAsSeen(it.id) }
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.DoneAll,
                                    contentDescription = "Mark all as read",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "Notification settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                    selectedTabIndex = selectedTabIndex,
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
                            selected = selectedTabIndex == index,
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
        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .fillMaxSize()
                .consumeWindowInsets(paddingValues)
                .padding(paddingValues),
            beyondViewportPageCount = 1
        ) { page ->
            val pageFilter = tabs[page].filter
            val filteredNotifications = remember(allNotifications, page) {
                allNotifications.filter(pageFilter)
            }
            val grouped = remember(filteredNotifications) {
                filteredNotifications.groupBy { timeGroupFor(it.sortTimestamp) }
            }
            val pageListState = tabListStates.getOrElse(page) { tabListStates[0] }

            LazyColumn(
                state = pageListState,
                modifier = Modifier.fillMaxSize()
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
                        val isCompact = notification.type in listOf(
                            NotificationType.LIKE,
                            NotificationType.REPOST,
                            NotificationType.ZAP
                        )
                        if (isCompact) {
                            CompactNotificationRow(
                                notification = notification,
                                isSeen = isSeen,
                                timeTick = timeTick,
                                onProfileClick = onProfileClick,
                                onClick = {
                                    NotificationsRepository.markAsSeen(notification.id)
                                    when {
                                        notification.targetNote != null -> onNoteClick(notification.targetNote!!)
                                        notification.note != null -> onNoteClick(notification.note!!)
                                    }
                                }
                            )
                        } else {
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

// ─── Compact row for likes / zaps / reposts ──────────────────────────────────

private val placeholderAuthor = Author(
    id = "",
    username = "...",
    displayName = "…",
    avatarUrl = null,
    isVerified = false
)

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

    // Live profile resolution: re-compose when a relevant actor profile updates
    var profileRevision by remember { mutableIntStateOf(0) }
    val actorPubkeys = notification.actorPubkeys
    val actorPubkeySet = remember(actorPubkeys) { actorPubkeys.map { normalizeAuthorIdForCache(it) }.toSet() }
    LaunchedEffect(actorPubkeySet) {
        profileCache.profileUpdated
            .filter { it in actorPubkeySet }
            .collect { profileRevision++ }
    }
    @Suppress("UNUSED_EXPRESSION") profileRevision

    val reactionEmoji = notification.reactionEmoji
    val hasCustomEmoji = notification.type == NotificationType.LIKE && reactionEmoji != null && reactionEmoji != "❤️" && reactionEmoji != "+"
    val typeIcon = when (notification.type) {
        NotificationType.LIKE -> Icons.Default.Favorite
        NotificationType.REPOST -> Icons.Default.Repeat
        NotificationType.ZAP -> Icons.Default.Bolt
        else -> Icons.Default.Notifications
    }
    val typeColor = when (notification.type) {
        NotificationType.LIKE -> Color(0xFFE91E63)
        NotificationType.REPOST -> Color(0xFF4CAF50)
        NotificationType.ZAP -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.primary
    }

    val actorAuthors = remember(actorPubkeys, profileRevision) {
        actorPubkeys.take(3).map { pk ->
            profileCache.getAuthor(normalizeAuthorIdForCache(pk))
                ?: placeholderAuthor.copy(id = pk, displayName = pk.take(8) + "…")
        }
    }
    // Build display text at render time with live profile names
    val displayText = remember(actorPubkeys, notification.type, notification.reactionEmoji, notification.zapAmountSats, profileRevision) {
        val action = when (notification.type) {
            NotificationType.LIKE -> {
                val emoji = notification.reactionEmoji
                if (emoji != null && emoji != "❤️" && emoji != "+") "reacted $emoji to your post" else "liked your post"
            }
            NotificationType.REPOST -> "reposted your post"
            NotificationType.ZAP -> {
                if (notification.zapAmountSats > 0) {
                    val sats = notification.zapAmountSats
                    val label = when {
                        sats >= 1_000_000 -> "${sats / 1_000_000}.${(sats % 1_000_000) / 100_000}M sats"
                        sats >= 1_000 -> "${sats / 1_000}.${(sats % 1_000) / 100}K sats"
                        else -> "$sats sats"
                    }
                    "zapped $label"
                } else "zapped your post"
            }
            else -> "interacted"
        }
        val names = actorPubkeys.take(2).map { pk ->
            val a = profileCache.getAuthor(normalizeAuthorIdForCache(pk))
            a?.displayName?.takeIf { it.isNotBlank() && !it.endsWith("...") } ?: pk.take(8) + "…"
        }
        when (actorPubkeys.size) {
            1 -> "${names[0]} $action"
            2 -> "${names[0]} and ${names[1]} $action"
            else -> "${names[0]}, ${names[1]}, and ${actorPubkeys.size - 2} others $action"
        }
    }

    val compactAccentColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (!isSeen) Modifier.drawBehind {
                    drawRect(
                        color = compactAccentColor,
                        topLeft = androidx.compose.ui.geometry.Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)
                    )
                } else Modifier
            ),
        color = if (!isSeen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Type icon or custom emoji (NIP-30 custom emoji with image URL support)
            if (hasCustomEmoji) {
                val emojiUrls = remember(reactionEmoji, notification.customEmojiUrl) {
                    if (notification.customEmojiUrl != null && reactionEmoji != null) {
                        mapOf(reactionEmoji to notification.customEmojiUrl)
                    } else emptyMap()
                }
                social.mycelium.android.ui.components.ReactionEmoji(
                    emoji = reactionEmoji!!,
                    customEmojiUrls = emojiUrls,
                    fontSize = 18.sp,
                    imageSize = 22.dp,
                    modifier = Modifier.widthIn(min = 22.dp)
                )
            } else {
                Icon(
                    imageVector = typeIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = typeColor
                )
            }
            Spacer(Modifier.width(10.dp))

            // Stacked avatars
            Box(modifier = Modifier.width(if (actorAuthors.size > 1) (20 + (actorAuthors.size - 1) * 14).dp else 24.dp)) {
                actorAuthors.forEachIndexed { i, author ->
                    Box(modifier = Modifier.offset(x = (i * 14).dp)) {
                        ProfilePicture(
                            author = author,
                            size = 24.dp,
                            onClick = { onProfileClick(author.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (!isSeen) FontWeight.Medium else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp
                )
                // Show target note preview snippet (strip nostr: bech32 references for clean display)
                val rawPreview = notification.targetNote?.content ?: notification.note?.content
                val previewText = remember(rawPreview) {
                    rawPreview?.replace(Regex("nostr:(nevent1|note1|nprofile1|npub1|naddr1)[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+", RegexOption.IGNORE_CASE), "")
                        ?.replace(Regex("\\s+"), " ")?.trim()
                }
                if (!previewText.isNullOrBlank()) {
                    Text(
                        text = previewText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

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
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFFF59E0B)
                        )
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

            // Timestamp
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    // Thin divider
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
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
    val linkStyle = remember(linkColor) { SpanStyle(color = linkColor) }

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
        color = if (!isSeen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f) else Color.Transparent
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header row: type label + timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (notification.type) {
                        NotificationType.REPLY -> Icons.AutoMirrored.Outlined.Reply
                        NotificationType.HIGHLIGHT -> Icons.Outlined.FormatQuote
                        NotificationType.REPORT -> Icons.Outlined.Flag
                        else -> Icons.Outlined.AlternateEmail
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = typeColor
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when (notification.type) {
                        NotificationType.REPLY -> when (notification.replyKind) {
                            11 -> "Thread reply"
                            1111 -> "Comment"
                            else -> "Reply"
                        }
                        NotificationType.HIGHLIGHT -> "Highlight"
                        NotificationType.REPORT -> "Report"
                        else -> "Mention"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = typeColor
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeAgo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // Original content first — gives context before showing the reply
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

            Spacer(Modifier.height(8.dp))

            // Replier: author row + their reply content
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePicture(
                    author = displayAuthor,
                    size = 32.dp,
                    onClick = { notification.author?.id?.let { onProfileClick(it) } }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = displayAuthor.displayName.ifBlank { displayAuthor.id.take(8) + "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = notification.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Inline reply content
            notification.note?.let { replyNote ->
                NotificationReplyContent(
                    replyNote = replyNote,
                    profileCache = profileCache,
                    onProfileClick = onProfileClick,
                    onClick = onClick,
                )
            }
        }
    }

    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}

/**
 * Extracted inline reply content: annotated text + embedded media + hashtags.
 * Splits ~100 lines from FullNotificationCard to reduce 6.5MB JIT.
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
    val imageUrls = replyNote.mediaUrls.filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) }
    val videoUrls = replyNote.mediaUrls.filter { social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }
    val mediaUrlSet = remember(replyNote.mediaUrls) { replyNote.mediaUrls.toSet() }
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyle = remember(linkColor) { SpanStyle(color = linkColor) }
    val annotatedContent = remember(replyNote.content, mediaUrlSet, linkStyle) {
        buildNoteContentAnnotatedString(
            content = replyNote.content,
            mediaUrls = mediaUrlSet,
            linkStyle = linkStyle,
            profileCache = profileCache
        )
    }
    Spacer(Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            if (annotatedContent.text.isNotBlank()) {
                social.mycelium.android.ui.components.ClickableNoteContent(
                    text = annotatedContent,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp
                    ),
                    maxLines = 4,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
                    onClick = { offset ->
                        val profile = annotatedContent.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                        val url = annotatedContent.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                        when {
                            profile != null -> onProfileClick(profile.item)
                            url != null -> uriHandler.openUri(url.item)
                            else -> onClick()
                        }
                    }
                )
            }
            // Embedded media
            if (imageUrls.size == 1 && videoUrls.isEmpty()) {
                if (annotatedContent.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = imageUrls[0],
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                )
            } else if (imageUrls.isNotEmpty() || videoUrls.isNotEmpty()) {
                if (annotatedContent.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    imageUrls.take(3).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                    videoUrls.take(2).forEach { _ ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            // Hashtags
            if (replyNote.hashtags.isNotEmpty()) {
                Text(
                    text = replyNote.hashtags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8FBC8F),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
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
    val imageUrls = remember(target.mediaUrls) {
        target.mediaUrls.filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) }
    }
    val videoUrls = remember(target.mediaUrls) {
        target.mediaUrls.filter { social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .defaultMinSize(minHeight = 36.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(1.dp)
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            val targetAuthorId = remember(target.author.id) { normalizeAuthorIdForCache(target.author.id) }
            val targetAuthor = remember(targetAuthorId, profileRevision) {
                profileCache.getAuthor(targetAuthorId) ?: target.author
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfilePicture(
                    author = targetAuthor,
                    size = 16.dp,
                    onClick = { onProfileClick(target.author.id) }
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = targetAuthor.displayName.ifBlank { targetAuthor.id.take(8) + "…" },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val targetMediaSet = remember(target.mediaUrls) { target.mediaUrls.toSet() }
            val targetAnnotated = remember(target.content, targetMediaSet, linkStyle) {
                buildNoteContentAnnotatedString(
                    content = target.content,
                    mediaUrls = targetMediaSet,
                    linkStyle = linkStyle,
                    profileCache = profileCache
                )
            }
            if (targetAnnotated.text.isNotBlank()) {
                Text(
                    text = targetAnnotated,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
            // Embedded media thumbnails for the target note
            if (imageUrls.isNotEmpty() || videoUrls.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    imageUrls.take(3).forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    }
                    videoUrls.take(1).forEach { _ ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Video",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

@Preview(showBackground = true)
@Composable
fun NotificationsScreenPreview() {
    MaterialTheme {
        NotificationsScreen(
            onBackClick = {}
        )
    }
}
