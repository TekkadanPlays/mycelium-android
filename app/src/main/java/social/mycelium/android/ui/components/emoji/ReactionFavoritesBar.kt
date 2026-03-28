package social.mycelium.android.ui.components.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.BreakIterator

/**
 * Compact horizontal bar showing the user's recent single-character emojis.
 * Tapping an emoji fires [onEmojiSelected]; tapping "…" opens the full picker.
 *
 * Only single-grapheme unicode emojis are shown (no emoji strings, no custom pack emojis)
 * to prevent overflow that makes the 3-dots button impossible to press.
 *
 * When [ownReactions] is non-empty, a "Your Reactions" section appears at the top
 * showing emojis the user has reacted with on this note, each with a remove (✕) button.
 * Tapping remove shows a confirmation dialog before publishing a kind-5 deletion.
 *
 * Layout:
 * [Your Reactions row (with ✕)] — only if ownReactions non-empty
 * [divider]
 * [recent emojis (max 6)] [⋯]
 */
@Composable
fun ReactionFavoritesBar(
    recentEmojis: List<String>,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: (shortcode: String, url: String) -> Unit,
    onOpenFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
    maxRecentEmojis: Int = 6,
    /** Own reactions on this note: list of (reactionEventId, emoji). Empty = no own reactions. */
    ownReactions: List<Pair<String, String>> = emptyList(),
    /** Custom emoji URLs from counts (for rendering NIP-30 custom emoji reactions). */
    customEmojiUrls: Map<String, String> = emptyMap(),
    /** Called when user confirms removal of a reaction: (reactionEventId, emoji). */
    onRemoveReaction: ((reactionEventId: String, emoji: String) -> Unit)? = null,
) {
    // Only show single-grapheme unicode emojis (no :shortcode: custom, no multi-emoji strings)
    val recentItems = remember(recentEmojis) {
        recentEmojis.filter { emoji ->
            !emoji.startsWith(":") && countGraphemeClusters(emoji) == 1
        }.take(maxRecentEmojis)
    }

    // Dedup own reactions by emoji (multiple relays may report same reaction)
    val uniqueOwnReactions = remember(ownReactions) {
        ownReactions.distinctBy { it.second }
    }

    // Confirmation dialog state
    var confirmRemoval by remember { mutableStateOf<Pair<String, String>?>(null) } // (eventId, emoji)

    Column(modifier = modifier) {
        // ── Your Reactions section ──
        if (uniqueOwnReactions.isNotEmpty() && onRemoveReaction != null) {
            Text(
                text = "Your Reactions",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 2.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                uniqueOwnReactions.forEach { (eventId, emoji) ->
                    val isCustom = emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                            .clickable { confirmRemoval = eventId to emoji }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (isCustom) {
                                val allSaved by social.mycelium.android.repository.EmojiPackSelectionRepository.allSavedEmojis.collectAsState()
                                val url = customEmojiUrls[emoji] ?: allSaved[emoji]
                                if (url != null) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = emoji.removeSurrounding(":"),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(text = emoji, fontSize = 16.sp)
                                }
                            } else {
                                Text(text = emoji, fontSize = 18.sp)
                            }
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove reaction",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }

        // ── Recent emojis row ──
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recent single-grapheme unicode emojis only
            recentItems.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = emoji,
                        fontSize = 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Visible
                    )
                }
            }

            // Divider between quick emojis and "..." to prevent mis-taps
            if (recentItems.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(2.dp))
            }

            // "..." button to open full picker
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onOpenFullPicker() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "More emojis",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }

    // ── Removal confirmation dialog ──
    if (confirmRemoval != null) {
        val (eventId, emoji) = confirmRemoval!!
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            title = { Text("Remove Reaction?") },
            text = {
                val isCustom = emoji.startsWith(":") && emoji.endsWith(":") && emoji.length > 2
                if (isCustom) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Remove your ")
                        val allSaved by social.mycelium.android.repository.EmojiPackSelectionRepository.allSavedEmojis.collectAsState()
                        val url = customEmojiUrls[emoji] ?: allSaved[emoji]
                        if (url != null) {
                            AsyncImage(
                                model = url,
                                contentDescription = emoji.removeSurrounding(":"),
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(emoji)
                        }
                        Text(" reaction from this note?")
                    }
                } else {
                    Text("Remove your $emoji reaction from this note?")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveReaction?.invoke(eventId, emoji)
                        confirmRemoval = null
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/** Count grapheme clusters in a string (handles multi-codepoint emojis like 👨‍👩‍👧‍👦 as 1 cluster). */
internal fun countGraphemeClusters(text: String): Int {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var count = 0
    while (it.next() != BreakIterator.DONE) count++
    return count
}
