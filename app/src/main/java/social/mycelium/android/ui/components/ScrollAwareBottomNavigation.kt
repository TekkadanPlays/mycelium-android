package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Bottom navigation bar that hides when the header collapses (scroll down)
 * and shows when the header expands (scroll up).
 *
 * Uses standard [NavigationBar] + [NavigationBarItem] per Android docs.
 * [NavigationBarDefaults.windowInsets] handles system nav bar insets.
 */
@Composable
fun ScrollAwareBottomNavigationBar(
    currentDestination: String,
    onDestinationClick: (String) -> Unit,
    isVisible: Boolean = true,
    notificationCount: Int = 0,
    topAppBarState: TopAppBarState,
    modifier: Modifier = Modifier
) {
    // Bar is visible when header is not fully collapsed
    val barVisible by remember(topAppBarState) {
        derivedStateOf { topAppBarState.collapsedFraction < 0.5f }
    }

    AnimatedVisibility(
        visible = isVisible && barVisible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        NavigationBar(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = NavigationBarDefaults.windowInsets
        ) {
            BottomNavDestinations.entries.forEach { destination ->
                NavigationBarItem(
                    icon = {
                        if (destination.route == "notifications" && notificationCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ) {
                                        Text(
                                            text = if (notificationCount > 99) "99+" else notificationCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            }
                        } else {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        }
                    },
                    selected = currentDestination == destination.route,
                    onClick = { onDestinationClick(destination.route) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}
