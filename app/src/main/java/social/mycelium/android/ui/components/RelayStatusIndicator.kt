package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import social.mycelium.android.data.RelayConnectionStatus
import social.mycelium.android.data.UserRelay

@Composable
fun RelayStatusIndicator(
    relays: List<UserRelay>,
    connectionStatus: Map<String, RelayConnectionStatus>,
    onRelayClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectedCount = relays.count { relay ->
        connectionStatus[relay.url] == RelayConnectionStatus.CONNECTED
    }
    val totalCount = relays.size
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onRelayClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status icon
            Icon(
                imageVector = Icons.Default.Router,
                contentDescription = "Relay Status",
                modifier = Modifier.size(20.dp),
                tint = if (connectedCount > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Status text
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Relay Status",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Text(
                    text = if (totalCount == 0) {
                        "No relays configured"
                    } else {
                        "$connectedCount of $totalCount connected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Connection indicator
            if (totalCount > 0) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when {
                        connectedCount == totalCount -> MaterialTheme.colorScheme.primary
                        connectedCount > 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.error
                    }
                ) {
                    Text(
                        text = when {
                            connectedCount == totalCount -> "All Connected"
                            connectedCount > 0 -> "Partial"
                            else -> "Disconnected"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            connectedCount == totalCount -> MaterialTheme.colorScheme.onPrimary
                            connectedCount > 0 -> MaterialTheme.colorScheme.onSecondary
                            else -> MaterialTheme.colorScheme.onError
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            // Arrow icon
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View Relays",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RelayStatusCompact(
    connectedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Router,
            contentDescription = "Relay Status",
            modifier = Modifier.size(16.dp),
            tint = if (connectedCount > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = "$connectedCount/$totalCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RelayStatusIndicatorPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RelayStatusIndicator(
                relays = listOf(
                    UserRelay("wss://relay1.example.com", isOnline = true),
                    UserRelay("wss://relay2.example.com", isOnline = false)
                ),
                connectionStatus = mapOf(
                    "wss://relay1.example.com" to RelayConnectionStatus.CONNECTED,
                    "wss://relay2.example.com" to RelayConnectionStatus.DISCONNECTED
                )
            )
            
            RelayStatusIndicator(
                relays = emptyList(),
                connectionStatus = emptyMap()
            )
        }
    }
}
