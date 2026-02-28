package social.mycelium.android.viewmodel

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import social.mycelium.android.auth.AmberSignerManager
import social.mycelium.android.auth.AmberState
import social.mycelium.android.data.AccountInfo
import social.mycelium.android.repository.RelayStorageManager
import social.mycelium.android.data.AuthState
import social.mycelium.android.data.UserProfile
import social.mycelium.android.data.GUEST_PROFILE
import social.mycelium.android.data.Note
import social.mycelium.android.repository.ContactListRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.ReactionsRepository
import social.mycelium.android.repository.ZapStatePersistence
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.repository.TopicsPublishService
import social.mycelium.android.repository.TopicsRepository
import com.example.cybin.nip19.Nip19Parser
import com.example.cybin.nip19.bechToBytes
import com.example.cybin.nip19.NPub
import com.example.cybin.nip19.NSec
import com.example.cybin.nip19.toNpub
import social.mycelium.android.data.UserRelay
import com.example.cybin.core.Event
import com.example.cybin.core.toHexString
import com.example.cybin.crypto.KeyPair
import com.example.cybin.signer.NostrSignerInternal
import com.example.cybin.relay.RelayUrlNormalizer
import com.example.cybin.core.EventTemplate
import com.example.cybin.nip25.ReactionEvent
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.services.EventPublisher
import social.mycelium.android.services.PublishResult
import social.mycelium.android.utils.normalizeAuthorIdForCache
import social.mycelium.android.utils.ClientTagManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages account state across the entire app.
 * Handles:
 * - Multiple account storage
 * - Account switching
 * - Login/logout
 * - Current active account
 *
 * Based on Amethyst's AccountStateViewModel architecture.
 */
class AccountStateViewModel(application: Application) : AndroidViewModel(application) {

    private val amberSignerManager = AmberSignerManager(application)
    private val prefs = application.getSharedPreferences("Mycelium_accounts", Application.MODE_PRIVATE)
    private val relayStorageManager = RelayStorageManager(application)

    // Current active account
    private val _currentAccount = MutableStateFlow<AccountInfo?>(null)
    val currentAccount: StateFlow<AccountInfo?> = _currentAccount.asStateFlow()

    // All saved accounts
    private val _savedAccounts = MutableStateFlow<List<AccountInfo>>(emptyList())
    val savedAccounts: StateFlow<List<AccountInfo>> = _savedAccounts.asStateFlow()

    // Auth state for UI
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /** Exposes the Amber signer state so composables can register foreground launchers. */
    val amberState: StateFlow<AmberState> = amberSignerManager.state

    /**
     * Set the Activity context for Amber signing. Call from MainActivity.onResume() so that
     * the external signer (NIP-55) can use the Activity for the ContentProvider/signing flow
     * after app restart or return from background.
     */
    fun setAmberActivityContext(activity: Activity) {
        amberSignerManager.setActivityContext(activity)
        // Re-validate signer on resume — recovers from transient failures
        // (e.g. Amber became available after initial check, or state degraded)
        amberSignerManager.revalidate()
    }

    /**
     * Clear the Activity context when the activity is destroyed. Call from MainActivity.onDestroy().
     */
    fun clearAmberActivityContext() {
        amberSignerManager.clearActivityContext()
    }

    /** True after loadSavedAccounts() and restore/setGuestMode have run. Use to avoid showing sign-in during init. */
    private val _accountsRestored = MutableStateFlow(false)
    val accountsRestored: StateFlow<Boolean> = _accountsRestored.asStateFlow()

    /**
     * True when the user has completed onboarding (relay setup) for the current account.
     * Gates ALL relay connections, feed subscriptions, and notification subscriptions.
     * Set to true immediately for returning users who already have relays configured.
     * Set to true when onboarding's onComplete fires for new users.
     * Reset to false on account switch (before new account is evaluated).
     */
    private val _onboardingComplete = MutableStateFlow(false)
    val onboardingComplete: StateFlow<Boolean> = _onboardingComplete.asStateFlow()

    fun setOnboardingComplete(complete: Boolean) {
        _onboardingComplete.value = complete
        // Persist per-account so returning users skip onboarding permanently
        val npub = _currentAccount.value?.npub
        if (npub != null) {
            prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE_PREFIX + npub, complete).apply()
        }
        Log.d("AccountStateViewModel", "onboardingComplete = $complete (persisted for $npub)")
    }

    /** Check persisted onboarding status for a given npub. */
    private fun isOnboardingPersistedFor(npub: String): Boolean {
        return prefs.getBoolean(PREF_ONBOARDING_COMPLETE_PREFIX + npub, false)
    }

    /** In-memory private key bytes for nsec accounts (hexPubkey -> privKey bytes). Used for reactions when not using Amber. */
    private val nsecPrivKeyByHex = mutableMapOf<String, ByteArray>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val PREF_CURRENT_ACCOUNT = "current_account_npub"
        private const val PREF_ALL_ACCOUNTS = "all_accounts_json"
        private const val PREF_ONBOARDING_COMPLETE_PREFIX = "onboarding_complete_"
    }

    init {
        Log.d("AccountStateViewModel", "🔐 Initializing AccountStateViewModel")

        // Load saved accounts
        viewModelScope.launch {
            loadSavedAccounts()

            // Try to restore last active account; set _accountsRestored only after current account is set
            val currentNpub = prefs.getString(PREF_CURRENT_ACCOUNT, null)
            if (currentNpub != null) {
                val account = _savedAccounts.value.find { it.npub == currentNpub }
                if (account != null) {
                    Log.d("AccountStateViewModel", "🔐 Restoring account: ${account.toShortNpub()}")
                    switchToAccount(account)
                    // _accountsRestored set at end of switchToAccount's launch block
                } else {
                    setGuestMode()
                    _accountsRestored.value = true
                }
            } else {
                setGuestMode()
                _accountsRestored.value = true
            }
        }

        // When current user's kind-0 is loaded, update auth state so header shows profile picture
        ProfileMetadataCache.getInstance().profileUpdated
            .onEach { pubkey ->
                val account = _currentAccount.value ?: return@onEach
                val hex = account.toHexKey() ?: return@onEach
                if (pubkey != hex) return@onEach
                val author = ProfileMetadataCache.getInstance().getAuthor(pubkey) ?: return@onEach
                val existingProfile = _authState.value.userProfile
                val updatedProfile = if (existingProfile != null) {
                    existingProfile.copy(
                        displayName = author.displayName.ifBlank { existingProfile.displayName },
                        name = author.username.ifBlank { existingProfile.name },
                        picture = author.avatarUrl ?: existingProfile.picture,
                        about = author.about ?: existingProfile.about,
                        nip05 = author.nip05 ?: existingProfile.nip05
                    )
                } else {
                    UserProfile(
                        pubkey = hex,
                        displayName = author.displayName,
                        name = author.username,
                        picture = author.avatarUrl,
                        about = author.about,
                        nip05 = author.nip05,
                        createdAt = System.currentTimeMillis()
                    )
                }
                _authState.update { it.copy(userProfile = updatedProfile) }
                val updatedAccount = account.copy(
                    displayName = author.displayName.ifBlank { account.displayName },
                    picture = author.avatarUrl ?: account.picture
                )
                _currentAccount.value = updatedAccount
                val updatedList = _savedAccounts.value.map { if (it.npub == account.npub) updatedAccount else it }
                _savedAccounts.value = updatedList
                saveSavedAccounts(updatedList)
                Log.d("AccountStateViewModel", "📸 Profile updated: picture=${author.avatarUrl?.take(40)}, name=${author.displayName}")
            }
            .launchIn(viewModelScope)

        // When disk cache restores profiles, check if current user's profile is now available
        // (diskCacheRestored does NOT emit individual profileUpdated events)
        viewModelScope.launch {
            ProfileMetadataCache.getInstance().diskCacheRestored.collect { restored ->
                if (!restored) return@collect
                val account = _currentAccount.value ?: return@collect
                val hex = account.toHexKey() ?: return@collect
                val author = ProfileMetadataCache.getInstance().getAuthor(hex) ?: return@collect
                val hasNewData = (author.avatarUrl != null && author.avatarUrl != account.picture) ||
                    (author.displayName.isNotBlank() && author.displayName != account.displayName)
                if (!hasNewData) return@collect
                val existingProfile = _authState.value.userProfile
                val updatedProfile = existingProfile?.copy(
                    displayName = author.displayName.ifBlank { existingProfile.displayName },
                    name = author.username.ifBlank { existingProfile.name },
                    picture = author.avatarUrl ?: existingProfile.picture,
                    about = author.about ?: existingProfile.about,
                    nip05 = author.nip05 ?: existingProfile.nip05
                ) ?: UserProfile(
                    pubkey = hex,
                    displayName = author.displayName,
                    name = author.username,
                    picture = author.avatarUrl,
                    about = author.about,
                    nip05 = author.nip05,
                    createdAt = System.currentTimeMillis()
                )
                _authState.update { it.copy(userProfile = updatedProfile) }
                val updatedAccount = account.copy(
                    displayName = author.displayName.ifBlank { account.displayName },
                    picture = author.avatarUrl ?: account.picture
                )
                _currentAccount.value = updatedAccount
                val updatedList = _savedAccounts.value.map { if (it.npub == account.npub) updatedAccount else it }
                _savedAccounts.value = updatedList
                saveSavedAccounts(updatedList)
                Log.d("AccountStateViewModel", "📸 Disk cache restored profile: picture=${author.avatarUrl?.take(40)}, name=${author.displayName}")
            }
        }

        // Observe Amber state changes for new logins
        viewModelScope.launch {
            amberSignerManager.state.collect { amberState ->
                Log.d("AccountStateViewModel", "🔐 Amber state changed: $amberState")

                when (amberState) {
                    is AmberState.LoggedIn -> {
                        // New login completed
                        handleNewAmberLogin(amberState.pubKey)
                    }
                    is AmberState.Error -> {
                        _authState.update { it.copy(error = amberState.message, isLoading = false) }
                    }
                    is AmberState.LoggingIn -> {
                        _authState.update { it.copy(isLoading = true, error = null) }
                    }
                    else -> {
                        // NotInstalled or NotLoggedIn - keep current state
                    }
                }
            }
        }
    }

    private suspend fun loadSavedAccounts() = withContext(Dispatchers.IO) {
        val accountsJson = prefs.getString(PREF_ALL_ACCOUNTS, null)
        if (accountsJson != null) {
            try {
                val accounts = json.decodeFromString<List<AccountInfo>>(accountsJson)
                // Keep accounts in the order they were added (don't sort by lastUsed)
                _savedAccounts.value = accounts
                Log.d("AccountStateViewModel", "📚 Loaded ${accounts.size} saved accounts")
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "❌ Failed to load accounts: ${e.message}")
                _savedAccounts.value = emptyList()
            }
        } else {
            Log.d("AccountStateViewModel", "📚 No saved accounts found")
            _savedAccounts.value = emptyList()
        }
    }

    private suspend fun saveSavedAccounts(accounts: List<AccountInfo>) = withContext(Dispatchers.IO) {
        try {
            val accountsJson = json.encodeToString(accounts)
            prefs.edit()
                .putString(PREF_ALL_ACCOUNTS, accountsJson)
                .apply()
            _savedAccounts.value = accounts
            Log.d("AccountStateViewModel", "💾 Saved ${accounts.size} accounts")
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "❌ Failed to save accounts: ${e.message}")
        }
    }

    private fun setGuestMode() {
        Log.d("AccountStateViewModel", "👤 Setting guest mode")
        _currentAccount.value = null
        _zappedNoteIds.value = emptySet()
        _zappedAmountByNoteId.value = emptyMap()
        _authState.value = AuthState(
            isAuthenticated = false,
            isGuest = true,
            userProfile = GUEST_PROFILE,
            isLoading = false
        )
        // Full teardown: disconnect relays, clear NIP-42 auth, reset NIP-65
        RelayConnectionStateMachine.getInstance().disconnectAndClearForAccountSwitch()
    }

    private suspend fun handleNewAmberLogin(hexPubkey: String) {
        Log.d("AccountStateViewModel", "✅ Handling new Amber login: ${hexPubkey.take(16)}...")

        // Reset onboarding gate — will be re-evaluated after account is set
        _onboardingComplete.value = false

        // Convert hex to npub
        val npub = try {
            hexPubkey.hexToByteArray().toNpub()
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "❌ Failed to convert to npub: ${e.message}")
            return
        }

        // Full teardown FIRST — disconnect relays, clear notes, NIP-42 auth, NIP-65
        // before updating currentAccount so navigation doesn't react to stale state.
        RelayConnectionStateMachine.getInstance().disconnectAndClearForAccountSwitch()

        // Check if this account already exists
        val existingAccount = _savedAccounts.value.find { it.npub == npub }

        val accountInfo = if (existingAccount != null) {
            // Update last used time
            existingAccount.copy(lastUsed = System.currentTimeMillis())
        } else {
            // Create new account
            AccountInfo(
                npub = npub,
                hasPrivateKey = false,
                isExternalSigner = true,
                isTransient = false,
                displayName = "Nostr User",
                picture = null,
                lastUsed = System.currentTimeMillis()
            )
        }

        // Save to accounts list - maintain order of addition
        val updatedAccounts = _savedAccounts.value
            .filter { it.npub != npub }
            .plus(accountInfo)

        saveSavedAccounts(updatedAccounts)

        // Set as current in prefs
        prefs.edit()
            .putString(PREF_CURRENT_ACCOUNT, npub)
            .apply()

        // Update auth state
        val userProfile = UserProfile(
            pubkey = hexPubkey,
            displayName = accountInfo.displayName ?: "Nostr User",
            name = hexPubkey.take(8),
            picture = accountInfo.picture,
            about = "Signed in with Amber Signer",
            createdAt = System.currentTimeMillis()
        )

        _authState.value = AuthState(
            isAuthenticated = true,
            isGuest = false,
            userProfile = userProfile,
            isLoading = false,
            error = null
        )

        restoreZapState(accountInfo.npub)
        ReactionsRepository.loadForAccount(getApplication(), accountInfo.npub)

        // Set NIP-42 signer for new account
        RelayConnectionStateMachine.getInstance().setNip42Signer(getCurrentSigner())

        // Publish account change LAST so all teardown is complete before
        // navigation LaunchedEffects react to the new account.
        _currentAccount.value = accountInfo

        // Check persisted onboarding status first, then fall back to relay existence
        if (isOnboardingPersistedFor(accountInfo.npub)) {
            _onboardingComplete.value = true
            Log.d("AccountStateViewModel", "Returning user (Amber, persisted) — onboardingComplete = true")
        } else {
            val outbox = relayStorageManager.loadOutboxRelays(hexPubkey)
            val categories = relayStorageManager.loadCategories(hexPubkey)
            if (outbox.isNotEmpty() || categories.any { it.relays.isNotEmpty() }) {
                _onboardingComplete.value = true
                prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE_PREFIX + accountInfo.npub, true).apply()
                Log.d("AccountStateViewModel", "Returning user (Amber, relays exist) — onboardingComplete = true")
            }
        }

        Log.d("AccountStateViewModel", "✅ Account activated: ${accountInfo.toShortNpub()}")
    }

    /**
     * Switch to a different saved account
     */
    fun switchToAccount(accountInfo: AccountInfo) {
        viewModelScope.launch {
            Log.d("AccountStateViewModel", "🔄 Switching to account: ${accountInfo.toShortNpub()}")

            // Reset onboarding gate — will be re-evaluated after account is set
            _onboardingComplete.value = false

            // Full teardown FIRST — disconnect relays, clear notes, NIP-42 auth, NIP-65
            // before updating currentAccount so navigation doesn't react to stale state.
            RelayConnectionStateMachine.getInstance().disconnectAndClearForAccountSwitch()

            // Update last used time
            val updatedAccount = accountInfo.copy(lastUsed = System.currentTimeMillis())

            val updatedAccounts = _savedAccounts.value
                .filter { it.npub != accountInfo.npub }
                .plus(updatedAccount)

            saveSavedAccounts(updatedAccounts)

            // Set as current
            prefs.edit()
                .putString(PREF_CURRENT_ACCOUNT, accountInfo.npub)
                .apply()

            // Convert npub to hex for user profile
            val hexPubkey = updatedAccount.toHexKey()

            if (hexPubkey != null) {
                val userProfile = UserProfile(
                    pubkey = hexPubkey,
                    displayName = updatedAccount.displayName ?: "Nostr User",
                    name = hexPubkey.take(8),
                    picture = updatedAccount.picture,
                    about = if (updatedAccount.isExternalSigner) "Signed in with Amber Signer" else "Nostr User",
                    createdAt = System.currentTimeMillis()
                )

                _authState.value = AuthState(
                    isAuthenticated = true,
                    isGuest = false,
                    userProfile = userProfile,
                    isLoading = false,
                    error = null
                )

                restoreZapState(updatedAccount.npub)
                ReactionsRepository.loadForAccount(getApplication(), updatedAccount.npub)

                // Set NIP-42 signer for new account
                RelayConnectionStateMachine.getInstance().setNip42Signer(getCurrentSigner())

                Log.d("AccountStateViewModel", "✅ Switched to account: ${updatedAccount.getDisplayNameOrNpub()}")
            }

            // Publish account change LAST so all teardown is complete before
            // navigation LaunchedEffects react to the new account.
            _currentAccount.value = updatedAccount
            _accountsRestored.value = true

            // Check persisted onboarding status first, then fall back to relay existence
            if (isOnboardingPersistedFor(updatedAccount.npub)) {
                _onboardingComplete.value = true
                Log.d("AccountStateViewModel", "Returning user (persisted) — onboardingComplete = true")
            } else {
                val hex = updatedAccount.toHexKey()
                if (hex != null) {
                    val outbox = relayStorageManager.loadOutboxRelays(hex)
                    val categories = relayStorageManager.loadCategories(hex)
                    if (outbox.isNotEmpty() || categories.any { it.relays.isNotEmpty() }) {
                        _onboardingComplete.value = true
                        // Persist for future — this account had relays before the persistence was added
                        prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE_PREFIX + updatedAccount.npub, true).apply()
                        Log.d("AccountStateViewModel", "Returning user (relays exist) — onboardingComplete = true")
                    }
                }
            }
        }
    }

    private fun restoreZapState(accountNpub: String) {
        _zappedNoteIds.value = ZapStatePersistence.loadZappedIds(getApplication(), accountNpub)
        _zappedAmountByNoteId.value = ZapStatePersistence.loadZappedAmounts(getApplication(), accountNpub)
    }

    /**
     * Login with Amber (creates intent)
     */
    fun loginWithAmber(): android.content.Intent {
        return amberSignerManager.createLoginIntent()
    }

    /**
     * Handle Amber login result
     */
    fun handleAmberLoginResult(resultCode: Int, data: android.content.Intent?) {
        Log.d("AccountStateViewModel", "🔐 Handling Amber login result - resultCode: $resultCode")
        amberSignerManager.handleLoginResult(resultCode, data)
    }

    /**
     * Login with a raw key string. Supports:
     * - nsec1... (private key) -> full account with signing
     * - npub1... (public key) -> read-only account
     * - 64-char hex (assumed public key) -> read-only account
     *
     * Returns null on success, or an error message string.
     */
    fun loginWithKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return "Key cannot be empty"

        try {
            when {
                trimmed.startsWith("nsec1") -> {
                    val parsed = Nip19Parser.uriToRoute(trimmed)
                    val nsecEntity = parsed?.entity as? NSec
                        ?: return "Invalid nsec key"
                    val privKeyBytes = try {
                        trimmed.bechToBytes()
                    } catch (e: Exception) {
                        Log.e("AccountStateViewModel", "nsec bechToBytes failed", e)
                        return "Invalid nsec key"
                    }
                    val hexPubkey = KeyPair(privKey = privKeyBytes).pubKey.toHexString()
                    nsecPrivKeyByHex[hexPubkey] = privKeyBytes
                    val npub = hexPubkey.hexToByteArray().toNpub()
                    createAndActivateAccount(
                        npub = npub,
                        hexPubkey = hexPubkey,
                        hasPrivateKey = true,
                        isExternalSigner = false
                    )
                    return null
                }
                trimmed.startsWith("npub1") -> {
                    val parsed = Nip19Parser.uriToRoute(trimmed)
                    val npubEntity = parsed?.entity as? NPub
                        ?: return "Invalid npub key"
                    val hexPubkey = npubEntity.hex
                    createAndActivateAccount(
                        npub = trimmed,
                        hexPubkey = hexPubkey,
                        hasPrivateKey = false,
                        isExternalSigner = false
                    )
                    return null
                }
                trimmed.length == 64 && trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> {
                    // Treat 64-char hex as pubkey (read-only)
                    val npub = trimmed.lowercase().hexToByteArray().toNpub()
                    createAndActivateAccount(
                        npub = npub,
                        hexPubkey = trimmed.lowercase(),
                        hasPrivateKey = false,
                        isExternalSigner = false
                    )
                    return null
                }
                else -> return "Unrecognized key format. Use nsec1... or npub1..."
            }
        } catch (e: Exception) {
            Log.e("AccountStateViewModel", "loginWithKey failed: ${e.message}", e)
            return "Login failed: ${e.message}"
        }
    }

    private fun createAndActivateAccount(
        npub: String,
        hexPubkey: String,
        hasPrivateKey: Boolean,
        isExternalSigner: Boolean
    ) {
        viewModelScope.launch {
            // Reset onboarding gate — will be re-evaluated after account is set
            _onboardingComplete.value = false

            val existing = _savedAccounts.value.find { it.npub == npub }
            val accountInfo = existing?.copy(
                lastUsed = System.currentTimeMillis(),
                hasPrivateKey = hasPrivateKey || existing.hasPrivateKey,
                isExternalSigner = isExternalSigner
            ) ?: AccountInfo(
                npub = npub,
                hasPrivateKey = hasPrivateKey,
                isExternalSigner = isExternalSigner,
                isTransient = false,
                displayName = "Nostr User",
                picture = null,
                lastUsed = System.currentTimeMillis()
            )

            val updatedAccounts = _savedAccounts.value
                .filter { it.npub != npub }
                .plus(accountInfo)
            saveSavedAccounts(updatedAccounts)

            prefs.edit()
                .putString(PREF_CURRENT_ACCOUNT, npub)
                .apply()

            _currentAccount.value = accountInfo

            val aboutText = when {
                hasPrivateKey -> "Signed in with private key"
                isExternalSigner -> "Signed in with Amber Signer"
                else -> "Read-only mode"
            }

            val userProfile = UserProfile(
                pubkey = hexPubkey,
                displayName = accountInfo.displayName ?: "Nostr User",
                name = hexPubkey.take(8),
                picture = accountInfo.picture,
                about = aboutText,
                createdAt = System.currentTimeMillis()
            )

            _authState.value = AuthState(
                isAuthenticated = true,
                isGuest = false,
                userProfile = userProfile,
                isLoading = false,
                error = null
            )
            _accountsRestored.value = true

            // Check persisted onboarding status first, then fall back to relay existence
            if (isOnboardingPersistedFor(accountInfo.npub)) {
                _onboardingComplete.value = true
                Log.d("AccountStateViewModel", "Returning user (key, persisted) — onboardingComplete = true")
            } else {
                val outbox = relayStorageManager.loadOutboxRelays(hexPubkey)
                val categories = relayStorageManager.loadCategories(hexPubkey)
                if (outbox.isNotEmpty() || categories.any { it.relays.isNotEmpty() }) {
                    _onboardingComplete.value = true
                    prefs.edit().putBoolean(PREF_ONBOARDING_COMPLETE_PREFIX + accountInfo.npub, true).apply()
                    Log.d("AccountStateViewModel", "Returning user (key, relays exist) — onboardingComplete = true")
                }
            }

            Log.d("AccountStateViewModel", "Account activated via key login: ${accountInfo.toShortNpub()}")
        }
    }

    /** Flow for one-shot error/success messages from async operations (reactions, follows, etc.) */
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    fun clearToast() { _toastMessage.value = null }

    /** Note IDs currently sending a zap (for loading indicator). */
    private val _zapInProgressNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val zapInProgressNoteIds: StateFlow<Set<String>> = _zapInProgressNoteIds.asStateFlow()

    /** Note IDs the current user has zapped (bolt turns yellow). */
    private val _zappedNoteIds = MutableStateFlow<Set<String>>(emptySet())
    val zappedNoteIds: StateFlow<Set<String>> = _zappedNoteIds.asStateFlow()

    /** Amount (sats) the current user zapped per note ID; for showing "You zapped X sats". */
    private val _zappedAmountByNoteId = MutableStateFlow<Map<String, Long>>(emptyMap())
    val zappedAmountByNoteId: StateFlow<Map<String, Long>> = _zappedAmountByNoteId.asStateFlow()

    /**
     * Get the NostrSigner for the current account (Amber or nsec-based).
     * Returns null if no signer is available (read-only npub accounts).
     */
    fun getCurrentSigner(): com.example.cybin.signer.NostrSigner? {
        val account = _currentAccount.value ?: return null
        val accountHex = account.toHexKey() ?: return null
        return when {
            account.isExternalSigner -> amberSignerManager.getCurrentSigner()
            account.hasPrivateKey -> {
                val privKeyBytes = nsecPrivKeyByHex[accountHex]
                if (privKeyBytes != null) NostrSignerInternal(KeyPair(privKey = privKeyBytes)) else null
            }
            else -> null
        }
    }

    /**
     * Send a NIP-25 reaction (kind-7) using the external signer (Amber).
     * Returns null on success, or an error message for synchronous failures.
     * Async failures are emitted via [toastMessage].
     */
    fun sendReaction(note: Note, emoji: String): String? {
        val account = _currentAccount.value ?: return "Sign in to react"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        val signer = when {
            account.isExternalSigner -> amberSignerManager.getCurrentSigner()
            account.hasPrivateKey -> {
                val privKeyBytes = nsecPrivKeyByHex[accountHex]
                if (privKeyBytes != null) NostrSignerInternal(KeyPair(privKey = privKeyBytes)) else null
            }
            else -> null
        } ?: return when {
            account.isExternalSigner -> "Amber signer not available"
            account.hasPrivateKey -> "Private key not available. Sign in again with nsec."
            else -> "Sign in with nsec or Amber to react"
        }

        val relaySet = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url)?.url }
            .toSet()
        if (relaySet.isEmpty()) return "No outbox relays configured"

        Log.d("AccountStateViewModel", "sendReaction: emoji=$emoji, noteId=${note.id.take(8)}, relays=${relaySet.size}")

        val targetPubkey = normalizeAuthorIdForCache(note.author.id)
        val targetEvent = Event(
            id = note.id,
            pubKey = targetPubkey,
            createdAt = (note.timestamp / 1000),
            kind = note.kind,
            tags = emptyArray(),
            content = note.content,
            sig = ""
        )
        val relayHint = note.relayUrl?.let { RelayUrlNormalizer.normalizeOrNull(it)?.url }
        val template = ReactionEvent.build(emoji, targetEvent, relayHint)

        // Inject NIP-89 client tag if enabled
        val finalTemplate = if (ClientTagManager.isEnabled(getApplication())) {
            EventTemplate(template.createdAt, template.kind, template.tags + arrayOf(ClientTagManager.CLIENT_TAG), template.content)
        } else template

        Log.d("AccountStateViewModel", "sendReaction: template kind=${finalTemplate.kind}, tags=${finalTemplate.tags.size}")

        viewModelScope.launch {
            try {
                Log.d("AccountStateViewModel", "sendReaction: signing...")
                val signed = signer.sign(finalTemplate)
                Log.d("AccountStateViewModel", "sendReaction: signed! id=${signed.id.take(8)}, kind=${signed.kind}, sig=${signed.sig.take(8)}...")

                // Validate the signed event
                if (signed.sig.isBlank()) {
                    Log.e("AccountStateViewModel", "sendReaction: signed event has empty sig!")
                    _toastMessage.value = "Reaction signing failed (empty signature)"
                    return@launch
                }

                Log.d("AccountStateViewModel", "sendReaction: sending to ${relaySet.size} outbox relays")
                RelayConnectionStateMachine.getInstance().send(signed, relaySet)
                Log.d("AccountStateViewModel", "sendReaction: sent successfully")

                ReactionsRepository.setLastReaction(note.id, emoji)
                ReactionsRepository.persist(getApplication(), account.npub)
                ReactionsRepository.recordEmoji(getApplication(), account.npub, emoji)
            } catch (e: Exception) {
                Log.e("AccountStateViewModel", "sendReaction failed: ${e.message}", e)
                _toastMessage.value = "Reaction failed: ${e.message?.take(60)}"
            }
        }
        return null
    }

    // ── Publishing helpers ─────────────────────────────────────────────

    /** Get the current NostrSigner (Amber or nsec-based), or null if unavailable. */
    private fun getSignerOrNull(): com.example.cybin.signer.NostrSigner? {
        return getCurrentSigner()
    }

    /** Human-readable error when signer is unavailable. */
    private fun signerUnavailableMessage(): String {
        val account = _currentAccount.value ?: return "Sign in to continue"
        return when {
            account.isExternalSigner -> "Amber signer not available"
            account.hasPrivateKey -> "Private key not available. Sign in again with nsec."
            else -> "Sign in with nsec or Amber to publish"
        }
    }

    /** Outbox relay URLs as a Set<String> (raw URL strings, pre-normalization happens at publish time). */
    fun getOutboxRelayUrlSet(): Set<String> {
        val account = _currentAccount.value ?: return emptySet()
        val accountHex = account.toHexKey() ?: return emptySet()
        return relayStorageManager.loadOutboxRelays(accountHex)
            .map { it.url }
            .toSet()
    }

    /**
     * Outbox relays for the current account (for relay picker when publishing kind-1).
     * Returns empty list if no account or no outbox configured.
     */
    fun getOutboxRelaysForPublish(): List<UserRelay> {
        val account = _currentAccount.value ?: return emptyList()
        val accountHex = account.toHexKey() ?: return emptyList()
        return relayStorageManager.loadOutboxRelays(accountHex)
    }

    /**
     * Merged publish relay set: outbox relays + relays from all subscribed categories.
     * Ensures events reach all relays the user has configured, not just outbox.
     */
    fun getPublishRelayUrlSet(): Set<String> {
        val account = _currentAccount.value ?: return emptySet()
        val accountHex = account.toHexKey() ?: return emptySet()
        val outbox = relayStorageManager.loadOutboxRelays(accountHex).map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
        val categoryRelays = relayStorageManager.loadCategories(accountHex)
            .filter { it.isSubscribed }
            .flatMap { it.relays }
            .map { social.mycelium.android.utils.normalizeRelayUrl(it.url) }
        return (outbox + categoryRelays).toSet()
    }

    /**
     * Publish a Kind 1 text note. Signs with Amber and sends to the given relay URLs.
     * Returns null on success, or an error message for synchronous failures.
     * Async failures are emitted via [toastMessage].
     *
     * The note is injected optimistically into the feed with [PublishState.Sending] immediately
     * after signing, so it appears at the top of the feed before relay confirmation.
     */
    fun publishKind1(content: String, relayUrls: Set<String>): String? {
        if (content.isBlank()) return "Note is empty"
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val pubkey = currentAccount.value?.toHexKey()
        viewModelScope.launch {
            when (val result = EventPublisher.publish(getApplication(), signer, relayUrls, kind = 1, content = content)) {
                is PublishResult.Success -> {
                    // Build a Note from the signed event and inject into feed optimistically
                    val author = pubkey?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
                        ?: social.mycelium.android.data.Author(id = result.event.pubKey, username = "", displayName = "You")
                    val note = social.mycelium.android.data.Note(
                        id = result.event.id,
                        author = author,
                        content = content,
                        timestamp = result.event.createdAt * 1000L,
                        kind = 1,
                        mediaUrls = social.mycelium.android.utils.UrlDetector.findUrls(content).filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }.distinct(),
                        hashtags = result.event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] },
                        relayUrls = relayUrls.toList()
                    )
                    NotesRepository.getInstance().injectOwnNote(note)
                    // Mark as confirmed — progress line turns green then fades
                    NotesRepository.getInstance().updatePublishState(result.event.id, social.mycelium.android.data.PublishState.Confirmed)
                }
                is PublishResult.Error -> {
                    _toastMessage.value = "Publish failed: ${result.message}"
                }
            }
        }
        return null
    }

    /**
     * Publish a Kind 1311 live activity chat message (NIP-53).
     * @param content The chat message text.
     * @param activityAddress The addressable event coordinate, e.g. "30311:<pubkey>:<dtag>"
     * @param relayUrls Relay URLs to send the message to (from the live activity).
     * Returns null on success, or an error message.
     */
    fun publishLiveChatMessage(content: String, activityAddress: String, relayUrls: Set<String>) {
        if (content.isBlank()) {
            _toastMessage.value = "Message is empty"
            return
        }
        val signer = getSignerOrNull()
        if (signer == null) {
            Log.w("AccountStateViewModel", "publishLiveChatMessage: signer unavailable")
            _toastMessage.value = signerUnavailableMessage()
            return
        }
        if (relayUrls.isEmpty()) {
            Log.w("AccountStateViewModel", "publishLiveChatMessage: no relays")
            _toastMessage.value = "No relays selected"
            return
        }
        Log.d("AccountStateViewModel", "publishLiveChatMessage: content='${content.take(30)}', addr=$activityAddress, relays=${relayUrls.size}")
        viewModelScope.launch {
            val result = EventPublisher.publish(getApplication(), signer, relayUrls, kind = 1311, content = content) {
                add(arrayOf("a", activityAddress))
            }
            when (result) {
                is PublishResult.Success -> Log.d("AccountStateViewModel", "Chat published: ${result.eventId.take(8)}")
                is PublishResult.Error -> {
                    Log.e("AccountStateViewModel", "Chat publish failed: ${result.message}")
                    _toastMessage.value = "Chat failed: ${result.message}"
                }
            }
        }
    }

    /**
     * Publish a Kind 11 topic (title, content, hashtags). Signs with Amber and sends to selected relays.
     * @param relayUrls Relay URLs to publish to (from relay picker). Falls back to outbox if empty.
     * Returns null on success, or an error message.
     */
    fun publishTopic(title: String, content: String, hashtags: List<String>, relayUrls: Set<String> = emptySet()): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = relayUrls.ifEmpty { getPublishRelayUrlSet() }
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val template = TopicsPublishService.buildTopicEventTemplate(title, content, hashtags)
            when (val result = EventPublisher.publish(getApplication(), signer, relaySet, template)) {
                is PublishResult.Success -> {
                    Log.d("AccountStateViewModel", "Topic published: ${result.eventId.take(8)}")
                    // Inject into TopicsRepository so "x new topics" counter updates immediately
                    TopicsRepository.getInstance(getApplication()).injectLocalTopic(result.event)
                }
                is PublishResult.Error -> _toastMessage.value = "Topic failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 1111 thread reply. Signs with Amber and sends to selected relays.
     * rootThreadId/rootThreadPubkey = the Kind 11 topic; parentReplyId/parentReplyPubkey = optional parent reply (Kind 1111).
     * @param relayUrls Relay URLs to publish to. Falls back to outbox if empty.
     */
    fun publishThreadReply(
        rootThreadId: String,
        rootThreadPubkey: String,
        parentReplyId: String?,
        parentReplyPubkey: String?,
        content: String,
        relayUrls: Set<String> = emptySet()
    ): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = relayUrls.ifEmpty { getPublishRelayUrlSet() }
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val template = TopicsPublishService.buildThreadReplyEventTemplate(
                rootThreadId, rootThreadPubkey, parentReplyId, parentReplyPubkey, content
            )
            when (val result = EventPublisher.publish(getApplication(), signer, relaySet, template)) {
                is PublishResult.Success -> Log.d("AccountStateViewModel", "Thread reply published: ${result.eventId.take(8)}")
                is PublishResult.Error -> _toastMessage.value = "Reply failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 1 reply with NIP-10 e-tags (root + reply markers).
     * Used for replying in kind-1 threads from the thread view.
     */
    fun publishKind1Reply(
        rootId: String,
        rootPubkey: String,
        parentId: String?,
        parentPubkey: String?,
        content: String,
        relayUrls: Set<String> = emptySet()
    ): String? {
        if (content.isBlank()) return "Reply is empty"
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = relayUrls.ifEmpty { getPublishRelayUrlSet() }
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val result = EventPublisher.publish(getApplication(), signer, relaySet, kind = 1, content = content) {
                // NIP-10 e-tags: root marker always points to the thread root
                add(arrayOf("e", rootId, "", "root"))
                // If replying to a specific reply (not the root), add reply marker
                if (parentId != null && parentId != rootId) {
                    add(arrayOf("e", parentId, "", "reply"))
                }
                // p-tags for the root author and (optionally) the parent reply author
                add(arrayOf("p", rootPubkey))
                if (parentPubkey != null && parentPubkey != rootPubkey) {
                    add(arrayOf("p", parentPubkey))
                }
            }
            when (result) {
                is PublishResult.Success -> {
                    Log.d("AccountStateViewModel", "Kind-1 reply published: ${result.eventId.take(8)}")
                    // Inject into ThreadReplyCache for instant display in thread view
                    val pubkey = currentAccount.value?.toHexKey()
                    val author = pubkey?.let { ProfileMetadataCache.getInstance().resolveAuthor(it) }
                        ?: social.mycelium.android.data.Author(id = result.event.pubKey, username = "", displayName = "You")
                    val replyNote = social.mycelium.android.data.Note(
                        id = result.event.id,
                        author = author,
                        content = content,
                        timestamp = result.event.createdAt * 1000L,
                        kind = 1,
                        isReply = true,
                        rootNoteId = rootId,
                        replyToId = parentId ?: rootId,
                        mediaUrls = social.mycelium.android.utils.UrlDetector.findUrls(content).filter { social.mycelium.android.utils.UrlDetector.isImageUrl(it) || social.mycelium.android.utils.UrlDetector.isVideoUrl(it) }.distinct(),
                        hashtags = result.event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
                    )
                    social.mycelium.android.cache.ThreadReplyCache.addLocalReply(rootId, replyNote)
                }
                is PublishResult.Error -> _toastMessage.value = "Reply failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 6 repost (boost). The content is the JSON of the original event,
     * with e-tag pointing to the original note and p-tag to its author.
     */
    fun publishRepost(noteId: String, noteAuthorPubkey: String, rawEventJson: String = "", originalNote: social.mycelium.android.data.Note? = null): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = getPublishRelayUrlSet()
        if (relaySet.isEmpty()) return "No relays configured"
        val pubkey = currentAccount.value?.toHexKey()
        viewModelScope.launch {
            val result = EventPublisher.publish(getApplication(), signer, relaySet, kind = 6, content = rawEventJson) {
                add(arrayOf("e", noteId))
                add(arrayOf("p", noteAuthorPubkey))
            }
            when (result) {
                is PublishResult.Success -> {
                    // Inject repost locally with publish progress — line turns green then fades
                    if (originalNote != null && pubkey != null) {
                        NotesRepository.getInstance().injectOwnRepost(originalNote, pubkey, result.event.id)
                        delay(400) // Minimum shimmer display before green confirmation
                        NotesRepository.getInstance().updatePublishState(result.event.id, social.mycelium.android.data.PublishState.Confirmed)
                    }
                }
                is PublishResult.Error -> _toastMessage.value = "Boost failed: ${result.message}"
            }
        }
        return null
    }

    // ── NIP-22: Anchored Events ──────────────────────────────────────────

    /**
     * Publish a Kind 1011 scoped moderation event: mark a note as off-topic within a hashtag anchor.
     * Signs with Amber and sends to outbox relays.
     */
    fun publishOffTopicModeration(anchor: String, noteId: String, reason: String = "off-topic"): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = getPublishRelayUrlSet()
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val template = TopicsPublishService.buildOffTopicModerationTemplate(anchor, noteId, reason)
            when (val result = EventPublisher.publish(getApplication(), signer, relaySet, template)) {
                is PublishResult.Success -> _toastMessage.value = "Marked as off-topic"
                is PublishResult.Error -> _toastMessage.value = "Moderation failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 1011 scoped moderation event: exclude a user from a hashtag anchor.
     */
    fun publishUserExclusion(anchor: String, pubkey: String, reason: String = "removed from topic"): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = getPublishRelayUrlSet()
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val template = TopicsPublishService.buildUserExclusionModerationTemplate(anchor, pubkey, reason)
            when (val result = EventPublisher.publish(getApplication(), signer, relaySet, template)) {
                is PublishResult.Success -> _toastMessage.value = "User excluded from topic"
                is PublishResult.Error -> _toastMessage.value = "Moderation failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Publish a Kind 30073 anchor subscription event (replaces previous).
     * [anchors] = list of NIP-73 identifiers; [moderators] = map of anchor -> list of moderator pubkeys.
     */
    fun publishAnchorSubscriptions(anchors: List<String>, moderators: Map<String, List<String>> = emptyMap()): String? {
        val signer = getSignerOrNull() ?: return signerUnavailableMessage()
        val relaySet = getPublishRelayUrlSet()
        if (relaySet.isEmpty()) return "No relays configured"
        viewModelScope.launch {
            val template = TopicsPublishService.buildAnchorSubscriptionTemplate(anchors, moderators)
            when (val result = EventPublisher.publish(getApplication(), signer, relaySet, template)) {
                is PublishResult.Success -> _toastMessage.value = "Anchor subscriptions updated"
                is PublishResult.Error -> _toastMessage.value = "Subscription failed: ${result.message}"
            }
        }
        return null
    }

    /**
     * Follow a user (publish updated kind-3 contact list via Amber).
     * Returns null on success, or an error message.
     */
    fun followUser(targetPubkey: String): String? {
        val account = _currentAccount.value ?: return "Sign in to follow"
        val signer = amberSignerManager.getCurrentSigner() ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url)?.url }
            .toSet()
        if (outboxRelays.isEmpty()) return "No outbox relays configured"

        val cacheRelayUrls = relayStorageManager.loadIndexerRelays(accountHex).map { it.url }

        viewModelScope.launch {
            val error = ContactListRepository.follow(
                myPubkey = accountHex,
                targetPubkey = targetPubkey,
                signer = signer,
                outboxRelays = outboxRelays,
                cacheRelayUrls = cacheRelayUrls
            )
            if (error != null) {
                Log.e("AccountStateViewModel", "followUser failed: $error")
            } else {
                Log.d("AccountStateViewModel", "Followed ${targetPubkey.take(8)}...")
            }
        }
        return null
    }

    /**
     * Unfollow a user (publish updated kind-3 contact list via Amber).
     * Returns null on success, or an error message.
     */
    fun unfollowUser(targetPubkey: String): String? {
        val account = _currentAccount.value ?: return "Sign in to unfollow"
        val signer = amberSignerManager.getCurrentSigner() ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url)?.url }
            .toSet()
        if (outboxRelays.isEmpty()) return "No outbox relays configured"

        val cacheRelayUrls = relayStorageManager.loadIndexerRelays(accountHex).map { it.url }

        viewModelScope.launch {
            val error = ContactListRepository.unfollow(
                myPubkey = accountHex,
                targetPubkey = targetPubkey,
                signer = signer,
                outboxRelays = outboxRelays,
                cacheRelayUrls = cacheRelayUrls
            )
            if (error != null) {
                Log.e("AccountStateViewModel", "unfollowUser failed: $error")
            } else {
                Log.d("AccountStateViewModel", "Unfollowed ${targetPubkey.take(8)}...")
            }
        }
        return null
    }

    /**
     * Send a zap on a note via NWC (NIP-57 + NIP-47).
     * Progress and errors are emitted via [toastMessage].
     */
    fun sendZap(
        note: Note,
        amountSats: Long,
        zapType: social.mycelium.android.repository.ZapType,
        message: String = ""
    ): String? {
        val account = _currentAccount.value ?: return "Sign in to zap"
        val signer = amberSignerManager.getCurrentSigner() ?: return "Amber signer not available"
        val accountHex = account.toHexKey() ?: return "Invalid account key"
        if (!social.mycelium.android.services.NwcPaymentManager.isConfigured(getApplication())) {
            return "Please connect NWC"
        }

        val outboxRelays = relayStorageManager.loadOutboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url)?.url }
            .toSet()

        val noteId = note.id
        val accountNpub = account.npub
        _zapInProgressNoteIds.value = _zapInProgressNoteIds.value + noteId
        viewModelScope.launch {
            try {
                social.mycelium.android.services.ZapPaymentHandler.zap(
                    context = getApplication(),
                    note = note,
                    amountSats = amountSats,
                    zapType = zapType,
                    message = message,
                    signer = signer,
                    outboxRelayUrls = outboxRelays,
                    onProgress = { progress ->
                        when (progress) {
                            is social.mycelium.android.services.ZapProgress.InProgress -> {
                                Log.d("AccountStateViewModel", "Zap progress: ${progress.step}")
                            }
                            is social.mycelium.android.services.ZapProgress.Success -> {
                                _zappedNoteIds.value = _zappedNoteIds.value + noteId
                                _zappedAmountByNoteId.value = _zappedAmountByNoteId.value + (noteId to amountSats)
                                ZapStatePersistence.saveZappedIds(getApplication(), accountNpub, _zappedNoteIds.value)
                                ZapStatePersistence.saveZappedAmounts(getApplication(), accountNpub, _zappedAmountByNoteId.value)
                                // Zap confirmed — bolt icon already turned yellow as feedback
                            }
                            is social.mycelium.android.services.ZapProgress.Failed -> {
                                viewModelScope.launch(Dispatchers.Main.immediate) {
                                    _toastMessage.value = "Zap failed: ${progress.message}"
                                }
                            }
                            is social.mycelium.android.services.ZapProgress.Idle -> {}
                        }
                    }
                )
            } finally {
                _zapInProgressNoteIds.value = _zapInProgressNoteIds.value - noteId
            }
        }
        return null
    }

    /**
     * Logout from specific account
     */
    fun logoutAccount(accountInfo: AccountInfo) {
        viewModelScope.launch {
            Log.d("AccountStateViewModel", "👋 Logging out account: ${accountInfo.toShortNpub()}")

            // Clear all relay data for this account
            val pubkey = accountInfo.toHexKey()
            if (pubkey != null) {
                relayStorageManager.clearUserData(pubkey)
                Log.d("AccountStateViewModel", "🗑️ Cleared relay data for account: ${accountInfo.toShortNpub()}")
            }

            // Remove from saved accounts
            val updatedAccounts = _savedAccounts.value.filter { it.npub != accountInfo.npub }
            saveSavedAccounts(updatedAccounts)

            // If this was the current account, switch to another or guest
            if (_currentAccount.value?.npub == accountInfo.npub) {
                if (updatedAccounts.isNotEmpty()) {
                    // Switch to most recently used account
                    switchToAccount(updatedAccounts.first())
                } else {
                    // No more accounts, go to guest mode
                    prefs.edit().remove(PREF_CURRENT_ACCOUNT).apply()

                    // Clear guest relay data as well when switching to guest mode
                    relayStorageManager.clearUserData("guest")
                    Log.d("AccountStateViewModel", "🗑️ Cleared guest relay data")

                    setGuestMode()
                }
            }
        }
    }

    /**
     * Logout current account
     */
    fun logoutCurrentAccount() {
        _currentAccount.value?.let { logoutAccount(it) }
    }

    /**
     * Get current account's npub
     */
    fun currentAccountNpub(): String? {
        return _currentAccount.value?.npub
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return _authState.value.isAuthenticated
    }

    /**
     * Clear any error messages
     */
    fun clearError() {
        _authState.update { it.copy(error = null) }
    }

    // ── Anchor Subscription Management ──────────────────────────────────────

    private val anchorSubscriptionRepo = social.mycelium.android.repository.AnchorSubscriptionRepository.getInstance()

    /**
     * Get the current set of subscribed anchors
     */
    fun getSubscribedAnchors(): StateFlow<Set<String>> {
        return anchorSubscriptionRepo.subscribedAnchors
    }

    /**
     * Check if user is subscribed to an anchor
     */
    fun isSubscribedToAnchor(anchor: String): Boolean {
        return anchorSubscriptionRepo.isSubscribed(anchor)
    }

    /**
     * Subscribe to an anchor (adds to subscription list and publishes update)
     */
    fun subscribeToAnchor(anchor: String): String? {
        val currentSubs = anchorSubscriptionRepo.getAllSubscriptions().toMutableSet()
        if (anchor in currentSubs) {
            return "Already subscribed to $anchor"
        }
        // Optimistic local update so UI reflects immediately
        anchorSubscriptionRepo.addLocalAnchor(anchor)
        currentSubs.add(anchor)
        return publishAnchorSubscriptions(currentSubs.toList())
    }

    /**
     * Unsubscribe from an anchor (removes from subscription list and publishes update)
     */
    fun unsubscribeFromAnchor(anchor: String): String? {
        val currentSubs = anchorSubscriptionRepo.getAllSubscriptions().toMutableSet()
        if (anchor !in currentSubs) {
            return "Not subscribed to $anchor"
        }
        // Optimistic local update so UI reflects immediately
        anchorSubscriptionRepo.removeLocalAnchor(anchor)
        currentSubs.remove(anchor)
        return publishAnchorSubscriptions(currentSubs.toList())
    }

    /**
     * Request subscription events for the current user
     */
    fun requestMySubscriptions() {
        val account = _currentAccount.value ?: return
        val accountHex = account.toHexKey() ?: return
        val relays = relayStorageManager.loadInboxRelays(accountHex)
            .mapNotNull { RelayUrlNormalizer.normalizeOrNull(it.url) }
            .map { it.url }
            .toSet()
        if (relays.isNotEmpty()) {
            anchorSubscriptionRepo.setCurrentUser(accountHex)
            anchorSubscriptionRepo.requestSubscriptionsFor(accountHex, relays)
        }
    }
    
    /**
     * Publish a kind:1 reply to a kind:11 topic with I tags and e tags
     * This creates a mesh network reply that spreads across relays while maintaining topic context
     */
    fun publishTopicReply(
        content: String,
        topicId: String,
        topicAuthorPubkey: String,
        hashtags: List<String>,
        relayUrls: Set<String> = emptySet()
    ) {
        val signer = getSignerOrNull() ?: run {
            _toastMessage.value = signerUnavailableMessage()
            return
        }
        val relaySet = relayUrls.ifEmpty { getPublishRelayUrlSet() }
        if (relaySet.isEmpty()) {
            _toastMessage.value = "No relays configured"
            return
        }
        viewModelScope.launch {
            val result = EventPublisher.publish(getApplication(), signer, relaySet, kind = 1, content = content) {
                hashtags.forEach { hashtag -> add(arrayOf("I", "#$hashtag")) }
                add(arrayOf("e", topicId, "", "root"))
                add(arrayOf("p", topicAuthorPubkey))
            }
            when (result) {
                is PublishResult.Success -> _toastMessage.value = "Reply published"
                is PublishResult.Error -> _toastMessage.value = "Reply failed: ${result.message}"
            }
        }
    }
}

// Extension to convert hex string to byte array
private fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
