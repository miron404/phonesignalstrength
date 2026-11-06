package barilyuk.mobilosignal

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import barilyuk.mobilosignal.databinding.ActivitySettingsBinding
import com.google.android.material.color.DynamicColors

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var dynamicColourApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dynamicColourApplied = Theming.apply(this)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Theming.isEnabled(this) != dynamicColourApplied) recreate()
    }

    /** targetSdk 35 means the window is edge to edge, so the bars no longer reserve space. */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.settingsContainer.updatePadding(bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val requestNotifications =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                val context = context ?: return@registerForActivityResult
                // No rationale left to show means Android did not even ask: the permission is
                // denied for good, and only the system settings can still change that.
                if (!granted &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    SystemSettings.openNotificationSettings(context)
                }
                syncNotification()
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Must be set before the screen is inflated: androidx.preference writes to the
            // default preferences file otherwise, and the service would never see the changes.
            preferenceManager.sharedPreferencesName = Prefs.FILE
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<SwitchPreferenceCompat>(Prefs.KEY_PERSISTENT_NOTIFICATION)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    // Posted: the new value is only persisted once this listener returns, and
                    // the service decides from the stored value.
                    view?.post { onNotificationSettingChanged(newValue as Boolean) }
                    true
                }

            findPreference<Preference>(KEY_ALLOW_NOTIFICATIONS)?.setOnPreferenceClickListener {
                askForNotifications()
                true
            }

            findPreference<SwitchPreferenceCompat>(Prefs.KEY_DYNAMIC_COLOUR)?.apply {
                // Wallpaper based colours are an Android 12 feature; hide the switch where it
                // would do nothing at all.
                isVisible = DynamicColors.isDynamicColorAvailable()
                setOnPreferenceChangeListener { _, _ ->
                    // Posted so the new value is persisted before the activity is rebuilt.
                    view?.post { activity?.recreate() }
                    true
                }
            }

            findPreference<Preference>(KEY_KILL_APP)?.setOnPreferenceClickListener {
                killApp()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // The user may be back from the system settings with notifications allowed.
            syncNotification()
        }

        /**
         * A foreground service cannot exist without a notification, so "off" means the service
         * stops entirely and the reading is only available while the app is open.
         */
        private fun onNotificationSettingChanged(enabled: Boolean) {
            val context = context ?: return
            if (enabled && !SignalStrengthService.canShowNotification(context)) {
                askForNotifications()
            }
            syncNotification()
        }

        private fun askForNotifications() {
            val context = context ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Before Android 13 there is no permission to ask for; notifications can only
                // have been blocked in the system settings.
                SystemSettings.openNotificationSettings(context)
            }
        }

        /** Starts or stops the service, and says so when notifications are blocked. */
        private fun syncNotification() {
            val context = context ?: return
            SignalStrengthService.sync(context)
            findPreference<Preference>(KEY_ALLOW_NOTIFICATIONS)?.isVisible =
                Prefs.isPersistentNotificationEnabled(context) &&
                !SignalStrengthService.canShowNotification(context)
        }

        private fun killApp() {
            val context = context ?: return
            SignalStrengthService.stop(context)
            activity?.finishAffinity()
        }

        private companion object {
            const val KEY_KILL_APP = "kill_app"
            const val KEY_ALLOW_NOTIFICATIONS = "allow_notifications"
        }
    }
}
