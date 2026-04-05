package social.mycelium.android.utils

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Size

/**
 * Global Coil interceptor that caps decoded image dimensions to screen width.
 *
 * Any [coil.request.ImageRequest] that arrives with [Size.ORIGINAL] (i.e. no
 * explicit `.size()` was set by the caller) is rewritten to decode at most
 * [maxDimensionPx] × [maxDimensionPx]. This prevents full-resolution decode
 * storms from the ~70 raw `AsyncImage` calls across the app that don't specify
 * a size (avatars in DMs, emoji pack icons, thread images, URL previews, etc.).
 *
 * Images that already have an explicit `.size()` (e.g. [ProfilePicture],
 * [NoteMediaCarousel]) are left untouched.
 *
 * Impact: a 4000×4000 JPEG that would decode to ~64MB ARGB_8888 now decodes
 * to ~4MB at 1080px, an **16× memory reduction** per uncapped image.
 */
class ImageSizeCapInterceptor(context: Context) : Interceptor {

    private val maxDimensionPx: Int = context.resources.displayMetrics.widthPixels

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
                .size(maxDimensionPx, maxDimensionPx)
                .build()
            chain.proceed(capped)
        } else {
            chain.proceed(request)
        }
    }
}
