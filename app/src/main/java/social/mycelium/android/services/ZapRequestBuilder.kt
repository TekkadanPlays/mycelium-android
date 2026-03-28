package social.mycelium.android.services

import android.util.Log
import social.mycelium.android.repository.sync.ZapType
import com.example.cybin.core.Event
import com.example.cybin.nip57.LnZapRequestEvent
import com.example.cybin.signer.NostrSigner

/**
 * Builds kind-9734 zap request events (NIP-57).
 *
 * Delegates to Cybin's [LnZapRequestEvent.create] for the heavy lifting,
 * translating Mycelium's [ZapType] to Cybin's [LnZapRequestEvent.ZapType].
 */
object ZapRequestBuilder {
    private const val TAG = "ZapRequestBuilder"

    /**
     * Convert Mycelium ZapType to Cybin ZapType.
     */
    private fun toCybinType(zapType: ZapType): LnZapRequestEvent.ZapType = when (zapType) {
        ZapType.PUBLIC -> LnZapRequestEvent.ZapType.PUBLIC
        ZapType.PRIVATE -> LnZapRequestEvent.ZapType.PRIVATE
        ZapType.ANONYMOUS -> LnZapRequestEvent.ZapType.ANONYMOUS
        ZapType.NONZAP -> LnZapRequestEvent.ZapType.NONZAP
    }

    /**
     * Build and sign a kind-9734 zap request for a specific note.
     *
     * @param noteEvent  The event being zapped
     * @param relays     Relay URLs to include in the request
     * @param signer     The user's signer (for public/private zaps)
     * @param message    Optional message to include
     * @param zapType    The type of zap (PUBLIC, PRIVATE, ANONYMOUS)
     * @return The signed zap request event, or null on failure
     */
    suspend fun buildForNote(
        noteEvent: Event,
        relays: Set<String>,
        signer: NostrSigner,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC
    ): Event? {
        if (zapType == ZapType.NONZAP) {
            Log.d(TAG, "Non-zap type: no zap request event needed")
            return null
        }
        return try {
            val cybinType = toCybinType(zapType)
            Log.d(TAG, "Building zap request: type=$cybinType, note=${noteEvent.id.take(8)}")
            LnZapRequestEvent.create(
                zappedEvent = noteEvent,
                relays = relays,
                signer = signer,
                pollOption = null,
                message = message,
                zapType = cybinType,
                toUserPubHex = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building zap request: ${e.message}", e)
            null
        }
    }

    /**
     * Build and sign a kind-9734 zap request for a user (profile zap, not tied to a note).
     *
     * @param userPubkeyHex  The hex pubkey of the user being zapped
     * @param relays         Relay URLs to include in the request
     * @param signer         The user's signer
     * @param message        Optional message to include
     * @param zapType        The type of zap
     * @return The signed zap request event, or null on failure
     */
    suspend fun buildForUser(
        userPubkeyHex: String,
        relays: Set<String>,
        signer: NostrSigner,
        message: String = "",
        zapType: ZapType = ZapType.PUBLIC
    ): Event? {
        if (zapType == ZapType.NONZAP) {
            Log.d(TAG, "Non-zap type: no zap request event needed")
            return null
        }
        return try {
            val cybinType = toCybinType(zapType)
            Log.d(TAG, "Building user zap request: type=$cybinType, user=${userPubkeyHex.take(8)}")
            LnZapRequestEvent.create(
                userHex = userPubkeyHex,
                relays = relays,
                signer = signer,
                message = message,
                zapType = cybinType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building user zap request: ${e.message}", e)
            null
        }
    }

    /**
     * Serialize a zap request event to its JSON string (for the LNUrl invoice callback).
     */
    fun toJson(event: Event): String {
        return event.toJson()
    }
}
