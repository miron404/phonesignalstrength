package barilyuk.mobilosignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isAutoStartEnabled(context)) {
            Log.d(TAG, "Auto start disabled, ignoring boot")
            return
        }

        Log.d(TAG, "Auto start enabled, starting SignalStrengthService")
        runCatching {
            context.startForegroundService(Intent(context, SignalStrengthService::class.java))
        }.onFailure { Log.e(TAG, "Failed to start service on boot", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
