package social.mycelium.android.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.Author
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.ui.components.common.ProfilePicture

/**
 * Data class representing a section of relays in the selection screen.
 * Each section has a header (profile or category name), a list of relay URLs,
 * and can be toggled on/off as a group.
 */
data class RelaySection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val author: Author? = null,
    val relays: List<RelayEntry> = emptyList(),
    val initiallyExpanded: Boolean = true,
    val initiallyAllSelected: Boolean = false
)

data class RelayEntry(
    val url: String,
    val displayName: String,
    val description: String? = null,
    val iconUrl: String? = null
)

/**
 * Dedicated full-screen relay selection for publishing.
 *
 * Layout:
 * - Section 1 (if replying): Target user profile header + their inbox relays (NIP-65 read)
 * - Section 2: Our profile header + our outbox relays
 * - Section 3+: Our custom relay categories from relay manager
 *
 * Each section has a toggle-all switch and individual checkboxes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySelectionScreen(
    title: String = "Publish to relays",
    sections: List<RelaySection>,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Track selected URLs — initialize from sections marked as initiallyAllSelected
    var selectedUrls by remember(sections) {
        val initial = mutableSetOf<String>()
        sections.forEach { section ->
            if (section.initiallyAllSelected) {
                section.relays.forEach { initial.add(it.url) }
            }
        }
        mutableStateOf<Set<String>>(initial)
    }

    // Track expanded/collapsed state per section
    var expandedSections by remember(sections) {
        mutableStateOf(sections.filter { it.initiallyExpanded }.map { it.id }.toSet())
    }

    // Intercept system back gesture so it returns to the compose screen
    // instead of popping the navigation stack (which would lose the draft).
    androidx.activity.compose.BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, fontWeight = FontWeight.Bold)
                            val count = selectedUrls.size
                            Text(
                                text = "$count relay${if (count != 1) "s" else ""} selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onConfirm(selectedUrls) },
                        modifier = Modifier.weight(1f),
                        enabled = selectedUrls.isNotEmpty()
                    ) {
                        Text("Publish")
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            sections.forEach { section ->
                val isExpanded = section.id in expandedSections
                val sectionUrls = section.relays.map { it.url }.toSet()
                val selectedInSection = selectedUrls.intersect(sectionUrls)
                val allSelected = selectedInSection.size == sectionUrls.size && sectionUrls.isNotEmpty()
                val someSelected = selectedInSection.isNotEmpty() && !allSelected

                // Section header with profile or icon
                item(key = "section_header_${section.id}") {
                    SectionHeaderWithToggle(
                        section = section,
                        isExpanded = isExpanded,
                        allSelected = allSelected,
                        someSelected = someSelected,
                        selectedCount = selectedInSection.size,
                        totalCount = sectionUrls.size,
                        onToggleExpand = {
                            expandedSections = if (isExpanded) {
                                expandedSections - section.id
                            } else {
                                expandedSections + section.id
                            }
                        },
                        onToggleAll = { selectAll ->
                            selectedUrls = if (selectAll) {
                                selectedUrls + sectionUrls
                            } else {
                                selectedUrls - sectionUrls
                            }
                        }
                    )
                }

                // Relay items (only when expanded)
                if (isExpanded) {
                    items(
                        items = section.relays,
                        key = { "relay_${section.id}_${it.url}" }
                    ) { relay ->
                        RelayCheckboxRow(
                            relay = relay,
                            isSelected = relay.url in selectedUrls,
                            onToggle = { selected ->
                                selectedUrls = if (selected) {
                                    selectedUrls + relay.url
                                } else {
                                    selectedUrls - relay.url
                                }
                            }
                        )
                    }

                    if (section.relays.isEmpty()) {
                        item(key = "empty_${section.id}") {
                            Text(
                                text = "No relays available",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 56.dp, top = 8.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Section header with profile info and toggle-all ─────────────────────────

@Composable
private fun SectionHeaderWithToggle(
    section: RelaySection,
    isExpanded: Boolean,
    allSelected: Boolean,
    someSelected: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onToggleExpand: () -> Unit,
    onToggleAll: (Boolean) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = tween(200),
        label = "chevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        // Profile header (if author is present)
        if (section.author != null) {
            ProfileSectionHeader(
                author = section.author,
                subtitle = section.subtitle
            )
        }

        // Section toggle row
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Section icon
                Icon(
                    imageVector = section.icon ?: Icons.Outlined.Router,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))

                // Title + count
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalCount > 0) {
                        Text(
                            text = "$selectedCount / $totalCount selected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Toggle-all checkbox
                if (totalCount > 0) {
                    TriStateCheckbox(
                        state = when {
                            allSelected -> androidx.compose.ui.state.ToggleableState.On
                            someSelected -> androidx.compose.ui.state.ToggleableState.Indeterminate
                            else -> androidx.compose.ui.state.ToggleableState.Off
                        },
                        onClick = { onToggleAll(!allSelected) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                // Expand/collapse chevron
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    }
}

// ── Profile section header (pfp, display name, NIP-05) ─────────────────────

@Composable
private fun ProfileSectionHeader(
    author: Author,
    subtitle: String? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfilePicture(
                author = author,
                size = 40.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = author.displayName.ifBlank { author.username.ifBlank { author.id.take(12) + "..." } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!author.nip05.isNullOrBlank()) {
                    social.mycelium.android.ui.components.common.Nip05Badge(
                        nip05 = author.nip05,
                        pubkeyHex = author.id,
                        showFullIdentifier = true
                    )
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Individual relay checkbox row ───────────────────────────────────────────

@Composable
private fun RelayCheckboxRow(
    relay: RelayEntry,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isSelected) }
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Relay icon or colored dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = relay.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!relay.description.isNullOrBlank()) {
                Text(
                    text = relay.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle(it) },
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

// ── Helper: Build sections from available data ──────────────────────────────

/**
 * Build the list of [RelaySection]s for the relay selection screen.
 *
 * @param targetAuthor The user being replied to (null for top-level posts).
 * @param targetInboxRelays The target user's inbox (read) relays from NIP-65.
 * @param myAuthor The current user's profile.
 * @param myOutboxRelays The current user's outbox relays.
 * @param relayCategories The current user's custom relay categories.
 * @param noteRelayUrls Relay URLs where the note being replied to was seen.
 */
fun buildRelaySections(
    targetAuthor: Author? = null,
    targetInboxRelays: List<String> = emptyList(),
    myAuthor: Author? = null,
    myOutboxRelays: List<UserRelay> = emptyList(),
    relayCategories: List<RelayCategory> = emptyList(),
    relayProfiles: List<RelayProfile> = emptyList(),
    noteRelayUrls: List<String> = emptyList(),
    taggedUserInboxes: List<Pair<Author, List<String>>> = emptyList(),
    announcementRelays: List<UserRelay> = emptyList()
): List<RelaySection> {
    val sections = mutableListOf<RelaySection>()
    val allUsedUrls = mutableSetOf<String>()

    // Per-user tagged inbox sections (Amethyst-style: each tagged person gets their own section)
    if (taggedUserInboxes.isNotEmpty()) {
        taggedUserInboxes.forEachIndexed { index, (author, inboxRelays) ->
            val displayName = author.displayName.ifBlank { author.username }.ifBlank { author.id.take(8) + "\u2026" }
            val inboxSubtitle = if (inboxRelays.isEmpty()) "Inbox not found via NIP-65" else "Where $displayName reads"
            sections.add(
                RelaySection(
                    id = "tagged_inbox_$index",
                    title = "$displayName\u2019s inbox",
                    subtitle = inboxSubtitle,
                    icon = Icons.Outlined.Inbox,
                    author = author,
                    relays = inboxRelays.filter { it !in allUsedUrls }.map { url ->
                        RelayEntry(
                            url = url,
                            displayName = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                            description = "NIP-65 inbox"
                        )
                    },
                    initiallyExpanded = true,
                    initiallyAllSelected = true
                )
            )
            allUsedUrls.addAll(inboxRelays)
        }
    } else if (targetAuthor != null) {
        // Fallback: single target user section (legacy path)
        val displayName = targetAuthor.displayName.ifBlank { "this user" }
        val inboxSubtitle = if (targetInboxRelays.isEmpty()) "Inbox not found via NIP-65" else "Where $displayName reads"
        sections.add(
            RelaySection(
                id = "target_inbox",
                title = "Their inbox relays",
                subtitle = inboxSubtitle,
                icon = Icons.Outlined.Inbox,
                author = targetAuthor,
                relays = targetInboxRelays.map { url ->
                    RelayEntry(
                        url = url,
                        displayName = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                        description = "NIP-65 inbox"
                    )
                },
                initiallyExpanded = true,
                initiallyAllSelected = true
            )
        )
        allUsedUrls.addAll(targetInboxRelays)
    }

    // Announcement relays — prioritized when composing from the announcements tab
    if (announcementRelays.isNotEmpty()) {
        val uniqueAnnouncement = announcementRelays.filter { it.url !in allUsedUrls }
        if (uniqueAnnouncement.isNotEmpty()) {
            sections.add(
                RelaySection(
                    id = "announcement_relays",
                    title = "Announcement relays",
                    subtitle = "Where your news is published",
                    icon = Icons.Outlined.Campaign,
                    relays = uniqueAnnouncement.map { relay ->
                        RelayEntry(
                            url = relay.url,
                            displayName = relay.displayName,
                            description = relay.description
                        )
                    },
                    initiallyExpanded = true,
                    initiallyAllSelected = true
                )
            )
            allUsedUrls.addAll(uniqueAnnouncement.map { it.url })
        }
    }

    // Section 2: Our outbox relays
    if (myAuthor != null || myOutboxRelays.isNotEmpty()) {
        sections.add(
            RelaySection(
                id = "my_outbox",
                title = "Your outbox relays",
                subtitle = "Where you publish",
                icon = Icons.Outlined.CloudUpload,
                author = myAuthor,
                relays = myOutboxRelays.map { relay ->
                    RelayEntry(
                        url = relay.url,
                        displayName = relay.displayName,
                        description = relay.description
                    )
                },
                initiallyExpanded = true,
                initiallyAllSelected = true
            )
        )
        allUsedUrls.addAll(myOutboxRelays.map { it.url })
    }

    // Section 3: Relays the note was seen on (collapsed, deduplicated against inbox+outbox)
    if (noteRelayUrls.isNotEmpty()) {
        val uniqueSeenRelays = noteRelayUrls.filter { it !in allUsedUrls }.distinct()
        if (uniqueSeenRelays.isNotEmpty()) {
            sections.add(
                RelaySection(
                    id = "note_seen_on",
                    title = "Note seen on",
                    subtitle = "${noteRelayUrls.size} relay${if (noteRelayUrls.size != 1) "s" else ""} total",
                    icon = Icons.Outlined.Router,
                    relays = uniqueSeenRelays.map { url ->
                        RelayEntry(
                            url = url,
                            displayName = url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                            description = "Note observed here"
                        )
                    },
                    initiallyExpanded = true,
                    initiallyAllSelected = false
                )
            )
            allUsedUrls.addAll(uniqueSeenRelays)
        }
    }

    // Section 4+: Custom relay categories
    relayCategories.forEach { category ->
        if (category.relays.isNotEmpty()) {
            val uniqueRelays = category.relays.filter { it.url !in allUsedUrls }
            if (uniqueRelays.isNotEmpty()) {
                sections.add(
                    RelaySection(
                        id = "category_${category.id}",
                        title = category.name,
                        icon = Icons.Outlined.Router,
                        relays = uniqueRelays.map { relay ->
                            RelayEntry(
                                url = relay.url,
                                displayName = relay.displayName,
                                description = relay.description
                            )
                        },
                        initiallyExpanded = true,
                        initiallyAllSelected = false
                    )
                )
                allUsedUrls.addAll(uniqueRelays.map { it.url })
            }
        }
    }

    // Relay profiles: each profile's categories rendered as sections (deduplicated)
    relayProfiles.forEach { profile ->
        profile.categories.forEach { category ->
            if (category.relays.isNotEmpty()) {
                val uniqueRelays = category.relays.filter { it.url !in allUsedUrls }
                if (uniqueRelays.isNotEmpty()) {
                    sections.add(
                        RelaySection(
                            id = "profile_${profile.id}_${category.id}",
                            title = "${profile.name} — ${category.name}",
                            icon = Icons.Outlined.Router,
                            relays = uniqueRelays.map { relay ->
                                RelayEntry(
                                    url = relay.url,
                                    displayName = relay.displayName,
                                    description = relay.description
                                )
                            },
                            initiallyExpanded = true,
                            initiallyAllSelected = false
                        )
                    )
                    allUsedUrls.addAll(uniqueRelays.map { it.url })
                }
            }
        }
    }

    return sections
}
