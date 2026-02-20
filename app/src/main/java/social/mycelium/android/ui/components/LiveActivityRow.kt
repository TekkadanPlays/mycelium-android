package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import social.mycelium.android.data.LiveActivity
import social.mycelium.android.data.LiveActivityStatus

/**
 * Horizontal scrollable row of live activity chips, displayed above the feed.
 * Only visible when there are active live streams. Inspired by Amethyst's approach.
 */
@Composable
fun LiveActivityRow(
    liveActivities: List<LiveActivity>,
    onActivityClick: (LiveActivity) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = liveActivities.isNotEmpty(),
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            Spacer(Modifier.height(2.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(
                    items = liveActivities,
                    key = { "${it.hostPubkey}:${it.dTag}" }
                ) { activity ->
                    LiveActivityChip(
                        activity = activity,
                        onClick = { onActivityClick(activity) }
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * A single live activity chip — tonal button with pulsing live dot,
 * host avatar, and stream title.
 */
@Composable
fun LiveActivityChip(
    activity: LiveActivity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 0.dp, bottom = 0.dp),
        shape = RoundedCornerShape(19.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        )
    ) {
        // Live status dot
        LiveStatusDot(status = activity.status)

        Spacer(Modifier.width(6.dp))

        // Host avatar
        if (activity.hostAuthor?.avatarUrl != null) {
            AsyncImage(
                model = activity.hostAuthor.avatarUrl,
                contentDescription = "Host avatar",
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Host",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(6.dp))

        // Title + participant count
        Column {
            Text(
                text = activity.title ?: activity.hostAuthor?.displayName ?: activity.hostPubkey.take(8),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            activity.currentParticipants?.let { count ->
                if (count > 0) {
                    Text(
                        text = "$count watching",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Full-width live activity card for vertical lists (e.g. LiveExplorerScreen).
 * Shows host avatar, title, participant count, status, and — when friends are
 * watching — a row of profile orbs for followed viewers below the main row.
 *
 * @param isFollowedHost  True when the host is someone we follow (green badge on avatar)
 * @param followedViewerAvatars  Avatar URLs (or null) for followed friends who are
 *                               participants/viewers of this broadcast (excluding the host).
 *                               Drives the green person icon on the right and the orb row.
 */
@Composable
fun LiveActivityCard(
    activity: LiveActivity,
    onClick: () -> Unit,
    isFollowedHost: Boolean = false,
    followedViewerAvatars: List<Pair<String, String?>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val hasFriendViewers = followedViewerAvatars.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Live status dot
            LiveStatusDot(status = activity.status)

            Spacer(Modifier.width(8.dp))

            // Host avatar
            if (activity.hostAuthor?.avatarUrl != null) {
                AsyncImage(
                    model = activity.hostAuthor.avatarUrl,
                    contentDescription = "Host avatar",
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Host",
                    modifier = Modifier.size(24.dp),
                    tint = if (isFollowedHost) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Green person badge for followed hosts (shown even when avatar is present)
            if (isFollowedHost && activity.hostAuthor?.avatarUrl != null) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Followed host",
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF4CAF50)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Title
            Text(
                text = activity.title ?: activity.hostAuthor?.displayName ?: activity.hostPubkey.take(8),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )

            // Participant count — person icon is green when friends are watching
            activity.currentParticipants?.let { count ->
                if (count > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = if (hasFriendViewers) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Status label
            Text(
                text = when (activity.status) {
                    LiveActivityStatus.LIVE -> "LIVE"
                    LiveActivityStatus.PLANNED -> "Planned"
                    LiveActivityStatus.ENDED -> "Ended"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = when (activity.status) {
                    LiveActivityStatus.LIVE -> Color(0xFFEF4444)
                    LiveActivityStatus.PLANNED -> Color(0xFFF59E0B)
                    LiveActivityStatus.ENDED -> MaterialTheme.colorScheme.outline
                }
            )
        }

        // Followed viewer orbs — show profile pics of friends watching this broadcast
        if (hasFriendViewers) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-4).dp)
            ) {
                // Show up to 8 orbs, then "+N" overflow
                val visible = followedViewerAvatars.take(8)
                val overflow = followedViewerAvatars.size - visible.size

                visible.forEach { (pubkey, avatarUrl) ->
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Friend watching",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(Color(0xFF4CAF50).copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = Color(0xFF4CAF50)
                            )
                        }
                    }
                }

                if (overflow > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "+$overflow",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontSize = 10.sp
                    )
                }

                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (followedViewerAvatars.size == 1) "friend watching"
                           else "${followedViewerAvatars.size} friends watching",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        )
    }
}

/**
 * Colored status indicator dot for live activities.
 * Red pulsing for LIVE, amber for PLANNED, gray for ENDED.
 */
@Composable
fun LiveStatusDot(
    status: LiveActivityStatus,
    modifier: Modifier = Modifier
) {
    val color = when (status) {
        LiveActivityStatus.LIVE -> Color(0xFFEF4444) // Red
        LiveActivityStatus.PLANNED -> Color(0xFFF59E0B) // Amber
        LiveActivityStatus.ENDED -> Color(0xFF9CA3AF) // Gray
    }

    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
