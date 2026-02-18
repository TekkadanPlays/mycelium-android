package social.mycelium.android.utils

import android.content.Context

/**
 * NIP-89 client tag manager.
 * Adds ["client", "Mycelium"] tag to all published events unless disabled for privacy.
 * See: https://github.com/nostr-protocol/nips/blob/master/89.md
 */
object ClientTagManager {
    private const val PREFS_NAME = "Mycelium_settings"
    private const val KEY_CLIENT_TAG_ENABLED = "client_tag_enabled"

    /** The client tag value: ["client", "Mycelium"] */
    val CLIENT_TAG: Array<String> = arrayOf("client", "Mycelium")

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CLIENT_TAG_ENABLED, true) // enabled by default
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CLIENT_TAG_ENABLED, enabled).apply()
    }
}
