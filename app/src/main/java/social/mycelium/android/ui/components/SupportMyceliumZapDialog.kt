package social.mycelium.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import social.mycelium.android.repository.NwcConfigRepository
import social.mycelium.android.repository.ZapType
import social.mycelium.android.services.LnurlResolver
import social.mycelium.android.services.NwcPaymentManager
import social.mycelium.android.services.NwcPaymentResult
import social.mycelium.android.utils.ZapUtils
import kotlinx.coroutines.launch

private const val DEVELOPER_LIGHTNING_ADDRESS = "tekkadan@coinos.io"

@Composable
fun SupportMyceliumZapDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nwcConfig = remember { NwcConfigRepository.getConfig(context) }
    var selectedAmount by remember { mutableStateOf<Long?>(null) }
    var customAmount by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var selectedZapType by remember { mutableStateOf(nwcConfig.zapType()) }
    var comment by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    val nwcConfigured = remember { NwcPaymentManager.isConfigured(context) }

    val zapTypeOptions = listOf(
        Triple(ZapType.PUBLIC, "Public", "Everyone can see your zap."),
        Triple(ZapType.PRIVATE, "Private", "Only the recipient knows who zapped."),
        Triple(ZapType.ANONYMOUS, "Anonymous", "No one knows who sent the zap."),
        Triple(ZapType.NONZAP, "Non-Zap", "Direct payment, no zap receipt.")
    )

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Support Mycelium",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFFA500)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Zap the developer to support Mycelium's development!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!nwcConfigured) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "⚠️ Set up Wallet Connect in Account Preferences first to send zaps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
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
                                    resultMessage = null
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (selectedAmount == amount) Color(0xFFFFA500).copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (selectedAmount == amount) BorderStroke(2.dp, Color(0xFFFFA500)) else null
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(
                                        text = "⚡ ${ZapUtils.formatZapAmount(amount)}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (selectedAmount == amount) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selectedAmount == amount) Color(0xFFFFA500) else MaterialTheme.colorScheme.onSurfaceVariant
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
                        selectedAmount = null
                        resultMessage = null
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
                        trailingIcon = { Text("sats", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Zap type selector
                Text(
                    text = "Zap type",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                zapTypeOptions.forEach { (type, label, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedZapType = type }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedZapType == type,
                            onClick = { selectedZapType = type },
                            modifier = Modifier.size(20.dp),
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFFFA500))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Comment
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Message (optional)") },
                    placeholder = { Text("Love Mycelium! \uD83D\uDC38") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                // Result message
                resultMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = if (isSuccess) Color(0xFF4CAF50).copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val amt = selectedAmount ?: return@Button
                            isLoading = true
                            resultMessage = null
                            scope.launch {
                                // Step 1: Resolve lightning address -> bolt11
                                val invoiceResult = LnurlResolver.fetchInvoice(
                                    lightningAddress = DEVELOPER_LIGHTNING_ADDRESS,
                                    amountSats = amt,
                                    comment = comment
                                )
                                when (invoiceResult) {
                                    is LnurlResolver.LnurlResult.Error -> {
                                        resultMessage = invoiceResult.message
                                        isSuccess = false
                                        isLoading = false
                                    }
                                    is LnurlResolver.LnurlResult.Invoice -> {
                                        // Step 2: Pay via NWC
                                        val payResult = NwcPaymentManager.payInvoice(context, invoiceResult.bolt11)
                                        when (payResult) {
                                            is NwcPaymentResult.Success -> {
                                                resultMessage = "⚡ Zap sent! Thank you for supporting Mycelium! \uD83D\uDC38"
                                                isSuccess = true
                                            }
                                            is NwcPaymentResult.Error -> {
                                                resultMessage = payResult.message
                                                isSuccess = false
                                            }
                                        }
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading && nwcConfigured && selectedAmount != null && (selectedAmount ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA500))
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sending…")
                        } else {
                            Icon(Icons.Filled.Bolt, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Zap ${selectedAmount?.let { ZapUtils.formatZapAmount(it) } ?: ""}")
                        }
                    }
                }
            }
        }
    }
}
