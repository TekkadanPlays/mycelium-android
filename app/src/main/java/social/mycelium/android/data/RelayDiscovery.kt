package social.mycelium.android.data

import androidx.compose.runtime.Immutable

/**
 * NIP-66 Relay Discovery Event (kind 30166).
 * Published by relay monitors to describe relay characteristics.
 * The `T` tag provides the relay type from the official nomenclature.
 * The `l` tags provide host metadata (geocode, ISP, IP, SSL).
 * The `content` field contains the NIP-11 JSON document.
 */
@Immutable
data class RelayDiscoveryEvent(
    val relayUrl: String,
    val monitorPubkey: String,
    val createdAt: Long,
    val relayTypes: List<RelayType>,
    val supportedNips: List<Int>,
    val requirements: List<String>,
    val network: String? = null,
    val rttOpen: Int? = null,
    val rttRead: Int? = null,
    val rttWrite: Int? = null,
    val topics: List<String> = emptyList(),
    val geohash: String? = null,
    val nip11Content: String? = null,
    // l-tag metadata from monitors
    val countryCode: String? = null,
    val isp: String? = null,
    val asNumber: String? = null,
    val asName: String? = null
)

/**
 * NIP-66 Relay Monitor Announcement (kind 10166).
 * Advertises a monitor's intent to publish kind 30166 events.
 */
@Immutable
data class RelayMonitorAnnouncement(
    val pubkey: String,
    val frequencySeconds: Int,
    val checks: List<String> = emptyList(),
    val timeouts: Map<String, Int> = emptyMap(),
    val geohash: String? = null
)

/**
 * Relay type taxonomy from NIP-66 `T` tag.
 * See: https://github.com/nostr-protocol/nips/issues/1282
 */
enum class RelayType(val tag: String, val displayName: String) {
    PUBLIC_OUTBOX("PublicOutbox", "Public Outbox"),
    PUBLIC_INBOX("PublicInbox", "Public Inbox"),
    PRIVATE_INBOX("PrivateInbox", "Private Inbox"),
    PRIVATE_STORAGE("PrivateStorage", "Private Storage"),
    SEARCH("Search", "Search / Indexer"),
    DIRECTORY("Directory", "Directory"),
    COMMUNITY("Community", "Community"),
    ALGO("Algo", "Algorithmic"),
    ARCHIVAL("Archival", "Archival"),
    LOCAL_CACHE("LocalCache", "Local Cache"),
    BLOB("Blob", "Blob Storage"),
    BROADCAST("Broadcast", "Broadcast"),
    PROXY("Proxy", "Proxy / Aggregator"),
    TRUSTED("Trusted", "Trusted"),
    PUSH("Push", "Push Notifications");

    companion object {
        private val byTag = entries.associateBy { it.tag.lowercase() }

        fun fromTag(tag: String): RelayType? = byTag[tag.lowercase()]
    }
}

/**
 * Aggregated relay discovery info combining data from one or more monitors.
 * This is the cached, ready-to-use form for the UI.
 * Fields mirror nostr.watch's Nip66CheckEvent for feature parity.
 */
@Immutable
data class DiscoveredRelay(
    val url: String,
    val types: Set<RelayType> = emptySet(),
    val supportedNips: Set<Int> = emptySet(),
    val requirements: Set<String> = emptySet(),
    val network: String? = null,
    val avgRttOpen: Int? = null,
    val avgRttRead: Int? = null,
    val avgRttWrite: Int? = null,
    val topics: Set<String> = emptySet(),
    val monitorCount: Int = 0,
    val lastSeen: Long = 0,
    val nip11Json: String? = null,
    // NIP-11 parsed fields (from event content JSON)
    val software: String? = null,
    val version: String? = null,
    val name: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val banner: String? = null,
    val paymentRequired: Boolean = false,
    val authRequired: Boolean = false,
    val restrictedWrites: Boolean = false,
    val hasNip11: Boolean = false,
    val operatorPubkey: String? = null,
    // Host metadata from l-tags
    val countryCode: String? = null,
    val isp: String? = null,
    // Monitor tracking
    val seenByMonitors: Set<String> = emptySet()
) {
    val isSearch: Boolean get() = RelayType.SEARCH in types
    val isOutbox: Boolean get() = RelayType.PUBLIC_OUTBOX in types
    val isInbox: Boolean get() = RelayType.PUBLIC_INBOX in types
    val isPrivateInbox: Boolean get() = RelayType.PRIVATE_INBOX in types
    val isDirectory: Boolean get() = RelayType.DIRECTORY in types

    /** Readable software name (strip git URLs, lowercase, cap length, filter spam) */
    val softwareShort: String? get() = software?.let { sw ->
        val cleaned = sw.removePrefix("git+").removePrefix("https://github.com/")
            .removePrefix("http://").removeSuffix(".git")
            .substringAfterLast("/").lowercase().trim()
        // Filter out spam: if cleaned name is still absurdly long, discard it
        if (cleaned.isBlank() || cleaned.length > 40) null
        else cleaned.take(30)
    }
}
