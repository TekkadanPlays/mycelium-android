package social.mycelium.android.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

/**
 * BroadcastReceiver that kills the app process when triggered from
 * the foreground service notification "Stop Mycelium" action.
 * Also used from Power settings "Force stop" button.
 */
class KillAppReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_KILL_APP = "social.mycelium.android.KILL_APP"
        private const val TAG = "KillAppReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_KILL_APP) return
        Log.d(TAG, "Kill app requested — stopping service and killing process")
        // Stop the foreground service first
        context.stopService(Intent(context, RelayForegroundService::class.java))
        // Kill the process — Android will allow the user to relaunch from launcher
        Process.killProcess(Process.myPid())
    }
}
