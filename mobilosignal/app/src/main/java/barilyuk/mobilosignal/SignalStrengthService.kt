package barilyuk.mobilosignal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

    /** Whether this instance holds a [SignalRepository] client, so it releases only its own. */
    private var acquired = false

    /** Last rendered line; identical updates are dropped instead of rebuilding the notification. */
    private var lastContent: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_ICON_BLACK || key == Prefs.KEY_STATUS_BAR_SIM) {
                lastContent = null
                render(SignalRepository.state.value)
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        val started = runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(NO_READING, getString(R.string.notification_single, NO_READING), NO_READING),
            )
        }
        if (started.isFailure) {
            // Android 12+ can refuse, e.g. when the system restarts this sticky service while the
            // app is in the background. Better to stay down than to crash.
            Log.w(TAG, "Not allowed to start in the foreground", started.exceptionOrNull())
            stopSelf()
            return
        }

        SignalRepository.acquire(this)
        acquired = true
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
        if (acquired) {
            acquired = false
            SignalRepository.release()
        }
    }

    private fun simLabel(sim: SimSignal): String = getString(R.string.sim_slot, sim.slotIndex + 1)

    private fun reading(sim: SimSignal): String {
        val value = sim.metrics.dbm?.let { getString(R.string.dbm_value, it.toString()) } ?: NO_READING
        return if (sim.generation == NetworkGeneration.UNKNOWN) {
            value
        } else {
            "$value (${sim.generation.label})"
        }
    }

    private fun render(state: SignalState) {
        // With two SIMs both are listed; the icon can only carry one number, so it follows the
        // SIM picked in the settings, or by default whichever is actually receiving better.
        val collapsed: String
        val expanded: String
        when {
            !state.permissionGranted -> {
                collapsed = getString(R.string.notification_no_permission)
                expanded = collapsed
            }

            state.sims.isEmpty() -> {
                collapsed = getString(R.string.no_sim)
                expanded = collapsed
            }

            state.isDualSim -> {
                collapsed = state.sims.joinToString(" | ") { "${simLabel(it)}: ${reading(it)}" }
                expanded = getString(R.string.notification_title) + "\n" +
                    state.sims.joinToString("\n") { "  ${simLabel(it)}: ${reading(it)}" }
            }

            else -> {
                val sim = state.sims.first()
                collapsed = reading(sim)
                expanded = getString(R.string.notification_single, reading(sim))
            }
        }

        if (collapsed == lastContent) return
        lastContent = collapsed

        val iconSim = Prefs.statusBarSlot(this)?.let { state.slot(it) } ?: state.strongest
        val iconText = iconSim?.metrics?.dbm?.toString() ?: NO_READING
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(collapsed, expanded, iconText))
    }

    private fun buildNotification(
        collapsedText: String,
        expandedText: String,
        iconText: String,
    ): Notification {
        val textColor = if (Prefs.isIconTextBlack(this)) Color.BLACK else Color.WHITE

        val collapsed = RemoteViews(packageName, R.layout.notification).apply {
            setTextViewText(R.id.notification_text, "📶 $collapsedText")
        }
        val expanded = RemoteViews(packageName, R.layout.notification_expanded).apply {
            setTextViewText(R.id.notification_text_expanded, "📶 $expandedText")
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
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                // Android 12+ otherwise holds a foreground service's notification back for up to
                // ten seconds, which for a status bar reading is just a missing reading.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
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

        /**
         * Whether the service has any reason to run: the user wants the notification, the SIMs
         * can be read, and the notification can actually be shown. Without the last one it would
         * run with nothing to show for it, which on Android 13+ is the default until the
         * notification permission is granted.
         */
        fun shouldRun(context: Context): Boolean =
            Prefs.isPersistentNotificationEnabled(context) &&
                hasPhoneStatePermission(context) &&
                canShowNotification(context)

        fun canShowNotification(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled()

        /** Starts the service if [shouldRun], and stops it otherwise. */
        fun sync(context: Context) {
            if (shouldRun(context)) start(context) else stop(context)
        }

        /** Starts the service if [shouldRun]; never stops it. Used at boot. */
        fun startIfWanted(context: Context) {
            if (shouldRun(context)) start(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignalStrengthService::class.java))
        }

        private fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, SignalStrengthService::class.java))
            }.onFailure { Log.w(TAG, "Cannot start the service", it) }
        }

        private fun hasPhoneStatePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
    }
}
