package com.cabin.navigation

import androidx.car.app.model.Distance
import com.cabin.platform.MeasurementUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DistanceFormatterTest {
    @Test fun `cluster distance honors manual preference over region`() {
        val imperial = DistanceFormatter.toDistance(1609, MeasurementUnit.IMPERIAL, Locale("pt", "BR"))
        val metric = DistanceFormatter.toDistance(1609, MeasurementUnit.METRIC, Locale.US)
        assertEquals(Distance.UNIT_MILES_P1, imperial.displayUnit)
        assertEquals(1.0, imperial.displayDistance, 0.001)
        assertEquals(Distance.UNIT_KILOMETERS_P1, metric.displayUnit)
        assertEquals(1.609, metric.displayDistance, 0.001)
    }

    @Test fun `system units retain locale policy and short distance behavior`() {
        assertEquals(Distance.UNIT_FEET, DistanceFormatter.toDistance(100, MeasurementUnit.SYSTEM, Locale.US).displayUnit)
        assertEquals(Distance.UNIT_METERS, DistanceFormatter.toDistance(100, MeasurementUnit.SYSTEM, Locale("pt", "BR")).displayUnit)
    }
}
