package barilyuk.mobilosignal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import barilyuk.mobilosignal.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Tracked so it can be dismissed in onDestroy instead of leaking the window. */
    private var permissionDialog: AlertDialog? = null

    /** The views making up one SIM's row, so both rows can share the rendering code. */
    private class SimViews(
        val label: View,
        val operatorName: TextView,
        val signalText: TextView,
        val bar: SignalBarView,
    )

    private lateinit var sim1Views: SimViews
    private lateinit var sim2Views: SimViews

    private val requestPhoneState =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SignalRepository.refresh()
                if (Prefs.isPersistentNotificationEnabled(this)) startSignalService()
            } else {
                Toast.makeText(this, "Permission denied, cannot read SIM signal", Toast.LENGTH_LONG)
                    .show()
            }
            // Only now, once the first dialog is gone: Android drops a permission request that
            // arrives while another one is still on screen.
            ensureNotificationPermission()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showNotificationRationale()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sim1Views = SimViews(
            label = binding.sim1OperatorLabel,
            operatorName = binding.sim1OperatorName,
            signalText = binding.tvSim1Signal,
            bar = binding.sim1Bar,
        )
        sim2Views = SimViews(
            label = binding.sim2OperatorLabel,
            operatorName = binding.sim2OperatorName,
            signalText = binding.tvSim2Signal,
            bar = binding.sim2Bar,
        )

        SignalRepository.init(applicationContext)
        setUpControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SignalRepository.state.collect { render(it) }
            }
        }

        if (hasPhoneStatePermission()) {
            ensureNotificationPermission()
        } else {
            requestPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    override fun onStart() {
        super.onStart()
        SignalRepository.acquire(this)
    }

    override fun onResume() {
        super.onResume()
        // With the notification switched off there is no service at all; the reading comes
        // straight from the repository while this screen is open.
        if (hasPhoneStatePermission() && Prefs.isPersistentNotificationEnabled(this)) {
            startSignalService()
        }
    }

    override fun onStop() {
        SignalRepository.release()
        super.onStop()
    }

    override fun onDestroy() {
        permissionDialog?.dismiss()
        permissionDialog = null
        super.onDestroy()
    }

    private fun setUpControls() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.exitButton.setOnClickListener {
            stopService(Intent(this, SignalStrengthService::class.java))
            finishAndRemoveTask()
        }
    }

    private fun render(state: SignalState) {
        renderSim(sim1Views, state.slot(0))
        renderSim(sim2Views, state.slot(1))
        binding.noSimText.visibility = if (state.sims.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderSim(views: SimViews, sim: SimSignal?) {
        val visibility = if (sim == null) View.GONE else View.VISIBLE
        views.label.visibility = visibility
        views.operatorName.visibility = visibility
        views.signalText.visibility = visibility
        views.bar.visibility = visibility
        if (sim == null) return

        val dbm = sim.metrics.dbm
        val value = dbm?.toString() ?: "--"

        views.operatorName.text = sim.operatorName
        views.signalText.text = "📶 $value dBm ${sim.generation.label}"
        views.signalText.setTextColor(ContextCompat.getColor(this, sim.generation.colorRes()))
        views.bar.setReading(dbm, sim.generation)
    }

    /** Green for 4G/5G, amber for 3G, red for 2G and no service. */
    @ColorRes
    private fun NetworkGeneration.colorRes(): Int = when (this) {
        NetworkGeneration.G4, NetworkGeneration.G5 -> R.color.generation_good
        NetworkGeneration.G3 -> R.color.generation_fair
        NetworkGeneration.G2, NetworkGeneration.UNKNOWN -> R.color.generation_poor
    }

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        // Nothing to explain while the permission is in place; the old build showed its dialog
        // unconditionally on every single launch.
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showNotificationRationale() {
        if (isFinishing) return
        permissionDialog?.dismiss()
        permissionDialog = AlertDialog.Builder(this)
            .setTitle("Notification permission required")
            .setMessage(
                "MobiloSignal's key feature — signal strength in the status bar — needs the " +
                    "notification permission. The app still works without it, but the reading " +
                    "will only be visible while this screen is open."
            )
            .setPositiveButton("Open settings") { _, _ -> openNotificationSettings() }
            .setNegativeButton("Later", null)
            .create()
            .also { it.show() }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                )
            }
        }
    }

    private fun startSignalService() {
        startForegroundService(Intent(this, SignalStrengthService::class.java))
    }
}
