// PhoneStateListener and friends are deprecated in favour of TelephonyCallback (API 31+). They are
// still the only option on the API 26..30 devices this app supports, so the whole file opts out of
// the deprecation warnings rather than sprinkling suppressions over every legacy branch.
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package barilyuk.mobilosignal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single owner of the telephony subscriptions.
 *
 * Both [MainActivity] and [SignalStrengthService] used to register their own listener per SIM,
 * which doubled the callback traffic whenever the app was open and gave the two of them
 * independent, drifting copies of the same state. They are now both plain observers of [state].
 *
 * Listening is reference counted: it starts when the first client calls [acquire] and stops when
 * the last one calls [release], so nothing keeps the modem callbacks alive once the notification
 * is gone and the activity is closed.
 *
 * All mutation happens on the main thread — every callback is delivered there, either by the main
 * executor (API 31+) or by the looper the listener was created on (below 31).
 */
object SignalRepository {

    private const val TAG = "SignalRepository"

    private val _state = MutableStateFlow(SignalState())
    val state: StateFlow<SignalState> = _state.asStateFlow()

    private var app: Context? = null
    private var telephonyManager: TelephonyManager? = null
    private var subscriptionManager: SubscriptionManager? = null

    private var clients = 0
    private var listening = false

    private val slots = linkedMapOf<Int, SimSignal>()
    private val registrations = mutableListOf<Registration>()
    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    @MainThread
    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        telephonyManager = ctx.getSystemService(TelephonyManager::class.java)
        subscriptionManager = ctx.getSystemService(SubscriptionManager::class.java)
        _state.value = SignalState(selectedSlot = Prefs.selectedSlot(ctx))
    }

    @MainThread
    fun acquire(context: Context) {
        init(context)
        clients++
        if (clients == 1) start()
    }

    @MainThread
    fun release() {
        if (clients == 0) return
        clients--
        if (clients == 0) stop()
    }

    /** Re-reads the SIMs, e.g. right after READ_PHONE_STATE has been granted. */
    @MainThread
    fun refresh() {
        if (!listening) return
        registerSubscriptionsListener()
        refreshSubscriptions()
    }

    @MainThread
    fun setSelectedSlot(slot: Int) {
        val ctx = app ?: return
        if (_state.value.selectedSlot == slot) return
        Prefs.setSelectedSlot(ctx, slot)
        _state.value = _state.value.copy(selectedSlot = slot)
    }

    private fun start() {
        if (listening) return
        listening = true
        registerSubscriptionsListener()
        refreshSubscriptions()
    }

    private fun stop() {
        listening = false
        unregisterSubscriptionsListener()
        unregisterAll()
        slots.clear()
        emit()
    }

    private fun registerSubscriptionsListener() {
        val ctx = app ?: return
        val sm = subscriptionManager ?: return
        if (subscriptionsListener != null || !hasPhoneStatePermission(ctx)) return

        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                // Fires on SIM insert/removal, airplane mode and eSIM profile switches.
                if (listening) refreshSubscriptions()
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sm.addOnSubscriptionsChangedListener(ContextCompat.getMainExecutor(ctx), listener)
            } else {
                sm.addOnSubscriptionsChangedListener(listener)
            }
        }
            .onSuccess { subscriptionsListener = listener }
            .onFailure { Log.w(TAG, "Cannot observe subscription changes", it) }
    }

    private fun unregisterSubscriptionsListener() {
        val listener = subscriptionsListener ?: return
        subscriptionsListener = null
        runCatching { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
    }

    // Guarded by hasPhoneStatePermission() below and wrapped in runCatching; lint cannot see
    // through either.
    @SuppressLint("MissingPermission")
    private fun refreshSubscriptions() {
        val ctx = app ?: return
        unregisterAll()
        slots.clear()

        if (!hasPhoneStatePermission(ctx)) {
            emit()
            return
        }

        val infos = runCatching { subscriptionManager?.activeSubscriptionInfoList }
            .onFailure { Log.w(TAG, "Cannot read active subscriptions", it) }
            .getOrNull()
            .orEmpty()

        val tm = telephonyManager
        for (info in infos) {
            val slot = info.simSlotIndex
            if (slot < 0) continue
            val perSim = runCatching { tm?.createForSubscriptionId(info.subscriptionId) }
                .getOrNull() ?: continue

            slots[slot] = SimSignal(
                slotIndex = slot,
                subscriptionId = info.subscriptionId,
                operatorName = operatorNameOf(info, perSim),
            )
            registrations += Registration(slot, perSim).also { it.register() }
        }
        emit()
    }

    /**
     * getCarrierName() is the network's own name; getDisplayName() is the label the user may have
     * renamed in system settings, and getSimOperatorName() is the last resort.
     */
    private fun operatorNameOf(info: SubscriptionInfo, perSim: TelephonyManager): String =
        sequenceOf(
            info.carrierName?.toString(),
            info.displayName?.toString(),
            runCatching { perSim.simOperatorName }.getOrNull(),
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            ?: "—"

    private fun unregisterAll() {
        registrations.forEach { it.unregister() }
        registrations.clear()
    }

    private fun emit() {
        _state.value = SignalState(
            sims = slots.values.sortedBy { it.slotIndex },
            selectedSlot = _state.value.selectedSlot,
        )
    }

    private fun onSignalStrength(slot: Int, registration: Registration, ss: SignalStrength) {
        val current = slots[slot] ?: return
        val generation = registration.displayGeneration
            ?: SignalParser.generationOf(registration.readDataNetworkType())
        val updated = current.copy(
            generation = generation,
            metrics = SignalParser.parse(ss, generation),
        )
        if (updated == current) return
        slots[slot] = updated
        emit()
    }

    private fun onDisplayInfo(slot: Int, generation: NetworkGeneration) {
        val current = slots[slot] ?: return
        if (current.generation == generation) return
        slots[slot] = current.copy(generation = generation)
        emit()
    }

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One SIM's live registration. Holds the per-subscription [TelephonyManager] it registered
     * with, because unregistering through any other instance only works by accident.
     */
    private class Registration(val slot: Int, val tm: TelephonyManager) {

        /** Set by the display-info callback on API 31+; null means "ask the data network type". */
        var displayGeneration: NetworkGeneration? = null

        /** Typed as [Any] so that API 26..30 devices never have to resolve TelephonyCallback. */
        private var telephonyCallback: Any? = null

        @Suppress("DEPRECATION")
        private var phoneStateListener: PhoneStateListener? = null

        fun register() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerModern() else registerLegacy()
        }

        @Suppress("DEPRECATION")
        fun unregister() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    runCatching { tm.unregisterTelephonyCallback(it) }
                }
                telephonyCallback = null
            } else {
                phoneStateListener?.let {
                    runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
                }
                phoneStateListener = null
            }
        }

        @SuppressLint("MissingPermission") // SecurityException is caught by runCatching.
        fun readDataNetworkType(): Int =
            runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)

        @RequiresApi(Build.VERSION_CODES.S)
        private fun registerModern() {
            val callback = object :
                TelephonyCallback(),
                TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.DisplayInfoListener {

                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    onSignalStrength(slot, this@Registration, signalStrength)
                }

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    val generation = SignalParser.generationOf(telephonyDisplayInfo)
                    displayGeneration = generation
                    onDisplayInfo(slot, generation)
                }
            }
            val executor = app?.let { ContextCompat.getMainExecutor(it) } ?: return
            runCatching { tm.registerTelephonyCallback(executor, callback) }
                .onSuccess { telephonyCallback = callback }
                .onFailure { Log.w(TAG, "Cannot listen on slot $slot", it) }
        }

        @Suppress("DEPRECATION")
        private fun registerLegacy() {
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    onSignalStrength(slot, this@Registration, signalStrength)
                }
            }
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
                .onSuccess { phoneStateListener = listener }
                .onFailure { Log.w(TAG, "Cannot listen on slot $slot", it) }
        }
    }
}
