package social.mycelium.android.data

import androidx.compose.runtime.Immutable

/**
 * NIP-53 Live Activity (kind:30311).
 * Represents a live stream or audio space advertised on Nostr relays.
 */
@Immutable
data class LiveActivity(
    /** Event id of the kind:30311 event. */
    val id: String,
    /** Pubkey of the event author (usually the host). */
    val hostPubkey: String,
    /** Addressable event "d" tag (unique identifier for this activity). */
    val dTag: String,
    /** Human-readable title. */
    val title: String?,
    /** Short description / summary. */
    val summary: String?,
    /** Preview image URL. */
    val imageUrl: String?,
    /** HLS / streaming URL (e.g. .m3u8). */
    val streamingUrl: String?,
    /** Recording URL (post-stream). */
    val recordingUrl: String?,
    /** Current status: planned, live, ended. */
    val status: LiveActivityStatus,
    /** Unix timestamp (seconds) when the activity starts. */
    val startsAt: Long?,
    /** Unix timestamp (seconds) when the activity ends. */
    val endsAt: Long?,
    /** Number of current participants / viewers. */
    val currentParticipants: Int?,
    /** Total participants over the lifetime of the activity. */
    val totalParticipants: Int?,
    /** Participants with roles: list of (pubkey, role, relayHint). */
    val participants: List<LiveActivityParticipant> = emptyList(),
    /** Hashtags from "t" tags. */
    val hashtags: List<String> = emptyList(),
    /** Relay URLs from "relays" tag. */
    val relayUrls: List<String> = emptyList(),
    /** Relay URL this event was received from. */
    val sourceRelayUrl: String? = null,
    /** Event created_at (seconds). Used for staleness check. */
    val createdAt: Long,
    /** Host author resolved from ProfileMetadataCache. */
    val hostAuthor: Author? = null
)

@Immutable
data class LiveActivityParticipant(
    val pubkey: String,
    val role: String? = null,
    val relayHint: String? = null
)

enum class LiveActivityStatus {
    PLANNED,
    LIVE,
    ENDED;

    companion object {
        fun fromTag(value: String?): LiveActivityStatus = when (value?.lowercase()) {
            "live" -> LIVE
            "planned" -> PLANNED
            "ended" -> ENDED
            else -> ENDED
        }
    }
}
