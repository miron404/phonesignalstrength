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
    private var permissionGranted = true

    /** Both keyed by SIM slot, and always holding the same keys. */
    private val slots = linkedMapOf<Int, SimSignal>()
    private val registrations = linkedMapOf<Int, Registration>()

    private var subscriptionsListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    @MainThread
    fun init(context: Context) {
        if (app != null) return
        val ctx = context.applicationContext
        app = ctx
        telephonyManager = ctx.getSystemService(TelephonyManager::class.java)
        subscriptionManager = ctx.getSystemService(SubscriptionManager::class.java)
        Prefs.migrate(ctx)
        permissionGranted = hasPhoneStatePermission(ctx)
        emit()
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

    /**
     * Re-reads the SIMs, e.g. right after READ_PHONE_STATE has been granted — including from the
     * system settings while the app was in the background, which no callback reports. Only acts
     * on differences, so it is cheap to call whenever the app comes to the foreground.
     */
    @MainThread
    fun refresh() {
        if (!listening) return
        registerSubscriptionsListener()
        refreshSubscriptions()
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
        emit()
    }

    private fun registerSubscriptionsListener() {
        val ctx = app ?: return
        val sm = subscriptionManager ?: return
        if (subscriptionsListener != null || !hasPhoneStatePermission(ctx)) return

        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                // Fires on SIM insert/removal, airplane mode, eSIM profile switches, any change to
                // a subscription record (the carrier name, for one), and once right after
                // registering. refreshSubscriptions() only acts on what actually changed.
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

    /**
     * Brings the registrations in line with the active subscriptions.
     *
     * A SIM whose subscription is unchanged keeps both its registration and its last reading.
     * Tearing everything down and starting over, as this used to, blanked every reading to "--"
     * until the modem reported again, which showed as a flicker in the app and the notification
     * every time the subscription listener fired.
     */
    // Guarded by hasPhoneStatePermission() below and wrapped in runCatching; lint cannot see
    // through either.
    @SuppressLint("MissingPermission")
    private fun refreshSubscriptions() {
        val ctx = app ?: return
        permissionGranted = hasPhoneStatePermission(ctx)
        if (!permissionGranted) {
            unregisterAll()
            emit()
            return
        }

        val wanted: Map<Int, SubscriptionInfo> =
            runCatching { subscriptionManager?.activeSubscriptionInfoList }
                .onFailure { Log.w(TAG, "Cannot read active subscriptions", it) }
                .getOrNull()
                .orEmpty()
                .filter { it.simSlotIndex >= 0 }
                // A slot can briefly report two subscriptions during an eSIM profile switch. Keep
                // one, so a second registration cannot write its readings over the first's.
                .distinctBy { it.simSlotIndex }
                .associateBy { it.simSlotIndex }

        val gone = registrations
            .filter { (slot, registration) -> wanted[slot]?.subscriptionId != registration.subscriptionId }
            .keys
        for (slot in gone) {
            registrations.remove(slot)?.unregister()
            slots.remove(slot)
        }

        val tm = telephonyManager
        for ((slot, info) in wanted) {
            val existing = slots[slot]
            if (existing != null) {
                // Same subscription. Only its name can have changed, e.g. the carrier name often
                // arrives a moment after the SIM itself.
                val name = operatorNameOf(info, registrations[slot]?.tm)
                if (name != existing.operatorName) slots[slot] = existing.copy(operatorName = name)
                continue
            }
            val perSim = runCatching { tm?.createForSubscriptionId(info.subscriptionId) }
                .getOrNull() ?: continue

            slots[slot] = SimSignal(
                slotIndex = slot,
                subscriptionId = info.subscriptionId,
                operatorName = operatorNameOf(info, perSim),
            )
            registrations[slot] = Registration(slot, info.subscriptionId, perSim).also { it.register() }
        }
        emit()
    }

    /**
     * getCarrierName() is the network's own name; getDisplayName() is the label the user may have
     * renamed in system settings, and getSimOperatorName() is the last resort.
     */
    private fun operatorNameOf(info: SubscriptionInfo, perSim: TelephonyManager?): String =
        sequenceOf(
            info.carrierName?.toString(),
            info.displayName?.toString(),
            runCatching { perSim?.simOperatorName }.getOrNull(),
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            ?: "—"

    private fun unregisterAll() {
        registrations.values.forEach { it.unregister() }
        registrations.clear()
        slots.clear()
    }

    /** StateFlow drops values equal to the current one, so emitting with no change is free. */
    private fun emit() {
        _state.value = SignalState(
            sims = slots.values.sortedBy { it.slotIndex },
            permissionGranted = permissionGranted,
        )
    }

    private fun onSignalStrength(registration: Registration, signalStrength: SignalStrength) {
        registration.lastSignalStrength = signalStrength
        update(registration)
    }

    private fun onDisplayInfo(registration: Registration, generation: NetworkGeneration) {
        registration.displayGeneration = generation
        update(registration)
    }

    /**
     * Re-derives one SIM's reading from its latest signal strength and network type. Both inputs
     * are re-read on either callback: when only the network type changed, the old reading has to
     * be parsed again for the new technology, or the badge would say 3G over an LTE value until
     * the modem next reports, which with its hysteresis can take a while.
     */
    private fun update(registration: Registration) {
        // A callback can still be queued for a registration that was just replaced.
        if (registrations[registration.slot] !== registration) return
        val current = slots[registration.slot] ?: return

        val network = registration.displayGeneration
            ?: SignalParser.generationOf(registration.readDataNetworkType())
        val metrics = SignalParser.parse(registration.lastSignalStrength, network)
        // The network type comes from the data registration, so a SIM that carries no data —
        // typically the second, voice-only SIM — reports it as unknown. The technology the
        // reading itself came from is the honest answer then.
        val generation = if (network == NetworkGeneration.UNKNOWN) metrics.technology else network

        val updated = current.copy(generation = generation, metrics = metrics)
        if (updated == current) return
        slots[registration.slot] = updated
        emit()
    }

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One SIM's live registration. Holds the per-subscription [TelephonyManager] it registered
     * with, because unregistering through any other instance only works by accident.
     */
    private class Registration(
        val slot: Int,
        val subscriptionId: Int,
        val tm: TelephonyManager,
    ) {

        /** Set by the display-info callback on API 30+; null means "ask the data network type". */
        var displayGeneration: NetworkGeneration? = null

        /** Kept so a network type change can re-parse the reading without waiting for a new one. */
        var lastSignalStrength: SignalStrength? = null

        /** Typed as [Any] so that API 26..30 devices never have to resolve TelephonyCallback. */
        private var telephonyCallback: Any? = null

        private var phoneStateListener: PhoneStateListener? = null

        fun register() {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> registerModern()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> registerWithDisplayInfo()
                else -> registerLegacy()
            }
        }

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
                    onSignalStrength(this@Registration, signalStrength)
                }

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    onDisplayInfo(this@Registration, SignalParser.generationOf(telephonyDisplayInfo))
                }
            }
            val executor = app?.let { ContextCompat.getMainExecutor(it) } ?: return
            runCatching { tm.registerTelephonyCallback(executor, callback) }
                .onSuccess { telephonyCallback = callback }
                .onFailure { Log.w(TAG, "Cannot listen on slot $slot", it) }
        }

        /**
         * Android 11 already reports the 5G NSA override, through PhoneStateListener. Without it
         * a 5G NSA connection shows as 4G there, because the data network type says LTE.
         */
        @RequiresApi(Build.VERSION_CODES.R)
        private fun registerWithDisplayInfo() {
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    onSignalStrength(this@Registration, signalStrength)
                }

                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    onDisplayInfo(this@Registration, SignalParser.generationOf(telephonyDisplayInfo))
                }
            }
            val events = PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or
                PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED
            runCatching { tm.listen(listener, events) }
                .onSuccess { phoneStateListener = listener }
                .onFailure {
                    Log.w(TAG, "Cannot listen for display info on slot $slot", it)
                    registerLegacy()
                }
        }

        private fun registerLegacy() {
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    onSignalStrength(this@Registration, signalStrength)
                }
            }
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
                .onSuccess { phoneStateListener = listener }
                .onFailure { Log.w(TAG, "Cannot listen on slot $slot", it) }
        }
    }
}
