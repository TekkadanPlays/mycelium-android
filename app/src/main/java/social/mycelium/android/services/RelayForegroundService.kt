package social.mycelium.android.services

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import social.mycelium.android.relay.RelayConnectionStateMachine
import social.mycelium.android.repository.NotesRepository
import social.mycelium.android.ui.settings.NotificationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service to keep relay connections alive while the app is backgrounded.
 * Shows a persistent notification via [NotificationChannelManager.CHANNEL_RELAY_SERVICE].
 * Respects [NotificationPreferences.backgroundServiceEnabled] — if the user disables
 * background service, the service stops itself.
 */
class RelayForegroundService : Service() {

    companion object {
        private const val TAG = "RelayForegroundService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Ensure all channels exist (idempotent)
        NotificationChannelManager.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Respect user preference — if background service is disabled, stop immediately
        if (!NotificationPreferences.backgroundServiceEnabled.value) {
            Log.d(TAG, "Background service disabled by user, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

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
            Log.w(TAG, "Foreground start blocked: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.w(TAG, "Foreground start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure relay connection is active when the service starts
        RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
        // Start keepalive health check to detect stale WebSocket connections while backgrounded
        RelayConnectionStateMachine.getInstance().startKeepalive()

        // Observe new note counts and update the service notification
        serviceScope.launch {
            NotesRepository.getInstance().newNotesCounts.collectLatest { counts ->
                val followingCount = counts.following
                if (followingCount > 0) {
                    val notification = NotificationChannelManager.buildRelayServiceNotification(
                        this@RelayForegroundService, followingCount
                    )
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NotificationChannelManager.NOTIFICATION_ID_RELAY_SERVICE, notification)
                }
            }
        }

        // Watch for user disabling background service at runtime
        serviceScope.launch {
            NotificationPreferences.backgroundServiceEnabled.collectLatest { enabled ->
                if (!enabled) {
                    Log.d(TAG, "Background service disabled at runtime, stopping")
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
