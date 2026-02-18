package social.mycelium.android.repository

import android.content.Context
import social.mycelium.android.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores recent emoji reactions and remembers the last reaction per note.
 * In-memory map is keyed by current account; loadForAccount/persist keep it in sync with SharedPreferences.
 */
object ReactionsRepository {

    private const val PREFS_PREFIX = "Mycelium_reactions_"
    private const val KEY_RECENT = "recent_emojis"
    private const val KEY_LAST_BY_NOTE = "last_by_note"
    private const val MAX_RECENT = 12

    private val json = Json { ignoreUnknownKeys = true }
    private val lastReactionByNoteId = ConcurrentHashMap<String, String>()

    /** Load persisted last-reaction map for this account into memory. Call when switching account. */
    fun loadForAccount(context: Context, accountNpub: String?) {
        val prefs = prefs(context, accountNpub)
        val stored = prefs.getString(KEY_LAST_BY_NOTE, null) ?: run {
            lastReactionByNoteId.clear()
            return
        }
        lastReactionByNoteId.clear()
        try {
            val map = json.decodeFromString<Map<String, String>>(stored)
            lastReactionByNoteId.putAll(map)
        } catch (_: Exception) {
            lastReactionByNoteId.clear()
        }
    }

    /** Persist current in-memory last-reaction map for this account. Call after setLastReaction. */
    fun persist(context: Context, accountNpub: String?) {
        if (accountNpub.isNullOrBlank()) return
        val prefs = prefs(context, accountNpub)
        val map = lastReactionByNoteId.toMap()
        prefs.edit().putString(KEY_LAST_BY_NOTE, json.encodeToString(map)).apply()
    }

    fun getRecentEmojis(context: Context, accountNpub: String?): List<String> {
        val prefs = prefs(context, accountNpub)
        val stored = prefs.getString(KEY_RECENT, null) ?: return defaultEmojis()
        return try {
            json.decodeFromString<List<String>>(stored).ifEmpty { defaultEmojis() }
        } catch (_: Exception) {
            defaultEmojis()
        }
    }

    fun recordEmoji(context: Context, accountNpub: String?, emoji: String) {
        if (emoji.isBlank()) return
        val prefs = prefs(context, accountNpub)
        val current = getRecentEmojis(context, accountNpub).toMutableList()
        current.removeAll { it == emoji }
        current.add(0, emoji)
        val trimmed = current.take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, json.encodeToString(trimmed)).apply()
    }

    fun setLastReaction(noteId: String, emoji: String) {
        if (noteId.isBlank() || emoji.isBlank()) return
        lastReactionByNoteId[noteId] = emoji
    }

    fun getLastReaction(noteId: String): String? = lastReactionByNoteId[noteId]

    private fun prefs(context: Context, accountNpub: String?) =
        context.getSharedPreferences("${PREFS_PREFIX}${accountNpub ?: "guest"}", Context.MODE_PRIVATE)

    private fun defaultEmojis(): List<String> =
        listOf("❤️", "👍", "😂", "😮", "😢", "😡")
}
