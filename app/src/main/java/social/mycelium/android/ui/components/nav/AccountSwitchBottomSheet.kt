package social.mycelium.android.ui.components.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.mycelium.android.data.AccountInfo
import social.mycelium.android.repository.relay.RelayStorageManager
import social.mycelium.android.viewmodel.AccountStateViewModel

/**
 * Bottom sheet for switching between multiple Nostr accounts.
 * Shows relay configuration status per account so the user knows
 * which accounts are fully set up vs need onboarding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitchBottomSheet(
    accountStateViewModel: AccountStateViewModel,
    onDismiss: () -> Unit = {},
    onAddAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val storageManager = remember(context) { RelayStorageManager(context) }
    val savedAccounts by accountStateViewModel.savedAccounts.collectAsState()
    val currentAccount by accountStateViewModel.currentAccount.collectAsState()
    val scrollState = rememberScrollState()

    var showLogoutDialog by remember { mutableStateOf<AccountInfo?>(null) }

    // Pre-compute relay counts per account so we can show status
    val relayCountByNpub = remember(savedAccounts) {
        savedAccounts.associate { account ->
            val hex = account.toHexKey()
            val count = if (hex != null) {
                val categories = storageManager.loadCategories(hex)
                categories.sumOf { it.relays.size }
            } else 0
            account.npub to count
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = "Accounts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // Account list
            if (savedAccounts.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "No accounts yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Sign in with Amber to get started",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { onAddAccount() },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("Login with Amber")
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/greenart7c3/Amber")))
                        },
                        modifier = Modifier.fillMaxWidth(0.7f)
                    ) {
                        Text("Download Amber")
                    }
                }
            } else {
                Spacer(Modifier.height(4.dp))
                savedAccounts.forEach { account ->
                    val relayCount = relayCountByNpub[account.npub] ?: 0
                    AccountListItem(
                        account = account,
                        isCurrentAccount = account.npub == currentAccount?.npub,
                        relayCount = relayCount,
                        onAccountClick = {
                            if (account.npub != currentAccount?.npub) {
                                accountStateViewModel.switchToAccount(account)
                            }
                            onDismiss()
                        },
                        onLogoutClick = {
                            showLogoutDialog = account
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // Add account button — only show when accounts exist (empty state has its own login button)
            if (savedAccounts.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddAccount() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add account",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "Add Another Account",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    // Logout confirmation dialog
    showLogoutDialog?.let { account ->
        AlertDialog(
            onDismissRequest = { showLogoutDialog = null },
            title = { Text("Log Out") },
            text = {
                Text("Remove ${account.getDisplayNameOrNpub()} from this device? Relay configuration will be cleared.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        accountStateViewModel.logoutAccount(account)
                        showLogoutDialog = null
                    }
                ) {
                    Text("Log Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AccountListItem(
    account: AccountInfo,
    isCurrentAccount: Boolean,
    relayCount: Int,
    onAccountClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val needsSetup = relayCount == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAccountClick)
            .then(
                if (isCurrentAccount) Modifier.background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                ) else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (isCurrentAccount) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!account.picture.isNullOrBlank()) {
                AsyncImage(
                    model = account.picture,
                    contentDescription = "Profile",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = account.displayName?.take(1)?.uppercase()
                        ?: account.toShortNpub().take(1).uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isCurrentAccount) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Account info + relay status
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.displayName ?: account.toShortNpub(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrentAccount) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = account.toShortNpub(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )

                if (account.isExternalSigner) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "· Amber",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Relay status badge
                if (needsSetup) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(9.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "needs setup",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 9.sp
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Public,
                                contentDescription = null,
                                modifier = Modifier.size(9.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "$relayCount relay${if (relayCount != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // Active indicator
        if (isCurrentAccount) {
            Icon(
                imageVector = Icons.Default.RadioButtonChecked,
                contentDescription = "Active account",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Logout button
        IconButton(
            onClick = onLogoutClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = "Log out",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
