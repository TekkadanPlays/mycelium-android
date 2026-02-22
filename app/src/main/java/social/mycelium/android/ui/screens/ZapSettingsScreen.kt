package social.mycelium.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.mycelium.android.utils.ZapAmountManager
import social.mycelium.android.utils.ZapUtils

/**
 * Full-page zap settings screen — singleton destination replacing the old dialog pop-ups.
 * Manages quick-access zap amounts and wallet configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZapSettingsScreen(
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ZapAmountManager.initialize(context) }
    val zapAmounts by ZapAmountManager.zapAmounts.collectAsState()
    var newAmountText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zap Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
        ) {
            // ── Section: Quick Zap Amounts ──
            item(key = "header_amounts") {
                Text(
                    text = "Quick Zap Amounts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item(key = "desc_amounts") {
                Text(
                    text = "These amounts appear in your zap menu for one-tap sending. Tap the \u00D7 to remove an amount.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Amount chips
            val sorted = zapAmounts.sortedDescending()
            items(sorted, key = { "amt_$it" }) { amount ->
                ZapAmountRow(
                    amount = amount,
                    onRemove = { ZapAmountManager.removeAmount(amount) }
                )
            }

            if (sorted.isEmpty()) {
                item(key = "empty_amounts") {
                    Text(
                        text = "No zap amounts configured. Add one below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // ── Add new amount ──
            item(key = "add_amount") {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = newAmountText,
                    onValueChange = { newAmountText = it },
                    label = { Text("Add zap amount (sats)") },
                    placeholder = { Text("e.g. 1000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            val amount = newAmountText.toLongOrNull()
                            if (amount != null && amount > 0 && !zapAmounts.contains(amount)) {
                                ZapAmountManager.addAmount(amount)
                                newAmountText = ""
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        val amount = newAmountText.toLongOrNull()
                        if (amount != null && amount > 0 && !zapAmounts.contains(amount)) {
                            IconButton(onClick = {
                                ZapAmountManager.addAmount(amount)
                                newAmountText = ""
                            }) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                )
            }

            // ── Section: Default Zap ──
            item(key = "header_default") {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Default Zap",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item(key = "desc_default") {
                Text(
                    text = "The amount sent when you single-tap the zap button on a note.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item(key = "default_chips") {
                val defaultAmount = zapAmounts.minOrNull() ?: 1L
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sorted.forEach { amount ->
                        FilterChip(
                            selected = amount == defaultAmount,
                            onClick = { /* TODO: persist default zap amount preference */ },
                            label = { Text(ZapUtils.formatZapAmountExact(amount)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZapAmountRow(
    amount: Long,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFA500)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = ZapUtils.formatZapAmountExact(amount) + " sats",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
