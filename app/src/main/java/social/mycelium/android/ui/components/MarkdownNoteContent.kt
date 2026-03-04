package social.mycelium.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Heuristic to detect whether note content contains Reddit-style markdown formatting.
 */
fun isMarkdown(content: String): Boolean =
    content.startsWith("> ") ||
        content.startsWith("# ") ||
        content.contains("##") ||
        content.contains("__") ||
        content.contains("**") ||
        content.contains("```") ||
        content.contains("](") ||
        content.contains("~~") ||
        content.contains("* ") ||
        content.contains("- ") ||
        content.matches(Regex("(?s).*\\n\\d+\\. .*"))

/**
 * Renders note content with Reddit-style markdown formatting.
 *
 * Supported syntax:
 * - **bold** / __bold__
 * - *italic* / _italic_
 * - ~~strikethrough~~
 * - `inline code`
 * - ```code block```
 * - # Heading 1 through ###### Heading 6
 * - > blockquote
 * - - bullet list / * bullet list
 * - 1. numbered list
 * - [link text](url)
 * - ^superscript
 * - --- horizontal rule
 *
 * Uses the app's theme colors for links instead of bright blue.
 */
@Composable
fun MarkdownNoteContent(
    content: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: () -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeForeground = MaterialTheme.colorScheme.onSurface
    val quoteBarColor = MaterialTheme.colorScheme.outlineVariant
    val quoteTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    val blocks = remember(content) { parseMarkdownBlocks(content) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val headingStyle = when (block.level) {
                        1 -> style.copy(fontSize = style.fontSize * 1.6f, fontWeight = FontWeight.Bold)
                        2 -> style.copy(fontSize = style.fontSize * 1.4f, fontWeight = FontWeight.Bold)
                        3 -> style.copy(fontSize = style.fontSize * 1.2f, fontWeight = FontWeight.SemiBold)
                        4 -> style.copy(fontSize = style.fontSize * 1.1f, fontWeight = FontWeight.SemiBold)
                        else -> style.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, linkColor, codeBackground, codeForeground),
                        style = headingStyle,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, linkColor, codeBackground, codeForeground),
                        style = style
                    )
                }
                is MdBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .width(3.dp)
                                .background(quoteBarColor, RoundedCornerShape(2.dp))
                                .padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, linkColor, codeBackground, codeForeground),
                            style = style.copy(
                                color = quoteTextColor,
                                fontStyle = FontStyle.Italic
                            )
                        )
                    }
                }
                is MdBlock.CodeBlock -> {
                    Text(
                        text = block.code,
                        style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = style.fontSize * 0.9f,
                            color = codeForeground
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(codeBackground, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    )
                }
                is MdBlock.ListItem -> {
                    Row(modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp)) {
                        Text(
                            text = block.bullet,
                            style = style,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, linkColor, codeBackground, codeForeground),
                            style = style,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        thickness = 1.dp,
                        color = quoteBarColor
                    )
                }
                is MdBlock.BlankLine -> {
                    Spacer(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

// ── Block-level parsing ──

internal sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Blockquote(val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class ListItem(val bullet: String, val text: String) : MdBlock()
    data object HorizontalRule : MdBlock()
    data object BlankLine : MdBlock()
}

private val headingRegex = Regex("^(#{1,6})\\s+(.*)")
private val bulletRegex = Regex("^\\s*[-*]\\s+(.*)")
private val numberedRegex = Regex("^\\s*(\\d+)\\.\\s+(.*)")
private val blockquoteRegex = Regex("^>\\s?(.*)")
private val hrRegex = Regex("^\\s*[-*_]{3,}\\s*$")

internal fun parseMarkdownBlocks(content: String): List<MdBlock> {
    val lines = content.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val trimmed = line.trimStart()
            // Single-line fenced code: ```content``` on one line
            if (trimmed.length > 6 && trimmed.endsWith("```")) {
                val inner = trimmed.removePrefix("```").removeSuffix("```")
                // Strip optional language hint (e.g. ```kotlin ... ```)
                val code = if (inner.contains('\n')) inner else {
                    val spaceIdx = inner.indexOf(' ')
                    if (spaceIdx > 0 && spaceIdx < 12 && inner.substring(0, spaceIdx).all { it.isLetterOrDigit() }) inner.substring(spaceIdx + 1) else inner
                }
                blocks.add(MdBlock.CodeBlock(code))
                i++
                continue
            }
            // Multi-line fenced code block: opening ``` on this line, closing ``` on a later line
            val codeLines = mutableListOf<String>()
            // The opening line may have a language hint after ```, skip it
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
            continue
        }

        // Horizontal rule
        if (hrRegex.matches(line)) {
            blocks.add(MdBlock.HorizontalRule)
            i++
            continue
        }

        // Heading
        val headingMatch = headingRegex.matchEntire(line)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2]
            blocks.add(MdBlock.Heading(level, text))
            i++
            continue
        }

        // Blockquote (collect consecutive > lines)
        val bqMatch = blockquoteRegex.matchEntire(line)
        if (bqMatch != null) {
            val quoteLines = mutableListOf(bqMatch.groupValues[1])
            i++
            while (i < lines.size) {
                val nextBq = blockquoteRegex.matchEntire(lines[i])
                if (nextBq != null) {
                    quoteLines.add(nextBq.groupValues[1])
                    i++
                } else break
            }
            blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            continue
        }

        // Bullet list item
        val bulletMatch = bulletRegex.matchEntire(line)
        if (bulletMatch != null) {
            blocks.add(MdBlock.ListItem("•", bulletMatch.groupValues[1]))
            i++
            continue
        }

        // Numbered list item
        val numMatch = numberedRegex.matchEntire(line)
        if (numMatch != null) {
            blocks.add(MdBlock.ListItem("${numMatch.groupValues[1]}.", numMatch.groupValues[2]))
            i++
            continue
        }

        // Blank line — emit spacer so paragraph breaks are preserved
        if (line.isBlank()) {
            // Avoid duplicate spacers (multiple consecutive blank lines → one spacer)
            if (blocks.lastOrNull() !is MdBlock.BlankLine) {
                blocks.add(MdBlock.BlankLine)
            }
            i++
            continue
        }

        // Regular paragraph — collect consecutive non-special lines
        val paraLines = mutableListOf(line)
        i++
        while (i < lines.size) {
            val next = lines[i]
            if (next.isBlank() ||
                next.trimStart().startsWith("```") ||
                headingRegex.matches(next) ||
                blockquoteRegex.matches(next) ||
                bulletRegex.matches(next) ||
                numberedRegex.matches(next) ||
                hrRegex.matches(next)
            ) break
            paraLines.add(next)
            i++
        }
        blocks.add(MdBlock.Paragraph(paraLines.joinToString("\n")))
    }

    return blocks
}

// ── Inline markdown parsing ──

// Order matters: longer delimiters first to avoid partial matches
private val inlinePatterns = listOf(
    // Triple-backtick inline code — must be before single backtick
    Regex("```([^`]+)```") to "CODE",
    // Single-backtick inline code
    Regex("`([^`]+)`") to "CODE",
    // Bold+Italic
    Regex("\\*\\*\\*(.+?)\\*\\*\\*") to "BOLD_ITALIC",
    Regex("___(.+?)___") to "BOLD_ITALIC",
    // Bold
    Regex("\\*\\*(.+?)\\*\\*") to "BOLD",
    Regex("__(.+?)__") to "BOLD",
    // Italic
    Regex("(?<!\\w)\\*(.+?)\\*(?!\\w)") to "ITALIC",
    Regex("(?<!\\w)_(.+?)_(?!\\w)") to "ITALIC",
    // Strikethrough
    Regex("~~(.+?)~~") to "STRIKE",
    // Link [text](url)
    Regex("\\[([^\\]]+)]\\(([^)]+)\\)") to "LINK",
    // Superscript ^word or ^(multiple words)
    Regex("\\^\\(([^)]+)\\)") to "SUPER",
    Regex("\\^(\\S+)") to "SUPER"
)

internal fun parseInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBackground: Color,
    codeForeground: Color
): AnnotatedString {
    return buildAnnotatedString {
        appendInlineMarkdown(text, linkColor, codeBackground, codeForeground)
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    linkColor: Color,
    codeBackground: Color,
    codeForeground: Color
) {
    // Find the earliest match across all patterns
    data class Match(val range: IntRange, val type: String, val groups: List<String>)

    var pos = 0
    while (pos < text.length) {
        var earliest: Match? = null
        for ((regex, type) in inlinePatterns) {
            val m = regex.find(text, pos) ?: continue
            if (earliest == null || m.range.first < earliest.range.first) {
                earliest = Match(m.range, type, m.groupValues)
            }
        }

        if (earliest == null || earliest.range.first >= text.length) {
            append(text.substring(pos))
            break
        }

        // Append plain text before the match
        if (earliest.range.first > pos) {
            append(text.substring(pos, earliest.range.first))
        }

        when (earliest.type) {
            "CODE" -> {
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = codeForeground,
                    fontSize = 13.sp
                )) {
                    append(earliest.groups[1])
                }
            }
            "BOLD_ITALIC" -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                    appendInlineMarkdown(earliest.groups[1], linkColor, codeBackground, codeForeground)
                }
            }
            "BOLD" -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineMarkdown(earliest.groups[1], linkColor, codeBackground, codeForeground)
                }
            }
            "ITALIC" -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineMarkdown(earliest.groups[1], linkColor, codeBackground, codeForeground)
                }
            }
            "STRIKE" -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineMarkdown(earliest.groups[1], linkColor, codeBackground, codeForeground)
                }
            }
            "LINK" -> {
                val linkText = earliest.groups[1]
                val url = earliest.groups[2]
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(linkText)
                }
                pop()
            }
            "SUPER" -> {
                withStyle(SpanStyle(
                    baselineShift = BaselineShift.Superscript,
                    fontSize = 10.sp
                )) {
                    appendInlineMarkdown(earliest.groups[1], linkColor, codeBackground, codeForeground)
                }
            }
        }

        pos = earliest.range.last + 1
    }
}
