package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import social.mycelium.android.ui.settings.FeedPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPreferencesScreen(
    onBackClick: () -> Unit,
    /** NIP-51 people lists available: (dTag, title) */
    peopleLists: List<Pair<String, String>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val currentFeedView by FeedPreferences.defaultFeedView.collectAsState()
    val currentSortOrder by FeedPreferences.defaultSortOrder.collectAsState()
    val currentListDTag by FeedPreferences.defaultListDTag.collectAsState()

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Feed Preferences") },
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Default Feed View ──
            FeedSectionHeader("Default Feed View")
            Text(
                "Which feed opens when you launch the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            FeedRadioItem(
                icon = Icons.Outlined.Home,
                label = "Home",
                sublabel = "Short-form notes (kind 1)",
                isSelected = currentFeedView == "HOME",
                onClick = { FeedPreferences.setDefaultFeedView("HOME") }
            )
            FeedRadioItem(
                icon = Icons.Outlined.Tag,
                label = "Topics",
                sublabel = "Topic-based notes (kind 1111)",
                isSelected = currentFeedView == "TOPICS",
                onClick = { FeedPreferences.setDefaultFeedView("TOPICS") }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // ── Default Sort Order ──
            FeedSectionHeader("Default Sort Order")
            Text(
                "How notes are ordered when you first open a feed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            FeedRadioItem(
                icon = Icons.Outlined.Schedule,
                label = "Latest",
                sublabel = "Newest notes first",
                isSelected = currentSortOrder == "LATEST",
                onClick = { FeedPreferences.setDefaultSortOrder("LATEST") }
            )
            FeedRadioItem(
                icon = Icons.Outlined.TrendingUp,
                label = "Popular",
                sublabel = "Most engaged notes first",
                isSelected = currentSortOrder == "POPULAR",
                onClick = { FeedPreferences.setDefaultSortOrder("POPULAR") }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // ── Default Feed Filter (Cold Start) ──
            FeedSectionHeader("Cold Start Filter")
            Text(
                "Which filter is active when you launch the app. Lists are from your NIP-51 people lists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            FeedRadioItem(
                icon = Icons.Outlined.People,
                label = "Following",
                sublabel = "Notes from people you follow",
                isSelected = currentListDTag == null,
                onClick = { FeedPreferences.setDefaultListDTag(null) }
            )

            peopleLists.forEach { (dTag, title) ->
                FeedRadioItem(
                    icon = Icons.AutoMirrored.Outlined.List,
                    label = title,
                    sublabel = "People list",
                    isSelected = currentListDTag == dTag,
                    onClick = { FeedPreferences.setDefaultListDTag(dTag) }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            // ── Auto-Save Drafts ──
            FeedSectionHeader("Drafts")
            Text(
                "Automatically save drafts while you compose, so you never lose your work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            val autoSaveDrafts by FeedPreferences.autoSaveDrafts.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { FeedPreferences.setAutoSaveDrafts(!autoSaveDrafts) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = if (autoSaveDrafts) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-save drafts",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (autoSaveDrafts) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = "Periodically save while typing and on back press",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoSaveDrafts,
                    onCheckedChange = { FeedPreferences.setAutoSaveDrafts(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun FeedSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 2.dp)
    )
}

@Composable
private fun FeedRadioItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = sublabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
