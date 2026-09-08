package com.cabin.platform

import com.cabin.ui.settings.teyesOilServiceReading
import com.cabin.ui.settings.teyesVehicleSettingsReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MeasurementFormatterTest {
    @Test fun `manual units take precedence over language and region`() {
        val brazil = Locale("pt", "BR")
        assertTrue(MeasurementFormatter.isImperial(MeasurementUnit.SYSTEM, Locale.US))
        assertFalse(MeasurementFormatter.isImperial(MeasurementUnit.SYSTEM, brazil))
        assertFalse(MeasurementFormatter.isImperial(MeasurementUnit.METRIC, Locale.US))
        assertTrue(MeasurementFormatter.isImperial(MeasurementUnit.IMPERIAL, brazil))
        assertEquals("62 mph", MeasurementFormatter.speed(100.0, MeasurementUnit.IMPERIAL, brazil))
        assertEquals("100 km/h", MeasurementFormatter.speed(100.0, MeasurementUnit.METRIC, Locale.US))
        assertEquals("1,0 mi", MeasurementFormatter.distance(1609.344, MeasurementUnit.IMPERIAL, brazil))
        assertEquals("1.6 km", MeasurementFormatter.distance(1609.344, MeasurementUnit.METRIC, Locale.US))
    }

    @Test fun `distance thresholds and real zero remain clear`() {
        assertEquals("0 m", MeasurementFormatter.distance(0.0, MeasurementUnit.METRIC, Locale.US))
        assertEquals("0 ft", MeasurementFormatter.distance(0.0, MeasurementUnit.IMPERIAL, Locale.US))
        assertEquals("999 m", MeasurementFormatter.distance(999.0, MeasurementUnit.METRIC, Locale.US))
        assertEquals("1.0 km", MeasurementFormatter.distance(1000.0, MeasurementUnit.METRIC, Locale.US))
        assertEquals("997 ft", MeasurementFormatter.distance(304.0, MeasurementUnit.IMPERIAL, Locale.US))
        assertEquals("0.2 mi", MeasurementFormatter.distance(305.0, MeasurementUnit.IMPERIAL, Locale.US))
    }

    @Test fun `converted vehicle readings retain freshness and metric telemetry`() {
        val vehicle = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE,
            availableCodes = setOf(89, 90, 179, 180, 181), speedKph = 100, engineRpm = 1500,
            oilServiceDistance = -250, oilServiceDistanceMiles = true)
        assertEquals("62 mph", teyesVehicleSettingsReadings(vehicle, MeasurementUnit.IMPERIAL).speed)
        assertEquals("100 km/h", teyesVehicleSettingsReadings(vehicle, MeasurementUnit.METRIC).speed)
        assertEquals("1500 RPM", teyesVehicleSettingsReadings(vehicle, MeasurementUnit.IMPERIAL).rpm)
        assertEquals("-402 km", teyesOilServiceReading(vehicle, MeasurementUnit.METRIC))
        assertEquals("-250 mi", teyesOilServiceReading(vehicle, MeasurementUnit.IMPERIAL))
        assertEquals(100, vehicle.speedKph)
        assertEquals("—", teyesVehicleSettingsReadings(vehicle.copy(health = TeyesTelemetryHealth.STALE), MeasurementUnit.IMPERIAL).speed)
        assertEquals("—", teyesOilServiceReading(vehicle.copy(availableCodes = setOf(181)), MeasurementUnit.IMPERIAL))
    }
}
