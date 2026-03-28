package social.mycelium.android.ui.components.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.MediaServer
import social.mycelium.android.utils.UnicodeStylizer

/**
 * Compact compose toolbar — a single clean row of small icons.
 * Dropdowns (server picker, unicode styles) expand above the row.
 * Optional onPublish adds a tinted send button on the right.
 * Optional onPollToggle adds a poll icon toggle.
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
    isPollMode: Boolean = false,
    onPollToggle: (() -> Unit)? = null,
    publishEnabled: Boolean = false,
    publishLabel: String = "Publish",
    onPublish: (() -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var showServerPicker by remember { mutableStateOf(false) }
    var showUnicodePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Expandable panels above the icon row
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
        AnimatedVisibility(visible = showUnicodePicker && onApplyUnicodeStyle != null) {
            UnicodeStylePicker(
                onSelect = { style ->
                    onApplyUnicodeStyle?.invoke(style)
                    showUnicodePicker = false
                },
                onDismiss = { showUnicodePicker = false }
            )
        }

        // ── Main icon row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left icons
            ToolbarIcon(Icons.Outlined.Image, "Attach", onClick = onAttachMedia)
            ToolbarIcon(
                Icons.Outlined.CloudUpload, "Server",
                tint = if (showServerPicker) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { showServerPicker = !showServerPicker }
            )

            ToolbarDivider()

            ToolbarIcon(
                Icons.Outlined.Code, "Markdown",
                tint = if (markdownEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onToggleMarkdown(!markdownEnabled) }
            )
            if (onApplyUnicodeStyle != null) {
                ToolbarIcon(
                    Icons.Outlined.TextFormat, "Styles",
                    tint = if (showUnicodePicker) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { showUnicodePicker = !showUnicodePicker }
                )
            }
            ToolbarIcon(
                Icons.AutoMirrored.Filled.ShowChart, "Zapraiser",
                tint = if (showZapRaiser) Color(0xFFF59E0B)
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onToggleZapRaiser(!showZapRaiser) }
            )
            if (onPollToggle != null) {
                ToolbarIcon(
                    Icons.Outlined.HowToVote, "Poll",
                    tint = if (isPollMode) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onPollToggle
                )
            }
            if (onScheduleClick != null) {
                ToolbarIcon(
                    Icons.Outlined.Schedule, "Schedule",
                    onClick = onScheduleClick
                )
            }

            Spacer(Modifier.weight(1f))

            // Publish / send button
            if (onPublish != null) {
                FilledIconButton(
                    onClick = onPublish,
                    enabled = publishEnabled,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = publishLabel,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/** Compact toolbar icon — 36dp touch target, 20dp icon. */
@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 18.dp),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = tint
        )
    }
}

/** Thin vertical divider between icon groups. */
@Composable
private fun ToolbarDivider() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    )
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
