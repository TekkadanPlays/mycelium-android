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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Videocam
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
 * Modern live activity card for vertical lists (e.g. LiveExplorerScreen).
 * Features a thumbnail image with status pill overlay, host info row,
 * viewer count badge, and followed-friend indicators.
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
    ) {
        // ── Thumbnail with status pill overlay ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Color.Black.copy(alpha = 0.8f))
        ) {
            if (activity.imageUrl != null) {
                AsyncImage(
                    model = activity.imageUrl,
                    contentDescription = "Stream preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Gradient scrim at bottom for text legibility
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                )
            } else {
                // No thumbnail — show a dark placeholder with icon
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.2f)
                    )
                }
            }

            // Status pill — top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(
                        color = when (activity.status) {
                            LiveActivityStatus.LIVE -> Color(0xFFEF4444)
                            LiveActivityStatus.PLANNED -> Color(0xFFF59E0B)
                            LiveActivityStatus.ENDED -> Color(0xFF6B7280)
                        },
                        shape = RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = when (activity.status) {
                        LiveActivityStatus.LIVE -> "LIVE"
                        LiveActivityStatus.PLANNED -> "SCHEDULED"
                        LiveActivityStatus.ENDED -> "ENDED"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
            }

            // Viewer count pill — top-right
            activity.currentParticipants?.let { count ->
                if (count > 0) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = if (hasFriendViewers) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            text = formatViewerCount(count),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Title overlaid on bottom of thumbnail
            Text(
                text = activity.title ?: "Untitled Stream",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth(0.85f)
            )
        }

        // ── Host info row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Host avatar with optional follow ring
            Box {
                if (activity.hostAuthor?.avatarUrl != null) {
                    AsyncImage(
                        model = activity.hostAuthor.avatarUrl,
                        contentDescription = "Host avatar",
                        modifier = Modifier
                            .size(32.dp)
                            .then(
                                if (isFollowedHost) Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                                    .padding(1.5.dp)
                                else Modifier
                            )
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (isFollowedHost) Color(0xFF4CAF50).copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isFollowedHost) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = activity.hostAuthor?.displayName
                            ?: activity.hostAuthor?.username
                            ?: activity.hostPubkey.take(12) + "…",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isFollowedHost) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Following",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50),
                            fontSize = 10.sp
                        )
                    }
                }
                if (activity.summary != null) {
                    Text(
                        text = activity.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                } else if (activity.hashtags.isNotEmpty()) {
                    Text(
                        text = activity.hashtags.take(3).joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // ── Friends watching row ──
        if (hasFriendViewers) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-6).dp)
            ) {
                val visible = followedViewerAvatars.take(6)
                val overflow = followedViewerAvatars.size - visible.size

                visible.forEach { (_, avatarUrl) ->
                    if (avatarUrl != null) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Friend watching",
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                                .padding(1.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color(0xFF4CAF50).copy(alpha = 0.2f), CircleShape),
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

                Spacer(Modifier.width(10.dp))
                Text(
                    text = buildString {
                        if (overflow > 0) append("+$overflow · ")
                        append(
                            if (followedViewerAvatars.size == 1) "1 friend watching"
                            else "${followedViewerAvatars.size} friends watching"
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** Format viewer count: 1.2k, 15k, etc. */
private fun formatViewerCount(count: Int): String = when {
    count >= 1_000_000 -> "${count / 1_000_000}.${(count % 1_000_000) / 100_000}M"
    count >= 10_000 -> "${count / 1000}k"
    count >= 1_000 -> "${count / 1000}.${(count % 1000) / 100}k"
    else -> count.toString()
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
