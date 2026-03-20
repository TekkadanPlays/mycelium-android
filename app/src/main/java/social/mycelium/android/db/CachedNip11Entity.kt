package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached NIP-11 relay information documents.
 * Keyed by normalized relay URL (wss://...). Stores the full JSON-serialized
 * RelayInformation so relay orb icons, names, and metadata survive app restarts
 * and scale to thousands of relays without SharedPreferences bloat.
 */
@Entity(tableName = "cached_nip11")
data class CachedNip11Entity(
    /** Normalized relay URL (e.g. "wss://relay.damus.io"). */
    @PrimaryKey val relayUrl: String,
    /** JSON-serialized RelayInformation (kotlinx.serialization). */
    val infoJson: String,
    /** Icon URL extracted for fast lookup without deserializing the full JSON. */
    val iconUrl: String? = null,
    /** Relay display name extracted for fast lookup. */
    val name: String? = null,
    /** Timestamp (ms) when this entry was fetched/refreshed. For 24h expiry. */
    val fetchedAt: Long = System.currentTimeMillis()
)
