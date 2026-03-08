package social.mycelium.android.utils

/**
 * Transforms plain ASCII text into Unicode mathematical/decorative variants.
 * Supports 15 styles plus combining strikethrough and underline.
 *
 * Based on Prism by hardran3.
 * @see <a href="https://github.com/hardran3/Prism">Prism</a>
 */
object UnicodeStylizer {

    enum class Style(val preview: String) {
        DEFAULT("Plain"),
        SERIF_BOLD("Serif Bold"),
        SERIF_ITALIC("Serif Italic"),
        SERIF_BOLD_ITALIC("Serif Bold Italic"),
        SANS_BOLD("Sans Bold"),
        SANS_ITALIC("Sans Italic"),
        SANS_BOLD_ITALIC("Sans Bold Italic"),
        SCRIPT("Script"),
        SCRIPT_BOLD("Script Bold"),
        FRAKTUR("Fraktur"),
        FRAKTUR_BOLD("Fraktur Bold"),
        MONOSPACE("Monospace"),
        DOUBLE_STRUCK("Double Struck"),
        CIRCLED("Circled"),
        SMALL_CAPS("Small Caps");
    }

    const val STRIKETHROUGH_CHAR = 0x0336
    const val UNDERLINE_CHAR = 0x0332

    fun stylize(text: String, style: Style): String {
        if (text.isEmpty() || style == Style.DEFAULT) return text
        val map = FORWARD_MAPS[style] ?: return text
        val sb = StringBuilder()
        text.codePoints().forEach { cp ->
            sb.append(map[cp] ?: String(Character.toChars(cp)))
        }
        return sb.toString()
    }

    fun normalize(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val styledStr = text.substring(i, i + charCount)

            val originalCp = REVERSE_MAP[styledStr]
            if (originalCp != null) {
                sb.append(Character.toChars(originalCp))
            } else {
                if (cp != STRIKETHROUGH_CHAR && cp != UNDERLINE_CHAR) {
                    sb.append(Character.toChars(cp))
                }
            }
            i += charCount
        }
        return sb.toString()
    }

    fun toggleStrikethrough(text: String): String {
        val isApplied = text.contains(STRIKETHROUGH_CHAR.toChar())
        val sb = StringBuilder()
        text.codePoints().forEach { cp ->
            if (cp != STRIKETHROUGH_CHAR) {
                sb.append(Character.toChars(cp))
                if (!isApplied && Character.isLetterOrDigit(cp)) {
                    sb.append(Character.toChars(STRIKETHROUGH_CHAR))
                }
            }
        }
        return sb.toString()
    }

    fun toggleUnderline(text: String): String {
        val isApplied = text.contains(UNDERLINE_CHAR.toChar())
        val sb = StringBuilder()
        text.codePoints().forEach { cp ->
            if (cp != UNDERLINE_CHAR) {
                sb.append(Character.toChars(cp))
                if (!isApplied && Character.isLetterOrDigit(cp)) {
                    sb.append(Character.toChars(UNDERLINE_CHAR))
                }
            }
        }
        return sb.toString()
    }

    private fun createMap(
        upperBase: Int,
        lowerBase: Int,
        digitBase: Int? = null,
        exceptions: Map<Int, Int> = emptyMap()
    ): Map<Int, String> {
        val map = mutableMapOf<Int, String>()
        for (i in 0 until 26) {
            val upperPlain = 'A'.code + i
            val upperStyled = exceptions[upperPlain] ?: (upperBase + i)
            map[upperPlain] = String(Character.toChars(upperStyled))

            val lowerPlain = 'a'.code + i
            val lowerStyled = exceptions[lowerPlain] ?: (lowerBase + i)
            map[lowerPlain] = String(Character.toChars(lowerStyled))
        }
        if (digitBase != null) {
            for (i in 0 until 10) {
                val digitPlain = '0'.code + i
                val digitStyled = digitBase + i
                map[digitPlain] = String(Character.toChars(digitStyled))
            }
        }
        return map
    }

    private val FORWARD_MAPS: Map<Style, Map<Int, String>> by lazy {
        mapOf(
            Style.SERIF_BOLD to createMap(0x1D400, 0x1D41A, 0x1D7CE),
            Style.SERIF_ITALIC to createMap(0x1D434, 0x1D44E, null, mapOf(
                'h'.code to 0x1D489
            )),
            Style.SERIF_BOLD_ITALIC to createMap(0x1D468, 0x1D482),
            Style.SANS_BOLD to createMap(0x1D5D4, 0x1D5EE, 0x1D7EC),
            Style.SANS_ITALIC to createMap(0x1D608, 0x1D622),
            Style.SANS_BOLD_ITALIC to createMap(0x1D63C, 0x1D656),
            Style.SCRIPT to createMap(0x1D49C, 0x1D4B6, null, mapOf(
                'B'.code to 0x212C, 'E'.code to 0x2130, 'F'.code to 0x2131, 'H'.code to 0x210B,
                'I'.code to 0x2110, 'L'.code to 0x2112, 'M'.code to 0x2133, 'R'.code to 0x211B,
                'e'.code to 0x212F, 'g'.code to 0x210A, 'o'.code to 0x2134
            )),
            Style.SCRIPT_BOLD to createMap(0x1D4D0, 0x1D4EA),
            Style.FRAKTUR to createMap(0x1D504, 0x1D51E, null, mapOf(
                'C'.code to 0x212D, 'H'.code to 0x210C, 'I'.code to 0x2111, 'R'.code to 0x211C, 'Z'.code to 0x2128
            )),
            Style.FRAKTUR_BOLD to createMap(0x1D56C, 0x1D586),
            Style.MONOSPACE to createMap(0x1D670, 0x1D68A, 0x1D7F6),
            Style.DOUBLE_STRUCK to createMap(0x1D538, 0x1D552, 0x1D7D8, mapOf(
                'C'.code to 0x2102, 'H'.code to 0x210D, 'N'.code to 0x2115, 'P'.code to 0x2119,
                'Q'.code to 0x211A, 'R'.code to 0x211D, 'Z'.code to 0x2124
            )),
            Style.CIRCLED to mutableMapOf<Int, String>().apply {
                for (i in 0 until 26) {
                    put('A'.code + i, String(Character.toChars(0x24B6 + i)))
                    put('a'.code + i, String(Character.toChars(0x24D0 + i)))
                }
                put('0'.code, String(Character.toChars(0x24EA)))
                for (i in 1..9) put('0'.code + i, String(Character.toChars(0x2460 + i - 1)))
            },
            Style.SMALL_CAPS to mutableMapOf<Int, String>().apply {
                val caps = mapOf(
                    'A' to "\u1D00", 'B' to "\u0299", 'C' to "\u1D04", 'D' to "\u1D05",
                    'E' to "\u1D07", 'F' to "\uA730", 'G' to "\u0262", 'H' to "\u029C",
                    'I' to "\u026A", 'J' to "\u1D0A", 'K' to "\u1D0B", 'L' to "\u029F",
                    'M' to "\u1D0D", 'N' to "\u0274", 'O' to "\u1D0F", 'P' to "\u1D18",
                    'Q' to "\u01EB", 'R' to "\u0280", 'S' to "\uA731", 'T' to "\u1D1B",
                    'U' to "\u1D1C", 'V' to "\u1D20", 'W' to "\u1D21", 'X' to "x",
                    'Y' to "\u028F", 'Z' to "\u1D22"
                )
                caps.forEach { (char, styled) ->
                    put(char.uppercaseChar().code, styled)
                    put(char.lowercaseChar().code, styled)
                }
            }
        )
    }

    private val REVERSE_MAP: Map<String, Int> by lazy {
        val reverse = mutableMapOf<String, Int>()
        FORWARD_MAPS.values.forEach { map ->
            map.forEach { (plain, styled) ->
                if (styled.length > 1 || styled[0].code > 127) {
                    reverse[styled] = plain
                }
            }
        }
        reverse
    }
}
