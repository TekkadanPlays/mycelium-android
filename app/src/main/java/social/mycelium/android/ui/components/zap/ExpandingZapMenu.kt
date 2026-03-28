package social.mycelium.android.ui.components.zap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.utils.ZapAmountManager
import androidx.compose.runtime.collectAsState

/**
 * Expanding zap menu that appears below the zap button
 * Features:
 * - Right-aligned chips that populate left
 * - Sorted largest to smallest (smallest near tap area)
 * - Tap outside to close functionality
 * - Works in both feed notes and comment sections
 */
@Composable
fun ExpandingZapMenu(
    isZapped: Boolean = false,
    zapCount: Int = 0,
    totalZappedAmount: Long = 0,
    onZap: (Long) -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    // Initialize ZapAmountManager and get shared state
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        ZapAmountManager.initialize(context)
    }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()

    Column(modifier = modifier) {
        // Main zap button
        IconButton(
            onClick = { onExpandedChange(!isExpanded) },
            enabled = enabled,
            modifier = Modifier.size(40.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = "Zap",
                    tint = if (isZapped) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                if (zapCount > 0 || totalZappedAmount > 0) {
                    Text(
                        text = if (totalZappedAmount > 0) {
                            social.mycelium.android.utils.ZapUtils.formatZapAmount(totalZappedAmount)
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
        }

        // Expanding zap menu - single row below the card controls
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 0.dp),
                reverseLayout = true // This makes it right-aligned
            ) {
                // Zap amounts - sorted largest to smallest
                items(zapAmounts.sortedDescending()) { amount ->
                    FilterChip(
                        selected = amount == 1L, // Highlight 1 sat
                        onClick = {
                            onExpandedChange(false)
                            onZap(amount)
                        },
                        label = {
                            Text(
                                text = social.mycelium.android.utils.ZapUtils.formatZapAmount(amount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFA500),
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                            containerColor = Color(0xFFFFA500),
                            labelColor = Color.White,
                            iconColor = Color.White,
                            disabledContainerColor = Color(0xFFFFA500),
                            disabledLabelColor = Color.White,
                            disabledLeadingIconColor = Color.White
                        )
                    )
                }

                // Edit chip - rightmost position
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            onExpandedChange(false)
                            onEditClick()
                        },
                        label = { Text("Edit") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit Zap Amounts",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    }
}
