package com.cabin.launcher

import com.cabin.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class UtilityWidgetsTest {
    private val route = NavigationState(status = 1, hasEta = true, timeToDestination = 600,
        lastUpdateElapsedRealtimeMs = 1000, etaUpdatedElapsedRealtimeMs = 1000)
    @Test fun `arrival estimate counts down from ETA reception rather than unrelated guidance`() {
        assertEquals(600, routeSecondsRemaining(route, true, 1000))
        assertEquals(590, routeSecondsRemaining(route.copy(lastUpdateElapsedRealtimeMs = 11000), true, 11000))
        assertNull(routeSecondsRemaining(route.copy(lastUpdateElapsedRealtimeMs = 32000), true, 32000))
    }
    @Test fun `missing stale disconnected and inactive ETA never appear as zero minutes`() {
        assertNull(routeSecondsRemaining(route, false, 1000))
        assertNull(routeSecondsRemaining(route.copy(status = 0), true, 1000))
        assertNull(routeSecondsRemaining(route.copy(hasEta = false), true, 1000))
        assertNull(routeSecondsRemaining(route.copy(etaUpdatedElapsedRealtimeMs = null), true, 1000))
        assertNull(routeSecondsRemaining(route, true, 0))
        assertNull(routeSecondsRemaining(route, true, 32000))
        assertEquals(0, routeSecondsRemaining(route.copy(timeToDestination = 0), true, 1000))
    }
}
