package social.mycelium.android.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.ui.settings.NotificationPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pushEnabled by NotificationPreferences.pushEnabled.collectAsState()
    val notifyReactions by NotificationPreferences.notifyReactions.collectAsState()
    val notifyZaps by NotificationPreferences.notifyZaps.collectAsState()
    val notifyReposts by NotificationPreferences.notifyReposts.collectAsState()
    val notifyMentions by NotificationPreferences.notifyMentions.collectAsState()
    val notifyReplies by NotificationPreferences.notifyReplies.collectAsState()
    val notifyDMs by NotificationPreferences.notifyDMs.collectAsState()
    val muteStrangers by NotificationPreferences.muteStrangers.collectAsState()

    val backgroundServiceEnabled by NotificationPreferences.backgroundServiceEnabled.collectAsState()

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
            // ── System Notification Settings ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "System notification settings",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Manage per-channel sounds, vibration, and badges",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Background Service ──
            SectionHeader("Background Service")

            SettingsToggleRow(
                title = "Keep relay connections alive",
                description = "Maintains WebSocket connections when the app is backgrounded so you receive notifications in real time",
                checked = backgroundServiceEnabled,
                onCheckedChange = { NotificationPreferences.setBackgroundServiceEnabled(it) }
            )

            if (backgroundServiceEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Uses a foreground service to keep connections open. " +
                            "Global feed mode may consume significant bandwidth and battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        lineHeight = 16.sp
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Push Notifications ──
            SectionHeader("Push Notifications")

            SettingsToggleRow(
                title = "Enable push notifications",
                description = "Show Android notifications for social events (replies, zaps, mentions)",
                checked = pushEnabled,
                onCheckedChange = { NotificationPreferences.setPushEnabled(it) }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Notification Types ──
            SectionHeader("Notification Types")

            SettingsToggleRow(
                title = "Replies",
                description = "When someone replies to your notes",
                checked = notifyReplies,
                onCheckedChange = { NotificationPreferences.setNotifyReplies(it) },
                enabled = pushEnabled
            )

            SettingsToggleRow(
                title = "Zaps",
                description = "When someone zaps your notes",
                checked = notifyZaps,
                onCheckedChange = { NotificationPreferences.setNotifyZaps(it) },
                enabled = pushEnabled
            )

            SettingsToggleRow(
                title = "Mentions",
                description = "When someone mentions you in a note",
                checked = notifyMentions,
                onCheckedChange = { NotificationPreferences.setNotifyMentions(it) },
                enabled = pushEnabled
            )

            SettingsToggleRow(
                title = "Reactions",
                description = "When someone reacts to your notes",
                checked = notifyReactions,
                onCheckedChange = { NotificationPreferences.setNotifyReactions(it) },
                enabled = pushEnabled
            )

            SettingsToggleRow(
                title = "Reposts",
                description = "When someone reposts your notes",
                checked = notifyReposts,
                onCheckedChange = { NotificationPreferences.setNotifyReposts(it) },
                enabled = pushEnabled
            )

            SettingsToggleRow(
                title = "Direct messages",
                description = "When you receive a direct message",
                checked = notifyDMs,
                onCheckedChange = { NotificationPreferences.setNotifyDMs(it) },
                enabled = pushEnabled
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Filtering ──
            SectionHeader("Filtering")

            SettingsToggleRow(
                title = "Mute notifications from non-follows",
                description = "Only show notifications from people you follow",
                checked = muteStrangers,
                onCheckedChange = { NotificationPreferences.setMuteStrangers(it) }
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
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
