package social.mycelium.android.utils

import android.util.Log
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.ErrorResult
import coil.request.SuccessResult

/**
 * Coil interceptor that handles blossom content-addressed URL fallback.
 *
 * When an image request fails for a blossom URL (identified by the 64-char hex hash
 * path pattern), this interceptor automatically retries the same hash on other known
 * blossom servers. This is possible because blossom blobs are content-addressed —
 * the same SHA-256 hash resolves to the same file on any server that has it.
 *
 * Flow:
 * 1. Check if the URL has a cached resolution → use that directly
 * 2. Proceed with original request
 * 3. On failure, if URL is blossom, try fallback servers sequentially
 * 4. Cache the first working server for future requests
 */
class BlossomFallbackInterceptor : Interceptor {

    companion object {
        private const val TAG = "BlossomFallback"
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val originalData = chain.request.data
        if (originalData !is String) return chain.proceed(chain.request)

        // If we already know a working URL for this blossom hash, swap it in
        val resolved = BlossomUrlResolver.getResolved(originalData)
        if (resolved != originalData) {
            val swappedRequest = chain.request.newBuilder().data(resolved).build()
            val result = chain.proceed(swappedRequest)
            if (result is SuccessResult) return result
            // Resolved URL stopped working — clear cache and fall through to retry
        }

        // If permanently failed, skip fallback to avoid wasting time
        if (BlossomUrlResolver.isPermanentlyFailed(originalData)) {
            return chain.proceed(chain.request)
        }

        // Try the original request first
        val originalResult = chain.proceed(chain.request)
        if (originalResult is SuccessResult) return originalResult

        // Only attempt fallback for blossom URLs
        if (!BlossomUrlResolver.isBlossom(originalData)) return originalResult

        // Original failed — try fallback servers
        val fallbacks = BlossomUrlResolver.getFallbackUrls(originalData)
        if (fallbacks.isEmpty()) return originalResult

        Log.d(TAG, "Original blossom URL failed, trying ${fallbacks.size} fallback servers for: $originalData")

        for (fallbackUrl in fallbacks) {
            try {
                val fallbackRequest = chain.request.newBuilder().data(fallbackUrl).build()
                val fallbackResult = chain.proceed(fallbackRequest)
                if (fallbackResult is SuccessResult) {
                    BlossomUrlResolver.recordResolved(originalData, fallbackUrl)
                    return fallbackResult
                }
            } catch (e: Exception) {
                Log.d(TAG, "Fallback failed: $fallbackUrl — ${e.message}")
            }
        }

        // All servers exhausted
        BlossomUrlResolver.recordPermanentFailure(originalData)
        return originalResult
    }
}
