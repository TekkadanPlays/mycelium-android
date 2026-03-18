package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import social.mycelium.android.data.Note
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.ZapType

/**
 * Card for rendering NIP-23 long-form content (kind 30023) articles in the feed.
 * Follows the same layout pattern as NoteCard: flat Column with surface background,
 * author row with ProfilePicture at 40dp, same padding conventions.
 */
@Composable
fun ArticleCard(
    note: Note,
    onNoteClick: (Note) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    // ── Action row callbacks (same as NoteCard) ──
    onBoost: ((Note) -> Unit)? = null,
    onQuote: ((Note) -> Unit)? = null,
    onFork: ((Note) -> Unit)? = null,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZapSettings: () -> Unit = {},
    onDelete: ((Note) -> Unit)? = null,
    isAuthorFollowed: Boolean = false,
    onFollowAuthor: ((String) -> Unit)? = null,
    onUnfollowAuthor: ((String) -> Unit)? = null,
    onBlockAuthor: ((String) -> Unit)? = null,
    onMuteAuthor: ((String) -> Unit)? = null,
    onBookmarkToggle: ((String, Boolean) -> Unit)? = null,
    isZapInProgress: Boolean = false,
    isZapped: Boolean = false,
    isBoosted: Boolean = false,
    shouldCloseZapMenus: Boolean = false,
    onRelayClick: (String) -> Unit = {},
    onNavigateToRelayList: ((List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val title = note.topicTitle ?: "Untitled"
    val summary = note.summary ?: note.content.take(200).let {
        if (it.length >= 200) "$it\u2026" else it
    }
    val coverImage = note.imageUrl
    val wordCount = note.content.split(Regex("\\s+")).size
    val readMinutes = (wordCount / 200).coerceAtLeast(1)

    val profileCache = ProfileMetadataCache.getInstance()
    val displayAuthor = remember(note.author.id) {
        profileCache.resolveAuthor(note.author.id)
    }
    val authorLabel = displayAuthor.displayName.ifBlank { displayAuthor.username }.ifBlank { note.author.id.take(8) + "\u2026" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onNoteClick(note) }
    ) {
        // ── Author row (matches NoteCard) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                author = displayAuthor,
                size = 40.dp,
                onClick = { onProfileClick(note.author.id) }
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = authorLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Relay orbs
            val relayUrlsForOrbs = remember(note.relayUrls, note.relayUrl) { note.displayRelayUrls() }
            RelayOrbs(relayUrls = relayUrlsForOrbs, onRelayClick = onRelayClick, onNavigateToRelayList = onNavigateToRelayList)
        }

        // ── Counts row: timestamp + article badge ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val formattedTime = remember(note.timestamp) { formatRelativeTime(note.timestamp) }
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = " \u2022 ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Outlined.Article,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$readMinutes min read",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ── Title ──
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        // ── Summary ──
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // ── Cover image (like media in NoteCard) ──
        if (coverImage != null) {
            AsyncImage(
                model = coverImage,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(max = 200.dp)
            )
        }

        // ── Hashtags ──
        if (note.hashtags.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                note.hashtags.take(3).forEach { tag ->
                    Text(
                        text = "#$tag",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ── Action row (same as NoteCard) ──
        var isZapMenuExpanded by remember { mutableStateOf(false) }
        var showCustomZapDialog by remember { mutableStateOf(false) }

        // Close zap menus when requested externally
        LaunchedEffect(shouldCloseZapMenus) {
            if (shouldCloseZapMenus) {
                isZapMenuExpanded = false
                showCustomZapDialog = false
            }
        }

        NoteActionRow(
            note = note,
            actionRowSchema = ActionRowSchema.KIND1_FEED,
            isZapInProgress = isZapInProgress,
            isZapped = isZapped,
            isBoosted = isBoosted,
            onVote = null,
            ownVoteValue = 0,
            voteScore = 0,
            reactionEmoji = null,
            isDetailsExpanded = false,
            onDetailsToggle = {},
            isZapMenuExpanded = isZapMenuExpanded,
            onZapMenuToggle = { isZapMenuExpanded = !isZapMenuExpanded },
            onShowCustomZapDialog = { showCustomZapDialog = true },
            recentEmojis = emptyList(),
            onReactWithEmoji = {},
            onReactWithCustomEmoji = { _, _ -> },
            onReactWithGif = {},
            onSaveDefaultEmoji = {},
            onComment = {},
            onBoost = onBoost,
            onQuote = onQuote,
            onFork = onFork,
            onZap = onZap,
            onCustomZap = onCustomZap,
            onZapSettings = onZapSettings,
            onProfileClick = onProfileClick,
            isAuthorFollowed = isAuthorFollowed,
            onFollowAuthor = onFollowAuthor,
            onUnfollowAuthor = onUnfollowAuthor,
            onMessageAuthor = null,
            onBlockAuthor = onBlockAuthor,
            onMuteAuthor = onMuteAuthor,
            onBookmarkToggle = onBookmarkToggle,
            onDelete = onDelete,
            extraMoreMenuItems = emptyList(),
            translationResult = null,
            isTranslating = false,
            showOriginal = false,
            onTranslate = {},
            onShowOriginal = {},
            onShowTranslation = {},
        )

        // Custom zap dialog
        if (showCustomZapDialog && onCustomZapSend != null) {
            ZapCustomDialog(
                onDismiss = { showCustomZapDialog = false },
                onSendZap = { amount, zapType, message ->
                    showCustomZapDialog = false
                    onCustomZapSend(note, amount, zapType, message)
                },
                onZapSettings = onZapSettings
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "now"
    }
}
