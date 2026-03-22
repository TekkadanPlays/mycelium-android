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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.BreakIterator

/**
 * Compact horizontal bar showing the user's recent single-character emojis.
 * Tapping an emoji fires [onEmojiSelected]; tapping "…" opens the full picker.
 *
 * Only single-grapheme unicode emojis are shown (no emoji strings, no custom pack emojis)
 * to prevent overflow that makes the 3-dots button impossible to press.
 *
 * Layout: [recent emojis (max 6)] [⋯]
 */
@Composable
fun ReactionFavoritesBar(
    recentEmojis: List<String>,
    onEmojiSelected: (String) -> Unit,
    onCustomEmojiSelected: (shortcode: String, url: String) -> Unit,
    onOpenFullPicker: () -> Unit,
    modifier: Modifier = Modifier,
    maxRecentEmojis: Int = 6,
) {
    // Only show single-grapheme unicode emojis (no :shortcode: custom, no multi-emoji strings)
    val recentItems = remember(recentEmojis) {
        recentEmojis.filter { emoji ->
            !emoji.startsWith(":") && countGraphemeClusters(emoji) == 1
        }.take(maxRecentEmojis)
    }

    Row(
        modifier = modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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

/** Count grapheme clusters in a string (handles multi-codepoint emojis like 👨‍👩‍👧‍👦 as 1 cluster). */
internal fun countGraphemeClusters(text: String): Int {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    var count = 0
    while (it.next() != BreakIterator.DONE) count++
    return count
}
