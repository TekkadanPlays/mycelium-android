package social.mycelium.android.utils

import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.NEvent
import com.example.cybin.nip19.NNote

/**
 * A quoted event reference with its ID and optional relay hints extracted from the nevent1 TLV.
 */
data class QuotedEventRef(
    val eventId: String,
    val relayHints: List<String>,
    val author: String? = null,
    val kind: Int? = null
)

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
     * Convenience wrapper — discards relay hints.
     */
    fun extractQuotedEventIds(content: String): List<String> =
        extractQuotedEventRefs(content).map { it.eventId }

    /**
     * Find all nevent1/note1 URIs in content and return full [QuotedEventRef] with relay hints.
     * nevent1 carries relay hints in TLV; note1 has none.
     */
    fun extractQuotedEventRefs(content: String): List<QuotedEventRef> {
        val seen = mutableSetOf<String>()
        val refs = mutableListOf<QuotedEventRef>()
        neventNotePattern.findAll(content).forEach { match ->
            val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) {
                match.value
            } else {
                "nostr:${match.value}"
            }
            try {
                val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
                when (val entity = parsed.entity) {
                    is NEvent -> {
                        if (entity.hex.length == 64 && seen.add(entity.hex)) {
                            refs.add(QuotedEventRef(
                                eventId = entity.hex,
                                relayHints = entity.relays,
                                author = entity.author,
                                kind = entity.kind
                            ))
                        }
                    }
                    is NNote -> {
                        if (entity.hex.length == 64 && seen.add(entity.hex)) {
                            refs.add(QuotedEventRef(
                                eventId = entity.hex,
                                relayHints = emptyList()
                            ))
                        }
                    }
                    else -> { }
                }
            } catch (_: Exception) { }
        }
        return refs
    }
}
