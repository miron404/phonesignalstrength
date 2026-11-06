package barilyuk.mobilosignal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
                SignalStrengthService.sync(this)
            }
            // A refusal needs no toast: the screen itself now says what is missing.
            // Only now, once the first dialog is gone: Android drops a permission request that
            // arrives while another one is still on screen.
            maybeRequestNotifications()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) SignalStrengthService.sync(this) else showNotificationRationale()
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

        // Only on a fresh start. After a rotation a request may still be on screen, and asking
        // again would make Android answer the duplicate with an immediate refusal.
        if (savedInstanceState == null) {
            if (hasPhoneStatePermission()) {
                maybeRequestNotifications()
            } else if (!isPhonePermissionDeniedForGood()) {
                requestPhonePermission()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        SignalRepository.acquire(this)
        // Picks up a permission granted from the system settings while the app was in the
        // background. Does nothing if the SIMs are unchanged.
        SignalRepository.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (Theming.isEnabled(this) != dynamicColourApplied) {
            recreate()
            return
        }
        // Starts the notification if it is wanted and possible, stops it if it no longer is (for
        // example because notifications were blocked in the system settings). With it off there
        // is no service at all; the reading comes straight from the repository while this
        // screen is open.
        SignalStrengthService.sync(this)
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
        binding.grantPermissionButton.setOnClickListener { onGrantPhonePermission() }
    }

    private fun render(state: SignalState) {
        binding.permissionCard.isVisible = !state.permissionGranted
        renderSim(binding.sim1Card, state.slot(0))
        renderSim(binding.sim2Card, state.slot(1))
        // Without the permission the list is empty too, but "no SIM" would be untrue.
        binding.noSimText.isVisible = state.permissionGranted && state.sims.isEmpty()
    }

    private fun renderSim(card: ViewSimCardBinding, sim: SimSignal?) {
        card.root.isVisible = sim != null
        if (sim == null) return

        val slotName = getString(R.string.sim_slot, sim.slotIndex + 1)
        card.simTitle.text = getString(R.string.sim_title, slotName, sim.operatorName)
        card.dbmValue.text = getString(R.string.dbm_value, sim.metrics.dbm?.toString() ?: "--")
        card.signalBar.setReading(sim.metrics.dbm, sim.generation)
        renderMetrics(card.metricsRow, sim.metrics.extras)

        // No network type and nothing to infer one from: a red chip with a dash would only alarm.
        card.generationBadge.isVisible = sim.generation != NetworkGeneration.UNKNOWN
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
        row.isVisible = metrics.isNotEmpty()
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

    /**
     * Android answers a request for a permission denied for good with an instant refusal and no
     * dialog, and reports "never asked" and "denied for good" the same way. The flag tells them
     * apart.
     */
    private fun isPhonePermissionDeniedForGood(): Boolean =
        Prefs.wasPhonePermissionAsked(this) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)

    private fun requestPhonePermission() {
        Prefs.setPhonePermissionAsked(this)
        requestPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun onGrantPhonePermission() {
        if (isPhonePermissionDeniedForGood()) {
            SystemSettings.openAppDetails(this)
        } else {
            requestPhonePermission()
        }
    }

    /**
     * Asks once. A refusal is explained once, after which the app stops asking on launch: from
     * then on, turning the notification on in the settings screen is where it gets allowed.
     */
    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // Nothing to ask for if the user has switched the notification off.
        if (!Prefs.isPersistentNotificationEnabled(this)) return
        if (Prefs.wasNotificationPromptShown(this)) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showNotificationRationale() {
        if (isFinishing || Prefs.wasNotificationPromptShown(this)) return
        Prefs.setNotificationPromptShown(this)
        permissionDialog?.dismiss()
        permissionDialog = AlertDialog.Builder(this)
            .setTitle(R.string.notification_rationale_title)
            .setMessage(R.string.notification_rationale_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                SystemSettings.openNotificationSettings(this)
            }
            .setNegativeButton(R.string.not_now, null)
            .create()
            .also { it.show() }
    }

    private companion object {
        /** Alpha of the generation chip's fill, over which the same hue is drawn as text. */
        const val BADGE_FILL_ALPHA = 0x33
    }
}
