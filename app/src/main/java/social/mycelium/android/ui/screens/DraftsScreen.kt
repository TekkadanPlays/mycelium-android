package social.mycelium.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.Draft
import social.mycelium.android.data.DraftType
import social.mycelium.android.data.UserRelay
import social.mycelium.android.relay.RelayEndpointStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftsScreen(
    drafts: List<Draft>,
    onBackClick: () -> Unit,
    onDraftClick: (Draft) -> Unit,
    onDeleteDraft: (String) -> Unit,
    draftsRelays: List<UserRelay> = emptyList(),
    perRelayState: Map<String, RelayEndpointStatus> = emptyMap(),
    onSyncDraft: ((Draft) -> Unit)? = null,
    onRelayClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Drafts")
                        if (drafts.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${drafts.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        if (drafts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No drafts",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Drafts are saved automatically when you leave a compose screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // ── Live drafts relay connection banner ──
                if (draftsRelays.isNotEmpty()) {
                    item(key = "relay_status_banner") {
                        DraftsRelayStatusBanner(
                            draftsRelays = draftsRelays,
                            perRelayState = perRelayState,
                            onRelayClick = onRelayClick
                        )
                    }
                }

                items(items = drafts, key = { it.id }) { draft ->
                    DraftItem(
                        draft = draft,
                        onClick = { onDraftClick(draft) },
                        onDelete = { onDeleteDraft(draft.id) },
                        onSync = if (onSyncDraft != null && draft.syncedRelays.isEmpty() && draftsRelays.isNotEmpty()) {
                            { onSyncDraft(draft) }
                        } else null,
                        draftsRelayUrls = draftsRelays.map { it.url },
                        perRelayState = perRelayState
                    )
                }
            }
        }
    }
}

/**
 * Live connection status banner for configured drafts relays.
 * Shows each relay with animated status orbs — green (connected),
 * amber (connecting), red (failed).
 */
@Composable
private fun DraftsRelayStatusBanner(
    draftsRelays: List<UserRelay>,
    perRelayState: Map<String, RelayEndpointStatus>,
    onRelayClick: ((String) -> Unit)?
) {
    val connectedCount = draftsRelays.count { perRelayState[it.url] == RelayEndpointStatus.Connected }
    val totalCount = draftsRelays.size

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Outlined.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (connectedCount > 0) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "Drafts Relays",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$connectedCount/$totalCount connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (connectedCount == totalCount && totalCount > 0) Color(0xFF4CAF50)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(8.dp))
            // Individual relay rows
            draftsRelays.forEach { relay ->
                val status = perRelayState[relay.url]
                val statusColor = when (status) {
                    RelayEndpointStatus.Connected -> Color(0xFF4CAF50)
                    RelayEndpointStatus.Connecting -> Color(0xFFFF9800)
                    RelayEndpointStatus.Failed -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                }
                val animColor by animateColorAsState(
                    targetValue = statusColor,
                    animationSpec = tween(400),
                    label = "relay_status"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = onRelayClick != null) {
                            onRelayClick?.invoke(relay.url)
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(animColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = relay.url.removePrefix("wss://").removePrefix("ws://").removeSuffix("/"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = when (status) {
                            RelayEndpointStatus.Connected -> "Connected"
                            RelayEndpointStatus.Connecting -> "Connecting…"
                            RelayEndpointStatus.Failed -> "Failed"
                            null -> "Idle"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = animColor,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftItem(
    draft: Draft,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onSync: (() -> Unit)?,
    draftsRelayUrls: List<String>,
    perRelayState: Map<String, RelayEndpointStatus>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = when (draft.type) {
                DraftType.NOTE -> Icons.Outlined.EditNote
                DraftType.ARTICLE -> Icons.Outlined.Article
                DraftType.TOPIC -> Icons.Outlined.Topic
                DraftType.REPLY_KIND1, DraftType.REPLY_KIND1111 -> Icons.AutoMirrored.Outlined.Reply
                DraftType.TOPIC_REPLY -> Icons.Outlined.Forum
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (draft.title != null) {
                Text(
                    draft.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                draft.content.take(120),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    draftTypeLabel(draft.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    formatRelativeTime(draft.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                // ── Sync status orbs ──
                if (draftsRelayUrls.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    DraftSyncStatusOrbs(
                        syncedRelays = draft.syncedRelays,
                        draftsRelayUrls = draftsRelayUrls,
                        perRelayState = perRelayState
                    )
                }
            }
        }

        // Sync button for unsynced drafts with available relays
        if (onSync != null) {
            IconButton(
                onClick = onSync,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = "Sync to relays",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete draft",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

/**
 * Compact sync status orbs showing which drafts relays have received this draft.
 * Green = synced to that relay, dim grey = not synced.
 */
@Composable
private fun DraftSyncStatusOrbs(
    syncedRelays: List<String>,
    draftsRelayUrls: List<String>,
    perRelayState: Map<String, RelayEndpointStatus>
) {
    val syncedSet = remember(syncedRelays) { syncedRelays.toSet() }

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        draftsRelayUrls.forEach { relayUrl ->
            val isSynced = relayUrl in syncedSet
            val isRelayConnected = perRelayState[relayUrl] == RelayEndpointStatus.Connected

            val orbColor = when {
                isSynced -> Color(0xFF4CAF50) // Green — synced
                isRelayConnected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f) // Dim — connected but not synced
                else -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f) // Dim red — relay not connected
            }
            val animColor by animateColorAsState(
                targetValue = orbColor,
                animationSpec = tween(400),
                label = "sync_orb"
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(animColor, CircleShape)
            )
        }

        // Show "Synced" or "Local" label
        if (syncedRelays.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = "Synced",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                fontSize = 9.sp
            )
        } else if (draftsRelayUrls.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = "Local",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                fontSize = 9.sp
            )
        }
    }
}

private fun draftTypeLabel(type: DraftType): String = when (type) {
    DraftType.NOTE -> "Note"
    DraftType.ARTICLE -> "Article"
    DraftType.TOPIC -> "Topic"
    DraftType.REPLY_KIND1 -> "Reply"
    DraftType.REPLY_KIND1111 -> "Thread Reply"
    DraftType.TOPIC_REPLY -> "Topic Reply"
}

private fun formatRelativeTime(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
