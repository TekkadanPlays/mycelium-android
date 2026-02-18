package social.mycelium.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import social.mycelium.android.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import social.mycelium.android.repository.NwcConfigRepository
import social.mycelium.android.repository.NwcConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPreferencesScreen(
    onBackClick: () -> Unit,
    accountStateViewModel: social.mycelium.android.viewmodel.AccountStateViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    
    val initialConfig = remember { NwcConfigRepository.getConfig(context) }

    var showWalletConnectDialog by remember { mutableStateOf(false) }
    var walletConnectPubkey by remember {
        mutableStateOf(initialConfig.pubkey)
    }
    var walletConnectRelay by remember {
        mutableStateOf(initialConfig.relay)
    }
    var walletConnectSecret by remember {
        mutableStateOf(initialConfig.secret)
    }
    var isWalletConnected by remember {
        mutableStateOf(
            initialConfig.pubkey.isNotEmpty() &&
            initialConfig.relay.isNotEmpty() &&
            initialConfig.secret.isNotEmpty()
        )
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = { Text("Account Preferences") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Wallet Connect Setting
            AccountPreferencesItem(
                icon = Icons.Default.AccountBalanceWallet,
                title = "Wallet Connect",
                subtitle = if (isWalletConnected) "Connected" else "Not connected",
                onClick = { showWalletConnectDialog = true }
            )

            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // Wallet Connect Dialog
    if (showWalletConnectDialog) {
        WalletConnectDialog(
            currentPubkey = walletConnectPubkey,
            currentRelay = walletConnectRelay,
            currentSecret = walletConnectSecret,
            onDismiss = { showWalletConnectDialog = false },
            onSave = { pubkey, relay, secret ->
                walletConnectPubkey = pubkey
                walletConnectRelay = relay
                walletConnectSecret = secret
                isWalletConnected = pubkey.isNotEmpty() && relay.isNotEmpty() && secret.isNotEmpty()

                NwcConfigRepository.saveConfig(
                    context,
                    NwcConfig(pubkey = pubkey, relay = relay, secret = secret)
                )

                showWalletConnectDialog = false
            },
            onDisconnect = {
                walletConnectPubkey = ""
                walletConnectRelay = ""
                walletConnectSecret = ""
                isWalletConnected = false

                NwcConfigRepository.clearConfig(context)

                showWalletConnectDialog = false
            }
        )
    }
}

@Composable
private fun AccountPreferencesItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun WalletConnectDialog(
    currentPubkey: String,
    currentRelay: String,
    currentSecret: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    var walletPubkey by remember { mutableStateOf(currentPubkey) }
    var walletRelay by remember { mutableStateOf(currentRelay) }
    var walletSecret by remember { mutableStateOf(currentSecret) }
    var isSecretVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    val isConnected = currentPubkey.isNotEmpty() || currentRelay.isNotEmpty() || currentSecret.isNotEmpty()

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
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Wallet Connect",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Enter your Nostr Wallet Connect (NWC) details to enable zap payments.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Paste NWC URI - right aligned clipboard icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(
                        onClick = {
                            val clipData = clipboardManager.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString()
                                if (text != null) {
                                    val parsed = social.mycelium.android.utils.ZapUtils.parseNwcUri(text)
                                    if (parsed != null) {
                                        walletPubkey = parsed.pubkey
                                        walletRelay = parsed.relay
                                        walletSecret = parsed.secret
                                    } else {
                                        walletPubkey = text
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste NWC URI",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Pubkey field
                OutlinedTextField(
                    value = walletPubkey,
                    onValueChange = { walletPubkey = it },
                    label = { Text("Wallet Service Pubkey") },
                    placeholder = { Text("npub... or hex") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Relay field
                OutlinedTextField(
                    value = walletRelay,
                    onValueChange = { walletRelay = it },
                    label = { Text("Wallet Service Relay") },
                    placeholder = { Text("wss://relay.server.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Secret field
                OutlinedTextField(
                    value = walletSecret,
                    onValueChange = { walletSecret = it },
                    label = { Text("Wallet Service Secret") },
                    placeholder = { Text("Secret key") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (isSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { isSecretVisible = !isSecretVisible }) {
                            Icon(
                                imageVector = if (isSecretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (isSecretVisible) "Hide secret" else "Show secret",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isConnected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect")
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onSave(walletPubkey, walletRelay, walletSecret) },
                        modifier = Modifier.weight(1f),
                        enabled = walletPubkey.isNotEmpty() && walletRelay.isNotEmpty() && walletSecret.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AccountPreferencesScreenPreview() {
    MaterialTheme {
        // Note: Preview doesn't need real AccountStateViewModel
        AccountPreferencesScreen(
            onBackClick = {},
            accountStateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
        )
    }
}
