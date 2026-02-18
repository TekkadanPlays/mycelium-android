package social.mycelium.android.utils

import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.NEvent
import com.example.cybin.nip19.NNote

/**
 * Extracts quoted event IDs from note content (nostr:nevent1... / nostr:note1... per NIP-19).
 */
object Nip19QuoteParser {

    private val neventNotePattern = Regex(
        "(nostr:)?@?(nevent1|note1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
        RegexOption.IGNORE_CASE
    )

    /**
     * Find all nevent1/note1 URIs in content and return their event IDs (hex).
     */
    fun extractQuotedEventIds(content: String): List<String> {
        val ids = mutableSetOf<String>()
        neventNotePattern.findAll(content).forEach { match ->
            val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) {
                match.value
            } else {
                "nostr:${match.value}"
            }
            try {
                val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
                val hex = when (val entity = parsed.entity) {
                    is NEvent -> entity.hex
                    is NNote -> entity.hex
                    else -> null
                }
                hex?.let { if (it.length == 64) ids.add(it) }
            } catch (_: Exception) { }
        }
        return ids.toList()
    }
}
