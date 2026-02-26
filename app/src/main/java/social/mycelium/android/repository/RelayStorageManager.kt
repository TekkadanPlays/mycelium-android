package social.mycelium.android.repository

import android.content.Context
import android.content.SharedPreferences
import social.mycelium.android.data.RelayCategory
import social.mycelium.android.data.RelayProfile
import social.mycelium.android.data.DefaultRelayCategories
import social.mycelium.android.data.DefaultRelayProfiles
import social.mycelium.android.data.UserRelay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Manages relay storage per user (pubkey/npub).
 * Stores relay categories and personal relay configurations separately for each account.
 *
 * Storage Structure:
 * - relay_categories_{pubkey} → List<RelayCategory> (General tab categories)
 * - relay_personal_outbox_{pubkey} → List<UserRelay> (Personal tab - Outbox)
 * - relay_personal_inbox_{pubkey} → List<UserRelay> (Personal tab - Inbox)
 * - relay_personal_cache_{pubkey} → List<UserRelay> (Personal tab - Cache)
 */
class RelayStorageManager(val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("relay_storage", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    companion object {
        private const val KEY_CATEGORIES = "relay_categories"
        private const val KEY_PERSONAL_OUTBOX = "relay_personal_outbox"
        private const val KEY_PERSONAL_INBOX = "relay_personal_inbox"
        private const val KEY_PERSONAL_CACHE = "relay_personal_cache"
        private const val KEY_PROFILES = "relay_profiles"
        private const val KEY_SYSTEM_ANNOUNCEMENTS = "relay_system_announcements"
        private const val KEY_SYSTEM_DRAFTS = "relay_system_drafts"
        private const val KEY_SYSTEM_OTHER = "relay_system_other"
        private const val KEY_ONBOARDING_PHASE = "onboarding_phase"
        private const val KEY_ONBOARDING_INDEXERS = "onboarding_indexers"

        /** Strip trailing slash from relay URLs so display/storage is consistent across the app. */
        fun normalizeRelayUrl(url: String): String = url.trim().removeSuffix("/")
    }

    private fun normalizeRelay(relay: UserRelay): UserRelay = relay.copy(url = normalizeRelayUrl(relay.url))
    private fun normalizeRelays(relays: List<UserRelay>): List<UserRelay> = relays.map { normalizeRelay(it) }
    private fun normalizeCategories(categories: List<RelayCategory>): List<RelayCategory> =
        categories.map { it.copy(relays = normalizeRelays(it.relays)) }
    private fun normalizeProfiles(profiles: List<RelayProfile>): List<RelayProfile> =
        profiles.map { it.copy(categories = normalizeCategories(it.categories)) }

    // ====== Relay Profiles ======

    fun saveProfiles(pubkey: String, profiles: List<RelayProfile>) {
        val key = "${KEY_PROFILES}_${pubkey}"
        val wrapper = RelayProfilesWrapper(normalizeProfiles(profiles))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    fun loadProfiles(pubkey: String): List<RelayProfile> {
        val key = "${KEY_PROFILES}_${pubkey}"
        val jsonString = prefs.getString(key, null)
        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<RelayProfilesWrapper>(jsonString)
                normalizeProfiles(wrapper.profiles)
            } catch (e: Exception) {
                listOf(DefaultRelayProfiles.getDefaultProfile())
            }
        } else {
            listOf(DefaultRelayProfiles.getDefaultProfile())
        }
    }

    // ====== General Tab - Relay Categories ======

    /**
     * Save relay categories for a specific user
     */
    fun saveCategories(pubkey: String, categories: List<RelayCategory>) {
        val key = "${KEY_CATEGORIES}_${pubkey}"
        val wrapper = RelayCategoriesWrapper(normalizeCategories(categories))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load relay categories for a specific user
     * Returns default category if no data exists
     */
    fun loadCategories(pubkey: String): List<RelayCategory> {
        val key = "${KEY_CATEGORIES}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<RelayCategoriesWrapper>(jsonString)
                normalizeCategories(wrapper.categories)
            } catch (e: Exception) {
                // If deserialization fails, return default
                DefaultRelayCategories.getAllDefaultCategories()
            }
        } else {
            DefaultRelayCategories.getAllDefaultCategories()
        }
    }

    // ====== Personal Tab - Outbox Relays ======

    /**
     * Save outbox relays for publishing notes
     */
    fun saveOutboxRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_OUTBOX}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load outbox relays
     */
    fun loadOutboxRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_OUTBOX}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
                normalizeRelays(wrapper.relays)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // ====== Personal Tab - Inbox Relays ======

    /**
     * Save inbox relays for receiving DMs and notifications
     */
    fun saveInboxRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_INBOX}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load inbox relays
     */
    fun loadInboxRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_INBOX}_${pubkey}"
        val jsonString = prefs.getString(key, null)

        return if (jsonString != null) {
            try {
                val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
                normalizeRelays(wrapper.relays)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // ====== Personal Tab - Indexer Relays ======

    /**
     * Save indexer relays (NIP-66 discovered search relays used for NIP-65 lookups)
     */
    fun saveIndexerRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_PERSONAL_CACHE}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    /**
     * Load indexer relays. Returns empty list if none configured —
     * relays are discovered via NIP-66 and user input during onboarding.
     */
    fun loadIndexerRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_PERSONAL_CACHE}_${pubkey}"
        val jsonString = prefs.getString(key, null) ?: return emptyList()

        return try {
            val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
            normalizeRelays(wrapper.relays)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ====== System Tab - Announcement Relays ======

    fun saveAnnouncementRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_SYSTEM_ANNOUNCEMENTS}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    fun loadAnnouncementRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_SYSTEM_ANNOUNCEMENTS}_${pubkey}"
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        return try {
            val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
            normalizeRelays(wrapper.relays)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ====== System Tab - Drafts Relays ======

    fun saveDraftsRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_SYSTEM_DRAFTS}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    fun loadDraftsRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_SYSTEM_DRAFTS}_${pubkey}"
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        return try {
            val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
            normalizeRelays(wrapper.relays)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ====== System Tab - Other System Relays ======

    fun saveOtherSystemRelays(pubkey: String, relays: List<UserRelay>) {
        val key = "${KEY_SYSTEM_OTHER}_${pubkey}"
        val wrapper = UserRelayListWrapper(normalizeRelays(relays))
        val jsonString = json.encodeToString(wrapper)
        prefs.edit().putString(key, jsonString).apply()
    }

    fun loadOtherSystemRelays(pubkey: String): List<UserRelay> {
        val key = "${KEY_SYSTEM_OTHER}_${pubkey}"
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        return try {
            val wrapper = json.decodeFromString<UserRelayListWrapper>(jsonString)
            normalizeRelays(wrapper.relays)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ====== Utility Methods ======

    /**
     * Get all relay URLs from all categories and personal relays for a user
     * Useful for connecting to all user's relays at once
     */
    fun getAllRelayUrls(pubkey: String): Set<String> {
        val categories = loadCategories(pubkey)
        val outbox = loadOutboxRelays(pubkey)
        val inbox = loadInboxRelays(pubkey)
        // NOTE: indexer relays are intentionally excluded — they are only for
        // NIP-65 lookups during onboarding, not for feed/notification connections.

        val allRelays = mutableSetOf<String>()

        // Add from categories
        categories.forEach { category ->
            category.relays.forEach { relay ->
                allRelays.add(relay.url)
            }
        }

        // Add from personal relays
        outbox.forEach { allRelays.add(it.url) }
        inbox.forEach { allRelays.add(it.url) }

        return allRelays
    }

    /**
     * Clear all relay data for a specific user
     */
    fun clearUserData(pubkey: String) {
        prefs.edit()
            .remove("${KEY_CATEGORIES}_${pubkey}")
            .remove("${KEY_PROFILES}_${pubkey}")
            .remove("${KEY_PERSONAL_OUTBOX}_${pubkey}")
            .remove("${KEY_PERSONAL_INBOX}_${pubkey}")
            .remove("${KEY_PERSONAL_CACHE}_${pubkey}")
            .remove("${KEY_SYSTEM_ANNOUNCEMENTS}_${pubkey}")
            .remove("${KEY_SYSTEM_DRAFTS}_${pubkey}")
            .remove("${KEY_SYSTEM_OTHER}_${pubkey}")
            .apply()
    }

    /**
     * Check if user has any saved relay data
     */
    fun hasUserData(pubkey: String): Boolean {
        return prefs.contains("${KEY_CATEGORIES}_${pubkey}") ||
               prefs.contains("${KEY_PROFILES}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_OUTBOX}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_INBOX}_${pubkey}") ||
               prefs.contains("${KEY_PERSONAL_CACHE}_${pubkey}") ||
               prefs.contains("${KEY_SYSTEM_ANNOUNCEMENTS}_${pubkey}") ||
               prefs.contains("${KEY_SYSTEM_DRAFTS}_${pubkey}") ||
               prefs.contains("${KEY_SYSTEM_OTHER}_${pubkey}")
    }

    // ====== Onboarding State Persistence ======

    fun saveOnboardingPhase(pubkey: String, phase: String) {
        prefs.edit().putString("${KEY_ONBOARDING_PHASE}_${pubkey}", phase).apply()
    }

    fun loadOnboardingPhase(pubkey: String): String? {
        return prefs.getString("${KEY_ONBOARDING_PHASE}_${pubkey}", null)
    }

    fun saveOnboardingSelectedIndexers(pubkey: String, urls: Set<String>) {
        prefs.edit().putStringSet("${KEY_ONBOARDING_INDEXERS}_${pubkey}", urls).apply()
    }

    fun loadOnboardingSelectedIndexers(pubkey: String): Set<String> {
        return prefs.getStringSet("${KEY_ONBOARDING_INDEXERS}_${pubkey}", emptySet()) ?: emptySet()
    }

    fun clearOnboardingState(pubkey: String) {
        prefs.edit()
            .remove("${KEY_ONBOARDING_PHASE}_${pubkey}")
            .remove("${KEY_ONBOARDING_INDEXERS}_${pubkey}")
            .apply()
    }
}

// ====== Serialization Wrappers ======

@Serializable
private data class RelayCategoriesWrapper(
    val categories: List<RelayCategory>
)

@Serializable
private data class RelayProfilesWrapper(
    val profiles: List<RelayProfile>
)

@Serializable
private data class UserRelayListWrapper(
    val relays: List<UserRelay>
)
