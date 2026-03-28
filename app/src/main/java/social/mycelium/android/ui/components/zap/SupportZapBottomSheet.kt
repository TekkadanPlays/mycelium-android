package social.mycelium.android.ui.components.zap

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import social.mycelium.android.services.LnurlResolver
import social.mycelium.android.services.NwcPaymentManager
import social.mycelium.android.services.NwcPaymentResult

private const val DEVELOPER_LIGHTNING_ADDRESS = "tekkadan@coinos.io"

/**
 * Support-the-developer zap flow using the modern ZapBottomSheet drawer.
 *
 * Wraps [ZapBottomSheet] and connects both quick-zap and custom-zap actions
 * to the same LNURL → NWC payment pipeline that the old SupportMyceliumZapDialog used.
 */
@Composable
fun SupportZapBottomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nwcConfigured = remember { NwcPaymentManager.isConfigured(context) }

    ZapBottomSheet(
        onDismiss = onDismiss,
        onZap = onZap@{ amount ->
            if (!nwcConfigured) {
                Toast.makeText(context, "Set up Wallet Connect in the Setup tab first", Toast.LENGTH_SHORT).show()
                return@onZap
            }
            scope.launch {
                Toast.makeText(context, "⚡ Sending $amount sats…", Toast.LENGTH_SHORT).show()
                performSupportZap(context, amount, "")
            }
        },
        onCustomZapSend = onCustomZap@{ amount, _, message ->
            if (!nwcConfigured) {
                Toast.makeText(context, "Set up Wallet Connect in the Setup tab first", Toast.LENGTH_SHORT).show()
                return@onCustomZap
            }
            scope.launch {
                Toast.makeText(context, "⚡ Sending $amount sats…", Toast.LENGTH_SHORT).show()
                performSupportZap(context, amount, message)
            }
        },
    )
}

private suspend fun performSupportZap(
    context: android.content.Context,
    amount: Long,
    comment: String
) {
    val invoiceResult = LnurlResolver.fetchInvoice(
        lightningAddress = DEVELOPER_LIGHTNING_ADDRESS,
        amountSats = amount,
        comment = comment
    )
    when (invoiceResult) {
        is LnurlResolver.LnurlResult.Error -> {
            Toast.makeText(context, "Failed: ${invoiceResult.message}", Toast.LENGTH_LONG).show()
        }
        is LnurlResolver.LnurlResult.Invoice -> {
            val payResult = NwcPaymentManager.payInvoice(context, invoiceResult.bolt11)
            when (payResult) {
                is NwcPaymentResult.Success -> {
                    Toast.makeText(context, "⚡ Zap sent! Thank you for supporting Mycelium! 🍄", Toast.LENGTH_LONG).show()
                }
                is NwcPaymentResult.Error -> {
                    Toast.makeText(context, "Payment failed: ${payResult.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
