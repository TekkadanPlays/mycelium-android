package social.mycelium.android.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import social.mycelium.android.ui.settings.ConnectionMode
import social.mycelium.android.ui.settings.NotificationPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBackClick: () -> Unit,
    onNavigateToPower: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val muteStrangers by NotificationPreferences.muteStrangers.collectAsState()
    val showDmContent by NotificationPreferences.showDmContent.collectAsState()
    val connectionMode by NotificationPreferences.connectionMode.collectAsState()
    val adaptiveInterval by NotificationPreferences.adaptiveCheckIntervalMinutes.collectAsState()

    val hasNotificationPermission = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val isBatteryOptimized = remember {
        mutableStateOf(
            try {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) != true
            } catch (_: Exception) { true }
        )
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Notifications & Power", fontWeight = FontWeight.Bold) },
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
            // ── Notification Health Status ──
            NotificationHealthSection(
                hasNotificationPermission = hasNotificationPermission.value,
                isBatteryOptimized = isBatteryOptimized.value,
                connectionMode = connectionMode,
                onFixPermission = {
                    if (Build.VERSION.SDK_INT >= 33) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                },
                onFixBattery = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        ).apply {
                            data = android.net.Uri.parse("package:" + context.packageName)
                        }
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(intent)
                        } else {
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            if (fallback.resolveActivity(context.packageManager) != null) {
                                context.startActivity(fallback)
                            } else {
                                val appSettings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:" + context.packageName)
                                }
                                context.startActivity(appSettings)
                            }
                        }
                    } catch (_: Exception) { }
                }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── System Notification Channels ──
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
                        "Manage notification channels",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Control which notification types appear, with sounds, vibration, and badges per channel",
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

            // ── Privacy ──
            SectionHeader("Privacy")

            SettingsToggleRow(
                title = "Show DM content in notifications",
                description = "When off, DM notifications show \u201cNew private message\u201d without revealing sender or content",
                checked = showDmContent,
                onCheckedChange = { NotificationPreferences.setShowDmContent(it) }
            )

            SettingsToggleRow(
                title = "Mute notifications from non-follows",
                description = "Only show notifications from people you follow",
                checked = muteStrangers,
                onCheckedChange = { NotificationPreferences.setMuteStrangers(it) }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Background Connectivity ──
            SectionHeader("Background Connectivity")

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

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Troubleshooting ──
            SectionHeader("Troubleshooting")

            var showKillConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showKillConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Force stop Mycelium",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Kills the app process so you can restart fresh. Use if the app is misbehaving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showKillConfirm) {
                AlertDialog(
                    onDismissRequest = { showKillConfirm = false },
                    confirmButton = {
                        TextButton(onClick = {
                            showKillConfirm = false
                            val killIntent = Intent(social.mycelium.android.services.KillAppReceiver.ACTION_KILL_APP).apply {
                                setPackage(context.packageName)
                            }
                            context.sendBroadcast(killIntent)
                        }) {
                            Text("Stop", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showKillConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                    title = { Text("Force stop Mycelium?") },
                    text = { Text("This will immediately kill the app. You can relaunch it from your home screen.") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NotificationHealthSection(
    hasNotificationPermission: Boolean,
    isBatteryOptimized: Boolean,
    connectionMode: ConnectionMode,
    onFixPermission: () -> Unit,
    onFixBattery: () -> Unit
) {
    val allGood = hasNotificationPermission && !isBatteryOptimized && connectionMode != ConnectionMode.WHEN_ACTIVE
    val statusColor = if (allGood) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (allGood) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    if (allGood) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = statusColor
                )
                Text(
                    if (allGood) "Notifications are properly configured"
                    else "Notification setup needs attention",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (allGood) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.height(10.dp))

            HealthCheckRow(
                label = "Notification permission",
                isOk = hasNotificationPermission,
                actionLabel = if (!hasNotificationPermission) "Enable" else null,
                onAction = onFixPermission
            )
            HealthCheckRow(
                label = "Battery optimization",
                isOk = !isBatteryOptimized,
                actionLabel = if (isBatteryOptimized) "Unrestrict" else null,
                onAction = onFixBattery
            )
            HealthCheckRow(
                label = "Background connectivity",
                isOk = connectionMode != ConnectionMode.WHEN_ACTIVE,
                description = when (connectionMode) {
                    ConnectionMode.ALWAYS_ON -> "Always On"
                    ConnectionMode.ADAPTIVE -> "Adaptive"
                    ConnectionMode.WHEN_ACTIVE -> "No background notifications"
                }
            )
        }
    }
}

@Composable
private fun HealthCheckRow(
    label: String,
    isOk: Boolean,
    actionLabel: String? = null,
    description: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actionLabel != null) Modifier.clickable(onClick = onAction) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (isOk) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                if (isOk) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.padding(3.dp),
                tint = if (isOk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
        if (actionLabel != null) {
            Text(
                actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
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

// ── Power Settings Screen (kept for backward compatibility) ─────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    NotificationSettingsScreen(onBackClick = onBackClick, modifier = modifier)
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
