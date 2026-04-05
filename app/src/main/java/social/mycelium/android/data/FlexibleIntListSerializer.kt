package social.mycelium.android.data

import social.mycelium.android.debug.MLog
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flexible serializer for the NIP-11 `supported_nips` field.
 *
 * Relays in the wild return this field in many broken formats:
 *   - `[1, 2, 9, 11]`       (spec-compliant array of ints)
 *   - `["1", "2", "9"]`     (array of strings)
 *   - `[1, "invalid", 3]`   (mixed array)
 *   - `1`                    (single integer)
 *   - `"1"`                  (single string — rare but exists)
 *   - `true`                 (boolean — broken relay)
 *   - `null`
 *
 * This serializer normalises all of those into `List<Int>?`, extracting
 * whatever integers it can and silently dropping the rest.
 */
object FlexibleIntListSerializer : KSerializer<List<Int>?> {
    private val listSerializer = ListSerializer(Int.serializer())

    override val descriptor: SerialDescriptor = listSerializer.descriptor

    override fun deserialize(decoder: Decoder): List<Int>? {
        require(decoder is JsonDecoder) { "FlexibleIntListSerializer requires Json" }

        return when (val element = decoder.decodeJsonElement()) {
            is JsonNull -> null

            is JsonArray -> {
                element.mapNotNull { el ->
                    try {
                        el.jsonPrimitive.intOrNull
                            ?: el.jsonPrimitive.content.toIntOrNull()
                    } catch (e: Exception) {
                        MLog.w("FlexibleIntList", "Skipping invalid array element: $el")
                        null
                    }
                }
            }

            is JsonPrimitive -> {
                val asInt = element.intOrNull ?: element.content.toIntOrNull()
                if (asInt != null) listOf(asInt) else null
            }

            else -> null
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: List<Int>?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            listSerializer.serialize(encoder, value)
        }
    }
}
