package social.mycelium.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import social.mycelium.android.repository.NwcConfigRepository
import social.mycelium.android.repository.NwcConfig

/**
 * Dialog for configuring Nostr Wallet Connect (NWC) settings
 */
@Composable
fun WalletConnectDialog(
    onDismiss: () -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val initialConfig = remember { NwcConfigRepository.getConfig(context) }

    var walletConnectPubkey by remember { mutableStateOf(initialConfig.pubkey) }
    var walletConnectRelay by remember { mutableStateOf(initialConfig.relay) }
    var walletConnectSecret by remember { mutableStateOf(initialConfig.secret) }
    var isSecretVisible by remember { mutableStateOf(false) }
    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    val isConnected = walletConnectPubkey.isNotEmpty() || walletConnectRelay.isNotEmpty() || walletConnectSecret.isNotEmpty()

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

                // Disconnect and Paste buttons - left and right aligned with icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Disconnect button (left) - only show if connected
                    if (isConnected) {
                        IconButton(
                            onClick = {
                                walletConnectPubkey = ""
                                walletConnectRelay = ""
                                walletConnectSecret = ""

                                NwcConfigRepository.clearConfig(context)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Disconnect",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        // Empty space when not connected
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    // Paste button (right)
                    IconButton(
                        onClick = {
                            val clipData = clipboardManager.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString()
                                if (text != null) {
                                    val parsed = social.mycelium.android.utils.ZapUtils.parseNwcUri(text)
                                    if (parsed != null) {
                                        walletConnectPubkey = parsed.pubkey
                                        walletConnectRelay = parsed.relay
                                        walletConnectSecret = parsed.secret
                                    } else {
                                        // If parsing fails, just paste into first field
                                        walletConnectPubkey = text
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
                    value = walletConnectPubkey,
                    onValueChange = { walletConnectPubkey = it },
                    label = { Text("Wallet Service Pubkey") },
                    placeholder = { Text("npub... or hex") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Relay field
                OutlinedTextField(
                    value = walletConnectRelay,
                    onValueChange = { walletConnectRelay = it },
                    label = { Text("Wallet Service Relay") },
                    placeholder = { Text("wss://relay.server.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(12.dp))

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
                    trailingIcon = {
                        IconButton(onClick = { isSecretVisible = !isSecretVisible }) {
                            Icon(
                                imageVector = if (isSecretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (isSecretVisible) "Hide secret" else "Show secret",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

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
                            NwcConfigRepository.saveConfig(
                                context,
                                NwcConfig(
                                    pubkey = walletConnectPubkey,
                                    relay = walletConnectRelay,
                                    secret = walletConnectSecret
                                )
                            )

                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = walletConnectPubkey.isNotEmpty() && walletConnectRelay.isNotEmpty() && walletConnectSecret.isNotEmpty()
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
