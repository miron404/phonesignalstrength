package barilyuk.mobilosignal

import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the parts that need no framework object. The TelephonyManager constants are compile-time
 * constants, so they are inlined and the android.jar stubs are never touched.
 */
class SignalParserTest {

    @Test
    fun gsmFamilyIs2G() {
        assertEquals(NetworkGeneration.G2, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_GSM))
        assertEquals(NetworkGeneration.G2, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals(NetworkGeneration.G2, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_GPRS))
    }

    @Test
    fun umtsFamilyIs3G() {
        assertEquals(NetworkGeneration.G3, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_UMTS))
        assertEquals(NetworkGeneration.G3, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_HSPAP))
    }

    @Test
    fun lteIs4G() {
        assertEquals(NetworkGeneration.G4, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_LTE))
    }

    @Test
    fun hiddenLteCarrierAggregationIs4G() {
        // TelephonyManager.NETWORK_TYPE_LTE_CA, hidden but returned by some Android 8 and 9 builds.
        assertEquals(NetworkGeneration.G4, SignalParser.generationOf(19))
    }

    @Test
    fun nrIs5G() {
        assertEquals(NetworkGeneration.G5, SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_NR))
    }

    @Test
    fun unknownAndWifiCallingAreLeftToTheReading() {
        assertEquals(
            NetworkGeneration.UNKNOWN,
            SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_UNKNOWN),
        )
        assertEquals(
            NetworkGeneration.UNKNOWN,
            SignalParser.generationOf(TelephonyManager.NETWORK_TYPE_IWLAN),
        )
    }

    @Test
    fun noSignalStrengthMeansNoReading() {
        assertEquals(CellMetrics.EMPTY, SignalParser.parse(null, NetworkGeneration.G4))
    }
}
