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
    val displayName: String,
    val generation: NetworkGeneration = NetworkGeneration.UNKNOWN,
    val metrics: CellMetrics = CellMetrics.EMPTY,
)

data class SignalState(
    val sims: List<SimSignal> = emptyList(),
    val selectedSlot: Int = 0,
) {
    val isDualSim: Boolean get() = sims.size > 1

    fun hasSlot(slot: Int): Boolean = sims.any { it.slotIndex == slot }

    /** The SIM the user picked, falling back to whatever is actually present. */
    val selected: SimSignal?
        get() = sims.firstOrNull { it.slotIndex == selectedSlot } ?: sims.firstOrNull()
}
