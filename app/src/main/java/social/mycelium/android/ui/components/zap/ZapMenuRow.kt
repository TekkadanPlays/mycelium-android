package social.mycelium.android.ui.components.zap

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.utils.ZapAmountManager
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Simple horizontal row of zap chips that appears below the action buttons
 * Completely separate from the zap button - just a row of chips
 */
@Composable
fun ZapMenuRow(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onZap: (Long) -> Unit,
    onCustomZap: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    
    // Initialize ZapAmountManager and get shared state
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ZapAmountManager.initialize(context)
    }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()
    
    // LazyListState for controlling scroll position
    val listState = rememberLazyListState()
    
    // Scroll to the right (end) when menu expands
    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            // Immediate scroll to prevent flickering - no delay needed
            listState.scrollToItem(Int.MAX_VALUE)
        }
    }

    // Simple horizontal row of chips
    AnimatedVisibility(
        visible = isExpanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
                // Zap amounts - sorted largest to smallest (far left)
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
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = Color(0xFFFFA500)
                        )
                    )
                }

                // Custom chip - opens custom zap dialog (same as long-press on bolt)
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            onExpandedChange(false)
                            onCustomZap()
                        },
                        label = { Text("Custom") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Bolt,
                                contentDescription = "Custom amount",
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Edit chip (far right)
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            onExpandedChange(false)
                            onSettingsClick()
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
