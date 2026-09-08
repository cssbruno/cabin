package com.cabin.launcher

import com.cabin.platform.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class VehicleGaugeWidgetsTest {
    private val vehicle = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE,
        availableCodes = setOf(89, 90, 137), speedKph = 100, engineRpm = 2400, oilLifePercent = 65)
    private fun read(gauge: VehicleGauge, v: TeyesClimateState = vehicle, units: MeasurementUnit = MeasurementUnit.METRIC) =
        vehicleGaugeReading(gauge, v, units, Locale.US)

    @Test fun `fresh TEYES fields display with converted units`() {
        assertEquals("100", read(VehicleGauge.SPEED).value)
        assertEquals("62", read(VehicleGauge.SPEED, units = MeasurementUnit.IMPERIAL).value)
        assertEquals("2400", read(VehicleGauge.RPM).value)
        assertEquals("65", read(VehicleGauge.OIL).value)
        assertFalse(read(VehicleGauge.SERVICE).available)
    }
    @Test fun `disconnected expired and unadvertised fields are hidden`() {
        assertFalse(read(VehicleGauge.SPEED, vehicle.copy(connected = false)).available)
        assertFalse(read(VehicleGauge.RPM, vehicle.copy(health = TeyesTelemetryHealth.STALE)).available)
        assertFalse(read(VehicleGauge.SPEED, vehicle.copy(availableCodes = emptySet())).available)
        assertFalse(read(VehicleGauge.OIL, vehicle.copy(oilLifePercent = 101)).available)
        assertFalse(read(VehicleGauge.RPM, vehicle.copy(engineRpm = 10001)).available)
    }
    @Test fun `oil service distance requires metadata and preserves overdue sign`() {
        val sample = vehicle.copy(availableCodes = setOf(179, 180, 181), oilServiceDistance = -100,
            oilServiceDistanceMiles = true)
        assertEquals("-161", read(VehicleGauge.SERVICE, sample).value)
        assertEquals("km", read(VehicleGauge.SERVICE, sample).unit)
        assertEquals("-100", read(VehicleGauge.SERVICE, sample, MeasurementUnit.IMPERIAL).value)
        assertFalse(read(VehicleGauge.SERVICE, sample.copy(availableCodes = setOf(181))).available)
        assertFalse(read(VehicleGauge.OIL, sample).available)
    }
    @Test fun `known profile variants retain only their verified dialect readings`() {
        for (profile in listOf(1048874, 1114410, 196906, 262442, 262465)) {
            val values = TeyesClimateControlPolicy.climateValues(profile, mapOf(89 to 100, 90 to 2400), TeyesVehicleDataLayout.LEGACY)
            assertTrue(read(VehicleGauge.SPEED, vehicle.copy(profileId = profile, availableCodes = values.keys)).available)
        }
        val reference = TeyesClimateControlPolicy.climateValues(1048874, mapOf(89 to 1, 90 to 2), TeyesVehicleDataLayout.CIVIC_0298)
        assertFalse(read(VehicleGauge.SPEED, vehicle.copy(profileId = 1048874, availableCodes = reference.keys)).available)
    }
}
