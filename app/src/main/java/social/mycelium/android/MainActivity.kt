package social.mycelium.android

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.repository.ProfileMetadataCache
import social.mycelium.android.repository.TopicsRepository
import social.mycelium.android.repository.ScopedModerationRepository
import social.mycelium.android.relay.NetworkConnectivityMonitor
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.services.RelayForegroundService
import social.mycelium.android.ui.navigation.MyceliumNavigation
import social.mycelium.android.ui.theme.MyceliumTheme
import social.mycelium.android.BuildConfig
import social.mycelium.android.utils.AppMemoryTrimmer
import social.mycelium.android.viewmodel.AppViewModel
import social.mycelium.android.viewmodel.AccountStateViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.Manifest

/**
 * Main Activity for Mycelium Android app.
 *
 * This activity uses proper Jetpack Navigation Compose for state management,
 * allowing infinite exploration through feeds, threads, and profiles with
 * full navigation history preservation (like Primal app).
 * Implements ComponentCallbacks2 to release memory on trim events.
 */
class MainActivity : ComponentActivity(), ComponentCallbacks2 {

    // Activity result launcher for Amber login
    private val amberLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onAmberLoginResult?.invoke(result.resultCode, result.data)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStartAfterPermission && isInForeground) {
            pendingStartAfterPermission = false
            startRelayForegroundService()
        } else {
            pendingStartAfterPermission = false
            android.util.Log.w("MainActivity", "Notification permission denied; skipping foreground service")
        }
    }

    // Callback to handle login result
    private var onAmberLoginResult: ((Int, Intent?) -> Unit)? = null
    private var shouldRunRelayService = false
    private var pendingStartAfterPermission = false
    private var networkMonitor: NetworkConnectivityMonitor? = null
    private var isInForeground = false
    private lateinit var accountStateViewModel: AccountStateViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detect main-thread disk/network violations in debug to avoid ANR regressions
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // Standard edge-to-edge: system bars visible, content draws behind them
        enableEdgeToEdge()

        // Memory trim is handled by the onTrimMemory override directly — do NOT
        // registerComponentCallbacks(this) because it causes infinite recursion in
        // onConfigurationChanged on Android 15 foldable devices.

        // Configure Coil with GIF decoder, optimized caching, and crossfade for smooth feed rendering
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .crossfade(100)
                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(this)
                        .maxSizePercent(0.25) // 25% of available app memory
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(150L * 1024 * 1024) // 150 MB disk cache
                        .build()
                }
                .build()
        )

        // Initialize relay health tracker (loads persisted blocklist before any connections)
        social.mycelium.android.relay.RelayHealthTracker.init(applicationContext)

        // Persist profile cache so avatars/display names survive process death; restore before feed
        ProfileMetadataCache.getInstance().init(applicationContext)

        // Persist feed so notes survive process death; restore on cold start
        NotesRepository.getInstance().prepareFeedCache(applicationContext)

        // Register kind-11 handler from app start so topics are collected before user opens Topics screen
        TopicsRepository.getInstance(applicationContext)

        // Register kind-1011 handler for NIP-22 scoped moderation events; init persistence
        ScopedModerationRepository.getInstance().init(applicationContext)
        
        // Initialize anchor subscription repository for kind:30073 events
        social.mycelium.android.repository.AnchorSubscriptionRepository.getInstance()

        // Register kind-30311 handler for NIP-53 live activities from app start
        social.mycelium.android.repository.LiveActivityRepository.getInstance()

        // Start network connectivity monitor to detect WiFi/cellular switches and trigger relay reconnection
        networkMonitor = NetworkConnectivityMonitor(applicationContext).also { it.start() }

        // Relay discovery — always init (restores disk cache), but only prefetch from
        // network at launch if the user opted in OR an account is already signed in.
        social.mycelium.android.repository.Nip66RelayDiscoveryRepository.init(applicationContext)
        val settingsPrefs = getSharedPreferences("Mycelium_settings", MODE_PRIVATE)
        val prefetchEnabled = settingsPrefs.getBoolean("relay_discovery_prefetch", false)
        val hasAccount = getSharedPreferences("Mycelium_accounts", MODE_PRIVATE)
            .getString("all_accounts_json", null)?.isNotBlank() == true
        if (prefetchEnabled || hasAccount) {
            social.mycelium.android.repository.Nip66RelayDiscoveryRepository.fetchRelayDiscovery()
        }

        // Re-apply relay subscription when app is resumed (e.g. after screen lock) so connection and notes resume
        // Also handle PiP pause/resume on app lifecycle
        // Also refresh NIP-66 if stale (>6h) on resume — non-blocking background fetch
        accountStateViewModel = ViewModelProvider(this)[AccountStateViewModel::class.java]

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // Only reconnect relays if onboarding is complete — prevents premature connections during login flow
                    if (accountStateViewModel.onboardingComplete.value) {
                        RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
                    }
                    social.mycelium.android.ui.components.PipStreamManager.resumeIfActive()
                    social.mycelium.android.repository.Nip66RelayDiscoveryRepository.refreshIfStale()
                }
                Lifecycle.Event.ON_STOP -> {
                    // Pause all inline video players when app goes to background
                    social.mycelium.android.ui.components.SharedPlayerPool.pauseAll()
                    if (!social.mycelium.android.ui.components.PipStreamManager.continueInBackground.value) {
                        social.mycelium.android.ui.components.PipStreamManager.pauseIfActive()
                    }
                }
                else -> {}
            }
        })

        // Initialize theme preferences (SharedPreferences-backed, must happen before setContent)
        social.mycelium.android.ui.theme.ThemePreferences.init(applicationContext)
        social.mycelium.android.ui.settings.MediaPreferences.init(applicationContext)
        social.mycelium.android.ui.settings.NotificationPreferences.init(applicationContext)

        // Create all notification channels (relay service, social, DMs) — idempotent
        social.mycelium.android.services.NotificationChannelManager.createChannels(applicationContext)

        // Initialize NotificationsRepository with context for Android push notifications
        social.mycelium.android.repository.NotificationsRepository.init(applicationContext)

        setContent {
            MyceliumTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val appViewModel: AppViewModel = viewModel()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

                    // Set up login result handler
                    onAmberLoginResult = { resultCode, data ->
                        accountStateViewModel.handleAmberLoginResult(resultCode, data)
                    }

                    // Use Jetpack Navigation for proper backstack management
                    MyceliumNavigation(
                        appViewModel = appViewModel,
                        accountStateViewModel = accountStateViewModel,
                        onAmberLogin = { intent -> amberLoginLauncher.launch(intent) }
                    )

                    // Start/stop foreground service based on auth state
                    LaunchedEffect(currentAccount) {
                        setRelayServiceEnabled(currentAccount != null)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (::accountStateViewModel.isInitialized) {
            accountStateViewModel.setAmberActivityContext(this)
        }
        if (shouldRunRelayService) {
            maybeStartRelayForegroundService()
        }
        // Start keepalive health check to detect stale WebSocket connections
        RelayConnectionStateMachine.getInstance().startKeepalive()
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

    override fun onDestroy() {
        if (::accountStateViewModel.isInitialized) {
            accountStateViewModel.clearAmberActivityContext()
        }
        networkMonitor?.stop()
        if (isFinishing) {
            // User explicitly closed the app — stop service and keepalive
            RelayConnectionStateMachine.getInstance().stopKeepalive()
            stopRelayForegroundService()
        }
        // When system reclaims the activity (backgrounding), keep the foreground
        // service running so the process stays alive and relay connections persist.
        super.onDestroy()
    }


    override fun onTrimMemory(level: Int) {
        // Do NOT call super.onTrimMemory — it dispatches to registered ComponentCallbacks
        // which includes 'this' (registered in onCreate), causing infinite recursion / StackOverflow.
        try {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> AppMemoryTrimmer.trimBackgroundCaches(level, this)
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> AppMemoryTrimmer.trimUiCaches(this)
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "onTrimMemory trim failed", e)
        }
    }

    private fun startRelayForegroundService() {
        val intent = Intent(this, RelayForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRelayForegroundService() {
        stopService(Intent(this, RelayForegroundService::class.java))
    }

    private fun setRelayServiceEnabled(enabled: Boolean) {
        shouldRunRelayService = enabled
        if (!enabled) {
            stopRelayForegroundService()
            return
        }
        // Respect user preference — don't start service if background mode is disabled
        if (!social.mycelium.android.ui.settings.NotificationPreferences.backgroundServiceEnabled.value) {
            return
        }
        if (isInForeground) {
            maybeStartRelayForegroundService()
        }
    }

    private fun maybeStartRelayForegroundService() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                pendingStartAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startRelayForegroundService()
    }
}
