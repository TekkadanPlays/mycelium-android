package social.mycelium.android.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import social.mycelium.android.debug.MLog
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.repository.feed.NotesRepository
import social.mycelium.android.ui.settings.NotificationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import social.mycelium.android.ui.settings.ConnectionMode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service to keep relay connections alive while the app is backgrounded.
 * Shows a persistent notification via [NotificationChannelManager.CHANNEL_RELAY_SERVICE].
 * Respects [NotificationPreferences.connectionMode] — only runs when mode is [ConnectionMode.ALWAYS_ON].
 * Stops itself if the user switches to a different mode.
 */
class RelayForegroundService : Service() {

    companion object {
        private const val TAG = "RelayForegroundService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile private var initialized = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Ensure all channels exist (idempotent)
        NotificationChannelManager.createChannels(this)
        // Acquire a partial wake lock to prevent the CPU from sleeping.
        // Without this, the OS freezes the process as oom_cached and
        // WebSocket connections go silent — no events, no notifications.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mycelium::RelayService").apply {
            acquire()
        }
        MLog.d(TAG, "Wake lock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Respect user preference — only run in ALWAYS_ON mode
        if (!NotificationPreferences.backgroundServiceEnabled) {
            MLog.d(TAG, "Connection mode is not ALWAYS_ON, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // startForeground is idempotent — safe to call on every onStartCommand
        try {
            val notification = NotificationChannelManager.buildRelayServiceNotification(this, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NotificationChannelManager.NOTIFICATION_ID_RELAY_SERVICE,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NotificationChannelManager.NOTIFICATION_ID_RELAY_SERVICE, notification)
            }
        } catch (e: SecurityException) {
            MLog.w(TAG, "Foreground start blocked: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            MLog.w(TAG, "Foreground start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // These are safe to call multiple times (idempotent / cancel-and-restart)
        RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
        RelayConnectionStateMachine.getInstance().startKeepalive()

        // Only launch coroutines once — duplicate startForegroundService() calls
        // (e.g. from onResume + applyConnectionModeScheduling) must not spawn extra collectors.
        if (initialized) {
            MLog.d(TAG, "Foreground service already initialized, skipping coroutine setup")
            return START_NOT_STICKY
        }
        initialized = true
        MLog.d(TAG, "Foreground service started (ALWAYS_ON mode)")

        // Enable Android push notifications after relay replay settles.
        // startSubscription() resets the push gate to Long.MAX_VALUE to suppress
        // historical replay. We must re-enable it here so events arriving while
        // the app is backgrounded actually fire Android notifications.
        serviceScope.launch {
            kotlinx.coroutines.delay(12_000L)
            // Enable push for ALL loaded accounts (active + background)
            val scopes = social.mycelium.android.repository.sync.AccountScopedRegistry.allScopes.value
            for ((pubkey, scope) in scopes) {
                if (scope.initialized) {
                    scope.notificationsRepository.enableAndroidNotifications()
                    MLog.d(TAG, "Push notifications enabled for account ${pubkey.take(8)}")
                }
            }
            MLog.d(TAG, "Push notifications enabled from foreground service (${scopes.size} accounts)")
        }

        // Observe new note counts and update the service notification.
        // When count drops to 0 (feed refreshed), reset notification to default text.
        serviceScope.launch {
            NotesRepository.getInstance().newNotesCounts.collectLatest { counts ->
                val followingCount = counts.following
                val notification = NotificationChannelManager.buildRelayServiceNotification(
                    this@RelayForegroundService, followingCount
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NotificationChannelManager.NOTIFICATION_ID_RELAY_SERVICE, notification)
            }
        }

        // Watch for user switching away from ALWAYS_ON mode at runtime
        serviceScope.launch {
            NotificationPreferences.connectionMode.collectLatest { mode ->
                if (mode != ConnectionMode.ALWAYS_ON) {
                    MLog.d(TAG, "Connection mode changed to $mode, stopping service")
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    MLog.d(TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            MLog.w(TAG, "Wake lock release failed: ${e.message}")
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
