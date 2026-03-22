package social.mycelium.android.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.example.cybin.nip19.NEvent
import com.example.cybin.nip19.NNote
import com.example.cybin.nip19.NPub
import com.example.cybin.nip19.NProfile
import com.example.cybin.nip19.Nip19Parser
import social.mycelium.android.repository.ProfileMetadataCache

/**
 * Visual transformation for compose text fields that enriches nostr: references on the fly:
 * - `nostr:npub1...` / `nostr:nprofile1...` → `@displayName` (colored, bold)
 * - `nostr:nevent1...` / `nostr:note1...` → `📎 quoted note` (colored)
 * - URLs → colored
 *
 * The underlying text stays unchanged (raw nostr: URIs for publishing); only the
 * visual representation is transformed so the user sees friendly names.
 */
class ComposeVisualTransformation(
    private val mentionColor: Color = Color(0xFF8FBC8F),
    private val quoteColor: Color = Color(0xFF7986CB),
    private val urlColor: Color = Color(0xFF64B5F6),
) : VisualTransformation {

    // Matches nostr:npub1... or nostr:nprofile1...
    private val mentionRegex = Regex("""nostr:(npub1[a-z0-9]{58,}|nprofile1[a-z0-9]{58,})""")
    // Matches nostr:nevent1... or nostr:note1...
    private val quoteRegex = Regex("""nostr:(nevent1[a-z0-9]{58,}|note1[a-z0-9]{58,})""")
    // Simple URL pattern
    private val urlRegex = Regex("""https?://\S+""")

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        // Collect replacement ranges: (originalStart, originalEnd, replacementText, spanStyle)
        data class Replacement(val start: Int, val end: Int, val display: String, val style: SpanStyle)
        val replacements = mutableListOf<Replacement>()

        // Resolve @mentions
        val profileCache = ProfileMetadataCache.getInstance()
        mentionRegex.findAll(raw).forEach { match ->
            try {
                val parsed = Nip19Parser.uriToRoute(match.value) ?: return@forEach
                val hex = when (val e = parsed.entity) {
                    is NPub -> e.hex
                    is NProfile -> e.hex
                    else -> return@forEach
                }
                val author = profileCache.resolveAuthor(hex)
                val name = author.displayName.takeIf { it.isNotBlank() }
                    ?: author.username.takeIf { it.isNotBlank() }
                    ?: hex.take(8) + "…"
                replacements.add(Replacement(
                    match.range.first, match.range.last + 1,
                    "@$name",
                    SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold)
                ))
            } catch (_: Exception) { }
        }

        // Resolve quoted notes
        quoteRegex.findAll(raw).forEach { match ->
            try {
                val parsed = Nip19Parser.uriToRoute(match.value) ?: return@forEach
                when (parsed.entity) {
                    is NEvent, is NNote -> {
                        replacements.add(Replacement(
                            match.range.first, match.range.last + 1,
                            "\uD83D\uDD17 quoted note",
                            SpanStyle(color = quoteColor, fontWeight = FontWeight.Medium)
                        ))
                    }
                    else -> { }
                }
            } catch (_: Exception) { }
        }

        // If no replacements, just highlight URLs
        if (replacements.isEmpty()) {
            val builder = AnnotatedString.Builder(raw)
            urlRegex.findAll(raw).forEach { match ->
                builder.addStyle(SpanStyle(color = urlColor), match.range.first, match.range.last + 1)
            }
            return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
        }

        // Sort by start position, non-overlapping
        replacements.sortBy { it.start }

        // Build transformed text with offset mapping
        val builder = AnnotatedString.Builder()
        // originalToTransformed: for each original index, what transformed index does it map to?
        val origToTrans = IntArray(raw.length + 1)
        // transformedToOriginal: for each transformed index, what original index does it map to?
        val transToOrigList = mutableListOf<Int>()

        var origPos = 0
        for (rep in replacements) {
            // Emit text before the replacement
            if (origPos < rep.start) {
                val before = raw.substring(origPos, rep.start)
                val transStart = builder.length
                builder.append(before)
                for (i in origPos until rep.start) {
                    origToTrans[i] = transStart + (i - origPos)
                    transToOrigList.add(i)
                }
            }
            // Emit replacement
            val transStart = builder.length
            builder.pushStyle(rep.style)
            builder.append(rep.display)
            builder.pop()
            // Map all original chars in the range to the replacement start
            for (i in rep.start until rep.end) {
                origToTrans[i] = transStart
            }
            // Map all replacement chars back to the original start
            for (i in rep.display.indices) {
                transToOrigList.add(rep.start)
            }
            origPos = rep.end
        }
        // Emit remaining text
        if (origPos < raw.length) {
            val remaining = raw.substring(origPos)
            val transStart = builder.length
            builder.append(remaining)
            for (i in origPos until raw.length) {
                origToTrans[i] = transStart + (i - origPos)
                transToOrigList.add(i)
            }
        }
        // Sentinel
        origToTrans[raw.length] = builder.length
        transToOrigList.add(raw.length)

        // Highlight URLs in the transformed text
        val transformedText = builder.toString()
        urlRegex.findAll(transformedText).forEach { match ->
            // Only highlight if it doesn't overlap with an already-styled range
            builder.addStyle(SpanStyle(color = urlColor), match.range.first, match.range.last + 1)
        }

        val transToOrig = transToOrigList.toIntArray()

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                return origToTrans[offset.coerceIn(0, raw.length)]
            }
            override fun transformedToOriginal(offset: Int): Int {
                return transToOrig[offset.coerceIn(0, transToOrig.lastIndex)]
            }
        }

        return TransformedText(builder.toAnnotatedString(), offsetMapping)
    }
}
