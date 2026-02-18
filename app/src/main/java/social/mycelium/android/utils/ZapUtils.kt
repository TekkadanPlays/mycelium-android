package social.mycelium.android.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Utility object for zap-related functionality in the Mycelium app.
 * This follows the NIP-57 (Zaps) specification for Lightning tips on Nostr.
 */
object ZapUtils {

    /**
     * Default zap amounts in satoshis
     */
    val DEFAULT_ZAP_AMOUNTS = listOf(
        21L,
        100L,
        500L,
        1000L,
        5000L,
        10000L
    )

    /**
     * Zap types as defined in NIP-57
     */
    enum class ZapType {
        PUBLIC,    // Regular public zap visible to everyone
        PRIVATE,   // Private zap using NIP-59 gift wrapping
        ANONYMOUS, // Anonymous zap without sender info
        NONZAP     // Regular Lightning payment without zap event
    }

    /**
     * Data class representing a zap action configuration
     */
    data class ZapAction(
        val amount: Long,
        val type: ZapType = ZapType.PUBLIC,
        val comment: String = ""
    )

    /**
     * Data class for storing wallet connection info
     */
    data class WalletConnection(
        val nwcUri: String,
        val isConnected: Boolean = false,
        val walletName: String? = null
    )

    /**
     * Format satoshi amount for display
     */
    fun formatSats(sats: Long): String {
        return when {
            sats >= 100_000_000 -> "%.2f BTC".format(sats / 100_000_000.0)
            sats >= 1_000_000 -> "%.2f M sats".format(sats / 1_000_000.0)
            sats >= 1_000 -> "%.1f K sats".format(sats / 1_000.0)
            else -> "$sats sats"
        }
    }

    /**
     * Format zap amount for compact display
     */
    fun formatZapAmount(sats: Long): String {
        return when {
            sats >= 1_000_000 -> "%.1fM".format(sats / 1_000_000.0)
            sats >= 1_000 -> "%.1fK".format(sats / 1_000.0)
            else -> sats.toString()
        }
    }

    /**
     * Format zap amount for exact display (no rounding)
     */
    fun formatZapAmountExact(sats: Long): String {
        return sats.toString()
    }

    /**
     * Get the zap icon
     */
    val zapIcon: ImageVector
        get() = Icons.Filled.Bolt

    /**
     * Validate NWC URI format
     * NWC URI format: nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
     */
    fun isValidNwcUri(uri: String): Boolean {
        if (!uri.startsWith("nostr+walletconnect://")) {
            return false
        }

        // Basic validation - check for required components
        return uri.contains("relay=") && uri.contains("secret=")
    }

    /**
     * Parse wallet name from NWC URI if available
     */
    fun parseWalletName(uri: String): String? {
        return try {
            val params = uri.substringAfter("?").split("&")
            params.find { it.startsWith("name=") }
                ?.substringAfter("name=")
                ?.replace("+", " ")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Data class to hold parsed NWC connection details
     */
    data class ParsedNwcUri(
        val pubkey: String,
        val relay: String,
        val secret: String
    )

    /**
     * Parse a full NWC URI into its components
     * Format: nostr+walletconnect://<pubkey>?relay=<relay>&secret=<secret>
     *
     * Example: nostr+walletconnect://b889ff5b1513b641...?relay=wss%3A%2F%2Frelay.getalby.com/v1&secret=71a8c14c...
     */
    fun parseNwcUri(uri: String): ParsedNwcUri? {
        return try {
            // Check if it starts with the correct protocol
            if (!uri.startsWith("nostr+walletconnect://")) {
                return null
            }

            // Extract pubkey (between :// and ?)
            val pubkey = uri.substringAfter("nostr+walletconnect://").substringBefore("?")

            // Extract parameters
            val paramsString = uri.substringAfter("?")
            val params = paramsString.split("&")

            // Find relay and secret
            var relay = ""
            var secret = ""

            params.forEach { param ->
                when {
                    param.startsWith("relay=") -> {
                        relay = java.net.URLDecoder.decode(
                            param.substringAfter("relay="),
                            "UTF-8"
                        )
                    }
                    param.startsWith("secret=") -> {
                        secret = param.substringAfter("secret=")
                    }
                }
            }

            // Validate that all fields are present
            if (pubkey.isEmpty() || relay.isEmpty() || secret.isEmpty()) {
                return null
            }

            ParsedNwcUri(pubkey, relay, secret)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a zap request for a note
     * This is a placeholder for future NIP-57 implementation
     */
    fun createZapRequest(
        recipientPubkey: String,
        amount: Long,
        relays: List<String>,
        comment: String = "",
        zapType: ZapType = ZapType.PUBLIC
    ): ZapAction {
        return ZapAction(
            amount = amount,
            type = zapType,
            comment = comment
        )
    }

    /**
     * Get zap type display name
     */
    fun getZapTypeName(type: ZapType): String {
        return when (type) {
            ZapType.PUBLIC -> "Public"
            ZapType.PRIVATE -> "Private"
            ZapType.ANONYMOUS -> "Anonymous"
            ZapType.NONZAP -> "Direct Payment"
        }
    }

    /**
     * Get zap type description
     */
    fun getZapTypeDescription(type: ZapType): String {
        return when (type) {
            ZapType.PUBLIC -> "Visible zap with your name and message"
            ZapType.PRIVATE -> "Private zap only visible to recipient"
            ZapType.ANONYMOUS -> "Anonymous zap without sender information"
            ZapType.NONZAP -> "Direct Lightning payment without creating a zap event"
        }
    }
}

/**
 * Extension functions for zap-related operations
 */

/**
 * Check if a zap amount is valid
 */
fun Long.isValidZapAmount(): Boolean {
    return this > 0 && this <= 21_000_000_000L // Max 21M BTC in sats
}

/**
 * Convert sats to millisats
 */
fun Long.toMillisats(): Long {
    return this * 1000
}

/**
 * Convert millisats to sats
 */
fun Long.toSats(): Long {
    return this / 1000
}
