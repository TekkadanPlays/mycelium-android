package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import social.mycelium.android.BuildConfig
import social.mycelium.android.data.Conversation
import social.mycelium.android.repository.DirectMessageRepository
import social.mycelium.android.ui.components.AdaptiveHeader

/**
 * Main messages screen — bottom nav destination.
 *
 * Drop-down menu toggles between Conversations and Requests views.
 * Expanding FAB provides sort, navigation, and mark-all-read actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNewMessage: () -> Unit,
    // AdaptiveHeader callbacks
    onMenuClick: () -> Unit = {},
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    onUserProfileClick: () -> Unit = {},
    onAccountsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onRelaysClick: () -> Unit = {},
    onLoginClick: (() -> Unit)? = null,
    onConfigureDmRelays: () -> Unit = {},
    isScreenVisible: Boolean = true,
    modifier: Modifier = Modifier
) {
    val conversations by DirectMessageRepository.conversations.collectAsState()
    val pendingCount by DirectMessageRepository.pendingGiftWrapCount.collectAsState()
    val isDecrypting by DirectMessageRepository.isDecrypting.collectAsState()
    val hasApprovedDecrypt by DirectMessageRepository.hasUserApprovedDecrypt.collectAsState()
    val autoDecrypt by social.mycelium.android.ui.settings.DmPreferences.autoDecryptDMs.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val dmRelayUrls by DirectMessageRepository.dmRelayUrls.collectAsState()

    // Lazily start the DM relay subscription when the user first visits this screen.
    // No relay connections are opened until this point (deferred in Phase 4).
    LaunchedEffect(isScreenVisible) {
        if (isScreenVisible) {
            if (dmRelayUrls.isEmpty()) {
                onConfigureDmRelays()
                return@LaunchedEffect
            }
            DirectMessageRepository.ensureSubscriptionStarted()
        }
    }

    // Auto-decrypt: only if user has already approved this session OR auto-decrypt setting is on.
    // When auto-decrypt is off, the user must tap the "Decrypt" button below.
    LaunchedEffect(isScreenVisible, pendingCount, hasApprovedDecrypt, autoDecrypt) {
        if (isScreenVisible && pendingCount > 0) {
            if (autoDecrypt || hasApprovedDecrypt) {
                DirectMessageRepository.decryptPending(userConfirmed = false)
            }
        }
    }

    // Drop-down toggle: conversations vs requests
    var showRequests by remember { mutableStateOf(false) }
    // Sort order: newest first (default) or oldest first
    var sortNewest by remember { mutableStateOf(true) }

    // Split: conversations with 2+ messages are "known", single inbound = "requests"
    val knownConversations = remember(conversations) {
        conversations.filter { it.messageCount >= 2 || it.lastMessage?.isOutgoing == true }
    }
    val requestConversations = remember(conversations) {
        conversations.filter { it.messageCount == 1 && it.lastMessage?.isOutgoing == false }
    }

    val activeList = if (showRequests) requestConversations else knownConversations
    val sortedList = remember(activeList, sortNewest) {
        if (sortNewest) activeList
        else activeList.sortedBy { it.lastMessage?.createdAt ?: 0L }
    }

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        topBar = {
            AdaptiveHeader(
                title = if (showRequests) "requests" else "messages",
                onMenuClick = onMenuClick,
                isGuest = isGuest,
                userDisplayName = userDisplayName,
                userAvatarUrl = userAvatarUrl,
                onProfileClick = onUserProfileClick,
                onAccountsClick = onAccountsClick,
                onSettingsClick = onSettingsClick,
                onRelaysClick = onRelaysClick,
                onLoginClick = onLoginClick,
                scrollBehavior = scrollBehavior,
                onMoreOptionClick = { option ->
                    when (option) {
                        "requests" -> showRequests = true
                        "conversations" -> showRequests = false
                    }
                }
            )
        },
        floatingActionButton = {
            DmFab(
                onNewMessage = onNewMessage,
                sortNewest = sortNewest,
                onToggleSort = { sortNewest = !sortNewest },
                onScrollUp = {
                    scope.launch {
                        val target = (listState.firstVisibleItemIndex - 10).coerceAtLeast(0)
                        listState.animateScrollToItem(target)
                    }
                },
                onScrollDown = {
                    scope.launch {
                        val target = (listState.firstVisibleItemIndex + 10).coerceAtMost(
                            (sortedList.size - 1).coerceAtLeast(0)
                        )
                        listState.animateScrollToItem(target)
                    }
                },
                onMarkAllRead = { DirectMessageRepository.markAllAsRead() },
                modifier = Modifier.padding(bottom = 80.dp)
            )
        },
        modifier = modifier
    ) { paddingValues ->
        // Show "Decrypt Messages" prompt when pending wraps exist and user hasn't confirmed yet
        val showDecryptPrompt = pendingCount > 0 && !hasApprovedDecrypt && !autoDecrypt && !isDecrypting

        if (sortedList.isEmpty()) {
            EmptyMessagesState(
                isRequests = showRequests,
                pendingCount = pendingCount,
                isDecrypting = isDecrypting,
                showDecryptButton = showDecryptPrompt,
                onDecryptClick = {
                    DirectMessageRepository.decryptPending(userConfirmed = true)
                }
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (!showRequests) {
                    item(key = "nip17_banner") {
                        Nip17Banner()
                    }
                }

                // Decrypt prompt banner in the list header
                if (showDecryptPrompt) {
                    item(key = "decrypt_prompt") {
                        DecryptPromptBanner(
                            pendingCount = pendingCount,
                            onDecryptClick = {
                                DirectMessageRepository.decryptPending(userConfirmed = true)
                            }
                        )
                    }
                }

                // Decrypting progress banner
                if (isDecrypting) {
                    item(key = "decrypting_banner") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Decrypting messages…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (showRequests) {
                    item(key = "requests_info") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                        ) {
                            Text(
                                "Messages from people you haven't replied to yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                items(sortedList, key = { it.peerPubkey }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        isRequest = showRequests,
                        onClick = { onConversationClick(conversation.peerPubkey) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

/**
 * Expanding FAB for the DMs screen.
 *
 * Actions: Sort toggle (Newest/Oldest), Page Up/Down, Mark all as read.
 */
@Composable
private fun DmFab(
    onNewMessage: () -> Unit,
    sortNewest: Boolean,
    onToggleSort: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onMarkAllRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(200),
        label = "dm_fab_rotation"
    )

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(tween(150)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(tween(100))
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // New message
                DmFabItem(
                    label = "New message",
                    icon = Icons.Outlined.Email,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = {
                        expanded = false
                        onNewMessage()
                    }
                )
                // Mark all as read
                DmFabItem(
                    label = "Read all",
                    icon = Icons.Outlined.DoneAll,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = {
                        expanded = false
                        onMarkAllRead()
                    }
                )
                // Sort toggle
                DmFabItem(
                    label = if (sortNewest) "Oldest" else "Newest",
                    icon = Icons.Outlined.SortByAlpha,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        expanded = false
                        onToggleSort()
                    }
                )
                // Page up
                DmFabItem(
                    label = "Up",
                    icon = Icons.Default.KeyboardArrowUp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        expanded = false
                        onScrollUp()
                    }
                )
                // Page down
                DmFabItem(
                    label = "Down",
                    icon = Icons.Default.KeyboardArrowDown,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        expanded = false
                        onScrollDown()
                    }
                )
            }
        }

        // Main FAB
        FloatingActionButton(
            onClick = { expanded = !expanded },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (expanded) "Close menu" else "Open menu",
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}

@Composable
private fun DmFabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 2.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = contentColor,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptyMessagesState(
    isRequests: Boolean,
    pendingCount: Int = 0,
    isDecrypting: Boolean = false,
    showDecryptButton: Boolean = false,
    onDecryptClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (isRequests) Icons.Outlined.MarkEmailUnread else Icons.Outlined.Email,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                if (isRequests) "No message requests"
                else if (showDecryptButton) "$pendingCount encrypted message${if (pendingCount != 1) "s" else ""}"
                else "No conversations yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isRequests) "First messages from unknown senders appear here"
                else if (isDecrypting) "Decrypting messages…"
                else if (showDecryptButton) "Tap below to decrypt with your signer"
                else if (pendingCount > 0) "$pendingCount encrypted message${if (pendingCount != 1) "s" else ""} waiting"
                else "Start a conversation — messages are end-to-end encrypted with NIP-17 gift wrapping",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp),
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (showDecryptButton) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onDecryptClick,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Decrypt Messages")
                }
            }
            if (isDecrypting) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.width(120.dp)
                )
            }
            if (BuildConfig.DEBUG) {
                val debugStatus by DirectMessageRepository.debugStatus.collectAsState()
                Spacer(Modifier.height(16.dp))
                Text(
                    debugStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Banner prompting the user to decrypt messages — displayed at the top of the
 * conversations list when there are pending gift wraps and auto-decrypt is off.
 */
@Composable
private fun DecryptPromptBanner(
    pendingCount: Int,
    onDecryptClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onDecryptClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "$pendingCount new encrypted message${if (pendingCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Tap to decrypt with your signer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            FilledTonalButton(
                onClick = onDecryptClick,
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("Decrypt", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Nip17Banner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "End-to-end encrypted · Gift-wrapped (NIP-17)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    isRequest: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with online-style accent ring for requests
        Box {
            if (conversation.peerAvatarUrl != null) {
                AsyncImage(
                    model = conversation.peerAvatarUrl,
                    contentDescription = conversation.peerDisplayName,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = conversation.peerDisplayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            // Unread dot
            if (conversation.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (conversation.unreadCount > 9) "9+" else "${conversation.unreadCount}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.peerDisplayName,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                conversation.lastMessage?.let { msg ->
                    Text(
                        text = formatDmTimestamp(msg.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unreadCount > 0)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = conversation.lastMessage?.content ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = if (conversation.unreadCount > 0)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
            if (isRequest) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${conversation.messageCount} message · tap to view",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatDmTimestamp(epochSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - epochSeconds
    return when {
        diff < 60 -> "now"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(epochSeconds * 1000))
        }
    }
}
