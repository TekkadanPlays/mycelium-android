package social.mycelium.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import social.mycelium.android.data.Conversation
import social.mycelium.android.repository.DirectMessageRepository

/**
 * Main messages screen — bottom nav destination.
 *
 * Two tabs:
 * - **Conversations**: Ongoing threads with people you follow or have exchanged 2+ messages with.
 * - **Requests**: One-time / first-contact messages from unknown senders (message requests).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    onNewMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val conversations by DirectMessageRepository.conversations.collectAsState()
    val pagerState = rememberPagerState(initialPage = 0) { 2 }
    val scope = rememberCoroutineScope()

    // Split: conversations with 2+ messages are "known", single inbound = "requests"
    val knownConversations = remember(conversations) {
        conversations.filter { it.messageCount >= 2 || it.lastMessage?.isOutgoing == true }
    }
    val requestConversations = remember(conversations) {
        conversations.filter { it.messageCount == 1 && it.lastMessage?.isOutgoing == false }
    }

    Scaffold(
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // Title row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Messages",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    FilledTonalIconButton(
                        onClick = onNewMessage,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "New message",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Custom pill-shaped tab row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DmTabPill(
                        label = "Conversations",
                        count = knownConversations.size,
                        icon = Icons.Outlined.Email,
                        selected = pagerState.currentPage == 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                        modifier = Modifier.weight(1f)
                    )
                    DmTabPill(
                        label = "Requests",
                        count = requestConversations.size,
                        icon = Icons.Outlined.MarkEmailUnread,
                        selected = pagerState.currentPage == 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        modifier = modifier
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { page ->
            val list = if (page == 0) knownConversations else requestConversations
            val isRequestTab = page == 1

            if (list.isEmpty()) {
                EmptyMessagesState(isRequests = isRequestTab)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    // Encryption banner — only on first tab, only once
                    if (page == 0) {
                        item(key = "nip17_banner") {
                            Nip17Banner()
                        }
                    }

                    if (isRequestTab) {
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

                    items(list, key = { it.peerPubkey }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            isRequest = isRequestTab,
                            onClick = { onConversationClick(conversation.peerPubkey) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DmTabPill(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(200),
        label = "tab_bg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.onPrimaryContainer
        else
            MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(200),
        label = "tab_content"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = contentColor
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = CircleShape,
                    color = if (selected)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                    modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
                        Text(
                            text = if (count > 99) "99+" else "$count",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessagesState(isRequests: Boolean) {
    val debugStatus by DirectMessageRepository.debugStatus.collectAsState()
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
                if (isRequests) "No message requests" else "No conversations yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (isRequests) "First messages from unknown senders appear here"
                else "Start a conversation — messages are end-to-end encrypted with NIP-17 gift wrapping",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 24.dp),
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            // Debug status
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
