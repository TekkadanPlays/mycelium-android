package social.mycelium.android.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a user-defined category of relays in the General tab.
 * Categories allow users to organize their relays by purpose or topic.
 */
@Immutable
@Serializable
data class RelayCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val relays: List<UserRelay> = emptyList(),
    val isDefault: Boolean = false,
    val isSubscribed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A relay profile is a complete network configuration.
 * Users can create multiple profiles (e.g. "Default", "Privacy", "Media")
 * and switch between them. Each profile contains its own set of categories,
 * and each category contains relays.
 */
@Immutable
@Serializable
data class RelayProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val categories: List<RelayCategory> = emptyList(),
    val isActive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Default relay categories that come pre-configured
 */
object DefaultRelayCategories {
    fun getDefaultCategory(): RelayCategory {
        return RelayCategory(
            id = "default_my_relays",
            name = "Default Category",
            relays = emptyList(),
            isDefault = true,
            isSubscribed = true
        )
    }

    fun getAllDefaultCategories(): List<RelayCategory> {
        return listOf(getDefaultCategory())
    }
}

object DefaultRelayProfiles {
    fun getDefaultProfile(): RelayProfile {
        return RelayProfile(
            id = "default_profile",
            name = "Default",
            categories = DefaultRelayCategories.getAllDefaultCategories(),
            isActive = true
        )
    }
}
