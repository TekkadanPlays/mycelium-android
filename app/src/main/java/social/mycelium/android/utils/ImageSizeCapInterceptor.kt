package social.mycelium.android.utils

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size

/**
 * Global Coil interceptor that caps **unconstrained** image decodes to 2/3 screen width.
 *
 * Any [coil.request.ImageRequest] that arrives with [Size.ORIGINAL] or undefined
 * dimensions (i.e. no explicit `.size()` was set by the caller) is rewritten to
 * decode at most [capPx] × [capPx]. This catches the ~70 raw `AsyncImage` calls
 * that don't specify a size (URL previews, DM avatars, thread images, etc.).
 *
 * Images that already have an explicit `.size()` (ProfilePicture at 120px,
 * ReactionEmoji at 48px, NoteMediaCarousel at screenWidth) are left untouched —
 * they control their own decode resolution for optimal memory use.
 */
class ImageSizeCapInterceptor(context: Context) : Interceptor {

    private val capPx: Int = context.resources.displayMetrics.widthPixels * 2 / 3

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val size = chain.size

        val needsCap = when {
            size == Size.ORIGINAL -> true
            size.width is Dimension.Undefined && size.height is Dimension.Undefined -> true
            else -> false
        }

        return if (needsCap) {
            val capped = request.newBuilder()
                .size(capPx, capPx)
                .build()
            chain.proceed(capped)
        } else {
            chain.proceed(request)
        }
    }
}
