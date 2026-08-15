package barilyuk.mobilosignal

import android.content.Intent
import android.os.Bundle
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Must be set before the screen is inflated: androidx.preference writes to the
            // default preferences file otherwise, and the service would never see the changes.
            preferenceManager.sharedPreferencesName = Prefs.FILE
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<SwitchPreferenceCompat>(Prefs.KEY_PERSISTENT_NOTIFICATION)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    applyNotificationSetting(newValue as Boolean)
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

        /**
         * A foreground service cannot exist without a notification, so "off" means the service
         * stops entirely and the reading is only available while the app is open.
         */
        private fun applyNotificationSetting(enabled: Boolean) {
            val context = context ?: return
            val intent = Intent(context, SignalStrengthService::class.java)
            if (enabled) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }

        private fun killApp() {
            val context = context ?: return
            context.stopService(Intent(context, SignalStrengthService::class.java))
            activity?.finishAffinity()
        }

        private companion object {
            const val KEY_KILL_APP = "kill_app"
        }
    }
}
