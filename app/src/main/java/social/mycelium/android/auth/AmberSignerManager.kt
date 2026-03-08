package social.mycelium.android.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.cybin.core.HexKey
import com.example.cybin.signer.NostrSigner
import com.example.cybin.nip55.ExternalSignerLogin
import com.example.cybin.nip55.NostrSignerExternal
import com.example.cybin.nip55.AmberDetector.isExternalSignerInstalled
import com.example.cybin.nip55.AmberDetector.getExternalSignersInstalled
import com.example.cybin.nip55.Permission
import com.example.cybin.nip55.CommandType
import android.net.Uri
import android.database.Cursor
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AmberState {
    object NotInstalled : AmberState()
    object NotLoggedIn : AmberState()
    object LoggingIn : AmberState()
    data class LoggedIn(val pubKey: HexKey, val signer: NostrSigner) : AmberState()
    data class Error(val message: String) : AmberState()
}

class AmberSignerManager(private val context: Context) {

    private val _state = MutableStateFlow<AmberState>(AmberState.NotInstalled)
    val state: StateFlow<AmberState> = _state.asStateFlow()

    private var currentSigner: NostrSignerExternal? = null

    // SharedPreferences for persisting auth state
    private val prefs: SharedPreferences = context.getSharedPreferences("amber_auth", Context.MODE_PRIVATE)

    companion object {
        const val AMBER_PACKAGE_NAME = "com.greenart7c3.nostrsigner"

        // SharedPreferences keys
        private const val PREF_IS_LOGGED_IN = "is_logged_in"
        private const val PREF_USER_PUBKEY = "user_pubkey"
        private const val PREF_PACKAGE_NAME = "package_name"

        val DEFAULT_PERMISSIONS = listOf<Permission>(
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 1 // Text notes (NIP-01)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 3 // Contact list (NIP-02 follow/unfollow)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 7 // Reactions (NIP-25)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 11 // Topics (NIP-22 anchored)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 1011 // Scoped moderation (NIP-22)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 1111 // Thread replies (NIP-22)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 1311 // Live chat messages (NIP-53)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 9735 // Zap receipts
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 13 // NIP-17 seal (gift-wrapped DMs)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 14 // NIP-17 chat message (rumor)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 10000 // NIP-51 mute list
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 10003 // NIP-51 bookmarks
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 22242 // NIP-42 relay auth events
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 24242 // Blossom BUD-01 auth (media upload)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 27235 // NIP-98 HTTP Auth (NIP-86 relay management)
            ),
            Permission(
                type = CommandType.SIGN_EVENT,
                kind = 30073 // Anchor subscriptions
            ),
            Permission(
                type = CommandType.NIP04_ENCRYPT
            ),
            Permission(
                type = CommandType.NIP04_DECRYPT
            ),
            Permission(
                type = CommandType.NIP44_ENCRYPT
            ),
            Permission(
                type = CommandType.NIP44_DECRYPT
            )
        )
    }

    init {
        checkAmberInstallation()
    }

    /**
     * @deprecated No-op — applicationContext is always used now.
     */
    fun setActivityContext(activity: android.app.Activity) { /* no-op */ }

    /** @deprecated No-op — applicationContext is always used now. */
    fun clearActivityContext() { /* no-op */ }

    /**
     * Re-validate signer state. Call on app resume to recover from transient failures.
     * If state is not LoggedIn but saved credentials exist, attempts restoration.
     */
    fun revalidate() {
        if (_state.value is AmberState.LoggedIn) return
        val isLoggedIn = prefs.getBoolean(PREF_IS_LOGGED_IN, false)
        if (isLoggedIn) {
            Log.d("AmberSignerManager", "Revalidating signer (current state: ${_state.value})")
            checkAmberInstallation()
        }
    }

    private fun checkAmberInstallation() {
        val installedSigners = getExternalSignersInstalled(context)
        Log.d("AmberSignerManager", "🔍 Checking Amber installation. Found ${installedSigners.size} signers: $installedSigners")

        if (installedSigners.isEmpty()) {
            Log.d("AmberSignerManager", "❌ No external signers installed")
            _state.value = AmberState.NotInstalled
            return
        }

        Log.d("AmberSignerManager", "✅ Amber or compatible signer is installed")

        // Check if we have saved auth state
        val isLoggedIn = prefs.getBoolean(PREF_IS_LOGGED_IN, false)
        val savedPubkey = prefs.getString(PREF_USER_PUBKEY, null)
        val savedPackageName = prefs.getString(PREF_PACKAGE_NAME, AMBER_PACKAGE_NAME)

        if (isLoggedIn && !savedPubkey.isNullOrEmpty()) {
            Log.d("AmberSignerManager", "🔐 Restoring saved Amber session: ${savedPubkey.take(16)}...")

            try {
                // Convert npub to hex if needed
                val hexPubkey = if (savedPubkey.startsWith("npub")) {
                    try {
                        val nip19 = com.example.cybin.nip19.Nip19Parser.uriToRoute(savedPubkey)
                        (nip19?.entity as? com.example.cybin.nip19.NPub)?.hex ?: savedPubkey
                    } catch (e: Exception) {
                        Log.w("AmberSignerManager", "Failed to convert npub to hex: ${e.message}")
                        savedPubkey
                    }
                } else {
                    savedPubkey
                }

                // Recreate the signer with saved credentials
                val signer = NostrSignerExternal(
                    pubKey = hexPubkey,
                    packageName = savedPackageName ?: AMBER_PACKAGE_NAME,
                    contentResolver = context.applicationContext.contentResolver
                )

                currentSigner = signer
                _state.value = AmberState.LoggedIn(hexPubkey, signer)
                Log.d("AmberSignerManager", "✅ Successfully restored Amber session")
            } catch (e: Exception) {
                Log.w("AmberSignerManager", "❌ Failed to restore Amber session: ${e.message}")
                // Clear invalid saved state
                clearSavedAuthState()
                _state.value = AmberState.NotLoggedIn
            }
        } else {
            Log.d("AmberSignerManager", "🔐 No saved Amber session found")
            _state.value = AmberState.NotLoggedIn
        }
    }

    fun getInstalledSigners() = getExternalSignersInstalled(context)

    /**
     * Save authentication state to persistent storage
     */
    private fun saveAuthState(pubkey: String, packageName: String) {
        prefs.edit()
            .putBoolean(PREF_IS_LOGGED_IN, true)
            .putString(PREF_USER_PUBKEY, pubkey)
            .putString(PREF_PACKAGE_NAME, packageName)
            .apply()
        Log.d("AmberSignerManager", "💾 Saved Amber auth state: ${pubkey.take(16)}...")
    }

    /**
     * Clear saved authentication state
     */
    private fun clearSavedAuthState() {
        prefs.edit()
            .putBoolean(PREF_IS_LOGGED_IN, false)
            .remove(PREF_USER_PUBKEY)
            .remove(PREF_PACKAGE_NAME)
            .apply()
        Log.d("AmberSignerManager", "🗑️ Cleared saved Amber auth state")
    }

    fun createLoginIntent(): Intent {
        _state.value = AmberState.LoggingIn
        return ExternalSignerLogin.createIntent(
            permissions = DEFAULT_PERMISSIONS,
            packageName = AMBER_PACKAGE_NAME
        )
    }

    fun handleLoginResult(resultCode: Int, data: Intent?) {
        try {
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                // Parse the actual Amber response according to NIP-55
                val pubkey = data.getStringExtra("result")
                val packageName = data.getStringExtra("package") ?: AMBER_PACKAGE_NAME

                if (pubkey != null) {
                    Log.d("AmberSignerManager", "✅ Amber login successful: ${pubkey.take(16)}...")

                    // Convert npub to hex if needed
                    val hexPubkey = if (pubkey.startsWith("npub")) {
                        try {
                            val nip19 = com.example.cybin.nip19.Nip19Parser.uriToRoute(pubkey)
                            val hex = (nip19?.entity as? com.example.cybin.nip19.NPub)?.hex
                            Log.d("AmberSignerManager", "🔄 Converted npub to hex: ${hex?.take(16)}...")
                            hex ?: pubkey
                        } catch (e: Exception) {
                            Log.w("AmberSignerManager", "Failed to convert npub to hex: ${e.message}")
                            pubkey
                        }
                    } else {
                        pubkey
                    }

                    // Create external signer with hex pubkey
                    val signer = NostrSignerExternal(
                        pubKey = hexPubkey,
                        packageName = packageName,
                        contentResolver = context.applicationContext.contentResolver
                    )

                    currentSigner = signer
                    _state.value = AmberState.LoggedIn(hexPubkey, signer)

                    // Save auth state for future app starts (save original npub/hex)
                    saveAuthState(hexPubkey, packageName)
                } else {
                    Log.e("AmberSignerManager", "❌ No pubkey in Amber response")
                    _state.value = AmberState.Error("No pubkey received from Amber")
                }
            } else {
                Log.w("AmberSignerManager", "❌ Amber login cancelled or failed")
                _state.value = AmberState.Error("Login cancelled or failed")
            }
        } catch (e: Exception) {
            Log.e("AmberSignerManager", "❌ Login error: ${e.message}", e)
            _state.value = AmberState.Error("Login error: ${e.message}")
        }
    }

    fun logout() {
        currentSigner = null
        _state.value = AmberState.NotLoggedIn
        // Clear saved auth state
        clearSavedAuthState()
        Log.d("AmberSignerManager", "👋 Logged out from Amber")
    }

    /**
     * Switch the Amber signer to a different account pubkey. Call during account switch
     * so the content provider uses the correct pubkey for NIP-44 decrypt, NIP-42 auth, etc.
     * No-op if the signer is already pointing at the requested pubkey.
     */
    fun switchToAccount(hexPubkey: String) {
        val current = _state.value
        if (current is AmberState.LoggedIn && current.pubKey == hexPubkey) return

        val packageName = prefs.getString(PREF_PACKAGE_NAME, AMBER_PACKAGE_NAME) ?: AMBER_PACKAGE_NAME
        val signer = NostrSignerExternal(
            pubKey = hexPubkey,
            packageName = packageName,
            contentResolver = context.applicationContext.contentResolver
        )
        currentSigner = signer
        _state.value = AmberState.LoggedIn(hexPubkey, signer)
        saveAuthState(hexPubkey, packageName)
        Log.d("AmberSignerManager", "Switched Amber signer to account: ${hexPubkey.take(16)}")
    }

    fun getCurrentPubKey(): HexKey? {
        return when (val currentState = _state.value) {
            is AmberState.LoggedIn -> currentState.pubKey
            else -> null
        }
    }

    fun getCurrentSigner(): NostrSigner? {
        val currentState = _state.value
        if (currentState is AmberState.LoggedIn) {
            return currentState.signer
        }

        // State is NOT LoggedIn — attempt recovery if we have saved credentials.
        // This handles: session restoration failure at init, transient Error states,
        // Amber becoming available after initial check, or state degradation.
        val isLoggedIn = prefs.getBoolean(PREF_IS_LOGGED_IN, false)
        val savedPubkey = prefs.getString(PREF_USER_PUBKEY, null)
        if (isLoggedIn && !savedPubkey.isNullOrEmpty()) {
            Log.w("AmberSignerManager", "Signer state is $currentState but saved credentials exist — attempting recovery")
            try {
                val hexPubkey = if (savedPubkey.startsWith("npub")) {
                    val nip19 = com.example.cybin.nip19.Nip19Parser.uriToRoute(savedPubkey)
                    (nip19?.entity as? com.example.cybin.nip19.NPub)?.hex ?: savedPubkey
                } else {
                    savedPubkey
                }
                val savedPackageName = prefs.getString(PREF_PACKAGE_NAME, AMBER_PACKAGE_NAME)
                val signer = NostrSignerExternal(
                    pubKey = hexPubkey,
                    packageName = savedPackageName ?: AMBER_PACKAGE_NAME,
                    contentResolver = context.applicationContext.contentResolver
                )
                currentSigner = signer
                _state.value = AmberState.LoggedIn(hexPubkey, signer)
                Log.d("AmberSignerManager", "Signer recovery successful — restored LoggedIn state")
                return signer
            } catch (e: Exception) {
                Log.e("AmberSignerManager", "Signer recovery failed: ${e.message}")
            }
        }

        // Fallback: on-demand signer for NotLoggedIn state (signing without being logged in)
        if (currentState is AmberState.NotLoggedIn) {
            try {
                return NostrSignerExternal(
                    pubKey = "",
                    packageName = AMBER_PACKAGE_NAME,
                    contentResolver = context.applicationContext.contentResolver
                )
            } catch (e: Exception) {
                Log.w("AmberSignerManager", "Failed to create on-demand signer: ${e.message}")
            }
        }

        Log.w("AmberSignerManager", "Amber not available - state: $currentState, no saved credentials")
        return null
    }
}
