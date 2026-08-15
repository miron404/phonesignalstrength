package barilyuk.mobilosignal

/** Cellular generation as shown to the user. */
enum class NetworkGeneration(val label: String) {
    UNKNOWN("0G"),
    G2("2G"),
    G3("3G"),
    G4("4G"),
    G5("5G"),
}

/**
 * The dBm window a bar is drawn over, plus the two thresholds that split it into poor / fair /
 * good.
 *
 * These are display ranges, not the full 3GPP ranges. LTE RSRP is specified down to -140 dBm, but
 * anything below about -115 is equally unusable, so spending a third of the bar on it would only
 * make the useful region harder to read. The thresholds follow the usual rules of thumb: for
 * LTE/NR, -100 and below is poor, -90 and above is good.
 */
data class SignalScale(
    val min: Int,
    val max: Int,
    val poorMax: Int,
    val fairMax: Int,
)

val NetworkGeneration.scale: SignalScale
    get() = when (this) {
        // RSSI
        NetworkGeneration.G2 -> SignalScale(min = -110, max = -65, poorMax = -95, fairMax = -85)
        // RSCP
        NetworkGeneration.G3 -> SignalScale(min = -110, max = -70, poorMax = -95, fairMax = -85)
        // RSRP
        NetworkGeneration.G4 -> SignalScale(min = -115, max = -75, poorMax = -100, fairMax = -90)
        // SS-RSRP
        NetworkGeneration.G5 -> SignalScale(min = -115, max = -75, poorMax = -100, fairMax = -90)
        NetworkGeneration.UNKNOWN -> SignalScale(min = -120, max = -50, poorMax = -100, fairMax = -90)
    }

/**
 * Signal metrics for one SIM. Every field is null when the modem or the vendor HAL does not
 * report it, which is common: SINR in particular is missing on a lot of devices.
 */
data class CellMetrics(
    /** Primary strength, dBm. RSRP on LTE/NR, RSSI on 2G/3G. */
    val dbm: Int? = null,
    /** Reference signal received quality, dB. LTE/NR only. */
    val rsrq: Int? = null,
    /** Signal to interference-plus-noise ratio, dB. LTE/NR only. */
    val sinr: Int? = null,
    /** The framework's own 0..4 bucket, i.e. how many bars the system would draw. */
    val level: Int = 0,
) {
    companion object {
        val EMPTY = CellMetrics()
    }
}

data class SimSignal(
    val slotIndex: Int,
    val subscriptionId: Int,
    /** Carrier name as reported by the SIM, e.g. "Vodafone". */
    val operatorName: String,
    val generation: NetworkGeneration = NetworkGeneration.UNKNOWN,
    val metrics: CellMetrics = CellMetrics.EMPTY,
)

data class SignalState(
    val sims: List<SimSignal> = emptyList(),
    val selectedSlot: Int = 0,
) {
    val isDualSim: Boolean get() = sims.size > 1

    fun slot(index: Int): SimSignal? = sims.firstOrNull { it.slotIndex == index }

    /** The SIM the user picked, falling back to whatever is actually present. */
    val selected: SimSignal?
        get() = slot(selectedSlot) ?: sims.firstOrNull()

    /**
     * The SIM with the best reading. The status bar icon can only carry one number, so with two
     * SIMs it shows whichever is actually receiving better.
     */
    val strongest: SimSignal?
        get() = sims.filter { it.metrics.dbm != null }.maxByOrNull { it.metrics.dbm ?: Int.MIN_VALUE }
            ?: sims.firstOrNull()
}
