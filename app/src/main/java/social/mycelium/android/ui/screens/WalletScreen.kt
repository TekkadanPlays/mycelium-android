package social.mycelium.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.NotificationType
import social.mycelium.android.lightning.PaymentResult
import social.mycelium.android.lightning.PhoenixWalletManager
import social.mycelium.android.lightning.SeedManager
import social.mycelium.android.repository.CoinosRepository
import social.mycelium.android.repository.CoinosTransaction
import social.mycelium.android.repository.NotificationsRepository
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.launch
import social.mycelium.android.BuildConfig

// ── Color palette ──────────────────────────────────────────────────────
// Orange is the star — used ONLY for the balance figure and the primary CTA.
// It's the dopamine color: the number you want to grow.
private val BitcoinOrange = Color(0xFFFF9900)
private val LightningYellow = Color(0xFFFFD700)
// Credit: warm emerald — universally "money in", feels rewarding
private val CreditGreen = Color(0xFF34D399)
private val CreditGreenDim = Color(0xFF059669)
// Debit: muted rose — gentle "money out", not alarming error-red
private val DebitRose = Color(0xFFF87171)
private val DebitRoseDim = Color(0xFFBE4B4B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    signer: NostrSigner?,
    pubkey: String?,
    modifier: Modifier = Modifier
) {
    // Feature gate: release builds show Coming Soon; debug builds show Phoenix wallet
    if (!BuildConfig.WALLET_DEV_MODE) {
        ComingSoonLander()
        return
    }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // Initialize Phoenix wallet manager
    LaunchedEffect(Unit) { PhoenixWalletManager.init(context) }

    val walletState by PhoenixWalletManager.state.collectAsState()
    val balanceMsat by PhoenixWalletManager.balanceMsat.collectAsState()
    val isConnected by PhoenixWalletManager.isConnected.collectAsState()

    // Auto-start if seed exists
    LaunchedEffect(walletState) {
        if (walletState is PhoenixWalletManager.WalletState.Starting) {
            PhoenixWalletManager.start(context)
        }
    }

    when (walletState) {
        is PhoenixWalletManager.WalletState.NotInitialized -> {
            // Still loading
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BitcoinOrange)
            }
        }
        is PhoenixWalletManager.WalletState.NoWallet -> {
            // Show wallet creation/restore screen
            WalletOnboardingScreen(
                onCreateWallet = {
                    scope.launch {
                        PhoenixWalletManager.createWallet(context)
                    }
                },
                onRestoreWallet = { words ->
                    scope.launch {
                        PhoenixWalletManager.restoreWallet(context, words)
                    }
                }
            )
        }
        is PhoenixWalletManager.WalletState.Starting -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = BitcoinOrange)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Starting Lightning node…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        is PhoenixWalletManager.WalletState.Running -> {
            val balanceSats = balanceMsat / 1000

            // Real zap data from notifications
            val allNotifications by NotificationsRepository.notifications.collectAsState()
            val zapTransactions = remember(allNotifications) {
                allNotifications
                    .filter { it.type == NotificationType.ZAP && it.zapAmountSats > 0 }
                    .map { notif ->
                        val senderName = notif.author?.displayName
                            ?: notif.author?.username
                            ?: notif.actorPubkeys.firstOrNull()?.take(8)?.let { "nostr:$it…" }
                        CoinosTransaction(
                            id = notif.id,
                            amount = notif.zapAmountSats,
                            memo = "Zap from $senderName",
                            createdAt = java.time.Instant.ofEpochSecond(notif.sortTimestamp).toString(),
                            confirmed = true,
                            type = "zap"
                        )
                    }
            }
            val totalZapSats = remember(zapTransactions) { zapTransactions.sumOf { it.amount } }

            var lastInvoice by remember { mutableStateOf<String?>(null) }
            var isLoading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }

            WalletDashboard(
                username = if (isConnected) "⚡ Connected" else "Connecting…",
                balanceSats = balanceSats,
                isLoading = isLoading || !isConnected,
                error = error,
                transactions = zapTransactions,
                lastInvoice = lastInvoice,
                isMockMode = false,
                zapCount = zapTransactions.size,
                totalZapSats = totalZapSats,
                onRefresh = { /* balance is live from channelsFlow */ },
                onCreateInvoice = { amount, memo ->
                    scope.launch {
                        isLoading = true
                        val invoice = PhoenixWalletManager.createInvoice(amount * 1000, memo)
                        if (invoice != null) {
                            lastInvoice = invoice
                        } else {
                            error = "Failed to create invoice"
                        }
                        isLoading = false
                    }
                },
                onPayInvoice = { bolt11 ->
                    scope.launch {
                        isLoading = true
                        when (val result = PhoenixWalletManager.payInvoice(bolt11)) {
                            is PaymentResult.Success -> { error = null }
                            is PaymentResult.Error -> { error = result.message }
                        }
                        isLoading = false
                    }
                },
                onCopyInvoice = { invoice ->
                    clipboardManager.setText(AnnotatedString(invoice))
                },
                onLogout = {
                    PhoenixWalletManager.stop()
                    SeedManager.deleteSeed(context)
                },
                onClearError = { error = null },
                modifier = modifier
            )
        }
        is PhoenixWalletManager.WalletState.Error -> {
            val errorState = walletState as PhoenixWalletManager.WalletState.Error
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = DebitRose
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Lightning node error",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { scope.launch { PhoenixWalletManager.start(context) } },
                        colors = ButtonDefaults.buttonColors(containerColor = BitcoinOrange)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun WalletOnboardingScreen(
    onCreateWallet: () -> Unit,
    onRestoreWallet: (List<String>) -> Unit
) {
    var showRestore by remember { mutableStateOf(false) }
    var restoreWords by remember { mutableStateOf("") }
    var restoreError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Orange glow behind bolt
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BitcoinOrange.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = "Lightning",
                    modifier = Modifier.size(72.dp),
                    tint = BitcoinOrange
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Lightning Wallet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Non-custodial · Your keys, your coins",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            if (!showRestore) {
                // Create new wallet
                Button(
                    onClick = onCreateWallet,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BitcoinOrange),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(0.75f)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create Wallet", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { showRestore = true },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(0.75f)
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore from Seed", fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Restore wallet
                OutlinedTextField(
                    value = restoreWords,
                    onValueChange = { restoreWords = it; restoreError = null },
                    label = { Text("12-word recovery phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 3,
                    maxLines = 4
                )

                if (restoreError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = restoreError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = DebitRose
                    )
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        val words = restoreWords.trim().lowercase().split("\\s+".toRegex())
                        if (words.size == 12) {
                            onRestoreWallet(words)
                        } else {
                            restoreError = "Please enter exactly 12 words"
                        }
                    },
                    enabled = restoreWords.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BitcoinOrange),
                    modifier = Modifier.fillMaxWidth(0.75f)
                ) {
                    Text("Restore Wallet", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { showRestore = false; restoreWords = ""; restoreError = null }) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun ComingSoonLander() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            // Subtle orange radial glow behind the icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BitcoinOrange.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = social.mycelium.android.R.drawable.ic_coinos_lightning),
                    contentDescription = "Lightning",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Coming soon..",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                letterSpacing = 1.5.sp
            )
        }
    }
}

@Composable
private fun NostrLoginScreen(
    signer: NostrSigner?,
    pubkey: String?,
    isLoading: Boolean,
    error: String?
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Orange glow behind bolt
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    BitcoinOrange.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = social.mycelium.android.R.drawable.ic_coinos_lightning),
                    contentDescription = "Lightning",
                    modifier = Modifier.size(72.dp),
                    tint = BitcoinOrange
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "CoinOS Wallet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = if (CoinosRepository.isMockMode) "Development mode — no real funds" else "Lightning wallet powered by CoinOS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            // Primary CTA — the ONE place orange earns its spot
            Button(
                onClick = {
                    if (signer != null && pubkey != null) {
                        CoinosRepository.loginWithNostr(signer, pubkey)
                    }
                },
                enabled = signer != null && pubkey != null && !isLoading,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BitcoinOrange,
                    disabledContainerColor = BitcoinOrange.copy(alpha = 0.3f)
                ),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(0.75f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…", fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect with Nostr", fontWeight = FontWeight.SemiBold)
                }
            }

            if (error != null) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DebitRose.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = DebitRose,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            if (signer == null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Nostr signer required.\nPlease set up Amber.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
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
    isMockMode: Boolean = false,
    zapCount: Int = 0,
    totalZapSats: Long = 0L,
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

    val surfaceColor = MaterialTheme.colorScheme.surface

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        // ── Balance Hero ──
        item(key = "balance") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                BitcoinOrange.copy(alpha = 0.06f),
                                surfaceColor
                            )
                        )
                    )
                    .padding(top = 28.dp, bottom = 20.dp, start = 24.dp, end = 24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Dev mode pill
                    if (isMockMode) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = "DEV MODE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    // Username + logout
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = username,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = "Logout",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onLogout() },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // THE balance — orange is earned here
                    Text(
                        text = formatSats(balanceSats),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        ),
                        color = BitcoinOrange
                    )
                    Text(
                        text = "sats",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        letterSpacing = 2.sp
                    )

                    // Zap summary line (real data)
                    if (zapCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = LightningYellow.copy(alpha = 0.8f)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "$zapCount zaps · ${formatSats(totalZapSats)} sats received",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }

                    if (isLoading) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .width(100.dp)
                                .height(1.5.dp)
                                .clip(RoundedCornerShape(1.dp)),
                            color = BitcoinOrange.copy(alpha = 0.6f),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    }

                    Spacer(Modifier.height(28.dp))

                    // ── Action buttons ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        WalletActionButton(
                            icon = Icons.AutoMirrored.Filled.CallReceived,
                            label = "Receive",
                            color = CreditGreen,
                            isActive = showReceive,
                            onClick = { showReceive = !showReceive; showSend = false }
                        )
                        WalletActionButton(
                            icon = Icons.AutoMirrored.Filled.Send,
                            label = "Send",
                            color = DebitRose,
                            isActive = showSend,
                            onClick = { showSend = !showSend; showReceive = false }
                        )
                        WalletActionButton(
                            icon = Icons.Filled.Refresh,
                            label = "Refresh",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            isActive = false,
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
                    shape = RoundedCornerShape(10.dp),
                    color = DebitRose.copy(alpha = 0.10f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = DebitRose,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp), tint = DebitRose.copy(alpha = 0.6f))
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

        // ── Section divider ──
        item(key = "tx_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (transactions.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${transactions.size} transactions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (transactions.isEmpty()) {
            item(key = "tx_empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.Receipt,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "No transactions yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            items(items = transactions.take(100), key = { it.id }) { tx ->
                TransactionRow(tx)
            }
        }
    }
}

// ── Action Button ────────────────────────────────────────────────────────

@Composable
private fun WalletActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (isActive) 0.20f else 0.08f,
        animationSpec = tween(200),
        label = "actionBg"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = bgAlpha),
            modifier = Modifier.size(50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) color else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ── Receive Panel ────────────────────────────────────────────────────────

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(CreditGreen, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Receive Lightning",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(14.dp))

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
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    val amount = amountText.toLongOrNull() ?: 0L
                    if (amount > 0) onCreateInvoice(amount, memo)
                },
                enabled = (amountText.toLongOrNull() ?: 0L) > 0 && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CreditGreenDim,
                    disabledContainerColor = CreditGreenDim.copy(alpha = 0.3f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text("Create Invoice", fontWeight = FontWeight.SemiBold)
                }
            }

            if (lastInvoice != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCopyInvoice(lastInvoice) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bolt, null, Modifier.size(14.dp), tint = LightningYellow)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Tap to copy invoice",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = lastInvoice,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

// ── Send Panel ──────────────────────────────────────────────────────────

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(DebitRose, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Send Lightning",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = bolt11,
                onValueChange = { bolt11 = it },
                label = { Text("Lightning invoice (lnbc…)") },
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(14.dp))

            Button(
                onClick = { if (bolt11.isNotBlank()) onPayInvoice(bolt11.trim()) },
                enabled = bolt11.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DebitRoseDim,
                    disabledContainerColor = DebitRoseDim.copy(alpha = 0.3f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Icon(Icons.Filled.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pay Invoice", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Transaction Row ─────────────────────────────────────────────────────

@Composable
private fun TransactionRow(tx: CoinosTransaction) {
    val isIncoming = tx.amount > 0
    val isZap = tx.type == "zap"

    val iconColor = if (isIncoming) CreditGreen else DebitRose
    val icon = when {
        isZap -> Icons.Filled.Bolt
        isIncoming -> Icons.AutoMirrored.Filled.CallReceived
        else -> Icons.AutoMirrored.Filled.Send
    }
    val label = when {
        isZap -> "Zap received"
        isIncoming -> "Received"
        else -> "Sent"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon circle
        Surface(
            shape = CircleShape,
            color = iconColor.copy(alpha = 0.10f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = iconColor
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // Label + memo
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!tx.memo.isNullOrBlank()) {
                Text(
                    text = tx.memo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Amount — green for in, rose for out. No orange here.
        Text(
            text = "${if (isIncoming) "+" else "−"}${formatSats(kotlin.math.abs(tx.amount))}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = iconColor
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "sats",
            style = MaterialTheme.typography.labelSmall,
            color = iconColor.copy(alpha = 0.5f)
        )
    }
}

// ── Utilities ────────────────────────────────────────────────────────────

private fun formatSats(sats: Long): String {
    val abs = kotlin.math.abs(sats)
    return when {
        abs >= 1_000_000 -> String.format("%.2fM", abs / 1_000_000.0)
        abs >= 100_000 -> String.format("%,d", abs)
        abs >= 1_000 -> String.format("%,d", abs)
        else -> abs.toString()
    }
}
