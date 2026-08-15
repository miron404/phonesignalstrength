package barilyuk.mobilosignal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import barilyuk.mobilosignal.databinding.ActivityMainBinding
import barilyuk.mobilosignal.databinding.ViewMetricBinding
import barilyuk.mobilosignal.databinding.ViewSimCardBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Tracked so it can be dismissed in onDestroy instead of leaking the window. */
    private var permissionDialog: AlertDialog? = null

    private var dynamicColourApplied = false


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
        dynamicColourApplied = Theming.apply(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

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
        if (Theming.isEnabled(this) != dynamicColourApplied) {
            recreate()
            return
        }
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

    /** targetSdk 35 means the window is edge to edge, so the bars no longer reserve space. */
    private fun applyInsets() {
        val base = resources.getDimensionPixelSize(R.dimen.screen_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                base + bars.left,
                base + bars.top,
                base + bars.right,
                base + bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpControls() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

    }

    private fun render(state: SignalState) {
        renderSim(binding.sim1Card, R.string.sim_1, state.slot(0))
        renderSim(binding.sim2Card, R.string.sim_2, state.slot(1))
        binding.noSimText.visibility = if (state.sims.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderSim(card: ViewSimCardBinding, @StringRes slotName: Int, sim: SimSignal?) {
        card.root.visibility = if (sim == null) View.GONE else View.VISIBLE
        if (sim == null) return

        card.simTitle.text = getString(R.string.sim_title, getString(slotName), sim.operatorName)
        card.dbmValue.text = getString(R.string.dbm_value, sim.metrics.dbm?.toString() ?: "--")
        card.signalBar.setReading(sim.metrics.dbm, sim.generation)
        renderMetrics(card.metricsRow, sim.metrics.extras)

        val colour = ContextCompat.getColor(this, sim.generation.colorRes())
        card.generationBadge.text = sim.generation.label
        card.generationBadge.setTextColor(colour)
        // A tonal chip: the same hue at low alpha behind it reads correctly in both themes,
        // where a solid fill would need a different text colour for each.
        card.generationBadge.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(colour, BADGE_FILL_ALPHA))
    }

    /**
     * The set of metrics changes only when the technology does, but the readings themselves
     * update constantly, so the rows are rebuilt only when the content actually differs.
     */
    private fun renderMetrics(row: LinearLayout, metrics: List<Metric>) {
        if (row.getTag(R.id.metricsRow) == metrics) return
        row.setTag(R.id.metricsRow, metrics)

        row.removeAllViews()
        row.visibility = if (metrics.isEmpty()) View.GONE else View.VISIBLE
        for (metric in metrics) {
            val item = ViewMetricBinding.inflate(layoutInflater, row, false)
            item.metricLabel.text = metric.label
            item.metricValue.text = metric.value
            row.addView(item.root)
        }
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

    private companion object {
        /** Alpha of the generation chip's fill, over which the same hue is drawn as text. */
        const val BADGE_FILL_ALPHA = 0x33
    }
}
