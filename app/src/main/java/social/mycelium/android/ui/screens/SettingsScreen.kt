package social.mycelium.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import social.mycelium.android.BuildConfig
import social.mycelium.android.repository.DebugEventStatsSnapshot
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.ui.components.SupportMyceliumZapDialog
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onNavigateTo: (String) -> Unit = {},
    onBugReportClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "settings",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        var showSupportZapDialog by remember { mutableStateOf(false) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsItem(icon = Icons.Outlined.Settings, title = "General", onClick = { onNavigateTo("general") })
            SettingsItem(icon = Icons.Outlined.Edit, title = "Appearance", onClick = { onNavigateTo("appearance") })
            SettingsItem(icon = Icons.Outlined.Notifications, title = "Notifications", onClick = { /* TODO */ })
            SettingsItem(icon = Icons.Outlined.PlayCircleOutline, title = "Media", onClick = { onNavigateTo("media") })
            SettingsItem(icon = Icons.Outlined.Person, title = "Account Preferences", onClick = { onNavigateTo("account_preferences") })
            SettingsItem(icon = Icons.Outlined.Lock, title = "Filters & Blocks", onClick = { /* TODO */ })
            SettingsItem(icon = Icons.Outlined.Settings, title = "Data and Storage", onClick = { /* TODO */ })
            SettingsItem(icon = Icons.Outlined.Public, title = "Relays", onClick = { onNavigateTo("relay_health") })

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            SettingsItem(icon = Icons.Outlined.FavoriteBorder, title = "Support Mycelium", onClick = { showSupportZapDialog = true }, iconTint = Color(0xFFE57373))
            val context = LocalContext.current
            SettingsItem(
                icon = Icons.Outlined.Lightbulb,
                title = "Submit Feedback",
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/TekkadanPlays/mycelium-android/discussions"))
                    )
                },
                iconTint = Color(0xFFFFC107)
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            SettingsItem(icon = Icons.Outlined.BugReport, title = "Report a Bug", onClick = onBugReportClick)
            SettingsItem(icon = Icons.Outlined.Info, title = "About", onClick = { onNavigateTo("about") })

            if (BuildConfig.DEBUG) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Text(
                    text = "Debug",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
                DebugEventStatsCard()
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showSupportZapDialog) {
            SupportMyceliumZapDialog(onDismiss = { showSupportZapDialog = false })
        }
    }
}

// SupportMyceliumZapDialog is in social.mycelium.android.ui.components.SupportMyceliumZapDialog

@Composable
private fun DebugEventStatsCard() {
    val stats by NotesRepository.getInstance().debugEventStats.collectAsState(initial = DebugEventStatsSnapshot(0, 0, 0, 0, 0, 0, 0))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Event stats (this session)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "total=${stats.total}  md=${stats.mdPct()}%  img=${stats.imgPct()}%  vid=${stats.vidPct()}%  gif=${stats.gifPct()}%  imeta=${stats.imetaPct()}%  emoji=${stats.emojiPct()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
    iconTint: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    MaterialTheme {
        SettingsScreen(onBackClick = {})
    }
}
