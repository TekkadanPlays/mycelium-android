package social.mycelium.android.repository.sync

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Zap type determines how the zap request is signed and displayed.
 */
enum class ZapType {
    PUBLIC,     // Signed with user's key, visible to all
    PRIVATE,    // Signed with user's key, encrypted content
    ANONYMOUS,  // Signed with ephemeral key
    NONZAP      // Regular LN payment, no zap receipt
}

@Serializable
data class NwcConfig(
    val pubkey: String = "",
    val relay: String = "",
    val secret: String = "",
    val defaultZapType: String = "PUBLIC"  // ZapType.name
) {
    fun zapType(): ZapType = try { ZapType.valueOf(defaultZapType) } catch (_: Exception) { ZapType.PUBLIC }
}

/**
 * Centralized storage for Nostr Wallet Connect settings (shared across entry points).
 */
object NwcConfigRepository {
    private const val PREFS = "Mycelium_prefs"
    private const val KEY_CONFIG = "nwc_config_json"
    private val json = Json { ignoreUnknownKeys = true }

    fun getConfig(context: Context): NwcConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONFIG, null) ?: return NwcConfig()
        return try {
            json.decodeFromString<NwcConfig>(raw)
        } catch (_: Exception) {
            NwcConfig()
        }
    }

    fun saveConfig(context: Context, config: NwcConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(config)).apply()
    }

    fun clearConfig(context: Context) {
        saveConfig(context, NwcConfig())
    }
}
