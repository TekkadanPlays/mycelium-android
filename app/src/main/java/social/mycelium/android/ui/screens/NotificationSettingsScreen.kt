package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Notifications", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Push Notifications ──
            SectionHeader("Push Notifications")

            // TODO: Push notification service selector (Unified Push / Firebase / None)
            var pushEnabled by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Enable push notifications",
                description = "Receive notifications when the app is in the background",
                checked = pushEnabled,
                onCheckedChange = { pushEnabled = it }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Notification Types ──
            SectionHeader("Notification Types")

            var notifyReactions by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Reactions",
                description = "When someone reacts to your notes",
                checked = notifyReactions,
                onCheckedChange = { notifyReactions = it }
            )

            var notifyZaps by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Zaps",
                description = "When someone zaps your notes",
                checked = notifyZaps,
                onCheckedChange = { notifyZaps = it }
            )

            var notifyReposts by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Reposts",
                description = "When someone reposts your notes",
                checked = notifyReposts,
                onCheckedChange = { notifyReposts = it }
            )

            var notifyMentions by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Mentions",
                description = "When someone mentions you in a note",
                checked = notifyMentions,
                onCheckedChange = { notifyMentions = it }
            )

            var notifyReplies by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Replies",
                description = "When someone replies to your notes",
                checked = notifyReplies,
                onCheckedChange = { notifyReplies = it }
            )

            var notifyDMs by remember { mutableStateOf(true) }
            SettingsToggleRow(
                title = "Direct messages",
                description = "When you receive a direct message",
                checked = notifyDMs,
                onCheckedChange = { notifyDMs = it }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Filtering ──
            SectionHeader("Filtering")

            var muteStrangers by remember { mutableStateOf(false) }
            SettingsToggleRow(
                title = "Mute notifications from non-follows",
                description = "Only show notifications from people you follow",
                checked = muteStrangers,
                onCheckedChange = { muteStrangers = it }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
