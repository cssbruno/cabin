package com.cabin.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeyesDrivingRecoveryTest {
    @Test
    fun `lost TEYES motion allows explicit parking confirmation without assuming stationary`() {
        val guard = TeyesDrivingGuard()
        guard.observe(35.0)
        assertFalse(guard.state.value.canRequestParkedAction)
        assertFalse(guard.confirmParked())

        // A stale or disconnected TEYES service cannot supply a fresh stationary reading.
        guard.observe(null)
        assertTrue(guard.state.value.moving)
        assertFalse(guard.state.value.speedKnown)
        assertFalse(guard.state.value.parkedConfirmed)
        assertTrue(guard.state.value.canRequestParkedAction)

        assertTrue(guard.confirmParked())
        assertTrue(guard.state.value.parkedConfirmed)
        assertFalse(guard.state.value.speedKnown)

        // Reconnected telemetry must revoke the manual confirmation immediately.
        guard.observe(12.0)
        assertFalse(guard.state.value.parkedConfirmed)
        assertFalse(guard.state.value.canRequestParkedAction)
    }

    @Test
    fun `observed zero speed still requires explicit parking confirmation`() {
        val guard = TeyesDrivingGuard()
        guard.observe(0.0)
        assertTrue(guard.state.value.canRequestParkedAction)
        assertTrue(guard.state.value.speedKnown)
        assertFalse(guard.state.value.parkedConfirmed)
    }
}
