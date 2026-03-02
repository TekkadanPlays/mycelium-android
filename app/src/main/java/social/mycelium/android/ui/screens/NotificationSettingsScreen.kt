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
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Info
import social.mycelium.android.ui.settings.ConnectionMode
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

// ── Power Settings Screen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val connectionMode by NotificationPreferences.connectionMode.collectAsState()
    val adaptiveInterval by NotificationPreferences.adaptiveCheckIntervalMinutes.collectAsState()

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Power", fontWeight = FontWeight.Bold) },
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
            PowerSectionHeader("Battery Optimization")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            // Primary: direct whitelist dialog for this package
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            ).apply {
                                data = android.net.Uri.parse("package:" + context.packageName)
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(intent)
                            } else {
                                // Fallback: open battery optimization list
                                val fallback = android.content.Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                                )
                                if (fallback.resolveActivity(context.packageManager) != null) {
                                    context.startActivity(fallback)
                                } else {
                                    // Last resort: open app detail settings
                                    val appSettings = android.content.Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                    ).apply {
                                        data = android.net.Uri.parse("package:" + context.packageName)
                                    }
                                    context.startActivity(appSettings)
                                }
                            }
                        } catch (_: Exception) {
                            // Ultimate fallback: open app detail settings
                            try {
                                val appSettings = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                ).apply {
                                    data = android.net.Uri.parse("package:" + context.packageName)
                                }
                                context.startActivity(appSettings)
                            } catch (_: Exception) { /* device has no settings at all */ }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Battery optimization settings",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Disable battery optimization for Mycelium to ensure reliable background connectivity",
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

            PowerSectionHeader("Background Connectivity")

            PowerConnectionModeOption(
                title = "Always On",
                description = "Persistent connections with real-time notifications. Higher battery usage.",
                selected = connectionMode == ConnectionMode.ALWAYS_ON,
                onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.ALWAYS_ON) }
            )
            if (connectionMode == ConnectionMode.ALWAYS_ON) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Uses a foreground service with persistent notification to keep WebSocket connections open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        lineHeight = 16.sp
                    )
                }
            }

            PowerConnectionModeOption(
                title = "Adaptive",
                description = "Periodic inbox checks for notifications. Good battery life.",
                selected = connectionMode == ConnectionMode.ADAPTIVE,
                onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.ADAPTIVE) },
                recommended = true
            )
            if (connectionMode == ConnectionMode.ADAPTIVE) {
                val intervals = listOf(15L, 30L, 60L, 120L, 360L)
                val labels = listOf("15 min", "30 min", "1 hour", "2 hours", "6 hours")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    intervals.forEachIndexed { index, interval ->
                        val isSelected = adaptiveInterval == interval
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.clickable {
                                NotificationPreferences.setAdaptiveCheckIntervalMinutes(interval)
                            }
                        ) {
                            Text(
                                labels[index],
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            PowerConnectionModeOption(
                title = "When Active",
                description = "Connections only while the app is open. Best battery life, no background notifications.",
                selected = connectionMode == ConnectionMode.WHEN_ACTIVE,
                onClick = { NotificationPreferences.setConnectionMode(ConnectionMode.WHEN_ACTIVE) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PowerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun PowerConnectionModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    recommended: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (recommended) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "Recommended",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
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
