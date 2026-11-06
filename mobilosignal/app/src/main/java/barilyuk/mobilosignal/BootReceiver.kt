package barilyuk.mobilosignal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the notification back after a reboot and after the app has been updated. An update kills
 * the running service and nothing restarts it, so without the second case the reading would
 * silently disappear until the app is next opened. Both broadcasts are on Android's list of
 * exemptions from the background foreground-service start restrictions.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Prefs.isAutoStartEnabled(context)) {
            Log.d(TAG, "Auto start disabled, ignoring ${intent.action}")
            return
        }

        Log.d(TAG, "Auto start enabled, starting SignalStrengthService after ${intent.action}")
        SignalStrengthService.startIfWanted(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
