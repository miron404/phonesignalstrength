package barilyuk.mobilosignal

import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Turns the framework's [SignalStrength] into [CellMetrics].
 *
 * Everything here is a pure function of its arguments so it can be reasoned about (and later
 * tested) without a device.
 */
object SignalParser {

    /**
     * Plausible dBm window. Anything outside is treated as "no reading": the framework uses
     * [android.telephony.CellInfo.UNAVAILABLE] (Integer.MAX_VALUE) as its sentinel, and some
     * vendors report 0 instead.
     */
    private val DBM_RANGE = -160..-20

    /** SS-RSRQ per 3GPP is -43..20 dB; LTE RSRQ is a narrower band inside it. */
    private val RSRQ_RANGE = -45..20

    /** SS-SINR / LTE RSSNR per 3GPP is -23..40 dB. */
    private val SINR_RANGE = -23..40

    /** WCDMA Ec/No per 3GPP is -24..1 dB. */
    private val ECNO_RANGE = -24..1

    // Metric names are technical abbreviations that are not translated.
    private const val RSRQ = "RSRQ"
    private const val SINR = "SINR"
    private const val ECNO = "Ec/No"
    private const val BIT_ERRORS = "Bit errors"

    @Suppress("DEPRECATION") // NETWORK_TYPE_IDEN is deprecated but still reported by old modems.
    fun generationOf(networkType: Int): NetworkGeneration = when (networkType) {
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> NetworkGeneration.G2

        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> NetworkGeneration.G3

        TelephonyManager.NETWORK_TYPE_LTE,
        TelephonyManager.NETWORK_TYPE_IWLAN -> NetworkGeneration.G4

        TelephonyManager.NETWORK_TYPE_NR -> NetworkGeneration.G5

        else -> NetworkGeneration.UNKNOWN
    }

    /**
     * On 5G NSA the data network type still reports LTE, because the LTE anchor carries the
     * signalling. The override type is what the system status bar itself uses to draw "5G".
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun generationOf(info: TelephonyDisplayInfo): NetworkGeneration =
        if (info.overrideNetworkType >= TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) {
            NetworkGeneration.G5
        } else {
            generationOf(info.networkType)
        }

    fun parse(signalStrength: SignalStrength?, generation: NetworkGeneration): CellMetrics {
        if (signalStrength == null) return CellMetrics.EMPTY
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                parseModern(signalStrength, generation)
            } else {
                parseLegacy(signalStrength)
            }
        } catch (t: Throwable) {
            // Vendor SignalStrength subclasses have been known to throw from their getters.
            CellMetrics.EMPTY
        }
    }

    /**
     * API 29+ exposes the per-technology readings directly. The list order is not specified, so
     * the entry is picked by the technology that is actually being displayed rather than by
     * taking the first element.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun parseModern(
        signalStrength: SignalStrength,
        generation: NetworkGeneration,
    ): CellMetrics {
        val all: List<CellSignalStrength> = signalStrength.cellSignalStrengths
        if (all.isEmpty()) return CellMetrics.EMPTY

        val preferred = when (generation) {
            NetworkGeneration.G5 -> all.firstOrNull { it is CellSignalStrengthNr }
            NetworkGeneration.G4 -> all.firstOrNull { it is CellSignalStrengthLte }
            NetworkGeneration.G3 ->
                all.firstOrNull { it is CellSignalStrengthWcdma || it is CellSignalStrengthTdscdma }
            NetworkGeneration.G2 ->
                all.firstOrNull { it is CellSignalStrengthGsm || it is CellSignalStrengthCdma }
            NetworkGeneration.UNKNOWN -> null
        }

        val chosen = preferred
            ?: all.firstOrNull { it.dbm.asDbm() != null }
            ?: all.first()

        return when (chosen) {
            is CellSignalStrengthNr -> CellMetrics(
                dbm = chosen.ssRsrp.asDbm() ?: chosen.dbm.asDbm(),
                level = chosen.level,
                extras = listOfNotNull(
                    chosen.ssRsrq.inRange(RSRQ_RANGE)?.let { Metric(RSRQ, "$it dB") },
                    chosen.ssSinr.inRange(SINR_RANGE)?.let { Metric(SINR, "$it dB") },
                ),
            )

            is CellSignalStrengthLte -> CellMetrics(
                dbm = chosen.rsrp.asDbm() ?: chosen.dbm.asDbm(),
                level = chosen.level,
                extras = listOfNotNull(
                    chosen.rsrq.inRange(RSRQ_RANGE)?.let { Metric(RSRQ, "$it dB") },
                    // Some HALs report RSSNR in 0.1 dB units; those land outside the range and
                    // are dropped rather than shown as a nonsense number.
                    chosen.rssnr.inRange(SINR_RANGE)?.let { Metric(SINR, "$it dB") },
                ),
            )

            is CellSignalStrengthWcdma -> CellMetrics(
                dbm = chosen.dbm.asDbm(),
                level = chosen.level,
                extras = listOfNotNull(ecNoOf(chosen)),
            )

            is CellSignalStrengthGsm -> CellMetrics(
                dbm = chosen.dbm.asDbm(),
                level = chosen.level,
                extras = listOfNotNull(bitErrorRateOf(chosen.bitErrorRate)),
            )

            else -> CellMetrics(dbm = chosen.dbm.asDbm(), level = chosen.level)
        }
    }

    /** Ec/No is the 3G equivalent of RSRQ. Only exposed from API 30. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ecNoOf(strength: CellSignalStrengthWcdma): Metric? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val ecNo = strength.ecNo.inRange(ECNO_RANGE) ?: return null
        return Metric(ECNO, "$ecNo dB")
    }

    /**
     * 2G reports an index rather than a rate, per 3GPP TS 27.007 8.5. The index on its own means
     * nothing to a reader, so it is expanded into the error rate band it stands for. 99 means the
     * modem has no measurement.
     */
    private fun bitErrorRateOf(index: Int): Metric? {
        val band = when (index) {
            0 -> "< 0.2%"
            1 -> "0.2–0.4%"
            2 -> "0.4–0.8%"
            3 -> "0.8–1.6%"
            4 -> "1.6–3.2%"
            5 -> "3.2–6.4%"
            6 -> "6.4–12.8%"
            7 -> "> 12.8%"
            else -> return null
        }
        return Metric(BIT_ERRORS, band)
    }

    /** API 26..28 only exposes the aggregate accessors. */
    @Suppress("DEPRECATION")
    private fun parseLegacy(signalStrength: SignalStrength): CellMetrics {
        // 0..31 is the valid ASU window; 99 means "unknown".
        val gsmAsu = signalStrength.gsmSignalStrength
        val dbm = if (gsmAsu in 0..31) {
            (-113 + 2 * gsmAsu).asDbm()
        } else {
            runCatching { signalStrength.cdmaDbm }.getOrNull()?.asDbm()
                ?: runCatching { signalStrength.evdoDbm }.getOrNull()?.asDbm()
        }
        return CellMetrics(dbm = dbm, level = signalStrength.level)
    }

    private fun Int.asDbm(): Int? = if (this in DBM_RANGE) this else null

    private fun Int.inRange(range: IntRange): Int? = if (this in range) this else null
}
