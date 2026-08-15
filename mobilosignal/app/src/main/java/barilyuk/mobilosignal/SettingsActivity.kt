package barilyuk.mobilosignal

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
    }
}
