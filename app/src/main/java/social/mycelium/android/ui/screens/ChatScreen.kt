package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.cybin.signer.NostrSigner
import social.mycelium.android.data.DirectMessage
import social.mycelium.android.repository.DirectMessageRepository
import social.mycelium.android.repository.ProfileMetadataCache

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    peerPubkey: String,
    signer: NostrSigner?,
    userPubkey: String?,
    relayUrls: Set<String>,
    onBackClick: () -> Unit,
    onProfileClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by DirectMessageRepository.activeMessages.collectAsState()
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }

    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val peerAuthor = remember(peerPubkey) { profileCache.getAuthor(peerPubkey) }
    val peerName = peerAuthor?.displayName
        ?: peerAuthor?.username
        ?: peerPubkey.take(12) + "..."
    val peerAvatar = peerAuthor?.avatarUrl

    // Set active peer on enter, clear on exit
    DisposableEffect(peerPubkey) {
        DirectMessageRepository.setActivePeer(peerPubkey)
        onDispose { DirectMessageRepository.setActivePeer(null) }
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onProfileClick(peerPubkey) }
                        ) {
                            if (peerAvatar != null) {
                                AsyncImage(
                                    model = peerAvatar,
                                    contentDescription = peerName,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = peerName.take(1).uppercase(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    peerName,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "NIP-17 Encrypted",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "Message...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (messageText.isNotBlank() && signer != null) {
                                    DirectMessageRepository.sendMessage(
                                        content = messageText.trim(),
                                        recipientPubkey = peerPubkey,
                                        signer = signer,
                                        relayUrls = relayUrls
                                    )
                                    messageText = ""
                                }
                            },
                            enabled = messageText.isNotBlank() && signer != null,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        // Group messages by date for separators
        val groupedMessages = remember(messages) {
            messages.groupBy { msg ->
                val cal = java.util.Calendar.getInstance().apply {
                    timeInMillis = msg.createdAt * 1000
                }
                Triple(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH))
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            groupedMessages.forEach { (_, dayMessages) ->
                // Date separator
                item(key = "date_${dayMessages.first().createdAt}") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatDateSeparator(dayMessages.first().createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                items(dayMessages, key = { it.id }) { message ->
                    val msgIdx = dayMessages.indexOf(message)
                    val prevMsg = dayMessages.getOrNull(msgIdx - 1)
                    val nextMsg = dayMessages.getOrNull(msgIdx + 1)
                    val isFirstInGroup = prevMsg == null || prevMsg.isOutgoing != message.isOutgoing ||
                        (message.createdAt - prevMsg.createdAt) > 120
                    val isLastInGroup = nextMsg == null || nextMsg.isOutgoing != message.isOutgoing ||
                        (nextMsg.createdAt - message.createdAt) > 120

                    MessageBubble(
                        message = message,
                        isFirstInGroup = isFirstInGroup,
                        isLastInGroup = isLastInGroup,
                        modifier = Modifier.padding(
                            top = if (isFirstInGroup) 6.dp else 1.dp,
                            bottom = if (isLastInGroup) 6.dp else 1.dp
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: DirectMessage,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isOutgoing = message.isOutgoing
    val alignment = if (isOutgoing) Alignment.CenterEnd else Alignment.CenterStart

    // Adaptive corner radii for message grouping
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = if (isFirstInGroup) cornerRadius else smallCorner,
            bottomEnd = if (isLastInGroup) smallCorner else smallCorner,
            bottomStart = cornerRadius
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInGroup) cornerRadius else smallCorner,
            topEnd = cornerRadius,
            bottomEnd = cornerRadius,
            bottomStart = if (isLastInGroup) smallCorner else smallCorner
        )
    }

    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.78f),
            shape = bubbleShape,
            color = bubbleColor
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    lineHeight = 20.sp
                )
                if (isLastInGroup) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatMessageTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = textColor.copy(alpha = 0.45f),
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

private val messageTimeFmt by lazy { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }

private fun formatMessageTime(epochSeconds: Long): String {
    return messageTimeFmt.format(java.util.Date(epochSeconds * 1000))
}

private val dayOfWeekFmt by lazy { java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()) }
private val dateSeparatorFmt by lazy { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()) }

private fun formatDateSeparator(epochSeconds: Long): String {
    val now = System.currentTimeMillis()
    val msgMs = epochSeconds * 1000
    val diffDays = ((now - msgMs) / 86_400_000).toInt()
    return when {
        diffDays == 0 -> "Today"
        diffDays == 1 -> "Yesterday"
        diffDays < 7 -> dayOfWeekFmt.format(java.util.Date(msgMs))
        else -> dateSeparatorFmt.format(java.util.Date(msgMs))
    }
}
