package social.mycelium.android.services

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.signer.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.source
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Blossom (BUD-01/02/04) HTTP blob storage client for media uploads.
 * Auth events are kind 24242, signed via Amber/NostrSigner.
 *
 * Tag order matches Amethyst for maximum relay/server compatibility:
 *   ["t", "upload"], ["expiration", "..."], ["size", "..."], ["x", "<sha256>"]
 *
 * Upload strategy: PUT /upload (primary, Amethyst-aligned) → POST /upload → PUT /media
 *
 * Based on Prism by hardran3 and Amethyst by Vitor Pamplona.
 *
 * @see <a href="https://github.com/hardran3/Prism">Prism</a>
 * @see <a href="https://github.com/vitorpamplona/amethyst">Amethyst</a>
 */
class BlossomClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "BlossomClient"
        private const val KIND_BLOSSOM_AUTH = 24242
    }

    data class UploadResult(val url: String, val sha256: String?)

    // ── SHA-256 hashing ──────────────────────────────────────────────────

    suspend fun hashFile(context: Context, uri: Uri): Pair<String, Long> = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file for hashing")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var totalBytes = 0L

        inputStream.use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        Log.d(TAG, "File hashed: ${hash.take(12)}... ($totalBytes bytes)")
        Pair(hash, totalBytes)
    }

    // ── Auth event signing ───────────────────────────────────────────────

    /**
     * Build and sign a kind-24242 Blossom authorization event.
     * Tag order matches Amethyst: t, expiration, size, x
     */
    suspend fun signUploadAuth(
        signer: NostrSigner,
        hash: String,
        size: Long,
        description: String = "Uploading file"
    ): Event {
        val expiration = (System.currentTimeMillis() / 1000) + 3600 // 1 hour
        val template = Event.build(KIND_BLOSSOM_AUTH, description) {
            add(arrayOf("t", "upload"))
            add(arrayOf("expiration", expiration.toString()))
            add(arrayOf("size", size.toString()))
            add(arrayOf("x", hash))
        }
        return signer.sign(template)
    }

    /**
     * Build and sign a kind-24242 Blossom delete authorization event.
     */
    suspend fun signDeleteAuth(
        signer: NostrSigner,
        hash: String,
        description: String = "Deleting blob"
    ): Event {
        val expiration = (System.currentTimeMillis() / 1000) + 3600
        val template = Event.build(KIND_BLOSSOM_AUTH, description) {
            add(arrayOf("t", "delete"))
            add(arrayOf("expiration", expiration.toString()))
            add(arrayOf("x", hash))
        }
        return signer.sign(template)
    }

    /**
     * Encode a signed auth event as a Nostr HTTP Authorization header value.
     */
    private fun encodeAuthHeader(signedEvent: Event): String {
        val json = signedEvent.toJson()
        val encoded = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
        return "Nostr $encoded"
    }

    // ── Upload ───────────────────────────────────────────────────────────

    /**
     * Upload a file to a Blossom server.
     *
     * @param context Android context for content resolver access
     * @param uri Content URI of the file to upload
     * @param signer NostrSigner for building the auth event
     * @param serverUrl Blossom server base URL (e.g. "https://blossom.example.com")
     * @param mimeType MIME type of the file
     * @return UploadResult with the URL and optional server-returned hash
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        signer: NostrSigner,
        serverUrl: String,
        mimeType: String?
    ): UploadResult = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.removeSuffix("/")
        val effectiveMime = mimeType ?: "application/octet-stream"

        // 1. Hash the file
        val (hash, size) = hashFile(context, uri)

        // 2. Sign auth event
        val authEvent = signUploadAuth(signer, hash, size)
        val authHeader = encodeAuthHeader(authEvent)
        Log.d(TAG, "Auth event signed: ${authEvent.id.take(8)}")

        // Helper: create a streaming RequestBody from the URI
        fun newRequestBody(): RequestBody {
            val mediaType = effectiveMime.toMediaTypeOrNull()
            val contentLength = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) { -1L }

            return object : RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = contentLength
                override fun writeTo(sink: okio.BufferedSink) {
                    context.contentResolver.openInputStream(uri)?.use { source ->
                        sink.writeAll(source.source())
                    }
                }
            }
        }

        var lastError = ""

        // Strategy 1: PUT /upload (Amethyst alignment)
        try {
            val request = Request.Builder()
                .url("$cleanServer/upload")
                .put(newRequestBody())
                .header("Authorization", authHeader)
                .header("Content-Type", effectiveMime)
                .header("Content-Length", size.toString())
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response body")
                val result = parseUploadResponse(body, hash)
                Log.d(TAG, "Upload success (PUT /upload): ${result.url}")
                return@withContext result
            } else {
                val errorBody = response.body?.string() ?: "No body"
                lastError = "PUT /upload failed (${response.code}): $errorBody"
                Log.w(TAG, lastError)
                response.close()
            }
        } catch (e: Exception) {
            lastError = "PUT /upload error: ${e.message}"
            Log.w(TAG, lastError)
        }

        // Strategy 2: POST /upload (some servers prefer POST)
        try {
            val request = Request.Builder()
                .url("$cleanServer/upload")
                .post(newRequestBody())
                .header("Authorization", authHeader)
                .header("Content-Type", effectiveMime)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response body")
                val result = parseUploadResponse(body, hash)
                Log.d(TAG, "Upload success (POST /upload): ${result.url}")
                return@withContext result
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "POST /upload error: ${e.message}")
        }

        // Strategy 3: PUT /media (BUD spec alternative)
        try {
            val request = Request.Builder()
                .url("$cleanServer/media")
                .put(newRequestBody())
                .header("Authorization", authHeader)
                .header("Content-Type", effectiveMime)
                .header("Content-Length", size.toString())
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: throw Exception("Empty response body")
                val result = parseUploadResponse(body, hash)
                Log.d(TAG, "Upload success (PUT /media): ${result.url}")
                return@withContext result
            } else {
                val errorBody = response.body?.string() ?: "No body"
                lastError = "PUT /media failed (${response.code}): $errorBody"
                Log.w(TAG, lastError)
                response.close()
            }
        } catch (e: Exception) {
            lastError = "PUT /media error: ${e.message}"
            Log.w(TAG, lastError)
        }

        throw Exception("Upload failed after all strategies: $lastError")
    }

    // ── Delete ───────────────────────────────────────────────────────────

    /**
     * Delete a blob from a Blossom server.
     *
     * @param hash SHA-256 hash of the blob to delete
     * @param signer NostrSigner for building the auth event
     * @param serverUrl Blossom server base URL
     * @return true if deleted successfully
     */
    suspend fun delete(
        hash: String,
        signer: NostrSigner,
        serverUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = serverUrl.removeSuffix("/")
        val authEvent = signDeleteAuth(signer, hash)
        val authHeader = encodeAuthHeader(authEvent)

        // Strategy 1: DELETE /<hash> (Standard Blossom)
        try {
            val request = Request.Builder()
                .url("$cleanServer/$hash")
                .delete()
                .header("Authorization", authHeader)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Delete success: ${hash.take(12)}")
                return@withContext true
            }
            response.close()
        } catch (_: Exception) { }

        // Strategy 2: DELETE /media/<hash>
        try {
            val request = Request.Builder()
                .url("$cleanServer/media/$hash")
                .delete()
                .header("Authorization", authHeader)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Delete success (legacy): ${hash.take(12)}")
                return@withContext true
            }
            response.close()
        } catch (_: Exception) { }

        Log.w(TAG, "Delete failed for hash: ${hash.take(12)}")
        false
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun parseUploadResponse(responseBody: String, localHash: String): UploadResult {
        val json = JSONObject(responseBody)
        val url = json.optString("url")
        if (url.isBlank()) throw Exception("Server returned empty URL")
        val serverHash = json.optString("sha256").takeIf { it.isNotBlank() }
            ?: extractHashFromUrl(url)
        return UploadResult(url, serverHash)
    }

    private fun extractHashFromUrl(url: String): String? {
        val pathSegment = url.substringAfterLast("/").substringBefore(".")
        return if (pathSegment.length == 64 && pathSegment.all { it.isLetterOrDigit() }) pathSegment else null
    }
}
