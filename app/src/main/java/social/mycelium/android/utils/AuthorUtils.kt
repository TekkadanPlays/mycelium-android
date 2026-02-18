package social.mycelium.android.utils

import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.NPub

/**
 * Normalize author id (npub or hex) to lowercase hex for ProfileMetadataCache lookup.
 * Cache stores by hex (kind-0 event.pubKey); note.author.id may be npub in some code paths.
 */
fun normalizeAuthorIdForCache(authorId: String): String {
    if (authorId.isBlank()) return authorId
    if (authorId.startsWith("npub1")) {
        return try {
            val nip19 = Nip19Parser.uriToRoute(authorId)
            (nip19?.entity as? NPub)?.hex?.lowercase() ?: authorId.lowercase()
        } catch (e: Exception) {
            authorId.lowercase()
        }
    }
    return authorId.lowercase()
}
