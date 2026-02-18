package social.mycelium.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import social.mycelium.android.utils.ZapAmountManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

/**
 * Dialog for configuring zap amounts and wallet settings
 */
@Composable
fun ZapConfigurationDialog(
    onDismiss: () -> Unit,
    onOpenWalletSettings: () -> Unit = {}
) {
    var newAmountText by remember { mutableStateOf("") }
    
    // Initialize ZapAmountManager and get shared state
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ZapAmountManager.initialize(context)
    }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()
    
    // LazyListState for controlling scroll position
    val listState = rememberLazyListState()
    
    // Scroll to the end (smallest zaps) when dialog opens
    LaunchedEffect(Unit) {
        if (zapAmounts.isNotEmpty()) {
            // Immediate scroll to prevent flickering
            listState.scrollToItem(zapAmounts.size - 1)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header with wallet icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Wallet icon in top left
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { onOpenWalletSettings() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AccountBalanceWallet,
                            contentDescription = "Wallet Settings",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = "Edit Zaps",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Manage your quick access zap amounts. Tap amounts to remove them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current zap amounts - sorted highest to lowest, aligned right
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(zapAmounts.sortedDescending()) { amount ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                // Remove amount when tapped
                                ZapAmountManager.removeAmount(amount)
                            },
                            label = {
                                Text(
                                    text = social.mycelium.android.utils.ZapUtils.formatZapAmountExact(amount),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFFFFA500),
                                labelColor = Color.White,
                                iconColor = Color.White,
                                disabledContainerColor = Color(0xFFFFA500),
                                disabledLabelColor = Color.White,
                                disabledLeadingIconColor = Color.White
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = Color(0xFFFFA500)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Add new amount - relay-style input with visual plus button
                OutlinedTextField(
                    value = newAmountText,
                    onValueChange = { newAmountText = it },
                    label = { Text("Add zap amount (sats)") },
                    placeholder = { Text("100") },
                    singleLine = true,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val amount = newAmountText.toLongOrNull()
                            if (amount != null && amount > 0 && !zapAmounts.contains(amount)) {
                                ZapAmountManager.addAmount(amount)
                                newAmountText = ""
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (newAmountText.isNotEmpty()) {
                            val amount = newAmountText.toLongOrNull()
                            if (amount != null && amount > 0 && !zapAmounts.contains(amount)) {
                                IconButton(
                                    onClick = {
                                        ZapAmountManager.addAmount(amount)
                                        newAmountText = ""
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = "Add Amount",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
