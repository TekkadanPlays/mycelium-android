package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.MediaServer
import social.mycelium.android.utils.UnicodeStylizer

/**
 * Unified compose toolbar for note/topic composition screens.
 * Includes: media server picker, markdown toggle, unicode styles,
 * zapraiser toggle, and schedule button.
 *
 * Placed above the publish button in compose screens.
 */
@Composable
fun ComposeToolbar(
    blossomServers: List<MediaServer>,
    nip96Servers: List<MediaServer>,
    selectedServer: MediaServer?,
    onServerSelected: (MediaServer) -> Unit,
    onAttachMedia: () -> Unit,
    markdownEnabled: Boolean,
    onToggleMarkdown: (Boolean) -> Unit,
    showZapRaiser: Boolean,
    onToggleZapRaiser: (Boolean) -> Unit,
    onApplyUnicodeStyle: ((UnicodeStylizer.Style) -> Unit)? = null,
    onScheduleClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showServerPicker by remember { mutableStateOf(false) }
    var showUnicodePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Server picker dropdown
        AnimatedVisibility(visible = showServerPicker) {
            MediaServerPicker(
                blossomServers = blossomServers,
                nip96Servers = nip96Servers,
                selectedServer = selectedServer,
                onSelect = {
                    onServerSelected(it)
                    showServerPicker = false
                },
                onDismiss = { showServerPicker = false }
            )
        }

        // Unicode style picker dropdown
        AnimatedVisibility(visible = showUnicodePicker && onApplyUnicodeStyle != null) {
            UnicodeStylePicker(
                onSelect = { style ->
                    onApplyUnicodeStyle?.invoke(style)
                    showUnicodePicker = false
                },
                onDismiss = { showUnicodePicker = false }
            )
        }

        // Toolbar row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Media attach + server picker
            IconButton(onClick = onAttachMedia) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = "Attach media",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Server selection chip
            AssistChip(
                onClick = { showServerPicker = !showServerPicker },
                label = {
                    Text(
                        selectedServer?.name ?: "Select server",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.padding(end = 4.dp)
            )

            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            // Markdown toggle
            IconButton(onClick = { onToggleMarkdown(!markdownEnabled) }) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = "Markdown preview",
                    tint = if (markdownEnabled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Unicode text styles
            if (onApplyUnicodeStyle != null) {
                IconButton(onClick = { showUnicodePicker = !showUnicodePicker }) {
                    Icon(
                        Icons.Outlined.TextFormat,
                        contentDescription = "Text styles",
                        tint = if (showUnicodePicker) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Zapraiser toggle
            IconButton(onClick = { onToggleZapRaiser(!showZapRaiser) }) {
                Icon(
                    Icons.AutoMirrored.Filled.ShowChart,
                    contentDescription = "Zapraiser",
                    tint = if (showZapRaiser) Color(0xFFF59E0B)
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Schedule button (only for notes/topics, not replies)
            if (onScheduleClick != null) {
                IconButton(onClick = onScheduleClick) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "Schedule",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UnicodeStylePicker(
    onSelect: (UnicodeStylizer.Style) -> Unit,
    onDismiss: () -> Unit
) {
    val sampleText = "Hello"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Text Style",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            UnicodeStylizer.Style.entries.forEach { style ->
                val preview = if (style == UnicodeStylizer.Style.DEFAULT) sampleText
                              else UnicodeStylizer.stylize(sampleText, style)
                Surface(
                    onClick = { onSelect(style) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            style.preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaServerPicker(
    blossomServers: List<MediaServer>,
    nip96Servers: List<MediaServer>,
    selectedServer: MediaServer?,
    onSelect: (MediaServer) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Media Server",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (blossomServers.isNotEmpty()) {
                Text(
                    "Blossom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                blossomServers.forEach { server ->
                    MediaServerOption(
                        server = server,
                        isSelected = server == selectedServer,
                        onClick = { onSelect(server) }
                    )
                }
            }

            if (nip96Servers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "NIP-96",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                nip96Servers.forEach { server ->
                    MediaServerOption(
                        server = server,
                        isSelected = server == selectedServer,
                        onClick = { onSelect(server) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaServerOption(
    server: MediaServer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else Color.Transparent,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    server.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
