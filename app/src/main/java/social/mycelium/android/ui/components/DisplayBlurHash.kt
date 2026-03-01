package social.mycelium.android.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import social.mycelium.android.utils.BlurHashDecoder

/**
 * Renders a blurhash string as a blurred placeholder image.
 * The bitmap is decoded once and remembered across recompositions.
 * Uses a small decode size (32px wide) for performance — it's just a placeholder.
 */
@Composable
fun DisplayBlurHash(
    blurhash: String?,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    modifier: Modifier = Modifier,
) {
    if (blurhash.isNullOrBlank()) return

    val bitmap: Bitmap? = remember(blurhash) {
        BlurHashDecoder.decode(blurhash, width = 32)
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}
