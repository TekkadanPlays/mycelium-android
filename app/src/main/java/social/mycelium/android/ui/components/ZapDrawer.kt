package social.mycelium.android.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.repository.NwcConfig
import social.mycelium.android.repository.NwcConfigRepository
import social.mycelium.android.repository.ZapType
import social.mycelium.android.utils.ZapAmountManager
import social.mycelium.android.utils.ZapUtils

private val ZapOrange = Color(0xFFFFA500)

private enum class ZapDrawerTab { Zap, Custom, Setup }

/**
 * Full-featured zap drawer — a bottom-sheet-style dialog (like the emoji picker)
 * that serves as the primary zap interaction surface.
 *
 * Tabs:
 * 1. **Zap**: Quick zap chips + "Add amount" input inline
 * 2. **Custom**: Enter a custom sat amount + optional message + zap type
 * 3. **Setup**: NWC verification + wallet connection settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapDrawer(
    onDismiss: () -> Unit,
    onZap: (Long) -> Unit,
    onCustomZapSend: ((Long, ZapType, String) -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ZapAmountManager.initialize(context)
    }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()

    // Mode: Zap, Custom, Setup
    var currentTab by remember { mutableStateOf(ZapDrawerTab.Zap) }

    // Custom zap state
    var customAmount by remember { mutableStateOf("") }
    var customMessage by remember { mutableStateOf("") }
    var selectedZapType by remember { mutableStateOf(ZapType.PUBLIC) }

    // Setup / Edit state
    var newAmountText by remember { mutableStateOf("") }

    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 16.dp)
            ) {
                // ── Drag handle ──
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Header Tabs (Pills) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pill Tabs
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item {
                            ZapTabTitle(
                                icon = Icons.Filled.Bolt,
                                label = "Zap",
                                isSelected = currentTab == ZapDrawerTab.Zap,
                                onClick = { currentTab = ZapDrawerTab.Zap }
                            )
                        }
                        item {
                            ZapTabTitle(
                                icon = Icons.Outlined.Edit,
                                label = "Custom",
                                isSelected = currentTab == ZapDrawerTab.Custom,
                                onClick = { currentTab = ZapDrawerTab.Custom }
                            )
                        }
                        item {
                            ZapTabTitle(
                                icon = Icons.Outlined.Settings,
                                label = "Setup",
                                isSelected = currentTab == ZapDrawerTab.Setup,
                                onClick = { currentTab = ZapDrawerTab.Setup }
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "zap_tab"
                ) { tab ->
                    when (tab) {
                        ZapDrawerTab.Zap -> ZapTabContent(
                            zapAmounts = zapAmounts,
                            onZap = { amount ->
                                onZap(amount)
                                onDismiss()
                            },
                            newAmountText = newAmountText,
                            onNewAmountTextChange = { newAmountText = it },
                            onAddAmount = { amount ->
                                ZapAmountManager.addAmount(amount)
                                newAmountText = ""
                            },
                            onRemoveAmount = { ZapAmountManager.removeAmount(it) }
                        )

                        ZapDrawerTab.Custom -> CustomZapContent(
                            customAmount = customAmount,
                            onAmountChange = { customAmount = it },
                            customMessage = customMessage,
                            onMessageChange = { customMessage = it },
                            selectedZapType = selectedZapType,
                            onZapTypeChange = { selectedZapType = it },
                            onSend = {
                                val amount = customAmount.toLongOrNull()
                                if (amount != null && amount > 0) {
                                    if (onCustomZapSend != null) {
                                        onCustomZapSend(amount, selectedZapType, customMessage)
                                    } else {
                                        onZap(amount)
                                    }
                                    onDismiss()
                                }
                            }
                        )

                        ZapDrawerTab.Setup -> SetupContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun ZapTabTitle(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (isSelected) ZapOrange.copy(alpha = 0.15f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) ZapOrange else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isSelected) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ZapOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
// ─── Zap Tab (Quick Zaps + Setup Inline) ─────────────────────
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ZapTabContent(
    zapAmounts: List<Long>,
    onZap: (Long) -> Unit,
    newAmountText: String,
    onNewAmountTextChange: (String) -> Unit,
    onAddAmount: (Long) -> Unit,
    onRemoveAmount: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // Chip grid — uses FlowRow so amounts wrap to multiple lines
        if (zapAmounts.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                zapAmounts.sortedDescending().forEach { amount ->
                    FilterChip(
                        selected = false,
                        onClick = { onZap(amount) },
                        label = {
                            Text(
                                text = ZapUtils.formatZapAmount(amount),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
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
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onRemoveAmount(amount) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ZapOrange.copy(alpha = 0.12f),
                            labelColor = ZapOrange,
                            iconColor = ZapOrange,
                        ),
                        border = BorderStroke(1.dp, ZapOrange.copy(alpha = 0.3f)),
                        modifier = Modifier.height(40.dp)
                    )
                }
            }
        } else {
            Text(
                "No custom amounts configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Add new amount inline
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newAmountText,
                onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) onNewAmountTextChange(it) },
                placeholder = { Text("Add amount (sats)", fontSize = 13.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val amount = newAmountText.toLongOrNull()
                        if (amount != null && amount > 0 && !zapAmounts.contains(amount)) {
                            onAddAmount(amount)
                        }
                    }
                ),
                modifier = Modifier.weight(1f).height(50.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp)
            )
            val addAmount = newAmountText.toLongOrNull()
            val canAdd = addAmount != null && addAmount > 0 && !zapAmounts.contains(addAmount)
            FilledIconButton(
                onClick = {
                    if (canAdd && addAmount != null) onAddAmount(addAmount)
                },
                enabled = canAdd,
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ZapOrange.copy(alpha = 0.15f),
                    contentColor = ZapOrange
                )
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// ─── Custom Zap Tab ──────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CustomZapContent(
    customAmount: String,
    onAmountChange: (String) -> Unit,
    customMessage: String,
    onMessageChange: (String) -> Unit,
    selectedZapType: ZapType,
    onZapTypeChange: (ZapType) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // Amount input
        OutlinedTextField(
            value = customAmount,
            onValueChange = {
                if (it.isEmpty() || it.all { c -> c.isDigit() }) onAmountChange(it)
            },
            label = { Text("Amount (sats)") },
            placeholder = { Text("Enter amount") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = ZapOrange
                )
            },
            trailingIcon = {
                Text(
                    "sats",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Zap type selector
        Text(
            "Zap type",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZapType.entries.forEach { zapType ->
                FilterChip(
                    selected = selectedZapType == zapType,
                    onClick = { onZapTypeChange(zapType) },
                    label = {
                        Text(
                            text = when (zapType) {
                                ZapType.PUBLIC -> "Public"
                                ZapType.PRIVATE -> "Private"
                                ZapType.ANONYMOUS -> "Anon"
                                ZapType.NONZAP -> "Non-Zap"
                            },
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ZapOrange.copy(alpha = 0.15f),
                        selectedLabelColor = ZapOrange,
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Message
        OutlinedTextField(
            value = customMessage,
            onValueChange = onMessageChange,
            label = { Text("Message (optional)") },
            placeholder = { Text("Great post! \uD83D\uDE80") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        val amount = customAmount.toLongOrNull()
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = amount != null && amount > 0,
            colors = ButtonDefaults.buttonColors(containerColor = ZapOrange),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Zap ${amount?.let { ZapUtils.formatZapAmount(it) } ?: ""}")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ═══════════════════════════════════════════════════════════════
// ─── Setup Tab (NWC configuration) ───────────────────────────
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SetupContent() {
    val context = LocalContext.current
    var initialConfig by remember { mutableStateOf(NwcConfigRepository.getConfig(context)) }

    var walletConnectPubkey by remember(initialConfig) { mutableStateOf(initialConfig.pubkey) }
    var walletConnectRelay by remember(initialConfig) { mutableStateOf(initialConfig.relay) }
    var walletConnectSecret by remember(initialConfig) { mutableStateOf(initialConfig.secret) }
    var isSecretVisible by remember { mutableStateOf(false) }

    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    val isConnected = initialConfig.pubkey.isNotBlank() && initialConfig.relay.isNotBlank() && initialConfig.secret.isNotBlank()
    val isPartiallyConnected = !isConnected && (initialConfig.pubkey.isNotBlank() || initialConfig.relay.isNotBlank() || initialConfig.secret.isNotBlank())
    val canSave = walletConnectPubkey.isNotBlank() && walletConnectRelay.isNotBlank() && walletConnectSecret.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        // Status Badge
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isConnected) Color(0xFF4CAF50).copy(alpha = 0.1f)
            else if (isPartiallyConnected) ZapOrange.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isConnected) Color(0xFF4CAF50)
                    else if (isPartiallyConnected) ZapOrange
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isConnected) "Wallet Connected"
                    else if (isPartiallyConnected) "Partially Configured"
                    else "Not Configured",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) Color(0xFF4CAF50)
                    else if (isPartiallyConnected) ZapOrange
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tool buttons: Disconnect & Paste
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isConnected || isPartiallyConnected) {
                TextButton(
                    onClick = {
                        walletConnectPubkey = ""
                        walletConnectRelay = ""
                        walletConnectSecret = ""
                        NwcConfigRepository.clearConfig(context)
                        initialConfig = NwcConfig()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            TextButton(
                onClick = {
                    val clipData = clipboardManager.primaryClip
                    if (clipData != null && clipData.itemCount > 0) {
                        val text = clipData.getItemAt(0).text?.toString()
                        if (text != null) {
                            val parsed = ZapUtils.parseNwcUri(text)
                            if (parsed != null) {
                                walletConnectPubkey = parsed.pubkey
                                walletConnectRelay = parsed.relay
                                walletConnectSecret = parsed.secret
                            } else {
                                walletConnectPubkey = text
                            }
                        }
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Paste NWC URI")
            }
        }

        // Pubkey field
        OutlinedTextField(
            value = walletConnectPubkey,
            onValueChange = { walletConnectPubkey = it },
            label = { Text("Wallet Service Pubkey") },
            placeholder = { Text("npub... or hex") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Relay field
        OutlinedTextField(
            value = walletConnectRelay,
            onValueChange = { walletConnectRelay = it },
            label = { Text("Wallet Service Relay") },
            placeholder = { Text("wss://relay.server.com") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Secret field
        OutlinedTextField(
            value = walletConnectSecret,
            onValueChange = { walletConnectSecret = it },
            label = { Text("Wallet Service Secret") },
            placeholder = { Text("Secret key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                IconButton(onClick = { isSecretVisible = !isSecretVisible }) {
                    Icon(
                        imageVector = if (isSecretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = "Toggle secret",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                val newCfg = NwcConfig(
                    pubkey = walletConnectPubkey,
                    relay = walletConnectRelay,
                    secret = walletConnectSecret
                )
                NwcConfigRepository.saveConfig(context, newCfg)
                initialConfig = newCfg
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave,
            colors = ButtonDefaults.buttonColors(containerColor = ZapOrange),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save Setup")
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
