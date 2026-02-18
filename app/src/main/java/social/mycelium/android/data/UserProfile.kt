package social.mycelium.android.data

import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val pubkey: String,
    val displayName: String? = null,
    val name: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val lud16: String? = null,
    val nip05: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val displayNameOrName: String
        get() = displayName ?: name ?: "Anonymous"
    
    val isGuest: Boolean
        get() = pubkey.isEmpty() || pubkey == "guest"
}

@Serializable
data class AuthState(
    val isAuthenticated: Boolean = false,
    val isGuest: Boolean = true,
    val userProfile: UserProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

// Guest user profile
val GUEST_PROFILE = UserProfile(
    pubkey = "guest",
    displayName = "Guest User",
    about = "You are browsing as a guest. Sign in to access all features.",
    createdAt = System.currentTimeMillis()
)
