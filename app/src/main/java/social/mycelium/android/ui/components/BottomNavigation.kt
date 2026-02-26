package social.mycelium.android.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun BottomNavigationBar(
        currentDestination: String,
        onDestinationClick: (String) -> Unit,
        modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier.height(72.dp)) {
        BottomNavDestinations.entries.forEach { destination ->
            NavigationBarItem(
                    icon = {
                        Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label,
                                tint =
                                        if (currentDestination == destination.route) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                        )
                    },
                    selected = currentDestination == destination.route,
                    onClick = { onDestinationClick(destination.route) },
                    colors =
                            NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor =
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = Color.Transparent // Remove the oval background
                            )
            )
        }
    }
}

enum class BottomNavDestinations(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    MESSAGES("messages", "DMs", Icons.Default.Email),
    WALLET("wallet", "Wallet", Icons.Filled.AccountBalanceWallet),
    ANNOUNCEMENTS("announcements", "News", Icons.Outlined.Campaign),
    ALERTS("notifications", "Alerts", Icons.Default.Notifications)
}
