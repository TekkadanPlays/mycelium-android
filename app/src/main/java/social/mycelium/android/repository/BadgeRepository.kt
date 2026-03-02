package social.mycelium.android.repository

import android.util.Log
import com.example.cybin.core.Event
import com.example.cybin.core.Filter
import com.example.cybin.relay.SubscriptionPriority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.mycelium.android.relay.RelayConnectionStateMachine
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-58 Badge Repository.
 *
 * Fetches and caches badge data for user profiles:
 * - Kind 30008: Profile badges list (which badges the user has accepted/pinned)
 * - Kind 8: Badge award events (who awarded the badge, to whom)
 * - Kind 30009: Badge definition events (name, description, image, thumb)
 *
 * Resolution chain: 30008 → e-tags → kind 8 → a-tags → kind 30009
 */
object BadgeRepository {

    private const val TAG = "BadgeRepository"
    private const val KIND_BADGE_AWARD = 8
    private const val KIND_BADGE_DEFINITION = 30009
    private const val KIND_PROFILE_BADGES = 30008

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Coroutine failed: ${t.message}", t) }
    )

    /**
     * Resolved badge for display: has image + name from the kind 30009 definition.
     */
    data class Badge(
        val definitionId: String,
        val awardEventId: String,
        val awardedBy: String,
        val name: String?,
        val description: String?,
        val thumbUrl: String?,
        val imageUrl: String?,
        val awardedAt: Long,
    ) {
        /** Best image URL for thumbnail display. */
        val displayImageUrl: String? get() = thumbUrl?.takeIf { it.isNotBlank() } ?: imageUrl?.takeIf { it.isNotBlank() }
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    /** Resolved badges per user pubkey hex. */
    private val badgesByUser = ConcurrentHashMap<String, List<Badge>>()

    /** StateFlow per user for reactive UI updates. Keyed by pubkey hex. */
    private val flowsByUser = ConcurrentHashMap<String, MutableStateFlow<List<Badge>>>()

    /** Track in-flight fetches to avoid duplicate subscriptions. */
    private val fetchingUsers = ConcurrentHashMap.newKeySet<String>()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Get or create a StateFlow of resolved badges for the given user.
     * Triggers a fetch if not already cached or in-flight.
     */
    fun badgesFor(pubkeyHex: String, relayUrls: List<String>): StateFlow<List<Badge>> {
        val flow = flowsByUser.getOrPut(pubkeyHex) {
            MutableStateFlow(badgesByUser[pubkeyHex] ?: emptyList())
        }
        // Kick off fetch if we haven't already
        if (fetchingUsers.add(pubkeyHex) && relayUrls.isNotEmpty()) {
            scope.launch { fetchBadges(pubkeyHex, relayUrls) }
        }
        return flow.asStateFlow()
    }

    /**
     * Force-refresh badges for a user (e.g. after receiving a badge award notification).
     */
    fun refreshBadges(pubkeyHex: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        fetchingUsers.remove(pubkeyHex) // allow re-fetch
        fetchingUsers.add(pubkeyHex)
        scope.launch { fetchBadges(pubkeyHex, relayUrls) }
    }

    // ── Fetch Pipeline ──────────────────────────────────────────────────────

    /**
     * Full resolution pipeline:
     * 1. Fetch kind 30008 (profile badges) for the user → get badge award event IDs
     * 2. Fetch those kind 8 events → get badge definition addresses (a-tags)
     * 3. Fetch kind 30009 definitions → resolve name, image, description
     * 4. Emit resolved badges
     */
    private suspend fun fetchBadges(pubkeyHex: String, relayUrls: List<String>) {
        try {
            // ── Step 1: Fetch kind 30008 ────────────────────────────────────
            val profileBadgesEvents = mutableListOf<Event>()
            val dTag = "profile_badges"
            val profileBadgesFilter = Filter(
                kinds = listOf(KIND_PROFILE_BADGES),
                authors = listOf(pubkeyHex),
                tags = mapOf("d" to listOf(dTag)),
                limit = 5
            )
            val sm = RelayConnectionStateMachine.getInstance()
            val handle1 = sm.requestTemporarySubscription(
                relayUrls, profileBadgesFilter, priority = SubscriptionPriority.LOW
            ) { event ->
                if (event.kind == KIND_PROFILE_BADGES && event.pubKey.lowercase() == pubkeyHex.lowercase()) {
                    profileBadgesEvents.add(event)
                }
            }
            delay(3000)
            handle1.cancel()

            // Pick the latest kind 30008 event
            val profileBadgesEvent = profileBadgesEvents.maxByOrNull { it.createdAt }
            if (profileBadgesEvent == null) {
                Log.d(TAG, "No kind 30008 profile_badges event for ${pubkeyHex.take(8)}")
                emitBadges(pubkeyHex, emptyList())
                return
            }

            // Extract badge award event IDs from e-tags
            val awardEventIds = profileBadgesEvent.tags
                .filter { it.size >= 2 && it[0] == "e" }
                .mapNotNull { it.getOrNull(1) }
                .distinct()

            if (awardEventIds.isEmpty()) {
                Log.d(TAG, "Kind 30008 has no e-tags for ${pubkeyHex.take(8)}")
                emitBadges(pubkeyHex, emptyList())
                return
            }

            Log.d(TAG, "Found ${awardEventIds.size} badge award refs for ${pubkeyHex.take(8)}")

            // ── Step 2: Fetch kind 8 award events ──────────────────────────
            val awardEvents = ConcurrentHashMap<String, Event>()
            val awardFilter = Filter(
                ids = awardEventIds,
                kinds = listOf(KIND_BADGE_AWARD),
                limit = awardEventIds.size
            )
            val handle2 = sm.requestTemporarySubscription(
                relayUrls, awardFilter, priority = SubscriptionPriority.LOW
            ) { event ->
                if (event.kind == KIND_BADGE_AWARD) {
                    awardEvents[event.id] = event
                }
            }
            delay(3000)
            handle2.cancel()

            if (awardEvents.isEmpty()) {
                Log.d(TAG, "No kind 8 award events fetched for ${pubkeyHex.take(8)}")
                emitBadges(pubkeyHex, emptyList())
                return
            }

            // Extract badge definition addresses from a-tags in award events
            // a-tag format: ["a", "30009:<pubkey>:<d-tag>", ...]
            data class DefRef(val awardEventId: String, val awardedBy: String, val awardedAt: Long, val aTagValue: String)
            val defRefs = mutableListOf<DefRef>()
            for (award in awardEvents.values) {
                val aTags = award.tags.filter { it.size >= 2 && it[0] == "a" }
                for (aTag in aTags) {
                    val aVal = aTag[1]
                    if (aVal.startsWith("$KIND_BADGE_DEFINITION:")) {
                        defRefs.add(DefRef(award.id, award.pubKey, award.createdAt, aVal))
                    }
                }
            }

            if (defRefs.isEmpty()) {
                Log.d(TAG, "No badge definition a-tags in award events for ${pubkeyHex.take(8)}")
                emitBadges(pubkeyHex, emptyList())
                return
            }

            Log.d(TAG, "Resolving ${defRefs.size} badge definitions for ${pubkeyHex.take(8)}")

            // ── Step 3: Fetch kind 30009 definitions ───────────────────────
            // Parse a-tag values to build filters: "30009:<author>:<d-tag>"
            data class DefKey(val author: String, val dTag: String)
            val defKeys = defRefs.map { ref ->
                val parts = ref.aTagValue.split(":", limit = 3)
                DefKey(parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" })
            }.filter { it.author.isNotBlank() && it.dTag.isNotBlank() }.distinct()

            val definitionEvents = ConcurrentHashMap<String, Event>() // key = "author:d-tag"
            // Batch fetch: all definition authors, filter by kind 30009
            val defAuthors = defKeys.map { it.author }.distinct()
            val defDTags = defKeys.map { it.dTag }.distinct()
            val defFilter = Filter(
                kinds = listOf(KIND_BADGE_DEFINITION),
                authors = defAuthors,
                tags = mapOf("d" to defDTags),
                limit = defKeys.size * 2
            )
            val handle3 = sm.requestTemporarySubscription(
                relayUrls, defFilter, priority = SubscriptionPriority.LOW
            ) { event ->
                if (event.kind == KIND_BADGE_DEFINITION) {
                    val dTagVal = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
                    val key = "${event.pubKey.lowercase()}:$dTagVal"
                    // Keep latest version of each replaceable event
                    val existing = definitionEvents[key]
                    if (existing == null || event.createdAt > existing.createdAt) {
                        definitionEvents[key] = event
                    }
                }
            }
            delay(3000)
            handle3.cancel()

            // ── Step 4: Resolve and emit ────────────────────────────────────
            val badges = defRefs.mapNotNull { ref ->
                val parts = ref.aTagValue.split(":", limit = 3)
                val defAuthor = parts.getOrElse(1) { "" }.lowercase()
                val defDTag = parts.getOrElse(2) { "" }
                val defEvent = definitionEvents["$defAuthor:$defDTag"] ?: return@mapNotNull null

                val name = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "name" }?.get(1)
                val description = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
                val thumb = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "thumb" }?.get(1)
                val image = defEvent.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)

                Badge(
                    definitionId = defEvent.id,
                    awardEventId = ref.awardEventId,
                    awardedBy = ref.awardedBy,
                    name = name,
                    description = description,
                    thumbUrl = thumb,
                    imageUrl = image,
                    awardedAt = ref.awardedAt * 1000L
                )
            }.distinctBy { it.definitionId }

            Log.d(TAG, "Resolved ${badges.size} badges for ${pubkeyHex.take(8)}")
            emitBadges(pubkeyHex, badges)
        } catch (e: Exception) {
            Log.e(TAG, "fetchBadges failed for ${pubkeyHex.take(8)}: ${e.message}", e)
            emitBadges(pubkeyHex, emptyList())
        }
    }

    private fun emitBadges(pubkeyHex: String, badges: List<Badge>) {
        badgesByUser[pubkeyHex] = badges
        flowsByUser.getOrPut(pubkeyHex) { MutableStateFlow(emptyList()) }.value = badges
    }
}
