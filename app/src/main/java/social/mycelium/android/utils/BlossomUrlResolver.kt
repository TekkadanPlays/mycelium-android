package social.mycelium.android.utils

import social.mycelium.android.debug.MLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Blossom URL utility: detects content-addressed blossom blob URLs and provides
 * fallback server resolution when the original server 404s.
 *
 * Blossom BUD-01 URLs follow the pattern: `https://<server>/<sha256hex>[.<ext>]`
 * The SHA-256 hash is content-addressable — the same blob can be retrieved from
 * any blossom server that has it.
 */
object BlossomUrlResolver {

    private const val TAG = "BlossomUrl"

    /** Known blossom server base URLs (no trailing slash). */
    private val KNOWN_BLOSSOM_SERVERS = listOf(
        "https://blossom.band",
        "https://blossom.primal.net",
        "https://24242.io",
        "https://blossom.azzamo.media",
        "https://blossom.yakihonne.com",
        "https://cdn.sovbit.host",
        "https://nostr.download",
        "https://cdn.satellite.earth",
        "https://nostrmedia.com",
    )

    /** Regex to match a blossom URL: server + / + 64-char hex hash + optional extension */
    private val BLOSSOM_URL_REGEX = Regex(
        """^(https?://[^/]+)/([0-9a-fA-F]{64})(\.[a-zA-Z0-9]+)?$"""
    )

    /** Cache: original URL → resolved working URL (avoids repeated fallback attempts) */
    private val resolvedCache = ConcurrentHashMap<String, String>()

    /** Cache: original URL → permanently failed (all servers tried, none had it) */
    private val failedCache = ConcurrentHashMap<String, Long>()
    private const val FAILED_TTL_MS = 30 * 60 * 1000L // 30 minutes

    /**
     * Parse a URL into its blossom components (server, hash, extension).
     * Returns null if the URL is not a blossom URL.
     */
    fun parse(url: String): BlossomParts? {
        val match = BLOSSOM_URL_REGEX.matchEntire(url.trim()) ?: return null
        val server = match.groupValues[1]
        val hash = match.groupValues[2].lowercase()
        val ext = match.groupValues[3] // includes the dot, or empty
        return BlossomParts(server, hash, ext)
    }

    /** Check if a URL looks like a blossom content-addressed URL. */
    fun isBlossom(url: String): Boolean = parse(url) != null

    /**
     * Get the resolved URL for a blossom URL. If we've already found a working
     * server for this hash, return it. Otherwise return the original URL.
     */
    fun getResolved(url: String): String = resolvedCache[url] ?: url

    /**
     * Check if a URL has permanently failed (all fallback servers exhausted).
     */
    fun isPermanentlyFailed(url: String): Boolean {
        val ts = failedCache[url] ?: return false
        if (System.currentTimeMillis() - ts > FAILED_TTL_MS) {
            failedCache.remove(url)
            return false
        }
        return true
    }

    /**
     * Generate fallback URLs for a blossom hash, trying other known servers.
     * The original server URL is excluded from the fallback list.
     */
    fun getFallbackUrls(url: String): List<String> {
        val parts = parse(url) ?: return emptyList()
        return KNOWN_BLOSSOM_SERVERS
            .filter { !parts.server.equals(it, ignoreCase = true) }
            .map { "$it/${parts.hash}${parts.ext}" }
    }

    /** Record that a particular fallback URL worked for this original URL. */
    fun recordResolved(originalUrl: String, workingUrl: String) {
        resolvedCache[originalUrl] = workingUrl
        failedCache.remove(originalUrl)
        MLog.d(TAG, "Resolved blossom fallback: $originalUrl → $workingUrl")
    }

    /** Record that all fallback servers were exhausted for this URL. */
    fun recordPermanentFailure(originalUrl: String) {
        failedCache[originalUrl] = System.currentTimeMillis()
        MLog.d(TAG, "All blossom servers exhausted for: $originalUrl")
    }

    data class BlossomParts(val server: String, val hash: String, val ext: String)
}
