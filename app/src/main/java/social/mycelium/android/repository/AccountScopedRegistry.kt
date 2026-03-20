package social.mycelium.android.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-account state container. Each logged-in account gets its own [AccountScope]
 * holding independent repository instances and a dedicated [CoroutineScope].
 *
 * When an account is removed, its scope is cancelled and all repositories are torn down.
 */
class AccountScope(
    val pubkeyHex: String,
    val scope: CoroutineScope,
) {
    // ── Per-account repositories ────────────────────────────────────────────
    // Instantiated lazily on first access so we don't allocate for accounts
    // that are saved but never activated in the current session.

    lateinit var notificationsRepository: NotificationsRepository
        private set
    // NoteCountsRepository, NotesRepository, and other repos will be scoped incrementally.
    // For now, only NotificationsRepository is per-account.

    /** Whether this scope's repositories have been initialized. */
    var initialized: Boolean = false
        private set

    /**
     * Initialize per-account repositories. Call once after creating the scope
     * or when switching to this account.
     */
    fun initialize(appContext: Context) {
        if (initialized) return
        notificationsRepository = NotificationsRepository(pubkeyHex, scope)
        notificationsRepository.init(appContext)
        initialized = true
        Log.d(TAG, "AccountScope initialized for ${pubkeyHex.take(8)}")
    }

    /**
     * Tear down all repositories and cancel the coroutine scope.
     */
    fun destroy() {
        if (initialized) {
            notificationsRepository.stopSubscription()
            // noteCountsRepository has no lifecycle to stop
        }
        scope.cancel("AccountScope destroyed for ${pubkeyHex.take(8)}")
        Log.d(TAG, "AccountScope destroyed for ${pubkeyHex.take(8)}")
    }

    companion object {
        private const val TAG = "AccountScope"
    }
}

/**
 * Registry that manages [AccountScope] instances for all logged-in accounts.
 *
 * ## Design
 * - **Active account**: The account currently displayed in the UI. Its feed,
 *   notifications, and all UI-bound state are visible.
 * - **Background accounts**: Accounts that maintain notification subscriptions
 *   so badge counts stay current, but do NOT have an active feed.
 *
 * ## Lifecycle
 * - Call [getOrCreateScope] when an account logs in or is restored.
 * - Call [setActiveAccount] when the user switches accounts.
 * - Call [removeScope] when an account is logged out.
 * - Call [clear] on app termination.
 *
 * ## Thread safety
 * All mutations are synchronized on [lock]. StateFlows are used for UI observation.
 */
object AccountScopedRegistry {

    private const val TAG = "AccountScopedRegistry"

    private val lock = Any()

    /** All loaded account scopes, keyed by hex pubkey. */
    private val scopes = mutableMapOf<String, AccountScope>()

    /** The currently active (foreground) account's hex pubkey. */
    private val _activeAccountPubkey = MutableStateFlow<String?>(null)
    val activeAccountPubkey: StateFlow<String?> = _activeAccountPubkey.asStateFlow()

    /** Observable map of all account scopes for UI (e.g. account switcher badge counts). */
    private val _allScopes = MutableStateFlow<Map<String, AccountScope>>(emptyMap())
    val allScopes: StateFlow<Map<String, AccountScope>> = _allScopes.asStateFlow()

    /**
     * Get or create an [AccountScope] for the given pubkey.
     * The scope is created with a dedicated [CoroutineScope] on [Dispatchers.IO].
     */
    fun getOrCreateScope(pubkeyHex: String, appContext: Context): AccountScope {
        synchronized(lock) {
            val existing = scopes[pubkeyHex]
            if (existing != null) {
                if (!existing.initialized) {
                    existing.initialize(appContext)
                }
                return existing
            }
            val scope = AccountScope(
                pubkeyHex = pubkeyHex,
                scope = CoroutineScope(
                    Dispatchers.IO + SupervisorJob() +
                        CoroutineExceptionHandler { _, throwable ->
                            Log.e(TAG, "Account ${pubkeyHex.take(8)} caught: ${throwable.message}", throwable)
                        }
                ),
            )
            scope.initialize(appContext)
            scopes[pubkeyHex] = scope
            _allScopes.value = scopes.toMap()
            Log.d(TAG, "Created scope for ${pubkeyHex.take(8)} (total: ${scopes.size})")
            return scope
        }
    }

    /**
     * Get the scope for a pubkey without creating one. Returns null if not loaded.
     */
    fun getScope(pubkeyHex: String): AccountScope? {
        synchronized(lock) {
            return scopes[pubkeyHex]
        }
    }

    /**
     * Get the currently active account's scope. Returns null if no account is active.
     */
    fun getActiveScope(): AccountScope? {
        val pubkey = _activeAccountPubkey.value ?: return null
        return getScope(pubkey)
    }

    /**
     * Set the active (foreground) account. This is the account whose feed and
     * notifications are displayed in the UI.
     */
    fun setActiveAccount(pubkeyHex: String?) {
        _activeAccountPubkey.value = pubkeyHex
        Log.d(TAG, "Active account set to ${pubkeyHex?.take(8) ?: "null"}")
    }

    /**
     * Remove and destroy the scope for a pubkey (account logout).
     */
    fun removeScope(pubkeyHex: String) {
        synchronized(lock) {
            val scope = scopes.remove(pubkeyHex)
            scope?.destroy()
            _allScopes.value = scopes.toMap()
            if (_activeAccountPubkey.value == pubkeyHex) {
                _activeAccountPubkey.value = null
            }
            Log.d(TAG, "Removed scope for ${pubkeyHex.take(8)} (remaining: ${scopes.size})")
        }
    }

    /**
     * Destroy all scopes (app termination or full logout).
     */
    fun clear() {
        synchronized(lock) {
            scopes.values.forEach { it.destroy() }
            scopes.clear()
            _allScopes.value = emptyMap()
            _activeAccountPubkey.value = null
            Log.d(TAG, "All account scopes cleared")
        }
    }

    /**
     * Get notification counts for all accounts (for badge display in account switcher).
     * Returns Map<pubkeyHex, unseenCount>.
     */
    fun getAllNotificationCounts(): Map<String, Int> {
        synchronized(lock) {
            return scopes.mapValues { (_, scope) ->
                if (scope.initialized) scope.notificationsRepository.unseenCount.value else 0
            }
        }
    }
}
