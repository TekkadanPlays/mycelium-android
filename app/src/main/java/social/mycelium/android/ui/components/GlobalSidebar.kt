package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.UserRelay
import social.mycelium.android.relay.RelayState
import social.mycelium.android.viewmodel.FeedState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSidebar(
    drawerState: DrawerState,
    activeProfile: RelayProfile? = null,
    outboxRelays: List<UserRelay> = emptyList(),
    inboxRelays: List<UserRelay> = emptyList(),
    feedState: FeedState = FeedState(),
    selectedDisplayName: String = "All Relays",
    relayState: RelayState = RelayState.Disconnected,
    connectionStatus: Map<String, RelayConnectionStatus> = emptyMap(),
    connectedRelayCount: Int = 0,
    subscribedRelayCount: Int = 0,
    indexerRelayCount: Int = 0,
    connectedIndexerCount: Int = 0,
    onItemClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit = {},
    onIndexerClick: () -> Unit = {},
    onRelayHealthClick: () -> Unit = {},
    onRelayDiscoveryClick: () -> Unit = {},
    troubleRelayCount: Int = 0,
    gesturesEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    activeProfile = activeProfile,
                    outboxRelays = outboxRelays,
                    inboxRelays = inboxRelays,
                    expandedCategories = feedState.expandedCategories,
                    selectedDisplayName = selectedDisplayName,
                    relayState = relayState,
                    connectionStatus = connectionStatus,
                    connectedRelayCount = connectedRelayCount,
                    subscribedRelayCount = subscribedRelayCount,
                    indexerRelayCount = indexerRelayCount,
                    connectedIndexerCount = connectedIndexerCount,
                    onItemClick = onItemClick,
                    onToggleCategory = onToggleCategory,
                    onIndexerClick = onIndexerClick,
                    onRelayHealthClick = onRelayHealthClick,
                    onRelayDiscoveryClick = onRelayDiscoveryClick,
                    troubleRelayCount = troubleRelayCount,
                    onClose = {
                        scope.launch {
                            drawerState.close()
                        }
                    }
                )
            }
        },
        modifier = modifier
    ) {
        content()
    }
}

@Composable
private fun DrawerContent(
    activeProfile: RelayProfile?,
    outboxRelays: List<UserRelay>,
    inboxRelays: List<UserRelay>,
    expandedCategories: Set<String>,
    selectedDisplayName: String,
    relayState: RelayState = RelayState.Disconnected,
    connectionStatus: Map<String, RelayConnectionStatus> = emptyMap(),
    connectedRelayCount: Int = 0,
    subscribedRelayCount: Int = 0,
    indexerRelayCount: Int = 0,
    connectedIndexerCount: Int = 0,
    onItemClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onIndexerClick: () -> Unit = {},
    onRelayHealthClick: () -> Unit = {},
    onRelayDiscoveryClick: () -> Unit = {},
    troubleRelayCount: Int = 0,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp)
            .padding(bottom = 80.dp) // Clear of bottom nav bar
    ) {
        // Header with selected relay/category and QR button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Relay Categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Current: $selectedDisplayName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(
                onClick = {
                    onIndexerClick()
                    onClose()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ManageSearch,
                    contentDescription = "Indexers"
                )
            }
            IconButton(
                onClick = {
                    onRelayHealthClick()
                    onClose()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box {
                    Icon(
                        imageVector = Icons.Outlined.HealthAndSafety,
                        contentDescription = "Relay Health",
                        tint = if (troubleRelayCount > 0) MaterialTheme.colorScheme.error
                               else LocalContentColor.current
                    )
                    if (troubleRelayCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-3).dp)
                                .size(8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
            }
            IconButton(
                onClick = {
                    onRelayDiscoveryClick()
                    onClose()
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.TravelExplore,
                    contentDescription = "Discover Relays"
                )
            }
        }

        // Connection status — simple dot + label
        val isConnecting = relayState is RelayState.Connecting
        val isConnected = relayState is RelayState.Connected || relayState is RelayState.Subscribed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when {
                            isConnected && connectedRelayCount > 0 -> MaterialTheme.colorScheme.primary
                            isConnecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.error
                        },
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = when {
                    connectedRelayCount > 0 -> "Connected"
                    isConnecting -> "Connecting\u2026"
                    relayState is RelayState.ConnectFailed -> "Connection failed"
                    else -> "Disconnected"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Global feed button
        val isGlobalSelected = selectedDisplayName == "Global"
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clickable {
                    onItemClick("global")
                    onClose()
                },
            shape = RoundedCornerShape(12.dp),
            color = if (isGlobalSelected) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = "Global feed",
                    modifier = Modifier.size(24.dp),
                    tint = if (isGlobalSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = "Global",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isGlobalSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // ── Merged Outbox + Inbox section with r/w tags ──
        if (outboxRelays.isNotEmpty() || inboxRelays.isNotEmpty()) {
            val mergedRelays = remember(outboxRelays, inboxRelays) {
                val inboxUrls = inboxRelays.map { it.url.trimEnd('/').lowercase() }.toSet()
                val byUrl = linkedMapOf<String, UserRelay>()
                outboxRelays.forEach { r -> byUrl[r.url.trimEnd('/').lowercase()] = r.copy(write = true, read = r.url.trimEnd('/').lowercase() in inboxUrls) }
                inboxRelays.forEach { r ->
                    val key = r.url.trimEnd('/').lowercase()
                    if (key !in byUrl) byUrl[key] = r.copy(read = true, write = false)
                }
                byUrl.values.toList()
            }
            FixedRelaySection(
                title = "Outbox",
                icon = Icons.Default.SwapVert,
                relays = mergedRelays,
                sectionId = "outbox",
                isExpanded = expandedCategories.contains("outbox"),
                connectionStatus = connectionStatus,
                onToggle = { onToggleCategory("outbox") },
                onRelayClick = { url -> onItemClick("relay:$url"); onClose() },
                showRwTags = true
            )
        }
        // ── Active Profile categories ──
        if (activeProfile != null && activeProfile.categories.isNotEmpty()) {
            if (outboxRelays.isNotEmpty() || inboxRelays.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
            // Profile name header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = activeProfile.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ActiveProfileSection(
                categories = activeProfile.categories,
                expandedCategories = expandedCategories,
                connectionStatus = connectionStatus,
                onCategoryClick = { categoryId ->
                    onItemClick("relay_category:$categoryId")
                    onClose()
                },
                onRelayClick = { relayUrl ->
                    onItemClick("relay:$relayUrl")
                    onClose()
                },
                onToggleCategory = onToggleCategory
            )
        }

        // Empty state — only if nothing at all
        if (activeProfile?.categories.isNullOrEmpty() && outboxRelays.isEmpty() && inboxRelays.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No relays configured yet.\nSet up relays in Relay Management.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ── Fixed Relay Section (Outbox / Inbox / Indexer) ──

@Composable
private fun FixedRelaySection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    relays: List<UserRelay>,
    sectionId: String,
    isExpanded: Boolean,
    connectionStatus: Map<String, RelayConnectionStatus>,
    onToggle: () -> Unit,
    onRelayClick: (String) -> Unit,
    showRwTags: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                text = "$title (${relays.size})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (isExpanded) {
        relays.forEach { relay ->
            val relayConnected = connectionStatus[relay.url] == RelayConnectionStatus.CONNECTED
            SidebarRelayRow(
                relay = relay,
                relayConnected = relayConnected,
                onClick = { onRelayClick(relay.url) },
                rwTag = if (showRwTags) when {
                    relay.read && relay.write -> "r/w"
                    relay.read -> "r"
                    relay.write -> "w"
                    else -> null
                } else null
            )
        }
    }
}

// ── Active Profile Category Section ──

@Composable
private fun ActiveProfileSection(
    categories: List<RelayCategory>,
    expandedCategories: Set<String>,
    connectionStatus: Map<String, RelayConnectionStatus> = emptyMap(),
    onCategoryClick: (String) -> Unit,
    onRelayClick: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        categories.forEach { category ->
            val isExpanded = expandedCategories.contains(category.id)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleCategory(category.id) }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "${category.name} (${category.relays.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isExpanded) {
                if (category.relays.isEmpty()) {
                    Text(
                        text = "No relays in this category",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 8.dp)
                    )
                } else {
                    category.relays.forEach { relay ->
                        val relayConnected = connectionStatus[relay.url] == RelayConnectionStatus.CONNECTED
                        SidebarRelayRow(
                            relay = relay,
                            relayConnected = relayConnected,
                            onClick = { onRelayClick(relay.url) }
                        )
                    }
                }
            }
        }
    }
}

// ── Shared Relay Row ──

@Composable
private fun SidebarRelayRow(
    relay: UserRelay,
    relayConnected: Boolean,
    onClick: () -> Unit,
    rwTag: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nip11Cache = remember { social.mycelium.android.cache.Nip11CacheManager.getInstance(context) }
    var relayInfo by remember(relay.url) { mutableStateOf(nip11Cache.getCachedRelayInfo(relay.url)) }
    LaunchedEffect(relay.url) {
        if (relayInfo == null) {
            relayInfo = nip11Cache.getRelayInfo(relay.url)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 40.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection dot — left side
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = if (relayConnected) "Connected" else "Disconnected",
            modifier = Modifier.size(6.dp),
            tint = if (relayConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        val iconUrl = relayInfo?.icon ?: relayInfo?.image
        if (iconUrl != null) {
            coil.compose.AsyncImage(
                model = iconUrl,
                contentDescription = "Relay icon",
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = relayInfo?.name ?: relay.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (rwTag != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = rwTag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
