package social.mycelium.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import social.mycelium.android.repository.EmojiPackSelectionRepository

/**
 * Compact horizontal bar showing the user's favorite/recent emojis and custom emoji pack
 * thumbnails. Tapping an emoji fires [onEmojiSelected]; tapping "…" opens the full picker.
 *
 * Layout: [emoji] [emoji] ... [custom] [custom] ... [⋯]
 *
 * Used as the quick-react row in both NoteCard reactions and thread reply reactions,
 * replacing the old hardcoded 10-emoji dropdown.
 */
@Composable
fun ReactionFavoritesBar(
    recentEmojis: List<String>,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: (shortcode: String, url: String) -> Unit,
    onOpenFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
    maxUnicodeEmojis: Int = 6,
    maxCustomEmojis: Int = 4,
) {
    // Merge recent unicode emojis with a few custom emojis from saved packs
    val savedEmojis by EmojiPackSelectionRepository.allSavedEmojis.collectAsState()
    val customSample = remember(savedEmojis) {
        savedEmojis.entries.take(maxCustomEmojis).map { it.key to it.value }
    }
    val unicodeEmojis = remember(recentEmojis) {
        recentEmojis.take(maxUnicodeEmojis)
    }

    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Recent unicode emojis
        unicodeEmojis.forEach { emoji ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onEmojiSelected(emoji) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emoji,
                    fontSize = 22.sp
                )
            }
        }

        // Custom emojis from saved packs (small thumbnails)
        customSample.forEach { (shortcode, url) ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onCustomEmojiSelected(shortcode, url) },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = shortcode.removeSurrounding(":"),
                    modifier = Modifier.size(26.dp)
                )
            }
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
