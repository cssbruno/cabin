package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class SyuRadioProtocolTest {
    @Test fun `commands require connected station feedback and known preset slots`() {
        val state = SyuRadioState(true, mapOf(0 to 65536, 1 to 10170, 165536 to 9950, 100000 to 1000))
        assertEquals(5, SyuRadioProtocol.command(state, 5, null)!!.first)
        assertNull(SyuRadioProtocol.command(state.copy(connected = false), 5, null))
        assertNull(SyuRadioProtocol.command(state.copy(samples = emptyMap()), 5, null))
        assertNull(SyuRadioProtocol.command(state, 13, null))
        assertNull(SyuRadioProtocol.command(state, 7, 65537))
        assertArrayEquals(intArrayOf(65536), SyuRadioProtocol.command(state, 8, 65536)!!.second)
        assertNull(SyuRadioProtocol.command(state, 8, 0))
        assertEquals("101.70 MHz", SyuRadioProtocol.label(state.band, state.frequency!!))
    }
    @Test fun `invalid band frequency and preset payloads are rejected`() {
        assertNull(SyuRadioProtocol.sample(4, listOf(65554, 9950)))
        assertNull(SyuRadioProtocol.sample(4, listOf(65536)))
        assertNull(SyuRadioProtocol.sample(50, listOf(1)))
        assertNull(SyuRadioState(true, mapOf(0 to 99, 1 to 10170)).frequency)
        assertNull(SyuRadioState(true, mapOf(0 to 65536, 1 to 65535)).frequency)
        assertEquals(165536 to 9950, SyuRadioProtocol.sample(4, listOf(65536, 9950)))
    }
    @Test fun `compatibility never carries readings through a disconnect`() {
        val vehicle = TeyesClimateState(connected = true, profileId = 1048874,
            availableCodes = setOf(1000, 36, 37), controlsAvailable = true)
        val live = vehicleCompatibility(vehicle)
        assertEquals(2, live.doorCount)
        assertEquals(setOf(36,37), live.fields)
        val lost = vehicleCompatibility(vehicle.copy(connected = false))
        assertNull(lost.profile)
        assertEquals(0, lost.climateActions)
        assertTrue(lost.fields.isEmpty())
    }
}
