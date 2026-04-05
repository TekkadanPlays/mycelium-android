package social.mycelium.android.services

import android.content.Context
import android.net.Uri
import android.util.Base64
import social.mycelium.android.debug.MLog
import com.example.cybin.core.Event
import com.example.cybin.signer.NostrSigner
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

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
    private val client: HttpClient = social.mycelium.android.network.MyceliumHttpClient.instance
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
        MLog.d(TAG, "File hashed: ${hash.take(12)}... ($totalBytes bytes)")
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
        MLog.d(TAG, "Auth event signed: ${authEvent.id.take(8)}")

        // Helper: read file bytes from URI for upload body
        val fileBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Cannot read file")
        val contentType = ContentType.parse(effectiveMime)

        var lastError = ""

        // Strategy 1: PUT /upload (Amethyst alignment)
        try {
            val response = client.put("$cleanServer/upload") {
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                header("Authorization", authHeader)
                header("Content-Length", size.toString())
                contentType(contentType)
                setBody(fileBytes)
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val result = parseUploadResponse(body, hash)
                MLog.d(TAG, "Upload success (PUT /upload): ${result.url}")
                return@withContext result
            } else {
                lastError = "PUT /upload failed (${response.status.value}): ${response.bodyAsText()}"
                MLog.w(TAG, lastError)
            }
        } catch (e: Exception) {
            lastError = "PUT /upload error: ${e.message}"
            MLog.w(TAG, lastError)
        }

        // Strategy 2: POST /upload (some servers prefer POST)
        try {
            val response = client.post("$cleanServer/upload") {
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                header("Authorization", authHeader)
                contentType(contentType)
                setBody(fileBytes)
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val result = parseUploadResponse(body, hash)
                MLog.d(TAG, "Upload success (POST /upload): ${result.url}")
                return@withContext result
            }
        } catch (e: Exception) {
            MLog.w(TAG, "POST /upload error: ${e.message}")
        }

        // Strategy 3: PUT /media (BUD spec alternative)
        try {
            val response = client.put("$cleanServer/media") {
                timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
                header("Authorization", authHeader)
                header("Content-Length", size.toString())
                contentType(contentType)
                setBody(fileBytes)
            }
            if (response.status.isSuccess()) {
                val body = response.bodyAsText()
                val result = parseUploadResponse(body, hash)
                MLog.d(TAG, "Upload success (PUT /media): ${result.url}")
                return@withContext result
            } else {
                lastError = "PUT /media failed (${response.status.value}): ${response.bodyAsText()}"
                MLog.w(TAG, lastError)
            }
        } catch (e: Exception) {
            lastError = "PUT /media error: ${e.message}"
            MLog.w(TAG, lastError)
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
            val response = client.delete("$cleanServer/$hash") {
                header("Authorization", authHeader)
            }
            if (response.status.isSuccess()) {
                MLog.d(TAG, "Delete success: ${hash.take(12)}")
                return@withContext true
            }
        } catch (_: Exception) { }

        // Strategy 2: DELETE /media/<hash>
        try {
            val response = client.delete("$cleanServer/media/$hash") {
                header("Authorization", authHeader)
            }
            if (response.status.isSuccess()) {
                MLog.d(TAG, "Delete success (legacy): ${hash.take(12)}")
                return@withContext true
            }
        } catch (_: Exception) { }

        MLog.w(TAG, "Delete failed for hash: ${hash.take(12)}")
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
