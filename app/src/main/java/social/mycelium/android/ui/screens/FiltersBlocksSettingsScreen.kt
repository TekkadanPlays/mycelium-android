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
fun FiltersBlocksSettingsScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Filters & Blocks", fontWeight = FontWeight.Bold) },
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
            // ── Content Filtering ──
            FiltersBlocksSectionHeader("Content Filtering")

            var warnReported by remember { mutableStateOf(true) }
            FiltersBlocksToggleRow(
                title = "Warn on reported content",
                description = "Show a warning on posts that have been reported by people you follow",
                checked = warnReported,
                onCheckedChange = { warnReported = it }
            )

            var filterSpam by remember { mutableStateOf(false) }
            FiltersBlocksToggleRow(
                title = "Filter spam from strangers",
                description = "Hide low-quality content from accounts you don't follow",
                checked = filterSpam,
                onCheckedChange = { filterSpam = it }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Sensitive Content ──
            FiltersBlocksSectionHeader("Sensitive Content")

            val showSensitive by social.mycelium.android.ui.settings.MediaPreferences.showSensitiveContent.collectAsState()
            FiltersBlocksToggleRow(
                title = "Show sensitive content",
                description = "Display content marked as sensitive without a warning overlay",
                checked = showSensitive,
                onCheckedChange = { social.mycelium.android.ui.settings.MediaPreferences.setShowSensitiveContent(it) }
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Topic Moderation (NIP-22) ──
            FiltersBlocksSectionHeader("Topic Moderation")

            val moderationRepo = remember { social.mycelium.android.repository.ScopedModerationRepository.getInstance() }
            val currentFilterMode by moderationRepo.filterMode.collectAsState()
            val currentThreshold by moderationRepo.flagThreshold.collectAsState()
            val moderationCount by moderationRepo.moderationCount.collectAsState()

            Text(
                text = "Control how kind:1011 moderation flags affect topic feeds. " +
                    "${if (moderationCount > 0) "$moderationCount moderation events collected." else "No moderation events yet."}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            social.mycelium.android.repository.ModerationFilterMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = currentFilterMode == mode,
                        onClick = { moderationRepo.setFilterMode(mode) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (mode) {
                                social.mycelium.android.repository.ModerationFilterMode.OFF -> "Off"
                                social.mycelium.android.repository.ModerationFilterMode.THRESHOLD -> "Threshold"
                                social.mycelium.android.repository.ModerationFilterMode.WOT -> "Web of Trust"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = when (mode) {
                                social.mycelium.android.repository.ModerationFilterMode.OFF -> "Show all notes regardless of moderation flags"
                                social.mycelium.android.repository.ModerationFilterMode.THRESHOLD -> "Hide notes/users that exceed a flag count threshold"
                                social.mycelium.android.repository.ModerationFilterMode.WOT -> "Only count flags from people you follow"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Threshold picker (only visible when not OFF)
            if (currentFilterMode != social.mycelium.android.repository.ModerationFilterMode.OFF) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Flag threshold",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    listOf(1, 2, 3, 5).forEach { threshold ->
                        val selected = currentThreshold == threshold
                        FilterChip(
                            selected = selected,
                            onClick = { moderationRepo.setFlagThreshold(threshold) },
                            label = { Text("$threshold") },
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            // ── Mute Lists ──
            FiltersBlocksSectionHeader("Mute Lists")

            // TODO: Navigate to muted users list
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Muted users", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Manage users whose content is hidden from your feeds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // TODO: Navigate to muted words/hashtags list
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Muted words & hashtags", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Hide notes containing specific words or hashtags",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FiltersBlocksSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun FiltersBlocksToggleRow(
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
