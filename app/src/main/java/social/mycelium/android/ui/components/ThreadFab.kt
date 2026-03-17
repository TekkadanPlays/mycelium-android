package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Reusable expanding FAB for thread views.
 *
 * Shows a "+" button that rotates to "×" when expanded, revealing action mini-FABs:
 * - Toggle root/all replies (collapse to root-only or expand all)
 * - Jump to next comment
 * - Jump to previous comment
 * - Reply action(s) (kind-1 or kind-1111 depending on context)
 *
 * @param listState LazyListState for jump-to-comment navigation
 * @param replyItems List of reply menu items to show (label, icon, onClick)
 * @param firstReplyIndex The index in the LazyColumn where replies start (after header items)
 * @param totalItems Total items in the LazyColumn
 * @param showRootOnly Current state of root/all toggle
 * @param onToggleRootOnly Callback to toggle between root replies and all replies
 * @param rootOnlyAvailable Whether the toggle should appear (only for threaded views with children)
 */
@Composable
fun ThreadFab(
    listState: LazyListState,
    replyItems: List<FabMenuItem>,
    firstReplyIndex: Int = 2,
    totalItems: Int = 0,
    showRootOnly: Boolean = false,
    onToggleRootOnly: () -> Unit = {},
    rootOnlyAvailable: Boolean = false,
    /** True = newest first (descending); false = oldest first (ascending, default). */
    isNewestFirst: Boolean = false,
    onToggleSortOrder: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = tween(200),
        label = "fab_rotation"
    )

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.navigationBarsPadding()
    ) {
        // Expanded menu items
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(tween(150)),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(tween(100))
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Reply action(s) — at top of menu (furthest from FAB)
                replyItems.forEach { item ->
                    MiniFabItem(
                        label = item.label,
                        icon = item.icon,
                        containerColor = item.containerColor ?: MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = item.contentColor ?: MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            expanded = false
                            item.onClick()
                        }
                    )
                }

                // Jump to previous comment
                MiniFabItem(
                    label = "Prev",
                    icon = Icons.Default.KeyboardArrowUp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        scope.launch {
                            val current = listState.firstVisibleItemIndex
                            val target = (current - 1).coerceAtLeast(firstReplyIndex)
                            listState.animateScrollToItem(target)
                        }
                    }
                )

                // Jump to next comment
                MiniFabItem(
                    label = "Next",
                    icon = Icons.Default.KeyboardArrowDown,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = {
                        scope.launch {
                            val current = listState.firstVisibleItemIndex
                            val maxIndex = (totalItems - 1).coerceAtLeast(firstReplyIndex)
                            val target = (current + 1).coerceAtMost(maxIndex)
                            listState.animateScrollToItem(target)
                        }
                    }
                )

                // Sort order toggle (newest/oldest first)
                if (onToggleSortOrder != null) {
                    MiniFabItem(
                        label = if (isNewestFirst) "Newest" else "Oldest",
                        icon = if (isNewestFirst) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        containerColor = if (isNewestFirst) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (isNewestFirst) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onToggleSortOrder()
                        }
                    )
                }

                // Toggle root/all replies — nearest to FAB
                if (rootOnlyAvailable) {
                    MiniFabItem(
                        label = if (showRootOnly) "All" else "Root",
                        icon = if (showRootOnly) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        containerColor = if (showRootOnly) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (showRootOnly) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onToggleRootOnly()
                        }
                    )
                }
            }
        }

        // Main FAB
        FloatingActionButton(
            onClick = { expanded = !expanded },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = if (expanded) "Close menu" else "Open menu",
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}

/**
 * A single mini-FAB row: icon + label badge.
 */
@Composable
private fun MiniFabItem(
    label: String,
    icon: ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Label chip
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 2.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        SmallFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = containerColor,
            contentColor = contentColor,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Data class for a FAB menu reply item.
 */
data class FabMenuItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val containerColor: androidx.compose.ui.graphics.Color? = null,
    val contentColor: androidx.compose.ui.graphics.Color? = null,
)
