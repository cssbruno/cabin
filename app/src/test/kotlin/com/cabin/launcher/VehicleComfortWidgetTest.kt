package com.cabin.launcher

import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import org.junit.Assert.*
import org.junit.Test

class VehicleComfortWidgetTest {
    private val live = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE, availableCodes = setOf(25, 33, 95))
    @Test fun `temperatures require fresh units and their own field`() {
        assertTrue(comfortFieldAvailable(live, 25, 33))
        assertFalse(comfortFieldAvailable(live.copy(availableCodes = setOf(25)), 25, 33))
        assertFalse(comfortFieldAvailable(live, 31, 33))
    }
    @Test fun `disconnection and stale data never become zero seat levels`() {
        assertTrue(comfortFieldAvailable(live, 95))
        assertFalse(comfortFieldAvailable(live, 94))
        assertFalse(comfortFieldAvailable(live.copy(connected = false), 95))
        assertFalse(comfortFieldAvailable(live.copy(health = TeyesTelemetryHealth.STALE), 95))
    }
    @Test fun `shared service fields are usable across vehicle profiles`() {
        listOf(0, 262465, 1048874, 123456).forEach { profile ->
            assertTrue(comfortFieldAvailable(live.copy(profileId = profile), 95))
        }
    }
}
