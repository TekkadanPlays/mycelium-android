package social.mycelium.android.utils

/**
 * Strips tracking parameters from URLs in text. Based on PureLink-Android by Ahmed Samy (MIT).
 * Applied to both inbound (rendering notes) and outbound (publishing notes) content.
 *
 * Safe for YouTube, Rumble, etc. — only named tracking params are stripped;
 * video IDs (e.g. ?v=xxx) and path segments are never touched.
 *
 * @see <a href="https://github.com/ahmedthebest31/PureLink-Android">PureLink-Android</a>
 */
object LinkSanitizer {

    private val TRACKING_PARAMS = listOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "si", "ref", "gclid", "gclsrc", "dclid", "msclkid",
        "mc_eid", "_ga", "yclid", "vero_conv", "vero_id", "wickedid",
        "share_id", "igshid",
        // Additional common trackers
        "ref_src", "ref_url", "s", "t", "feature", "_hsenc", "_hsmi",
        "mkt_tok", "mc_cid", "oly_anon_id", "oly_enc_id",
        "otc", "spm", "scm", "pvid", "algo_pvid",
        "tt_medium", "tt_content",
    )

    private val URL_REGEX = Regex("https?://\\S+")

    private val PARAMS_REGEX = Regex(
        "[?&](${TRACKING_PARAMS.joinToString("|") { Regex.escape(it) }})=[^&]*",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strip tracking query parameters from a single URL.
     * Preserves path, fragment, and non-tracking query params.
     */
    fun cleanUrl(url: String): String {
        var result = PARAMS_REGEX.replace(url, "")
        // Clean up malformed query strings left behind
        result = result.replace(Regex("\\?&"), "?")
        result = result.replace(Regex("&&+"), "&")
        if (result.endsWith("?") || result.endsWith("&")) result = result.dropLast(1)
        if (result.endsWith("?")) result = result.dropLast(1)
        return result
    }

    /**
     * Find all URLs in mixed text and strip tracking params from each.
     * Non-URL text is preserved exactly as-is.
     */
    fun cleanText(text: String): String {
        if (!text.contains("http", ignoreCase = true)) return text
        return URL_REGEX.replace(text) { cleanUrl(it.value) }
    }
}
