package barilyuk.mobilosignal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the ongoing status bar notification in sync with [SignalRepository].
 *
 * It no longer talks to the telephony APIs itself; it is one of the repository's clients.
 */
class SignalStrengthService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** Last rendered line; identical updates are dropped instead of rebuilding the notification. */
    private var lastContent: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_ICON_BLACK) {
                lastContent = null
                render(SignalRepository.state.value)
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(placeholderContent(), NO_READING))

        SignalRepository.acquire(this)
        Prefs.of(this).registerOnSharedPreferenceChangeListener(prefsListener)

        scope.launch {
            SignalRepository.state.collect { render(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        scope.cancel()
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        SignalRepository.release()
    }

    private fun placeholderContent(): String =
        "$NO_READING (${NetworkGeneration.UNKNOWN.label})"

    private fun render(state: SignalState) {
        val sim = state.selected
        val dbm = sim?.metrics?.dbm
        val generation = sim?.generation ?: NetworkGeneration.UNKNOWN
        val simSuffix = if (state.isDualSim && sim != null) " (SIM${sim.slotIndex + 1})" else ""

        val value = if (dbm == null) NO_READING else "$dbm dBm"
        val content = "$value (${generation.label})$simSuffix"
        if (content == lastContent) return
        lastContent = content

        val iconText = dbm?.toString() ?: NO_READING
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(content, iconText))
    }

    private fun buildNotification(content: String, iconText: String): Notification {
        val textColor = if (Prefs.isIconTextBlack(this)) Color.BLACK else Color.WHITE

        val collapsed = RemoteViews(packageName, R.layout.notification).apply {
            setTextViewText(R.id.notification_text, "📶 $content")
        }
        val expanded = RemoteViews(packageName, R.layout.notification_expanded).apply {
            setTextViewText(R.id.notification_text_expanded, "📶 Signal strength: $content")
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(Icon.createWithBitmap(StatusBarIcon.render(iconText, textColor)))
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Signal Strength Monitoring",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows current signal strength"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SignalStrengthService"
        private const val CHANNEL_ID = "SignalStrengthChannel"
        private const val NOTIFICATION_ID = 1
        private const val NO_READING = "--"
    }
}
