package social.mycelium.android.data

import kotlinx.serialization.Serializable

@Serializable
enum class MediaServerType {
    BLOSSOM, NIP96
}

@Serializable
data class MediaServer(
    val name: String,
    val baseUrl: String,
    val type: MediaServerType = MediaServerType.BLOSSOM
)

/**
 * Default media servers matching Amethyst for cross-client compatibility.
 * Blossom servers use BUD-01/02/04 HTTP blob storage with kind-24242 auth.
 * NIP-96 servers use HTTP file storage with kind-27235 auth.
 */
object DefaultMediaServers {

    val BLOSSOM_SERVERS = listOf(
        MediaServer("Nostr.Build", "https://blossom.band/", MediaServerType.BLOSSOM),
        MediaServer("24242.io", "https://24242.io/", MediaServerType.BLOSSOM),
        MediaServer("Azzamo", "https://blossom.azzamo.media", MediaServerType.BLOSSOM),
        MediaServer("YakiHonne", "https://blossom.yakihonne.com/", MediaServerType.BLOSSOM),
        MediaServer("Primal", "https://blossom.primal.net/", MediaServerType.BLOSSOM),
        MediaServer("Sovbit", "https://cdn.sovbit.host", MediaServerType.BLOSSOM),
        MediaServer("Nostr.Download", "https://nostr.download", MediaServerType.BLOSSOM),
        MediaServer("Satellite (Paid)", "https://cdn.satellite.earth", MediaServerType.BLOSSOM),
        MediaServer("NostrMedia (Paid)", "https://nostrmedia.com", MediaServerType.BLOSSOM),
    )

    val NIP96_SERVERS = listOf(
        MediaServer("Nostr.Build", "https://nostr.build", MediaServerType.NIP96),
        MediaServer("NostrCheck.me", "https://nostrcheck.me", MediaServerType.NIP96),
        MediaServer("Sovbit", "https://files.sovbit.host", MediaServerType.NIP96),
        MediaServer("Void.cat", "https://void.cat", MediaServerType.NIP96),
    )

    fun getDefaultBlossom(): MediaServer = BLOSSOM_SERVERS[0]

    fun allDefaults(): List<MediaServer> = BLOSSOM_SERVERS + NIP96_SERVERS
}
