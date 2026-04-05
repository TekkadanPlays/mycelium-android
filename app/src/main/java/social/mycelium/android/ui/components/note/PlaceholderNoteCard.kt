package social.mycelium.android.ui.components.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.Note

/**
 * Lightweight placeholder for notes outside the viewport render window.
 *
 * Zero side effects: no LaunchedEffect, no collectAsState, no profile
 * fetches, no content parsing, no Coil image loads. Renders only the
 * author name, a content snippet, and a timestamp from data already
 * present on the Note object.
 *
 * This is the key to viewport-gated rendering: hundreds of off-screen
 * notes cost ~0 recomposition overhead instead of 14 LaunchedEffects
 * and 7 StateFlow subscriptions each.
 */
@Composable
fun PlaceholderNoteCard(
    note: Note,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayName = remember(note.author) {
        note.author.displayName.ifBlank { note.author.id.take(8) + "…" }
    }
    val snippet = remember(note.content) {
        val line = note.content
            .lineSequence()
            .firstOrNull { it.isNotBlank() && !it.startsWith("nostr:") }
            ?: note.content
        // Safe truncation: don't split surrogate pairs (emoji) at char boundary
        if (line.length <= 120) line
        else {
            val end = line.offsetByCodePoints(0, minOf(120, line.codePointCount(0, line.length)))
            line.substring(0, end)
        }
    }
    val timeAgo = remember(note.timestamp) { formatTimeAgo(note.timestamp) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar placeholder circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (snippet.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Media indicator
        if (note.mediaUrls.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "\uD83D\uDCCE ${note.mediaUrls.size} media",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.5.dp
    )
}

private fun formatTimeAgo(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diffSec = (now - timestampMs) / 1000
    return when {
        diffSec < 60 -> "${diffSec}s"
        diffSec < 3600 -> "${diffSec / 60}m"
        diffSec < 86400 -> "${diffSec / 3600}h"
        diffSec < 604800 -> "${diffSec / 86400}d"
        else -> "${diffSec / 604800}w"
    }
}
