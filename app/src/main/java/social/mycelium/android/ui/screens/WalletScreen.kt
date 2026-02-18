package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.repository.CoinosRepository
import social.mycelium.android.repository.CoinosTransaction
import com.example.cybin.signer.NostrSigner

private val BitcoinOrange = Color(0xFFFF9900)
private val LightningYellow = Color(0xFFFFD700)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    signer: NostrSigner?,
    pubkey: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) { CoinosRepository.init(context) }

    val isLoggedIn by CoinosRepository.isLoggedIn.collectAsState()
    val username by CoinosRepository.username.collectAsState()
    val balanceSats by CoinosRepository.balanceSats.collectAsState()
    val isLoading by CoinosRepository.isLoading.collectAsState()
    val error by CoinosRepository.error.collectAsState()
    val transactions by CoinosRepository.transactions.collectAsState()
    val lastInvoice by CoinosRepository.lastInvoice.collectAsState()

    if (!isLoggedIn) {
        NostrLoginScreen(
            signer = signer,
            pubkey = pubkey,
            isLoading = isLoading,
            error = error
        )
    } else {
        WalletDashboard(
            username = username ?: "",
            balanceSats = balanceSats,
            isLoading = isLoading,
            error = error,
            transactions = transactions,
            lastInvoice = lastInvoice,
            onRefresh = {
                CoinosRepository.refreshBalance()
                CoinosRepository.fetchTransactions()
            },
            onCreateInvoice = { amount, memo -> CoinosRepository.createInvoice(amount, memo) },
            onPayInvoice = { bolt11 -> CoinosRepository.payInvoice(bolt11) },
            onCopyInvoice = { invoice ->
                clipboardManager.setText(AnnotatedString(invoice))
            },
            onLogout = { CoinosRepository.logout() },
            onClearError = { CoinosRepository.clearError() },
            modifier = modifier
        )
    }
}

@Composable
private fun NostrLoginScreen(
    signer: NostrSigner?,
    pubkey: String?,
    isLoading: Boolean,
    error: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Bitcoin icon
        Surface(
            shape = CircleShape,
            color = BitcoinOrange.copy(alpha = 0.15f),
            modifier = Modifier.size(80.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = BitcoinOrange
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Bitcoin Wallet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Powered by coinos.io",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Sign in with your Nostr identity.\nNo password or captcha needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(32.dp))

        if (signer != null && pubkey != null) {
            Button(
                onClick = { CoinosRepository.loginWithNostr(signer, pubkey) },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BitcoinOrange)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting...", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect with Nostr", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = pubkey.take(8) + "..." + pubkey.takeLast(8),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Sign in to Mycelium with Amber or nsec to connect your wallet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletDashboard(
    username: String,
    balanceSats: Long,
    isLoading: Boolean,
    error: String?,
    transactions: List<CoinosTransaction>,
    lastInvoice: String?,
    onRefresh: () -> Unit,
    onCreateInvoice: (Long, String) -> Unit,
    onPayInvoice: (String) -> Unit,
    onCopyInvoice: (String) -> Unit,
    onLogout: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showReceive by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRefresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        // ── Balance Card ──
        item(key = "balance") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                BitcoinOrange.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = username,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.Logout,
                            contentDescription = "Logout",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onLogout() },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = formatSats(balanceSats),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "sats",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isLoading) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .width(120.dp)
                                .height(2.dp),
                            color = BitcoinOrange
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        WalletActionButton(
                            icon = Icons.Filled.CallReceived,
                            label = "Receive",
                            color = Color(0xFF4CAF50),
                            onClick = { showReceive = !showReceive; showSend = false }
                        )
                        WalletActionButton(
                            icon = Icons.Filled.Send,
                            label = "Send",
                            color = BitcoinOrange,
                            onClick = { showSend = !showSend; showReceive = false }
                        )
                        WalletActionButton(
                            icon = Icons.Filled.Refresh,
                            label = "Refresh",
                            color = MaterialTheme.colorScheme.primary,
                            onClick = onRefresh
                        )
                    }
                }
            }
        }

        // ── Error ──
        if (error != null) {
            item(key = "error") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ── Receive Panel ──
        item(key = "receive_panel") {
            AnimatedVisibility(
                visible = showReceive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ReceivePanel(
                    lastInvoice = lastInvoice,
                    isLoading = isLoading,
                    onCreateInvoice = onCreateInvoice,
                    onCopyInvoice = onCopyInvoice
                )
            }
        }

        // ── Send Panel ──
        item(key = "send_panel") {
            AnimatedVisibility(
                visible = showSend,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                SendPanel(isLoading = isLoading, onPayInvoice = onPayInvoice)
            }
        }

        // ── Transactions ──
        item(key = "tx_header") {
            Text(
                text = "Recent Activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        if (transactions.isEmpty()) {
            item(key = "tx_empty") {
                Text(
                    text = "No transactions yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        } else {
            items(items = transactions.take(50), key = { it.id }) { tx ->
                TransactionRow(tx)
            }
        }
    }
}

@Composable
private fun WalletActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReceivePanel(
    lastInvoice: String?,
    isLoading: Boolean,
    onCreateInvoice: (Long, String) -> Unit,
    onCopyInvoice: (String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Receive Lightning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                label = { Text("Amount (sats)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("Memo (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0L
                    if (amount > 0) onCreateInvoice(amount, memo)
                },
                enabled = (amountText.toLongOrNull() ?: 0L) > 0 && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Create Invoice")
                }
            }

            if (lastInvoice != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyInvoice(lastInvoice) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, Modifier.size(16.dp), tint = LightningYellow)
                            Spacer(Modifier.width(4.dp))
                            Text("Invoice (tap to copy)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = lastInvoice,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendPanel(
    isLoading: Boolean,
    onPayInvoice: (String) -> Unit
) {
    var bolt11 by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Send Lightning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = bolt11,
                onValueChange = { bolt11 = it },
                label = { Text("Lightning invoice (lnbc...)") },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { if (bolt11.isNotBlank()) onPayInvoice(bolt11.trim()) },
                enabled = bolt11.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BitcoinOrange)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pay Invoice")
                }
            }
        }
    }
}

@Composable
private fun TransactionRow(tx: CoinosTransaction) {
    val isIncoming = tx.amount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = if (isIncoming) Color(0xFF4CAF50).copy(alpha = 0.12f) else BitcoinOrange.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (isIncoming) Icons.Filled.CallReceived else Icons.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isIncoming) Color(0xFF4CAF50) else BitcoinOrange
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isIncoming) "Received" else "Sent",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            if (!tx.memo.isNullOrBlank()) {
                Text(
                    text = tx.memo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "${if (isIncoming) "+" else ""}${formatSats(tx.amount)} sats",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isIncoming) Color(0xFF4CAF50) else BitcoinOrange
        )
    }
}

private fun formatSats(sats: Long): String {
    val abs = kotlin.math.abs(sats)
    return when {
        abs >= 1_000_000 -> String.format("%.2fM", abs / 1_000_000.0)
        abs >= 1_000 -> String.format("%,d", abs)
        else -> abs.toString()
    }
}
