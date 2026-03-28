package social.mycelium.android.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.LiveActivity
import social.mycelium.android.data.LiveActivityStatus
import social.mycelium.android.repository.LiveActivityRepository
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.ui.components.live.LiveActivityCard

/**
 * Full-screen live broadcast explorer.
 * Separates broadcasts into two sections:
 * 1. "From People You Follow" — host is someone we follow (sorted by status then recency)
 * 2. "Discover" — all other broadcasts
 *
 * Each card shows followed viewer orbs when friends are participants/viewers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveExplorerScreen(
    onBackClick: () -> Unit,
    onActivityClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = remember { LiveActivityRepository.getInstance() }
    val profileCache = remember { ProfileMetadataCache.getInstance() }
    val allActivities by repository.allActivities.collectAsState()
    val followedSet by repository.followedPubkeysFlow.collectAsState()

    // Separate followed-host broadcasts from others
    val (followedHostActivities, otherActivities) = remember(allActivities, followedSet) {
        val statusOrder = { activity: LiveActivity ->
            when (activity.status) {
                LiveActivityStatus.LIVE -> 0
                LiveActivityStatus.PLANNED -> 1
                LiveActivityStatus.ENDED -> 2
            }
        }
        val comparator = compareBy<LiveActivity> { statusOrder(it) }
            .thenByDescending { it.createdAt }

        val followed = mutableListOf<LiveActivity>()
        val other = mutableListOf<LiveActivity>()

        for (activity in allActivities) {
            if (activity.hostPubkey.lowercase() in followedSet) {
                followed.add(activity)
            } else {
                other.add(activity)
            }
        }

        followed.sortedWith(comparator) to other.sortedWith(comparator)
    }

    // Pre-compute followed viewer avatars per activity (pubkey → avatarUrl?)
    // Excludes the host — we only want non-host participants who are in our follow list
    val followedViewersMap = remember(allActivities, followedSet) {
        if (followedSet.isEmpty()) emptyMap()
        else allActivities.associate { activity ->
            val hostLower = activity.hostPubkey.lowercase()
            val viewers = activity.participants
                .filter { p ->
                    val pk = p.pubkey.lowercase()
                    pk != hostLower && pk in followedSet
                }
                .map { p ->
                    val author = profileCache.getAuthor(p.pubkey)
                    p.pubkey to author?.avatarUrl
                }
            ("${activity.hostPubkey}:${activity.dTag}") to viewers
        }
    }

    // Count live vs total for the header
    val liveCount = remember(allActivities) { allActivities.count { it.status == LiveActivityStatus.LIVE } }
    val plannedCount = remember(allActivities) { allActivities.count { it.status == LiveActivityStatus.PLANNED } }

    Scaffold(
        topBar = {
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Live",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            if (liveCount > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFEF4444), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$liveCount live",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        modifier = modifier
    ) { padding ->
        if (allActivities.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No live broadcasts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Live streams will appear here when\nsomeone starts broadcasting",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
            ) {
                // ── Section: From People You Follow ──
                if (followedHostActivities.isNotEmpty()) {
                    item(key = "header_followed") {
                        LiveSectionHeader(
                            title = "Following",
                            count = followedHostActivities.size,
                            liveCount = followedHostActivities.count { it.status == LiveActivityStatus.LIVE },
                            isFollowSection = true
                        )
                    }
                    items(
                        items = followedHostActivities,
                        key = { "f:${it.hostPubkey}:${it.dTag}" }
                    ) { activity ->
                        val activityKey = "${activity.hostPubkey}:${activity.dTag}"
                        val viewers = followedViewersMap[activityKey] ?: emptyList()
                        LiveActivityCard(
                            activity = activity,
                            onClick = {
                                val addressableId = Uri.encode(activityKey)
                                onActivityClick(addressableId)
                            },
                            isFollowedHost = true,
                            followedViewerAvatars = viewers
                        )
                    }
                    item(key = "spacer_between") {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // ── Section: Discover ──
                if (otherActivities.isNotEmpty()) {
                    item(key = "header_discover") {
                        LiveSectionHeader(
                            title = if (followedHostActivities.isNotEmpty()) "Discover" else "Broadcasts",
                            count = otherActivities.size,
                            liveCount = otherActivities.count { it.status == LiveActivityStatus.LIVE },
                            isFollowSection = false
                        )
                    }
                    items(
                        items = otherActivities,
                        key = { "d:${it.hostPubkey}:${it.dTag}" }
                    ) { activity ->
                        val activityKey = "${activity.hostPubkey}:${activity.dTag}"
                        val viewers = followedViewersMap[activityKey] ?: emptyList()
                        LiveActivityCard(
                            activity = activity,
                            onClick = {
                                val addressableId = Uri.encode(activityKey)
                                onActivityClick(addressableId)
                            },
                            isFollowedHost = false,
                            followedViewerAvatars = viewers
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun LiveSectionHeader(
    title: String,
    count: Int,
    liveCount: Int,
    isFollowSection: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isFollowSection) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        if (liveCount > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFFEF4444).copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "$liveCount live",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFEF4444),
                    fontSize = 10.sp
                )
            }
        }
    }
}
