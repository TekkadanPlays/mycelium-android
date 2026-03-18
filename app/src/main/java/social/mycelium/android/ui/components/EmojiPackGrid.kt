package social.mycelium.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import social.mycelium.android.repository.EmojiPackRepository
import social.mycelium.android.repository.EmojiPackSelectionRepository

/**
 * Renders a NIP-30 emoji pack (kind 30030) as a labeled grid of emoji images.
 * Triggers a fetch if the pack isn't cached yet, then re-renders when it arrives.
 *
 * When [onAddPack] and [onRemovePack] are provided, shows a +/✓ button in the
 * header row to save or remove the pack from the user's kind-10030 selection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPackGrid(
    author: String,
    dTag: String,
    relayHints: List<String>,
    modifier: Modifier = Modifier,
    onAddPack: ((author: String, dTag: String, relayHint: String?) -> Unit)? = null,
    onRemovePack: ((author: String, dTag: String) -> Unit)? = null,
) {
    val address = remember(author, dTag) { "30030:$author:$dTag" }

    // Trigger fetch if not cached
    LaunchedEffect(address) {
        EmojiPackRepository.fetchIfNeeded(author, dTag, relayHints)
    }

    // Observe packs flow for reactivity
    val packs by EmojiPackRepository.packs.collectAsState()
    val pack = packs[address]

    // Observe saved state for +/✓ button
    val savedPacks by EmojiPackSelectionRepository.savedPacks.collectAsState()
    val isSaved = remember(savedPacks, author, dTag) {
        savedPacks.any { it.author == author && it.dTag == dTag }
    }

    if (pack != null && pack.emojis.isNotEmpty()) {
        Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pack.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(bottom = 2.dp)
                )
                // Use explicit callbacks if provided, otherwise fall back to global actions
                val addAction = onAddPack ?: EmojiPackSelectionRepository.onAddPackAction
                val removeAction = onRemovePack ?: EmojiPackSelectionRepository.onRemovePackAction
                if (addAction != null && removeAction != null) {
                    if (isSaved) {
                        IconButton(
                            onClick = { removeAction(author, dTag) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Remove pack",
                                tint = Color(0xFF8FBC8F),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = { addAction(author, dTag, relayHints.firstOrNull()) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Save pack",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
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
