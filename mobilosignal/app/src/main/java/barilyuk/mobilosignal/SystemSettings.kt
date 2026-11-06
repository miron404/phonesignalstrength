package barilyuk.mobilosignal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Deep links into the system settings, with a fallback for OEM builds that lack a screen. */
object SystemSettings {

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        runCatching { context.startActivity(intent) }
    }
}
