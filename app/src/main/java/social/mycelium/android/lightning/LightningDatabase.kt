package social.mycelium.android.lightning

import android.content.Context
import android.util.Log
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.channel.states.PersistedChannelState
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.serialization.channel.Serialization
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.wire.LiquidityAds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * File-backed channels database. Channel states are serialized to individual files
 * using lightning-kmp's built-in [Serialization] codec.
 */
class FileChannelsDb(private val dir: File) : ChannelsDb {
    private val TAG = "FileChannelsDb"
    private val mutex = Mutex()

    init {
        dir.mkdirs()
    }

    override suspend fun addOrUpdateChannel(state: PersistedChannelState) = mutex.withLock {
        val data = Serialization.serialize(state)
        File(dir, state.channelId.toHex()).writeBytes(data)
    }

    override suspend fun removeChannel(channelId: ByteVector32) = mutex.withLock {
        File(dir, channelId.toHex()).delete()
        Unit
    }

    override suspend fun listLocalChannels(): List<PersistedChannelState> = mutex.withLock {
        dir.listFiles()?.mapNotNull { file ->
            try {
                val result = Serialization.deserialize(file.readBytes())
                (result as? Serialization.DeserializationResult.Success)?.state
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize channel ${file.name}: ${e.message}")
                null
            }
        } ?: emptyList()
    }

    override suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry) {
        // HTLC info is used for penalty transactions on revoked commitments.
        // For a mobile wallet this is handled by the watchtower (ACINQ's trampoline node).
        // We store in memory only — acceptable for mobile use.
        htlcInfoStore.getOrPut(channelId) { mutableListOf() }
            .add(HtlcInfo(commitmentNumber, paymentHash, cltvExpiry))
    }

    override suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>> {
        return htlcInfoStore[channelId]
            ?.filter { it.commitmentNumber == commitmentNumber }
            ?.map { it.paymentHash to it.cltvExpiry }
            ?: emptyList()
    }

    override fun close() {}

    private data class HtlcInfo(val commitmentNumber: Long, val paymentHash: ByteVector32, val cltvExpiry: CltvExpiry)
    private val htlcInfoStore = mutableMapOf<ByteVector32, MutableList<HtlcInfo>>()
}

/**
 * In-memory payments database. Payments are tracked while the app is running.
 * A persistent implementation backed by Room can be added later.
 */
class InMemoryPaymentsDb : PaymentsDb {
    private val incomingPayments = mutableMapOf<ByteVector32, LightningIncomingPayment>() // keyed by paymentHash
    private val outgoingPayments = mutableMapOf<UUID, LightningOutgoingPayment>()
    private val onChainIncoming = mutableMapOf<UUID, OnChainIncomingPayment>()
    private val onChainOutgoing = mutableMapOf<UUID, OnChainOutgoingPayment>()
    private val mutex = Mutex()

    // --- IncomingPaymentsDb ---

    override suspend fun addIncomingPayment(incomingPayment: IncomingPayment) = mutex.withLock {
        when (incomingPayment) {
            is LightningIncomingPayment -> incomingPayments[incomingPayment.paymentHash] = incomingPayment
            is OnChainIncomingPayment -> onChainIncoming[incomingPayment.id] = incomingPayment
            else -> {} // legacy types ignored
        }
    }

    override suspend fun getLightningIncomingPayment(paymentHash: ByteVector32): LightningIncomingPayment? = mutex.withLock {
        incomingPayments[paymentHash]
    }

    override suspend fun receiveLightningPayment(
        paymentHash: ByteVector32,
        parts: List<LightningIncomingPayment.Part>,
        liquidityPurchase: LiquidityAds.LiquidityTransactionDetails?
    ) = mutex.withLock {
        incomingPayments[paymentHash]?.let { existing ->
            incomingPayments[paymentHash] = existing.addReceivedParts(parts, liquidityPurchase)
        }
        Unit
    }

    override suspend fun listLightningExpiredPayments(fromCreatedAt: Long, toCreatedAt: Long): List<LightningIncomingPayment> = mutex.withLock {
        incomingPayments.values
            .filter { it.createdAt in fromCreatedAt..toCreatedAt && it.isExpired() && it.parts.isEmpty() }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun removeLightningIncomingPayment(paymentHash: ByteVector32): Boolean = mutex.withLock {
        incomingPayments.remove(paymentHash) != null
    }

    // --- OutgoingPaymentsDb ---

    override suspend fun addOutgoingPayment(outgoingPayment: OutgoingPayment) = mutex.withLock {
        when (outgoingPayment) {
            is LightningOutgoingPayment -> outgoingPayments[outgoingPayment.id] = outgoingPayment
            is OnChainOutgoingPayment -> onChainOutgoing[outgoingPayment.id] = outgoingPayment
        }
    }

    override suspend fun addLightningOutgoingPaymentParts(parentId: UUID, parts: List<LightningOutgoingPayment.Part>) = mutex.withLock {
        outgoingPayments[parentId]?.let { existing ->
            outgoingPayments[parentId] = existing.copy(parts = existing.parts + parts)
        }
        Unit
    }

    override suspend fun getLightningOutgoingPayment(id: UUID): LightningOutgoingPayment? = mutex.withLock {
        outgoingPayments[id]
    }

    override suspend fun getLightningOutgoingPaymentFromPartId(partId: UUID): LightningOutgoingPayment? = mutex.withLock {
        outgoingPayments.values.find { payment -> payment.parts.any { it.id == partId } }
    }

    override suspend fun completeLightningOutgoingPayment(id: UUID, status: LightningOutgoingPayment.Status.Completed) = mutex.withLock {
        outgoingPayments[id]?.let { existing ->
            outgoingPayments[id] = existing.copy(status = status)
        }
        Unit
    }

    override suspend fun completeLightningOutgoingPaymentPart(parentId: UUID, partId: UUID, status: LightningOutgoingPayment.Part.Status.Completed) = mutex.withLock {
        outgoingPayments[parentId]?.let { existing ->
            val updatedParts = existing.parts.map { part ->
                if (part.id == partId) part.copy(status = status) else part
            }
            outgoingPayments[parentId] = existing.copy(parts = updatedParts)
        }
        Unit
    }

    override suspend fun listLightningOutgoingPayments(paymentHash: ByteVector32): List<LightningOutgoingPayment> = mutex.withLock {
        outgoingPayments.values.filter { it.paymentHash == paymentHash }
    }

    // --- PaymentsDb (combined) ---

    override suspend fun getInboundLiquidityPurchase(txId: TxId): LiquidityAds.LiquidityTransactionDetails? = mutex.withLock {
        // Check on-chain incoming
        onChainIncoming.values.find { it.txId == txId }?.liquidityPurchaseDetails
            ?: onChainOutgoing.values.find { it.txId == txId }?.liquidityPurchaseDetails
            ?: incomingPayments.values.find { it.liquidityPurchaseDetails?.txId == txId }?.liquidityPurchaseDetails
    }

    override suspend fun setLocked(txId: TxId) = mutex.withLock {
        val now = fr.acinq.lightning.utils.currentTimestampMillis()
        onChainIncoming.entries.filter { it.value.txId == txId }.forEach { (id, payment) ->
            onChainIncoming[id] = payment.setLocked(now)
        }
        onChainOutgoing.entries.filter { it.value.txId == txId }.forEach { (id, payment) ->
            onChainOutgoing[id] = payment.setLocked(now)
        }
    }

    /** Get all completed payments for UI display. */
    fun getAllPayments(): List<WalletPayment> {
        return (incomingPayments.values.filter { it.completedAt != null } +
                outgoingPayments.values.filter { it.completedAt != null } +
                onChainIncoming.values.filter { it.completedAt != null } +
                onChainOutgoing.values.filter { it.completedAt != null })
            .sortedByDescending { it.createdAt }
    }
}

/** Combines channels and payments DB into the [Databases] interface required by lightning-kmp. */
class LightningDatabases(
    override val channels: ChannelsDb,
    override val payments: PaymentsDb
) : Databases
