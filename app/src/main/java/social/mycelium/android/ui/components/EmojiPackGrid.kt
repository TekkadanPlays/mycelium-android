package social.mycelium.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import social.mycelium.android.repository.EmojiPackRepository

/**
 * Renders a NIP-30 emoji pack (kind 30030) as a labeled grid of emoji images.
 * Triggers a fetch if the pack isn't cached yet, then re-renders when it arrives.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPackGrid(
    author: String,
    dTag: String,
    relayHints: List<String>,
    modifier: Modifier = Modifier
) {
    val address = remember(author, dTag) { "30030:$author:$dTag" }

    // Trigger fetch if not cached
    LaunchedEffect(address) {
        EmojiPackRepository.fetchIfNeeded(author, dTag, relayHints)
    }

    // Observe packs flow for reactivity
    val packs by EmojiPackRepository.packs.collectAsState()
    val pack = packs[address]

    if (pack != null && pack.emojis.isNotEmpty()) {
        Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = pack.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                pack.emojis.forEach { (shortcode, url) ->
                    AsyncImage(
                        model = url,
                        contentDescription = shortcode.removeSurrounding(":"),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }
    } else if (pack == null) {
        // Loading state — show minimal placeholder
        Text(
            text = "Loading emoji pack…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}
