package com.cabin.ui

import com.cabin.platform.TeyesClimateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class ProjectionClimateSummaryTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test fun `unknown fields never imply off or zero fan or a temperature unit`() {
        val state = TeyesClimateState(connected = true, leftTemperature = 44, ac = false)
        assertEquals("Climate updated", projectionClimateSummaryText(testResources, state))
        assertEquals("Climate updated", projectionClimateSummaryText(testResources, state.copy(availableCodes = setOf(25))))
    }

    @Test fun `alternate verified profile uses its own ac and fan codes`() {
        val state =
            TeyesClimateState(
                connected = true,
                profileId = 262465,
                availableCodes = setOf(30, 35, 25, 33),
                ac = true,
                fanLevel = 3,
                leftTemperature = 44,
            )
        assertEquals("A/C on · Left 22.0°C · Fan 3/7", projectionClimateSummaryText(testResources, state))
        assertEquals("Climate updated", projectionClimateSummaryText(testResources, state.copy(availableCodes = setOf(24, 29))))
    }

    @Test fun `summary respects temperature units and fresh defrost codes`() {
        val state =
            TeyesClimateState(
                connected = true,
                availableCodes = setOf(24, 31, 33, 22),
                rightTemperature = 72,
                fahrenheit = true,
                frontDefrost = true,
                rearDefrost = true,
            )
        val text = projectionClimateSummaryText(testResources, state)
        assertTrue(text.contains("Right 72°F"))
        assertTrue(text.contains("Front defrost"))
        assertFalse(text.contains("Rear defrost"))
    }

    @Test fun `disconnected telemetry is unavailable even if stale fields remain`() {
        assertEquals("Climate data unavailable", projectionClimateSummaryText(testResources, TeyesClimateState(ac = true, availableCodes = setOf(24))))
    }
}
