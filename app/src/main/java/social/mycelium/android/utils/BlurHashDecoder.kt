package social.mycelium.android.utils

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.withSign

/**
 * Decodes blurhash strings into Bitmaps for use as image/video placeholders.
 * Based on the reference implementation and Amethyst's BlurHashDecoder.
 * See https://blurha.sh for the specification.
 */
object BlurHashDecoder {

    private val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~".toCharArray()
    private val charMap = ALPHABET.mapIndexed { i, c -> c.code to i }.toMap()
        .let { map -> Array(255) { map[it] ?: 0 } }

    private fun decodeAt(str: String, at: Int): Int = charMap[str[at].code]

    private fun decode(str: String, from: Int, to: Int): Int {
        var result = 0
        for (i in from until to) {
            result = result * 83 + charMap[str[i].code]
        }
        return result
    }

    private fun decodeFixed2(str: String, from: Int): Int =
        charMap[str[from].code] * 83 + charMap[str[from + 1].code]

    private fun srgbToLinear(value: Int): Float {
        val v = value.coerceIn(0, 255) / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) (v * 12.92f * 255f + 0.5f).toInt()
        else ((1.055f * v.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }

    private fun signedPow2(value: Float) = value.pow(2f).withSign(value)

    private fun decodeDc(colorEnc: Int): FloatArray {
        val r = colorEnc shr 16
        val g = (colorEnc shr 8) and 255
        val b = colorEnc and 255
        return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
    }

    private fun decodeAc(value: Int, maxAc: Float): FloatArray {
        val r = value / (19 * 19)
        val g = (value / 19) % 19
        val b = value % 19
        return floatArrayOf(
            signedPow2((r - 9) / 9.0f) * maxAc,
            signedPow2((g - 9) / 9.0f) * maxAc,
            signedPow2((b - 9) / 9.0f) * maxAc,
        )
    }

    /**
     * Decode a blurhash string into an Android Bitmap.
     * Returns null if the hash is invalid.
     *
     * @param blurHash the blurhash string
     * @param width desired output width in pixels (small values like 20–32 are fine for placeholders)
     * @param height desired output height; if null, computed from the hash's aspect ratio
     */
    fun decode(blurHash: String?, width: Int = 20, height: Int? = null): Bitmap? {
        if (blurHash == null || blurHash.length < 6) return null

        val numCompEnc = decodeAt(blurHash, 0)
        val numCompX = (numCompEnc % 9) + 1
        val numCompY = (numCompEnc / 9) + 1
        if (blurHash.length != 4 + 2 * numCompX * numCompY) return null

        val h = height ?: (width * numCompY.toFloat() / numCompX.toFloat()).roundToInt()

        val maxAc = (decodeAt(blurHash, 1) + 1) / 166f
        val colors = Array(numCompX * numCompY) { i ->
            if (i == 0) decodeDc(decode(blurHash, 2, 6))
            else decodeAc(decodeFixed2(blurHash, 4 + i * 2), maxAc)
        }

        val pixels = IntArray(width * h)
        for (y in 0 until h) {
            for (x in 0 until width) {
                var r = 0f; var g = 0f; var b = 0f
                for (j in 0 until numCompY) {
                    for (i in 0 until numCompX) {
                        val basis = (cos(PI * x * i / width) * cos(PI * y * j / h)).toFloat()
                        val color = colors[j * numCompX + i]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[x + width * y] =
                    (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }

        return Bitmap.createBitmap(pixels, width, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Extract aspect ratio (width/height) from a blurhash string without decoding.
     * Returns null for invalid hashes.
     */
    fun aspectRatio(blurHash: String?): Float? {
        if (blurHash == null || blurHash.length < 6) return null
        val numCompEnc = decodeAt(blurHash, 0)
        val numCompX = (numCompEnc % 9) + 1
        val numCompY = (numCompEnc / 9) + 1
        if (blurHash.length != 4 + 2 * numCompX * numCompY) return null
        return numCompX.toFloat() / numCompY.toFloat()
    }
}
