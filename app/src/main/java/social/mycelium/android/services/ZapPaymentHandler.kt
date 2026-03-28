package social.mycelium.android.services

import android.content.Context
import android.util.Log
import social.mycelium.android.data.Note
import social.mycelium.android.repository.sync.NwcConfigRepository
import social.mycelium.android.repository.cache.ProfileMetadataCache
import social.mycelium.android.repository.sync.ZapType
import social.mycelium.android.utils.normalizeAuthorIdForCache
import com.example.cybin.core.Event
import com.example.cybin.signer.NostrSigner
import social.mycelium.android.lightning.PaymentResult
import social.mycelium.android.lightning.PhoenixWalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zap progress state for the UI.
 */
sealed class ZapProgress {
    data object Idle : ZapProgress()
    data class InProgress(val step: String) : ZapProgress()
    data class Success(val preimage: String?) : ZapProgress()
    data class Failed(val message: String) : ZapProgress()
}

/**
 * Orchestrates the full zap flow:
 * 1. Resolve the author's lud16 to get LNURLp pay info
 * 2. Build and sign a kind-9734 zap request event
 * 3. Fetch a bolt11 invoice from the LN address with the zap request
 * 4. Pay the invoice via NWC (NIP-47)
 * 5. Report progress at each step
 */
object ZapPaymentHandler {
    private const val TAG = "ZapPaymentHandler"

    /**
     * Execute a full zap on a note.
     *
     * @param context          Application context
     * @param note             The note being zapped
     * @param amountSats       The amount in satoshis
     * @param zapType          Public/Private/Anonymous/NonZap
     * @param message          Optional zap message
     * @param signer           The user's NostrSigner (from Amber)
     * @param outboxRelayUrls  The user's outbox relay URLs for the zap request
     * @param onProgress       Callback for progress updates
     */
    suspend fun zap(
        context: Context,
        note: Note,
        amountSats: Long,
        zapType: ZapType,
        message: String = "",
        signer: NostrSigner,
        outboxRelayUrls: Set<String> = emptySet(),
        onProgress: (ZapProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Zap started: note=${note.id.take(8)}, amount=$amountSats sats")
        try {
            onProgress(ZapProgress.InProgress("Looking up Lightning address..."))

            // 1. Get the author's lud16
            val authorHex = normalizeAuthorIdForCache(note.author.id)
            val author = ProfileMetadataCache.getInstance().resolveAuthor(authorHex)
            val lud16 = author.lud16
            if (lud16.isNullOrBlank()) {
                Log.w(TAG, "Zap failed: author has no lud16 (hex=${authorHex.take(8)})")
                onProgress(ZapProgress.Failed("Author has no Lightning address (lud16)"))
                return@withContext
            }
            Log.d(TAG, "Zapping ${note.id.take(8)}: lud16=$lud16, amount=$amountSats sats, type=$zapType")

            // 2. Resolve the LNURLp endpoint
            val payInfo = LightningAddressResolver.resolveLud16(lud16)
            if (payInfo == null) {
                Log.w(TAG, "Zap failed: could not resolve LNURLp for $lud16")
                onProgress(ZapProgress.Failed("Could not resolve Lightning address: $lud16"))
                return@withContext
            }
            Log.d(TAG, "LNURLp resolved: callback=${payInfo.callback.take(40)}...")

            val amountMillisats = amountSats * 1000L

            // Validate amount bounds
            if (amountMillisats < payInfo.minSendable) {
                Log.w(TAG, "Zap failed: amount $amountSats below min ${payInfo.minSendable / 1000} sats")
                onProgress(ZapProgress.Failed("Amount too low: min ${payInfo.minSendable / 1000} sats"))
                return@withContext
            }
            if (amountMillisats > payInfo.maxSendable) {
                Log.w(TAG, "Zap failed: amount $amountSats above max ${payInfo.maxSendable / 1000} sats")
                onProgress(ZapProgress.Failed("Amount too high: max ${payInfo.maxSendable / 1000} sats"))
                return@withContext
            }

            // 3. Build zap request event (if not NONZAP)
            var zapRequestJson: String? = null
            if (zapType != ZapType.NONZAP && payInfo.allowsNostr) {
                onProgress(ZapProgress.InProgress("Signing zap request..."))

                // Build a minimal Event for the zap request builder
                val noteEvent = buildMinimalEvent(note)

                val zapRequest = ZapRequestBuilder.buildForNote(
                    noteEvent = noteEvent,
                    relays = outboxRelayUrls,
                    signer = signer,
                    message = message,
                    zapType = zapType
                )

                if (zapRequest != null) {
                    zapRequestJson = ZapRequestBuilder.toJson(zapRequest)
                    Log.d(TAG, "Zap request built: id=${zapRequest.id.take(8)}")
                } else {
                    Log.w(TAG, "Zap failed: ZapRequestBuilder.buildForNote returned null")
                    onProgress(ZapProgress.Failed("Failed to build zap request"))
                    return@withContext
                }
            }

            // 4. Fetch bolt11 invoice
            onProgress(ZapProgress.InProgress("Fetching invoice..."))
            Log.d(TAG, "Fetching invoice from callback...")
            val bolt11 = LightningAddressResolver.fetchInvoice(
                callbackUrl = payInfo.callback,
                amountMillisats = amountMillisats,
                zapRequestJson = zapRequestJson
            )

            if (bolt11.isNullOrBlank()) {
                Log.w(TAG, "Zap failed: fetchInvoice returned null/blank")
                onProgress(ZapProgress.Failed("Failed to get Lightning invoice"))
                return@withContext
            }

            Log.d(TAG, "Got bolt11 invoice: ${bolt11.take(20)}...")

            // 5. Pay the invoice — prefer embedded Phoenix wallet, fall back to NWC
            onProgress(ZapProgress.InProgress("Paying invoice..."))

            if (PhoenixWalletManager.isReady()) {
                // Pay directly from the embedded Lightning node (non-custodial)
                Log.d(TAG, "Paying via Phoenix embedded wallet...")
                val phoenixResult = PhoenixWalletManager.payInvoice(bolt11)
                when (phoenixResult) {
                    is PaymentResult.Success -> {
                        Log.d(TAG, "Zap successful via Phoenix! preimage=${phoenixResult.preimage.take(16)}")
                        onProgress(ZapProgress.Success(phoenixResult.preimage))
                    }
                    is PaymentResult.Error -> {
                        Log.w(TAG, "Phoenix payment failed: ${phoenixResult.message}")
                        onProgress(ZapProgress.Failed(phoenixResult.message))
                    }
                }
            } else if (NwcPaymentManager.isConfigured(context)) {
                // Fall back to NWC (external wallet via Nostr Wallet Connect)
                Log.d(TAG, "Phoenix not available, paying via NWC...")
                val nwcResult = NwcPaymentManager.payInvoice(context, bolt11)
                when (nwcResult) {
                    is NwcPaymentResult.Success -> {
                        Log.d(TAG, "Zap successful via NWC! preimage=${nwcResult.preimage?.take(16)}")
                        onProgress(ZapProgress.Success(nwcResult.preimage))
                    }
                    is NwcPaymentResult.Error -> {
                        Log.w(TAG, "NWC payment failed: ${nwcResult.message}")
                        onProgress(ZapProgress.Failed(nwcResult.message))
                    }
                }
            } else {
                Log.w(TAG, "Zap failed: no payment method available")
                onProgress(ZapProgress.Failed("No wallet available. Set up Lightning wallet or NWC in Settings."))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Zap flow failed: ${e.message}", e)
            onProgress(ZapProgress.Failed("Zap failed: ${e.message?.take(80)}"))
        }
    }

    /**
     * Build a minimal Quartz Event from a Mycelium Note (for zap request creation).
     */
    private fun buildMinimalEvent(note: Note): Event {
        return Event(
            id = note.id,
            pubKey = normalizeAuthorIdForCache(note.author.id),
            createdAt = note.timestamp,
            kind = 1,
            tags = emptyArray(),
            content = note.content,
            sig = "" // Not needed for zap request tag building
        )
    }
}
