package barilyuk.mobilosignal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalModelsTest {

    private fun sim(slot: Int, generation: NetworkGeneration, dbm: Int?) = SimSignal(
        slotIndex = slot,
        subscriptionId = 100 + slot,
        operatorName = "Operator $slot",
        generation = generation,
        metrics = CellMetrics(dbm = dbm),
    )

    @Test
    fun strongestComparesReadingsOnTheirOwnScale() {
        // -85 dBm GSM RSSI is only fair; -90 dBm LTE RSRP is good. Raw dBm would pick the 2G SIM.
        val state = SignalState(
            sims = listOf(
                sim(0, NetworkGeneration.G2, -85),
                sim(1, NetworkGeneration.G4, -90),
            ),
        )
        assertEquals(1, state.strongest?.slotIndex)
    }

    @Test
    fun strongestPicksTheBetterReadingOnTheSameTechnology() {
        val state = SignalState(
            sims = listOf(
                sim(0, NetworkGeneration.G4, -105),
                sim(1, NetworkGeneration.G4, -95),
            ),
        )
        assertEquals(1, state.strongest?.slotIndex)
    }

    @Test
    fun strongestStillOrdersReadingsBeyondTheScale() {
        // Both are above the top of the LTE scale; clamping would make them a tie.
        val state = SignalState(
            sims = listOf(
                sim(0, NetworkGeneration.G4, -70),
                sim(1, NetworkGeneration.G4, -60),
            ),
        )
        assertEquals(1, state.strongest?.slotIndex)
    }

    @Test
    fun strongestIgnoresSimsWithoutReading() {
        val state = SignalState(
            sims = listOf(
                sim(0, NetworkGeneration.G4, null),
                sim(1, NetworkGeneration.G2, -105),
            ),
        )
        assertEquals(1, state.strongest?.slotIndex)
    }

    @Test
    fun strongestFallsBackToFirstSimWithoutAnyReading() {
        val state = SignalState(
            sims = listOf(
                sim(0, NetworkGeneration.G4, null),
                sim(1, NetworkGeneration.G4, null),
            ),
        )
        assertEquals(0, state.strongest?.slotIndex)
    }

    @Test
    fun strongestIsNullWithoutSims() {
        assertNull(SignalState().strongest)
    }

    @Test
    fun fractionSpansTheScale() {
        val scale = NetworkGeneration.G4.scale
        assertEquals(0f, scale.fractionOf(scale.min), DELTA)
        assertEquals(1f, scale.fractionOf(scale.max), DELTA)
        assertEquals(0.5f, scale.fractionOf((scale.min + scale.max) / 2), DELTA)
    }

    @Test
    fun fractionIsNotClamped() {
        val scale = NetworkGeneration.G4.scale
        assertEquals(-0.25f, scale.fractionOf(scale.min - (scale.max - scale.min) / 4), DELTA)
    }

    @Test
    fun everyScaleIsOrdered() {
        for (generation in NetworkGeneration.values()) {
            val scale = generation.scale
            assertTrue("$generation: min must be below poorMax", scale.min < scale.poorMax)
            assertTrue("$generation: poorMax must be below fairMax", scale.poorMax < scale.fairMax)
            assertTrue("$generation: fairMax must be below max", scale.fairMax < scale.max)
        }
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
