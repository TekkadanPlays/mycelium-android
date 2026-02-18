package social.mycelium.android.data

import kotlinx.serialization.Serializable

/**
 * Information about a saved account.
 * Stores minimal info needed to identify and switch between accounts.
 */
@Serializable
data class AccountInfo(
    val npub: String,                           // Nostr public key in npub format
    val hasPrivateKey: Boolean = false,         // Whether we have the private key (nsec)
    val isExternalSigner: Boolean = false,      // Whether using Amber/external signer
    val isTransient: Boolean = false,           // Whether this is a temporary session
    val displayName: String? = null,            // Cached display name for UI
    val picture: String? = null,                // Cached profile picture URL
    val lastUsed: Long = System.currentTimeMillis()  // Last time this account was used
) {
    /**
     * Convert npub to hex pubkey for internal use
     */
    fun toHexKey(): String? {
        return try {
            val nip19 = com.example.cybin.nip19.Nip19Parser.uriToRoute(npub)
            (nip19?.entity as? com.example.cybin.nip19.NPub)?.hex
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Short display for account (npub1...xyz)
     */
    fun toShortNpub(): String {
        return if (npub.length > 16) {
            "${npub.substring(0, 8)}...${npub.substring(npub.length - 4)}"
        } else {
            npub
        }
    }

    /**
     * Get display name or fallback to short npub
     */
    fun getDisplayNameOrNpub(): String {
        return displayName ?: toShortNpub()
    }
}
