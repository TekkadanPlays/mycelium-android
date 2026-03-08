package social.mycelium.android.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

/**
 * Live markdown syntax highlighting for compose text fields.
 * Renders headings, bold, italic, code, blockquotes, lists,
 * URLs, and nostr: entity references with colored spans.
 *
 * Two modes:
 * - Raw mode (stripDelimiters=false): Shows markdown syntax with colored highlighting
 * - Visual mode (stripDelimiters=true): Hides delimiters and shows formatted text
 *
 * Based on Prism by hardran3.
 * @see <a href="https://github.com/hardran3/Prism">Prism</a>
 */
class MarkdownVisualTransformation(
    private val highlightColor: Color = Color(0xFF9C27B0),
    private val codeColor: Color = Color(0xFF4CAF50),
    private val linkColor: Color = Color(0xFF2196F3),
    private val nostrColor: Color = Color(0xFF9C27B0),
    private val h1Style: TextStyle? = null,
    private val h2Style: TextStyle? = null,
    private val h3Style: TextStyle? = null,
    private val stripDelimiters: Boolean = false
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val result = renderMarkdown(text.text)
        return TransformedText(result.annotatedString, result.offsetMapping)
    }

    data class MarkdownResult(
        val annotatedString: AnnotatedString,
        val offsetMapping: OffsetMapping
    )

    private fun renderMarkdown(text: String): MarkdownResult {
        val builder = AnnotatedString.Builder()
        val originalToTransformed = IntArray(text.length + 1)
        val transformedToOriginalList = mutableListOf<Int>()

        fun emit(content: String, origStart: Int, origLength: Int) {
            val startInTrans = builder.length
            builder.append(content)
            for (k in 0 until content.length) {
                transformedToOriginalList.add(origStart + k.coerceAtMost(origLength - 1))
            }
            for (k in 0 until origLength) {
                originalToTransformed[origStart + k] = startInTrans + k.coerceAtMost(content.length - 1)
            }
        }

        fun skip(origStart: Int, origLength: Int) {
            val currentTrans = builder.length
            for (k in 0 until origLength) {
                originalToTransformed[origStart + k] = currentTrans
            }
        }

        val lines = text.split("\n")
        var currentOffset = 0

        // Pre-scan for fenced code blocks
        val inCodeBlock = BooleanArray(lines.size)
        val isFenceLine = BooleanArray(lines.size)
        var fenceOpen = false
        for (idx in lines.indices) {
            if (lines[idx].trimStart().startsWith("```")) {
                isFenceLine[idx] = true
                fenceOpen = !fenceOpen
            } else if (fenceOpen) {
                inCodeBlock[idx] = true
            }
        }

        lines.forEachIndexed { i, line ->
            val lineStart = currentOffset

            when {
                isFenceLine[i] -> {
                    if (!stripDelimiters) emit(line, lineStart, line.length)
                    else skip(lineStart, line.length)
                }
                inCodeBlock[i] -> {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor.copy(alpha = 0.1f), color = codeColor))
                    emit(line, lineStart, line.length)
                    builder.pop()
                }
                line.startsWith("#") && line.getOrNull(line.takeWhile { it == '#' }.length) == ' ' -> {
                    val level = line.takeWhile { it == '#' }.length
                    val prefixLen = level + 1
                    val style = when (level) {
                        1 -> h1Style
                        2 -> h2Style
                        else -> h3Style
                    }
                    builder.pushStyle(SpanStyle(fontSize = style?.fontSize ?: TextUnit.Unspecified, fontWeight = FontWeight.Bold, color = highlightColor))
                    if (!stripDelimiters) {
                        renderInline(line, lineStart, builder, ::emit, ::skip)
                    } else {
                        skip(lineStart, prefixLen)
                        renderInline(line.substring(prefixLen), lineStart + prefixLen, builder, ::emit, ::skip)
                    }
                    builder.pop()
                }
                line.startsWith("> ") -> {
                    builder.pushStyle(SpanStyle(color = highlightColor.copy(alpha = 0.7f), fontStyle = FontStyle.Italic, background = highlightColor.copy(alpha = 0.05f)))
                    if (!stripDelimiters) {
                        renderInline(line, lineStart, builder, ::emit, ::skip)
                    } else {
                        skip(lineStart, 2)
                        renderInline(line.substring(2), lineStart + 2, builder, ::emit, ::skip)
                    }
                    builder.pop()
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    builder.pushStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold))
                    emit(line.substring(0, 2), lineStart, 2)
                    builder.pop()
                    renderInline(line.substringAfter(" "), lineStart + 2, builder, ::emit, ::skip)
                }
                else -> {
                    renderInline(line, lineStart, builder, ::emit, ::skip)
                }
            }

            currentOffset += line.length
            if (i < lines.size - 1) {
                val nlStart = currentOffset
                builder.append("\n")
                transformedToOriginalList.add(nlStart)
                originalToTransformed[nlStart] = builder.length - 1
                currentOffset += 1
            }
        }

        originalToTransformed[text.length] = builder.length
        transformedToOriginalList.add(text.length)

        val o2t = originalToTransformed
        val t2o = transformedToOriginalList.toIntArray()

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                o2t[offset.coerceIn(0, text.length)]
            override fun transformedToOriginal(offset: Int): Int =
                t2o[offset.coerceIn(0, t2o.size - 1)]
        }

        return MarkdownResult(builder.toAnnotatedString(), mapping)
    }

    private fun renderInline(
        text: String,
        baseOffset: Int,
        builder: AnnotatedString.Builder,
        emit: (String, Int, Int) -> Unit,
        skip: (Int, Int) -> Unit
    ) {
        val pattern = Regex("""(https?://[^\s]+|nostr:(?:nevent1|note1|naddr1|npub1|nprofile1)[a-z0-9]+|\*\*\*(.+?)\*\*\*|\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|#\w+)""", RegexOption.IGNORE_CASE)
        var lastIdx = 0

        pattern.findAll(text).forEach { match ->
            val plainText = text.substring(lastIdx, match.range.first)
            emit(plainText, baseOffset + lastIdx, plainText.length)

            val m = match.value
            val mStart = baseOffset + match.range.first

            when {
                m.startsWith("http") -> {
                    builder.pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    emit(m, mStart, m.length)
                    builder.pop()
                }
                m.startsWith("nostr:") -> {
                    builder.pushStyle(SpanStyle(color = nostrColor, fontWeight = FontWeight.Bold))
                    emit(m, mStart, m.length)
                    builder.pop()
                }
                m.startsWith("***") && m.endsWith("***") -> {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    if (stripDelimiters) {
                        skip(mStart, 3)
                        emit(m.substring(3, m.length - 3), mStart + 3, m.length - 6)
                        skip(mStart + m.length - 3, 3)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                m.startsWith("**") && m.endsWith("**") && m.length >= 4 -> {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    if (stripDelimiters) {
                        skip(mStart, 2)
                        emit(m.substring(2, m.length - 2), mStart + 2, m.length - 4)
                        skip(mStart + m.length - 2, 2)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                m.startsWith("*") && m.endsWith("*") && m.length >= 2 -> {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    if (stripDelimiters) {
                        skip(mStart, 1)
                        emit(m.substring(1, m.length - 1), mStart + 1, m.length - 2)
                        skip(mStart + m.length - 1, 1)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                m.startsWith("`") && m.endsWith("`") -> {
                    builder.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.LightGray.copy(alpha = 0.2f)))
                    if (stripDelimiters) {
                        skip(mStart, 1)
                        emit(m.substring(1, m.length - 1), mStart + 1, m.length - 2)
                        skip(mStart + m.length - 1, 1)
                    } else {
                        emit(m, mStart, m.length)
                    }
                    builder.pop()
                }
                m.startsWith("#") -> {
                    builder.pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold))
                    emit(m, mStart, m.length)
                    builder.pop()
                }
                else -> emit(m, mStart, m.length)
            }
            lastIdx = match.range.last + 1
        }
        val remaining = text.substring(lastIdx)
        emit(remaining, baseOffset + lastIdx, remaining.length)
    }
}
