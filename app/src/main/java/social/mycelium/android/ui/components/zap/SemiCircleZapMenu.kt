package social.mycelium.android.ui.components.zap

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import social.mycelium.android.repository.NwcConfigRepository
import social.mycelium.android.repository.ZapType
import social.mycelium.android.utils.ZapAmountManager
import androidx.compose.runtime.collectAsState

/**
 * Improved zap menu with better theming and layout
 * Features:
 * - Wallet button opens Wallet Connect popup
 * - Plus button creates custom zap amount popup
 * - Zap amount chips with remove functionality
 * - Invisible card that sizes to content
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZapButtonWithMenu(
    isZapped: Boolean = false,
    zapCount: Int = 0,
    totalZappedAmount: Long = 0,
    onZap: (Long) -> Unit,
    onCustomZap: () -> Unit,
    onCustomZapSend: ((amountSats: Long, zapType: ZapType, message: String) -> Unit)? = null,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }
    var showCustomZapDialog by remember { mutableStateOf(false) }
    
    // Initialize ZapAmountManager and get shared state
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ZapAmountManager.initialize(context)
    }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()

    Column(modifier = modifier) {
        // Main zap button - tap to expand menu, long-press for custom zap dialog
        Box(
            modifier = Modifier
                .size(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = { isMenuExpanded = !isMenuExpanded },
                    onLongClick = { showCustomZapDialog = true }
                ),
            contentAlignment = Alignment.Center
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
        AnimatedVisibility(
            visible = isMenuExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
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
                    ZapAmountChip(
                        amount = amount,
                        isSelected = amount == 1L, // Highlight 1 sat
                        onClick = {
                            isMenuExpanded = false
                            onZap(amount)
                        }
                    )
                }

                // Edit chip - rightmost position
                item {
                    FilterChip(
                        selected = false,
                        onClick = {
                            isMenuExpanded = false
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

    // Wallet Connect dialog (actual NWC dialog)
    if (showWalletConnectDialog) {
        social.mycelium.android.ui.components.zap.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }

    // Custom Zap dialog (long-press)
    if (showCustomZapDialog) {
        ZapCustomDialog(
            onDismiss = { showCustomZapDialog = false },
            onSendZap = { amount, type, msg ->
                showCustomZapDialog = false
                onCustomZapSend?.invoke(amount, type, msg) ?: onZap(amount)
            }
        )
    }
}

@Composable
private fun ZapAmountChip(
    amount: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit = {}
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
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


@Composable
private fun ZapConfigurationDialog(
    zapAmounts: List<Long>,
    onDismiss: () -> Unit,
    onOpenWalletSettings: () -> Unit
) {
    var newAmountText by remember { mutableStateOf("") }

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

                // Current zap amounts
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(zapAmounts.size) { index ->
                        val amount = zapAmounts[index]
                        FilterChip(
                            selected = false,
                                onClick = {
                                // Remove amount when tapped
                                ZapAmountManager.removeAmount(amount)
                                },
                            label = {
                                Text(
                                    text = social.mycelium.android.utils.ZapUtils.formatZapAmount(amount),
                                    fontWeight = FontWeight.Medium
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
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Add new amount - input with plus and clear icons
                OutlinedTextField(
                    value = newAmountText,
                    onValueChange = { newAmountText = it },
                    label = { Text("Add zap amount (sats)") },
                    placeholder = { Text("100") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    trailingIcon = {
                        if (newAmountText.isNotEmpty()) {
                            Row {
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
                                // Clear field button
                                IconButton(
                                    onClick = { newAmountText = "" }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Clear",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(22.dp)
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
                        modifier = Modifier.weight(1f),
                        enabled = newAmountText.isEmpty()
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

/**
 * Custom zap dialog with amount, zap type selection, and optional message.
 * Like Amethyst's ZapCustomDialog: lets users set amount, choose Public/Private/Anonymous/Non-Zap,
 * and add a message before sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapCustomDialog(
    onDismiss: () -> Unit,
    onSendZap: (amountSats: Long, zapType: ZapType, message: String) -> Unit,
    onZapSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val nwcConfig = remember { NwcConfigRepository.getConfig(context) }
    var selectedAmount by remember { mutableStateOf<Long?>(null) }
    var customAmount by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var selectedZapType by remember { mutableStateOf(nwcConfig.zapType()) }
    var message by remember { mutableStateOf("") }
    var zapTypeDropdownExpanded by remember { mutableStateOf(false) }

    val zapTypeOptions = listOf(
        Triple(ZapType.PUBLIC, "Public", "Everyone can see your zap"),
        Triple(ZapType.PRIVATE, "Private", "Only the recipient knows"),
        Triple(ZapType.ANONYMOUS, "Anonymous", "No one knows who sent it"),
        Triple(ZapType.NONZAP, "Non-Zap", "Direct payment, no receipt")
    )

    val selectedZapLabel = zapTypeOptions.first { it.first == selectedZapType }.second

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header — settings icon instead of bolt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Custom Zap",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = {
                            onDismiss()
                            onZapSettings?.invoke()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Zap settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Amount grid
                Text(
                    text = "Choose amount",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val amounts = listOf(21L, 100L, 500L, 1000L, 5000L, 10000L)
                amounts.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { amount ->
                            Surface(
                                onClick = {
                                    selectedAmount = amount
                                    showCustomInput = false
                                    customAmount = ""
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                color = if (selectedAmount == amount && !showCustomInput) Color(0xFFFFA500).copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (selectedAmount == amount && !showCustomInput) androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFFA500)) else null
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = "⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmount(amount)}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (selectedAmount == amount && !showCustomInput) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedAmount == amount && !showCustomInput) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Custom amount
                OutlinedButton(
                    onClick = {
                        showCustomInput = !showCustomInput
                        if (showCustomInput) selectedAmount = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (showCustomInput) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                ) {
                    Text("Custom amount")
                }

                if (showCustomInput) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customAmount,
                        onValueChange = {
                            if (it.isEmpty() || it.all { c -> c.isDigit() }) {
                                customAmount = it
                                selectedAmount = it.toLongOrNull()
                            }
                        },
                        label = { Text("Amount in sats") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        trailingIcon = { Text("sats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Zap type — dropdown menu instead of radio buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Zap type",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = zapTypeDropdownExpanded,
                            onExpandedChange = { zapTypeDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedZapLabel,
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = zapTypeDropdownExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFFFFA500),
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = zapTypeDropdownExpanded,
                                onDismissRequest = { zapTypeDropdownExpanded = false }
                            ) {
                                zapTypeOptions.forEach { (type, label, description) ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(label, fontWeight = FontWeight.Medium)
                                                Text(
                                                    description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedZapType = type
                                            zapTypeDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Message field
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message (optional)") },
                    placeholder = { Text("Zap! ⚡") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Selected amount display + action buttons
                val effectiveAmount = selectedAmount
                if (effectiveAmount != null && effectiveAmount > 0) {
                    // Amount display — separate from button
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        color = Color(0xFFFFA500).copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚡ ${social.mycelium.android.utils.ZapUtils.formatZapAmountExact(effectiveAmount)} sats",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFA500)
                            )
                            Text(
                                text = " • $selectedZapLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

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
                            val amount = selectedAmount
                            if (amount != null && amount > 0) {
                                NwcConfigRepository.saveConfig(
                                    context,
                                    nwcConfig.copy(defaultZapType = selectedZapType.name)
                                )
                                onSendZap(amount, selectedZapType, message)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = selectedAmount != null && (selectedAmount ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Zap")
                    }
                }
            }
        }
    }
}