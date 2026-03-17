package social.mycelium.android.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for cached NIP-65 relay lists (kind-10002).
 * Keyed by hex pubkey (lowercase). Stores comma-separated relay URLs
 * for read and write sets so outbox resolution doesn't require re-fetching.
 */
@Entity(tableName = "cached_nip65")
data class CachedNip65Entity(
    @PrimaryKey val pubkey: String,
    /** Comma-separated write (outbox) relay URLs. */
    val writeRelays: String,
    /** Comma-separated read (inbox) relay URLs. */
    val readRelays: String,
    val updatedAt: Long = System.currentTimeMillis()
)
