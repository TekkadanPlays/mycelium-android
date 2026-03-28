package social.mycelium.android.utils

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import social.mycelium.android.data.UrlPreviewInfo
import social.mycelium.android.repository.cache.ProfileMetadataCache
import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.NAddress
import com.example.cybin.nip19.NEvent
import com.example.cybin.nip19.NNote
import com.example.cybin.nip19.NProfile
import com.example.cybin.nip19.NPub

private val npubPattern = Regex(
    "(nostr:)?@?(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)
private val neventNotePattern = Regex(
    "(nostr:)?@?(nevent1|note1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)
// NIP-08-style: @ followed by 64-char hex, or bare 64-char hex (word boundary so we don't match longer strings)
private val hexPubkeyPattern = Regex("(?<![0-9a-fA-F])@?([0-9a-fA-F]{64})(?![0-9a-fA-F])")
// NIP-19 nprofile (profile with relay hints)
private val nprofilePattern = Regex(
    "(nostr:)?@?(nprofile1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)
// NIP-19 naddr (addressable events, e.g. communities)
private val naddrPattern = Regex(
    "(nostr:)?@?(naddr1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)

private data class Segment(val start: Int, val end: Int, val type: Int, val data: Any?)
private const val SEG_URL = 0
private const val SEG_NPUB = 1
private const val SEG_NEVENT = 2
private const val SEG_EMBEDDED_MEDIA = 3
private const val SEG_NADDR = 4
private const val SEG_NPROFILE = 5
private const val SEG_HASHTAG = 6
private const val SEG_RELAY = 7
private const val SEG_CUSTOM_EMOJI = 8
private const val SEG_EMOJI_PACK = 9

// NIP-30 custom emoji pattern: :shortcode: where shortcode is alphanumeric + underscore/hyphen
private val customEmojiPattern = Regex(":(\\w[\\w-]*):", RegexOption.IGNORE_CASE)

/** Extract emoji shortcode→URL map from raw event tags (NIP-30: ["emoji", "shortcode", "url"]). */
fun extractEmojiUrls(tags: List<List<String>>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    for (tag in tags) {
        if (tag.size >= 3 && tag[0] == "emoji") {
            map[":${tag[1]}:"] = tag[2]
        }
    }
    return map
}

// Hashtag pattern: # followed by word characters (letters, digits, underscore), at least 1 char
private val hashtagPattern = Regex("(?<=\\s|^)#(\\w+)", RegexOption.IGNORE_CASE)
// Relay URL pattern: wss:// or ws:// followed by domain (+ optional path)
private val relayUrlPattern = Regex("wss?://[a-zA-Z0-9._~:/?#\\[\\]@!$&'()*+,;=-]+", RegexOption.IGNORE_CASE)

/**
 * Builds AnnotatedString for note content: clickable URLs (excluding embedded media),
 * embedded media URLs hidden, @displayName for npub (NIP-19), and empty replacement for nevent/note (quoted block shown separately).
 */
fun buildNoteContentAnnotatedString(
    content: String,
    mediaUrls: Set<String>,
    linkStyle: SpanStyle,
    profileCache: ProfileMetadataCache,
    range: IntRange? = null,
    emojiUrls: Map<String, String> = emptyMap()
): AnnotatedString {
    val segments = mutableListOf<Segment>()
    val contentUrls = UrlDetector.findUrls(content)
    val embeddedMedia = mediaUrls
    val rStart = range?.first ?: 0
    val rEnd = range?.last?.plus(1) ?: content.length
    val slice = content.substring(rStart, rEnd.coerceAtMost(content.length))

    // Embedded media URL segments (hide the URL text; media is shown as image/video below)
    for (url in contentUrls) {
        if (url !in embeddedMedia) continue
        var idx = 0
        while (true) {
            val start = content.indexOf(url, idx)
            if (start < 0) break
            segments.add(Segment(start, start + url.length, SEG_EMBEDDED_MEDIA, null))
            idx = start + 1
        }
    }

    // URL segments (only link URLs that are not embedded images)
    for (url in contentUrls) {
        if (url in embeddedMedia) continue
        var idx = 0
        while (true) {
            val start = content.indexOf(url, idx)
            if (start < 0) break
            segments.add(Segment(start, start + url.length, SEG_URL, url))
            idx = start + 1
        }
    }

    // NIP-19 npub
    npubPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val hex = (parsed.entity as? NPub)?.hex ?: return@forEach
            if (hex.length == 64) {
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_NPUB, hex))
            }
        } catch (_: Exception) { }
    }

    // Hex pubkey in content (e.g. NIP-08 p-tag style or pasted pubkey) – resolve via ProfileMetadataCache like npub
    hexPubkeyPattern.findAll(content).forEach { match ->
        val hex = match.groupValues.getOrNull(1)?.takeIf { it.length == 64 } ?: return@forEach
        segments.add(Segment(match.range.first, match.range.last + 1, SEG_NPUB, hex.lowercase()))
    }

    // NIP-19 nprofile (profile with relay hints) – resolve to @displayName like npub
    nprofilePattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val hex = (parsed.entity as? NProfile)?.hex ?: return@forEach
            if (hex.length == 64) {
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_NPROFILE, hex))
            }
        } catch (_: Exception) { }
    }

    // NIP-19 naddr (addressable events, e.g. communities) – display as readable label, optional click to navigate
    naddrPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val naddr = parsed.entity as? NAddress ?: return@forEach
            // kind 30030 = NIP-30 emoji pack; kind 34550 = NIP-72 community; kind 30023 = NIP-23 article
            if (naddr.kind == 30030) {
                val author = naddr.author ?: ""
                val dTag = naddr.dTag
                val relays = naddr.relays ?: emptyList()
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_EMOJI_PACK, Triple(author, dTag, relays)))
                return@forEach
            }
            if (naddr.kind == 30023) {
                // Article — hide from inline text; rendered as embedded article block
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_NEVENT, null))
                return@forEach
            }
            val label = if (naddr.kind == 34550) "Community" else "Addressable event"
            val nostrUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
            val aTag = "${naddr.kind ?: 0}:${naddr.author ?: ""}:${naddr.dTag}"
            segments.add(Segment(match.range.first, match.range.last + 1, SEG_NADDR, Triple(nostrUri, label, aTag)))
        } catch (_: Exception) { }
    }

    // NIP-19 nevent/note (replace with empty; quoted block shown below)
    neventNotePattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            when (parsed.entity) {
                is NEvent, is NNote -> segments.add(Segment(match.range.first, match.range.last + 1, SEG_NEVENT, null))
                else -> { }
            }
        } catch (_: Exception) { }
    }

    // Hashtags in content — highlight with green
    hashtagPattern.findAll(content).forEach { match ->
        segments.add(Segment(match.range.first, match.range.last + 1, SEG_HASHTAG, match.value))
    }

    // Relay URLs (wss:// / ws://) — tappable to open relay info page
    relayUrlPattern.findAll(content).forEach { match ->
        val relayUrl = match.value.trimEnd('.', ',', ')', ']', ';', '!', '?') // strip trailing punctuation
        segments.add(Segment(match.range.first, match.range.first + relayUrl.length, SEG_RELAY, relayUrl))
    }

    // NIP-30 custom emoji :shortcode: — only if event has emoji tags
    if (emojiUrls.isNotEmpty()) {
        customEmojiPattern.findAll(content).forEach { match ->
            val shortcode = ":${match.groupValues[1]}:"
            val url = emojiUrls[shortcode]
            if (url != null) {
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_CUSTOM_EMOJI, shortcode to url))
            }
        }
    }

    // Deduplicate overlapping segments: NIP-19 types take priority over generic URLs
    val nip19Types = setOf(SEG_NPUB, SEG_NEVENT, SEG_NPROFILE, SEG_NADDR, SEG_EMBEDDED_MEDIA)
    val nip19Ranges = segments.filter { it.type in nip19Types }.map { it.start..it.end }
    segments.removeAll { seg ->
        seg.type == SEG_URL && nip19Ranges.any { range -> seg.start >= range.first && seg.end <= range.last }
    }

    // Expand hidden segments (embedded media + nevent) to consume surrounding whitespace/newlines
    // so that removing the URL text doesn't leave blank lines or dead space.
    // IMPORTANT: preserve at least one \n so text before/after the URL doesn't merge.
    fun expandToConsumeWhitespace(seg: Segment): Segment {
        if (seg.type != SEG_EMBEDDED_MEDIA && seg.type != SEG_NEVENT && seg.type != SEG_EMOJI_PACK) return seg

        // ── Leading side: eat horizontal whitespace + at most one newline ──
        var newStart = seg.start
        while (newStart > 0 && content[newStart - 1].let { it == ' ' || it == '\t' }) newStart--
        if (newStart > 0 && content[newStart - 1] == '\n') newStart-- // eat one \n
        if (newStart > 0 && content[newStart - 1] == '\r') newStart-- // eat preceding \r if any

        // ── Trailing side: eat horizontal whitespace + the URL's own line break ──
        var newEnd = seg.end
        while (newEnd < content.length && content[newEnd].let { it == ' ' || it == '\t' }) newEnd++
        // Eat the URL's own trailing newline (one \r?\n)
        if (newEnd < content.length && content[newEnd] == '\r') newEnd++
        if (newEnd < content.length && content[newEnd] == '\n') newEnd++
        // Eat additional truly-blank lines (lines that are entirely whitespace)
        while (newEnd < content.length) {
            var lineEnd = newEnd
            while (lineEnd < content.length && content[lineEnd].let { it == ' ' || it == '\t' }) lineEnd++
            if (lineEnd < content.length && (content[lineEnd] == '\n' || content[lineEnd] == '\r')) {
                if (content[lineEnd] == '\r') lineEnd++
                if (lineEnd < content.length && content[lineEnd] == '\n') lineEnd++
                newEnd = lineEnd
            } else {
                break
            }
        }

        // If there was text both before and after the URL, ensure at least one \n
        // survives so paragraphs don't merge into run-on text.
        val hasTextBefore = newStart > 0 && content[newStart - 1].let { it != '\n' && it != '\r' }
        val hasTextAfter = newEnd < content.length && content[newEnd].let { it != '\n' && it != '\r' }
        if (hasTextBefore && hasTextAfter) {
            // Pull newStart forward to keep a \n at the boundary
            val origStart = newStart
            // Find the first \n in the consumed leading region and keep it
            for (k in origStart until seg.start) {
                if (content[k] == '\n') { newStart = k; break }
            }
        }

        return Segment(newStart, newEnd, seg.type, seg.data)
    }
    val expandedSegments = segments.map { expandToConsumeWhitespace(it) }

    val (text, segs) = if (range != null) {
        val clamped = expandedSegments
            .filter { it.start < rEnd && it.end > rStart }
            .map { Segment(
                (it.start - rStart).coerceAtLeast(0),
                (it.end - rStart).coerceAtMost(slice.length),
                it.type,
                it.data
            ) }
            .sortedBy { it.start }
        slice to clamped
    } else {
        expandedSegments.sortedBy { it.start }
        content to expandedSegments.sortedBy { it.start }
    }

    var pos = 0
    val hashtagStyle = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF8FBC8F)) // SageGreen
    val mentionStyle = SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF8E30EB), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) // Purple (same as OP highlight)
    return buildAnnotatedString {
        for (seg in segs) {
            if (seg.start < pos) continue
            append(text.substring(pos, seg.start))
            when (seg.type) {
                SEG_URL -> {
                    pushStringAnnotation("URL", seg.data as String)
                    withStyle(linkStyle) { append(seg.data as String) }
                    pop()
                }
                SEG_NPUB -> {
                    val hex = seg.data as String
                    val author = profileCache.resolveAuthor(hex)
                    val isPlaceholder = author.displayName == author.id.take(8) + "..."
                    val label = if (isPlaceholder) {
                        "@${author.id.take(8)}\u2026"
                    } else {
                        "@${author.displayName}"
                    }
                    pushStringAnnotation("PROFILE", hex)
                    withStyle(mentionStyle) { append(label) }
                    pop()
                }
                SEG_NEVENT -> {
                    // Replace with nothing; quoted block is rendered below
                }
                SEG_NPROFILE -> {
                    val hex = seg.data as String
                    val author = profileCache.resolveAuthor(hex)
                    val isPlaceholder = author.displayName == author.id.take(8) + "..."
                    val label = if (isPlaceholder) {
                        "@${author.id.take(8)}\u2026"
                    } else {
                        "@${author.displayName}"
                    }
                    pushStringAnnotation("PROFILE", hex)
                    withStyle(mentionStyle) { append(label) }
                    pop()
                }
                SEG_NADDR -> {
                    val (nostrUri, label, _) = seg.data as Triple<*, *, *>
                    pushStringAnnotation("NADDR", nostrUri as String)
                    withStyle(linkStyle) { append(label as String) }
                    pop()
                }
                SEG_EMBEDDED_MEDIA -> {
                    // Hide URL; embedded media is shown as image/video below content
                }
                SEG_HASHTAG -> {
                    val tag = seg.data as String
                    pushStringAnnotation("HASHTAG", tag)
                    withStyle(hashtagStyle) { append(tag) }
                    pop()
                }
                SEG_RELAY -> {
                    val relayUrl = seg.data as String
                    pushStringAnnotation("RELAY", relayUrl)
                    withStyle(linkStyle) { append(relayUrl) }
                    pop()
                }
                SEG_CUSTOM_EMOJI -> {
                    val (shortcode, _) = seg.data as Pair<*, *>
                    // Use inline content placeholder — the composable will supply the actual image
                    appendInlineContent(shortcode as String, "[$shortcode]")
                }
                SEG_EMOJI_PACK -> {
                    // Hide naddr text; emoji pack block is rendered separately
                }
            }
            pos = seg.end
        }
        if (pos < text.length) append(text.substring(pos))
    }
}

/**
 * Singleton LRU cache for parsed content blocks.
 * Prevents layout shifts when LazyColumn recycles items — on re-entry the cached
 * blocks are used as `initialValue` in `produceState` so content renders instantly.
 */
object ContentBlockCache {
    private const val MAX_ENTRIES = 300
    private val map = object : LinkedHashMap<String, List<NoteContentBlock>>(MAX_ENTRIES + 16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<NoteContentBlock>>?) =
            size > MAX_ENTRIES
    }

    /** Build a cache key from the content string and media URLs. */
    fun key(content: String, mediaUrls: Set<String>, consumedUrls: Set<String> = emptySet(), mentionVersion: Int = 0): String {
        // Identity is content + which URLs are media + which are consumed + mention resolution version
        return if (mediaUrls.isEmpty() && consumedUrls.isEmpty()) {
            "$mentionVersion:$content"
        } else {
            "$mentionVersion:$content\u0000${mediaUrls.sorted().joinToString(",")}\u0000${consumedUrls.sorted().joinToString(",")}"
        }
    }

    @Synchronized
    fun get(key: String): List<NoteContentBlock>? = map[key]

    @Synchronized
    fun put(key: String, blocks: List<NoteContentBlock>) {
        map[key] = blocks
    }
}

/** Item when rendering content with HTTP metadata directly beneath each URL. */
sealed class NoteContentBlock {
    data class Content(val annotated: AnnotatedString, val emojiUrls: Map<String, String> = emptyMap()) : NoteContentBlock()
    data class Preview(val previewInfo: UrlPreviewInfo) : NoteContentBlock()
    /** A group of consecutive media URLs (images/videos) to render as an inline album carousel. */
    data class MediaGroup(val urls: List<String>) : NoteContentBlock()
    /** An inline quoted note reference (nostr:nevent1.../nostr:note1...) at its position in the text flow. */
    data class QuotedNote(val eventId: String) : NoteContentBlock()
    /** An inline NIP-53 live event reference (nevent1 with kind=30311). */
    data class LiveEventReference(val eventId: String, val author: String?, val relays: List<String>) : NoteContentBlock()
    /** An inline NIP-30 emoji pack reference (naddr with kind=30030). */
    data class EmojiPack(val author: String, val dTag: String, val relayHints: List<String>) : NoteContentBlock()
    /** An inline NIP-23 article reference (naddr with kind=30023). */
    data class Article(val author: String, val dTag: String, val relayHints: List<String>) : NoteContentBlock()
}

/**
 * Builds interleaved content blocks: text, inline URL previews, and media groups.
 *
 * Media URLs that appear consecutively in the content (possibly separated only by whitespace/newlines)
 * are grouped into a single [NoteContentBlock.MediaGroup] so the UI can render them as an album
 * carousel at the correct position in the text flow.
 */
fun buildNoteContentWithInlinePreviews(
    content: String,
    mediaUrls: Set<String>,
    urlPreviews: List<UrlPreviewInfo>,
    linkStyle: SpanStyle,
    profileCache: ProfileMetadataCache,
    consumedUrls: Set<String> = emptySet(),
    emojiUrls: Map<String, String> = emptyMap()
): List<NoteContentBlock> {
    @Suppress("NAME_SHADOWING") val content = LinkSanitizer.cleanText(content)
    // Locate every URL with its character position
    val urlPositions = UrlDetector.findUrlsWithPositions(content) // List<Pair<IntRange, String>>
    val previewByUrl = urlPreviews.associateBy { it.url }

    // Build an ordered list of "markers" – each is either a media URL or a link-preview URL at a position
    data class Marker(val start: Int, val end: Int, val url: String, val isMedia: Boolean, val preview: UrlPreviewInfo?, val quotedEventId: String? = null, val liveEventAuthor: String? = null, val liveEventRelays: List<String>? = null, val emojiPackAuthor: String? = null, val emojiPackDTag: String? = null, val emojiPackRelays: List<String>? = null, val articleAuthor: String? = null, val articleDTag: String? = null, val articleRelays: List<String>? = null)
    // consumedUrls are hidden from text (like media) but not rendered as media groups
    val allHiddenUrls = mediaUrls + consumedUrls

    // Detect nostr:nevent1.../nostr:note1... references with positions for inline quoted notes
    val quotePattern = Regex("(nostr:)?(nevent1|note1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)", RegexOption.IGNORE_CASE)
    val quoteMarkers = quotePattern.findAll(content).mapNotNull { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = com.example.cybin.nip19.Nip19Parser.uriToRoute(fullUri) ?: return@mapNotNull null
            when (val entity = parsed.entity) {
                is com.example.cybin.nip19.NEvent -> {
                    val hex = entity.hex
                    if (hex.length != 64) return@mapNotNull null
                    if (entity.kind == 30311) {
                        Marker(match.range.first, match.range.last + 1, match.value, false, null, hex, entity.author, entity.relays)
                    } else {
                        Marker(match.range.first, match.range.last + 1, match.value, false, null, hex)
                    }
                }
                is com.example.cybin.nip19.NNote -> {
                    val hex = entity.hex
                    if (hex.length == 64) Marker(match.range.first, match.range.last + 1, match.value, false, null, hex) else null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }.toList()

    // Detect nostr:naddr1... references to kind-30030 emoji packs and kind-30023 articles
    val emojiPackMarkers = mutableListOf<Marker>()
    val articleMarkers = mutableListOf<Marker>()
    naddrPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val naddr = parsed.entity as? NAddress ?: return@forEach
            when (naddr.kind) {
                30030 -> emojiPackMarkers.add(Marker(match.range.first, match.range.last + 1, match.value, false, null,
                    emojiPackAuthor = naddr.author ?: "", emojiPackDTag = naddr.dTag,
                    emojiPackRelays = naddr.relays))
                30023 -> articleMarkers.add(Marker(match.range.first, match.range.last + 1, match.value, false, null,
                    articleAuthor = naddr.author ?: "", articleDTag = naddr.dTag,
                    articleRelays = naddr.relays ?: emptyList()))
            }
        } catch (_: Exception) { }
    }

    val urlMarkers = urlPositions.map { (range, url) ->
        val isMed = url in mediaUrls
        val isConsumed = url in consumedUrls
        Marker(range.first, range.last + 1, url, isMed || isConsumed, if (!isMed && !isConsumed) previewByUrl[url] else null)
    }

    // Merge and sort all markers by position; quote/emoji-pack/article markers take priority over URL markers at same position
    val specialRanges = (quoteMarkers + emojiPackMarkers + articleMarkers).map { it.start..it.end }.toSet()
    val filteredUrlMarkers = urlMarkers.filter { m -> specialRanges.none { qr -> m.start in qr || m.end - 1 in qr } }
    val markers = (filteredUrlMarkers + quoteMarkers + emojiPackMarkers + articleMarkers).sortedBy { it.start }

    // Also hide quote/emoji-pack/article URIs from text rendering
    val hiddenUris = (quoteMarkers + emojiPackMarkers + articleMarkers).map { it.url }.toSet()
    val allHiddenUrlsWithQuotes = allHiddenUrls + hiddenUris

    if (markers.isEmpty()) {
        val full = buildNoteContentAnnotatedString(content, allHiddenUrlsWithQuotes, linkStyle, profileCache, null, emojiUrls)
        return if (full.isNotEmpty()) listOf(NoteContentBlock.Content(full, emojiUrls)) else emptyList()
    }

    // Group consecutive media markers (only whitespace/newlines between them) into MediaGroups
    val blocks = mutableListOf<NoteContentBlock>()
    var cursor = 0 // current position in content

    fun emitTextBlock(from: Int, to: Int) {
        if (from >= to) return
        val chunk = buildNoteContentAnnotatedString(
            content, allHiddenUrlsWithQuotes, linkStyle, profileCache,
            IntRange(from, to - 1), emojiUrls
        )
        if (chunk.isNotEmpty()) blocks.add(NoteContentBlock.Content(chunk, emojiUrls))
    }

    var i = 0
    while (i < markers.size) {
        val m = markers[i]
        if (m.articleAuthor != null && m.articleDTag != null) {
            // Inline article reference (kind-30023) – emit text before it, then the article block
            emitTextBlock(cursor, m.start)
            blocks.add(NoteContentBlock.Article(m.articleAuthor, m.articleDTag, m.articleRelays ?: emptyList()))
            cursor = m.end
            i++
        } else if (m.emojiPackAuthor != null && m.emojiPackDTag != null) {
            // Inline emoji pack reference – emit text before it, then the pack block
            emitTextBlock(cursor, m.start)
            blocks.add(NoteContentBlock.EmojiPack(m.emojiPackAuthor, m.emojiPackDTag, m.emojiPackRelays ?: emptyList()))
            cursor = m.end
            i++
        } else if (m.quotedEventId != null) {
            // Inline quoted note or live event reference – emit text before it, then the block
            emitTextBlock(cursor, m.start)
            if (m.liveEventRelays != null) {
                blocks.add(NoteContentBlock.LiveEventReference(m.quotedEventId, m.liveEventAuthor, m.liveEventRelays))
            } else {
                blocks.add(NoteContentBlock.QuotedNote(m.quotedEventId))
            }
            cursor = m.end
            i++
        } else if (m.isMedia) {
            // Start of a potential media group – collect consecutive media markers
            val groupUrls = mutableListOf<String>()
            if (m.url !in consumedUrls) groupUrls.add(m.url)
            var groupEnd = m.end
            var j = i + 1
            while (j < markers.size && markers[j].isMedia) {
                // Check that only whitespace separates this media URL from the previous one
                val between = content.substring(groupEnd, markers[j].start)
                if (between.isNotBlank()) break
                if (markers[j].url !in consumedUrls) groupUrls.add(markers[j].url)
                groupEnd = markers[j].end
                j++
            }
            // Emit any text before this media group
            emitTextBlock(cursor, m.start)
            // Only emit MediaGroup if there are actual media URLs (not just consumed ones)
            if (groupUrls.isNotEmpty()) blocks.add(NoteContentBlock.MediaGroup(groupUrls))
            cursor = groupEnd
            i = j
        } else if (m.preview != null) {
            // Link with preview – emit text up to (and including) the URL, then the preview
            emitTextBlock(cursor, m.end)
            blocks.add(NoteContentBlock.Preview(m.preview))
            cursor = m.end
            i++
        } else {
            // Regular URL without preview – just advance past it (text builder handles it)
            i++
        }
    }
    // Emit any trailing text
    emitTextBlock(cursor, content.length)
    return blocks
}

/**
 * Extracts pubkey hex values from note content (npub decoded + 64-char hex) for kind-0 requests.
 * Use in NotesRepository (and reply repos) to request profiles for tagged/mentioned users.
 */
fun extractPubkeysFromContent(content: String): List<String> {
    return extractPubkeysWithHintsFromContent(content).map { it.first }
}

/**
 * Extracts pubkey hex values from note content (npub, nprofile, 64-char hex) for kind-0 requests.
 * Returns `List<Pair<pubkey, relayHints>>` — relay hints come from nprofile TLV data.
 * npub and bare hex entries have empty relay hint lists.
 *
 * Order is preserved: entries appear in the order they are encountered in the content,
 * so first-seen nprofile hints get priority when constructing profile fetch relay sets.
 */
fun extractPubkeysWithHintsFromContent(content: String): List<Pair<String, List<String>>> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<Pair<String, List<String>>>()
    npubPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val hex = (parsed.entity as? NPub)?.hex?.lowercase() ?: return@forEach
            if (hex.length == 64 && seen.add(hex)) result.add(hex to emptyList())
        } catch (_: Exception) { }
    }
    // NIP-19 nprofile mentions (profile with relay hints) — relay hints are preserved
    nprofilePattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val profile = parsed.entity as? NProfile ?: return@forEach
            val hex = profile.hex.lowercase()
            if (hex.length == 64 && seen.add(hex)) {
                result.add(hex to profile.relays.filter { it.startsWith("wss://") || it.startsWith("ws://") })
            }
        } catch (_: Exception) { }
    }
    hexPubkeyPattern.findAll(content).forEach { match ->
        val hex = match.groupValues.getOrNull(1)?.takeIf { it.length == 64 }?.lowercase() ?: return@forEach
        if (seen.add(hex)) result.add(hex to emptyList())
    }
    return result
}

