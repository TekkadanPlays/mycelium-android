package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class ZappedAmountsWrapper(val entries: List<ZappedAmountEntry> = emptyList())

@Serializable
private data class ZappedAmountEntry(val noteId: String, val amount: Long)

/**
 * Persists zapped note IDs and amounts per account so the zap icon and "You zapped X sats"
 * survive process death. Keyed by account npub.
 */
object ZapStatePersistence {
    private const val PREFS_NAME = "Mycelium_zap_state"
    private const val KEY_PREFIX_IDS = "zapped_ids_"
    private const val KEY_PREFIX_AMOUNTS = "zapped_amounts_"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveZappedIds(context: Context, accountNpub: String, noteIds: Set<String>) {
        val key = KEY_PREFIX_IDS + accountNpub
        prefs(context).edit().putStringSet(key, noteIds).apply()
    }

    fun loadZappedIds(context: Context, accountNpub: String): Set<String> {
        val key = KEY_PREFIX_IDS + accountNpub
        return prefs(context).getStringSet(key, null)?.toSet() ?: emptySet()
    }

    fun saveZappedAmounts(context: Context, accountNpub: String, amounts: Map<String, Long>) {
        val key = KEY_PREFIX_AMOUNTS + accountNpub
        val wrapper = ZappedAmountsWrapper(amounts.map { (k, v) -> ZappedAmountEntry(k, v) })
        prefs(context).edit().putString(key, json.encodeToString(wrapper)).apply()
    }

    fun loadZappedAmounts(context: Context, accountNpub: String): Map<String, Long> {
        val key = KEY_PREFIX_AMOUNTS + accountNpub
        val str = prefs(context).getString(key, null) ?: return emptyMap()
        return try {
            val wrapper = json.decodeFromString<ZappedAmountsWrapper>(str)
            wrapper.entries.associate { it.noteId to it.amount }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
