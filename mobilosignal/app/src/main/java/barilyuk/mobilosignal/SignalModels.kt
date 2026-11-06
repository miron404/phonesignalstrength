package barilyuk.mobilosignal

/** Cellular generation as shown to the user. */
enum class NetworkGeneration(val label: String) {
    /** No network type and no reading to infer one from. Not shown as a badge. */
    UNKNOWN("—"),
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
) {
    /**
     * Where [dbm] sits on this scale: 0f at [min], 1f at [max]. Deliberately not clamped, so two
     * readings that are both off the scale still compare correctly; callers that draw clamp it.
     */
    fun fractionOf(dbm: Int): Float {
        val span = (max - min).toFloat()
        if (span <= 0f) return 0f
        return (dbm - min) / span
    }
}

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
 * A secondary reading, already formatted. Which ones exist depends on the technology: RSRQ and
 * SINR on LTE/NR, Ec/No on 3G, bit error rate on 2G. Anything the modem does not report is simply
 * absent from the list rather than being carried around as a null to render as a dash.
 */
data class Metric(val label: String, val value: String)

/**
 * Signal metrics for one SIM. [dbm] is null when the modem or the vendor HAL does not report it,
 * which is common.
 */
data class CellMetrics(
    /** Primary strength, dBm. RSRP on LTE/NR, RSSI on 2G/3G. */
    val dbm: Int? = null,
    /** The framework's own 0..4 bucket, i.e. how many bars the system would draw. */
    val level: Int = 0,
    /** Quality metrics for the technology in use; empty when none are available. */
    val extras: List<Metric> = emptyList(),
    /**
     * The technology the reading itself came from, when that can be told. Used when the network
     * type is unknown, which is common on a SIM that is not the data SIM.
     */
    val technology: NetworkGeneration = NetworkGeneration.UNKNOWN,
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
    /**
     * False while READ_PHONE_STATE is missing. The SIM list is empty then too, but for a
     * different reason than "no SIM inserted", and the UI has to say which.
     */
    val permissionGranted: Boolean = true,
) {
    val isDualSim: Boolean get() = sims.size > 1

    fun slot(index: Int): SimSignal? = sims.firstOrNull { it.slotIndex == index }

    /**
     * The SIM with the best reading. Readings are compared by where they sit on their own
     * technology's scale, not by raw dBm: GSM RSSI and LTE RSRP measure different things, and RSRP
     * runs some 20-30 dB below RSSI for the same reception, so a raw comparison would favour 2G.
     */
    val strongest: SimSignal?
        get() = sims
            .mapNotNull { sim -> sim.metrics.dbm?.let { sim to sim.generation.scale.fractionOf(it) } }
            .maxByOrNull { it.second }
            ?.first
            ?: sims.firstOrNull()
}
