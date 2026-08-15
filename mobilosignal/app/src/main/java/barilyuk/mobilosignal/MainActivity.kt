package barilyuk.mobilosignal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    /** Guards against the radio group's listener firing while the UI is being updated. */
    private var updatingSimSelection = false

    private val requestPhoneState =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SignalRepository.refresh()
                startSignalService()
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
        if (hasPhoneStatePermission()) startSignalService()
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
        binding.autostartCheckBox.isChecked = Prefs.isAutoStartEnabled(this)
        binding.autostartCheckBox.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAutoStartEnabled(this, isChecked)
        }

        val iconTextBlack = Prefs.isIconTextBlack(this)
        binding.radioBlack.isChecked = iconTextBlack
        binding.radioWhite.isChecked = !iconTextBlack
        binding.textColorRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setIconTextBlack(this, checkedId == R.id.radioBlack)
        }

        binding.simSelectionRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingSimSelection) return@setOnCheckedChangeListener
            SignalRepository.setSelectedSlot(if (checkedId == R.id.radioSim2) 1 else 0)
        }

        binding.exitButton.setOnClickListener {
            stopService(Intent(this, SignalStrengthService::class.java))
            finishAndRemoveTask()
        }
    }

    private fun render(state: SignalState) {
        val sim = state.selected
        val dbm = sim?.metrics?.dbm
        val generation = sim?.generation ?: NetworkGeneration.UNKNOWN

        val value = if (dbm == null) "--" else dbm.toString()
        binding.signalstrengthTextView.text = "📶 $value dBm ${generation.label}"
        binding.dbmValueText.text = "$value dBm"

        updateMarker(dbm)
        updateSimControls(state)
    }

    private fun updateSimControls(state: SignalState) {
        val sim1 = state.hasSlot(0)
        val sim2 = state.hasSlot(1)
        val enabledColor = binding.signalstrengthTextView.currentTextColor
        val disabledColor = ContextCompat.getColor(this, android.R.color.darker_gray)

        binding.radioSim1.isEnabled = sim1
        binding.radioSim1.alpha = if (sim1) 1.0f else 0.5f
        binding.radioSim1.setTextColor(if (sim1) enabledColor else disabledColor)

        binding.radioSim2.isEnabled = sim2
        binding.radioSim2.alpha = if (sim2) 1.0f else 0.5f
        binding.radioSim2.setTextColor(if (sim2) enabledColor else disabledColor)

        // `selected` already falls back to a present SIM if the saved slot is gone.
        val shownSlot = state.selected?.slotIndex ?: state.selectedSlot
        val target = if (shownSlot == 1) R.id.radioSim2 else R.id.radioSim1
        if (binding.simSelectionRadioGroup.checkedRadioButtonId != target) {
            updatingSimSelection = true
            binding.simSelectionRadioGroup.check(target)
            updatingSimSelection = false
        }
    }

    // NOTE: the marker maths below is deliberately left as it was. Both the hardcoded pixel
    // offset and the layout-params juggling disappear once the gradient PNG is replaced by a
    // custom view, so patching them here would only be thrown away.
    private fun updateMarker(dbm: Int?) {
        val position = markerPosition(dbm)

        binding.signalGradientBar.post {
            binding.dbmValueText.measure(
                View.MeasureSpec.UNSPECIFIED,
                View.MeasureSpec.UNSPECIFIED,
            )

            val barWidth = binding.signalGradientBar.width
            val markerWidth = binding.signalMarker.width
            val textWidth = binding.dbmValueText.measuredWidth
            val markerX = (position * (barWidth - markerWidth)).toInt()

            val markerParams = binding.signalMarker.layoutParams as RelativeLayout.LayoutParams
            markerParams.leftMargin = 10 + markerX
            binding.signalMarker.layoutParams = markerParams

            val idealTextX = 10 + markerX + (markerWidth / 2) - (textWidth / 2)
            val finalTextX = idealTextX.coerceIn(10, 10 + barWidth - textWidth)

            val textParams = binding.dbmValueText.layoutParams as RelativeLayout.LayoutParams
            textParams.leftMargin = finalTextX
            binding.dbmValueText.layoutParams = textParams
        }
    }

    private fun markerPosition(dbm: Int?): Float {
        if (dbm == null) return 0.0f
        val clamped = dbm.coerceIn(-120, -50)
        return (clamped + 120) / 70.0f
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
        val intent = Intent(this, SignalStrengthService::class.java)
        startForegroundService(intent)
    }
}
