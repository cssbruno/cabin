package com.cabin.ui.settings

import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class SettingsPresentationTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test
    fun `narrow and split windows use horizontal settings navigation`() {
        assertTrue(useHorizontalSettingsNavigation(360f))
        assertTrue(useHorizontalSettingsNavigation(599f))
        assertFalse(useHorizontalSettingsNavigation(600f))
        assertFalse(useHorizontalSettingsNavigation(1024f))
    }

    @Test
    fun `vehicle service status never claims available readings from connection alone`() {
        assertEquals("FYT vehicle service unavailable", teyesVehicleSettingsStatus(testResources, TeyesClimateState()))
        assertEquals("Connecting to FYT vehicle service…", teyesVehicleSettingsStatus(testResources, TeyesClimateState(health = TeyesTelemetryHealth.CONNECTING)))
        assertEquals("Waiting for fresh FYT readings", teyesVehicleSettingsStatus(testResources, TeyesClimateState(connected = true, health = TeyesTelemetryHealth.STALE)))
        assertEquals("FYT connected · vehicle readings unavailable", teyesVehicleSettingsStatus(testResources, TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE)))
    }

    @Test
    fun `fresh FYT readings use only the existing supported fields`() {
        val vehicle = liveVehicle()
        assertEquals(TeyesVehicleSettingsReadings("45 km/h", "850 RPM", "78 %"), teyesVehicleSettingsReadings(vehicle))
        assertEquals("FYT vehicle readings available", teyesVehicleSettingsStatus(testResources, vehicle))
    }

    @Test
    fun `missing stale disconnected and invalid FYT readings remain unavailable`() {
        val unavailable = TeyesVehicleSettingsReadings()
        assertEquals(unavailable, teyesVehicleSettingsReadings(TeyesClimateState()))
        assertEquals(unavailable, teyesVehicleSettingsReadings(liveVehicle().copy(health = TeyesTelemetryHealth.STALE)))
        assertEquals(unavailable, teyesVehicleSettingsReadings(liveVehicle().copy(connected = false)))
        assertEquals(unavailable, teyesVehicleSettingsReadings(liveVehicle().copy(availableCodes = emptySet())))
        assertEquals(unavailable, teyesVehicleSettingsReadings(liveVehicle().copy(speedKph = -1, engineRpm = 10_001, oilLifePercent = 101)))
    }

    @Test
    fun `each value needs its own available FYT code`() {
        assertEquals(
            TeyesVehicleSettingsReadings("45 km/h", "—", "—"),
            teyesVehicleSettingsReadings(liveVehicle().copy(availableCodes = setOf(89))),
        )
    }

    @Test
    fun `Civic oil service distance requires fresh distance sign and unit fields`() {
        val civic =
            liveVehicle().copy(
                profileId = 1048874,
                availableCodes = setOf(179, 180, 181),
                oilLifePercent = null,
                oilServiceDistance = 500,
            )
        assertEquals("500 km", teyesOilServiceReading(civic))
        val overdueMiles = civic.copy(oilServiceDistance = -250, oilServiceDistanceMiles = true)
        assertEquals("-402 km", teyesOilServiceReading(overdueMiles, MeasurementUnit.METRIC))
        assertEquals("-250 mi", teyesOilServiceReading(overdueMiles, MeasurementUnit.IMPERIAL))
        assertEquals("0 km", teyesOilServiceReading(civic.copy(oilServiceDistance = 0)))
        assertEquals("—", teyesOilServiceReading(civic.copy(availableCodes = setOf(181))))
        assertEquals("—", teyesOilServiceReading(civic.copy(health = TeyesTelemetryHealth.STALE)))
        assertEquals("—", teyesOilServiceReading(civic.copy(connected = false)))
        assertEquals("—", teyesVehicleSettingsReadings(civic).oilLife)
        assertEquals("FYT vehicle readings available", teyesVehicleSettingsStatus(testResources, civic))
    }

    @Test
    fun `actual zero values remain distinguishable from missing telemetry`() {
        assertEquals(
            TeyesVehicleSettingsReadings("0 km/h", "0 RPM", "0 %"),
            teyesVehicleSettingsReadings(liveVehicle().copy(speedKph = 0, engineRpm = 0, oilLifePercent = 0)),
        )
    }

    private fun liveVehicle() =
        TeyesClimateState(
            connected = true,
            health = TeyesTelemetryHealth.LIVE,
            availableCodes = setOf(89, 90, 137),
            speedKph = 45,
            engineRpm = 850,
            oilLifePercent = 78,
        )
}
