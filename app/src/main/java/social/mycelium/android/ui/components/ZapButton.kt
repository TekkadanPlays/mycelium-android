package social.mycelium.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import social.mycelium.android.utils.ZapUtils

/**
 * A button component for zapping (tipping) notes on Nostr
 * Supports quick zap and custom amount selection
 */
@Composable
fun ZapButton(
    isZapped: Boolean = false,
    zapCount: Int = 0,
    totalZappedAmount: Long = 0,
    onZap: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCount: Boolean = true,
    iconSize: Int = 20
) {
    var showZapDialog by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isZapped) 1.1f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "zap_scale"
    )

    Row(
        modifier = modifier
            .clickable(enabled = enabled) { showZapDialog = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = "Zap",
            tint = if (isZapped) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(iconSize.dp)
                .scale(scale)
        )

        if (showCount && (zapCount > 0 || totalZappedAmount > 0)) {
            Text(
                text = if (totalZappedAmount > 0) {
                    ZapUtils.formatZapAmount(totalZappedAmount)
                } else {
                    zapCount.toString()
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    color = if (isZapped) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }

    if (showZapDialog) {
        ZapDialog(
            onDismiss = { showZapDialog = false },
            onZap = { amount, comment ->
                onZap(amount, comment)
                showZapDialog = false
            }
        )
    }
}

/**
 * Dialog for selecting zap amount and adding optional comment
 */
@Composable
private fun ZapDialog(
    onDismiss: () -> Unit,
    onZap: (Long, String) -> Unit
) {
    var selectedAmount by remember { mutableStateOf<Long?>(null) }
    var customAmount by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Zap",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = "Close",
                            tint = Color(0xFFFFA500)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Quick amount buttons
                Text(
                    text = "Quick amounts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Amount selection grid
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ZapUtils.DEFAULT_ZAP_AMOUNTS.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { amount ->
                                ZapAmountChip(
                                    amount = amount,
                                    isSelected = selectedAmount == amount,
                                    onClick = {
                                        selectedAmount = amount
                                        showCustomInput = false
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Add empty spaces if row is not complete
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Custom amount button
                    OutlinedButton(
                        onClick = {
                            showCustomInput = !showCustomInput
                            selectedAmount = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (showCustomInput) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            }
                        )
                    ) {
                        Text("Custom amount")
                    }
                }

                // Custom amount input
                AnimatedVisibility(
                    visible = showCustomInput,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customAmount,
                            onValueChange = {
                                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                    customAmount = it
                                    selectedAmount = it.toLongOrNull()
                                }
                            },
                            label = { Text("Amount in sats") },
                            placeholder = { Text("Enter amount") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            trailingIcon = {
                                Text(
                                    text = "sats",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Comment input
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Add a comment (optional)") },
                    placeholder = { Text("Great post! 🚀") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
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
                            selectedAmount?.let { amount ->
                                onZap(amount, comment)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedAmount != null && (selectedAmount ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFA500)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Zap ${selectedAmount?.let { ZapUtils.formatZapAmount(it) } ?: ""}")
                    }
                }
            }
        }
    }
}

/**
 * Chip component for quick zap amount selection
 */
@Composable
private fun ZapAmountChip(
    amount: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            Color(0xFFFFA500).copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFA500))
        } else {
            null
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = "⚡ ${ZapUtils.formatZapAmount(amount)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        Color(0xFFFFA500)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            )
        }
    }
}

/**
 * Compact version of zap button for use in tight spaces
 */
@Composable
fun CompactZapButton(
    isZapped: Boolean = false,
    totalZappedAmount: Long = 0,
    onZap: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var showZapDialog by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showZapDialog = true },
        enabled = enabled,
        modifier = modifier.size(32.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = if (isZapped) {
                        Color(0xFFFFA500).copy(alpha = 0.2f)
                    } else {
                        Color.Transparent
                    },
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = "Zap",
                tint = if (isZapped) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    if (showZapDialog) {
        ZapDialog(
            onDismiss = { showZapDialog = false },
            onZap = { amount, comment ->
                onZap(amount, comment)
                showZapDialog = false
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ZapButtonPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ZapButton(
                isZapped = false,
                zapCount = 0,
                totalZappedAmount = 0,
                onZap = { _, _ -> }
            )

            ZapButton(
                isZapped = true,
                zapCount = 5,
                totalZappedAmount = 5000,
                onZap = { _, _ -> }
            )

            CompactZapButton(
                isZapped = false,
                onZap = { _, _ -> }
            )

            CompactZapButton(
                isZapped = true,
                totalZappedAmount = 1000,
                onZap = { _, _ -> }
            )
        }
    }
}
