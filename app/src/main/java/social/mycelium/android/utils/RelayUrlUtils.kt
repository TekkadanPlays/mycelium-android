package social.mycelium.android.utils

import com.example.cybin.relay.RelayUrlNormalizer

/**
 * Normalize a relay URL for consistent comparison and map lookups.
 * Delegates to Cybin's RelayUrlNormalizer (lowercase, trim, strip trailing slash,
 * strip default ports, ensure wss:// scheme). Falls back to basic normalization
 * if Cybin rejects the URL (e.g. localhost, IP-only).
 */
fun normalizeRelayUrl(url: String): String =
    RelayUrlNormalizer.normalizeOrNull(url)?.url ?: url.trim().lowercase().removeSuffix("/")
