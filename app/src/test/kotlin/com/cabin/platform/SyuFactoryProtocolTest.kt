package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class SyuFactoryProtocolTest {
    @Test fun `camera formats and feedback indices remain profile specific`() {
        for (mode in 0..2) {
            assertEquals(15 to listOf(mode + 4, 255), SyuFactoryProtocol.frame(131114, SyuFactoryControl.CAMERA_MODE, mode))
            assertEquals(2 to listOf(mode), SyuFactoryProtocol.frame(131109, SyuFactoryControl.CAMERA_MODE, mode))
        }
        assertEquals(setOf(134), SyuFactoryProtocol.codes(131114))
        assertEquals(setOf(4), SyuFactoryProtocol.codes(131109))
        assertTrue(SyuFactoryProtocol.decode(131109, mapOf(134 to 1)).isEmpty())
        assertTrue(SyuFactoryProtocol.decode(131114, mapOf(134 to 255)).isEmpty())
        assertNull(SyuFactoryProtocol.frame(131109, SyuFactoryControl.CAMERA_MODE, 3))
    }

    @Test fun `WC availability flags are separate from selected state`() {
        val state = SyuFactoryProtocol.decode(17, mapOf(148 to 0x100, 149 to 0x101, 150 to 0, 151 to 1, 152 to 0xffff))
        assertEquals(0, state[SyuFactoryControl.MIRROR_SYNC])
        assertEquals(1, state[SyuFactoryControl.MIRROR_REVERSE_DIP])
        assertEquals(0, state[SyuFactoryControl.MIRROR_PARK_FOLD])
        assertFalse(state.containsKey(SyuFactoryControl.RAIN_WIPERS))
        assertFalse(state.containsKey(SyuFactoryControl.REVERSE_REAR_WIPER))
    }

    @Test fun `parking settings keep WC values and bounded command indices`() {
        val state = SyuFactoryProtocol.decode(17, mapOf(117 to 0x108, 118 to 0x109, 119 to -1))
        assertEquals(8, state[SyuFactoryControl.PARKING_FRONT_VOLUME])
        assertFalse(state.containsKey(SyuFactoryControl.PARKING_FRONT_TONE))
        assertFalse(state.containsKey(SyuFactoryControl.PARKING_REAR_VOLUME))
        assertEquals(40 to listOf(8), SyuFactoryProtocol.frame(17, SyuFactoryControl.PARKING_FRONT_VOLUME, 8))
        assertNull(SyuFactoryProtocol.frame(17, SyuFactoryControl.PARKING_FRONT_VOLUME, 9))
    }

    @Test fun `no family wildcard or cross profile command fallback`() {
        assertEquals(37, SyuFactoryProtocol.wcGolfProfiles.size)
        assertTrue(SyuFactoryProtocol.controls(160).isEmpty()) // RZC is a different dialect.
        assertTrue(SyuFactoryProtocol.controls(999999).isEmpty())
        assertNull(SyuFactoryProtocol.frame(131109, SyuFactoryControl.MIRROR_SYNC, 1))
        assertNull(SyuFactoryProtocol.frame(17, SyuFactoryControl.CAMERA_MODE, 1))
    }

    @Test fun `expired factory values cannot remain actionable`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(148, 0x101, 0)
        assertEquals(1, SyuFactoryProtocol.decode(17, samples.airSnapshot(59999))[SyuFactoryControl.MIRROR_SYNC])
        assertTrue(SyuFactoryProtocol.decode(17, samples.airSnapshot(60000)).isEmpty())
    }
    @Test fun `charging requires capabilities and translates bounded choices`() {
        val p = SyuFactoryProtocol.HYBRID_PROFILE
        assertTrue(SyuFactoryProtocol.decode(p, mapOf(300 to 13)).isEmpty())
        val state = SyuFactoryProtocol.decode(p, mapOf(299 to 240, 300 to 13, 301 to 45, 302 to 1, 303 to 10))
        assertEquals(2, state[SyuFactoryControl.CHARGE_CURRENT])
        assertEquals(14, state[SyuFactoryControl.CHARGE_TEMPERATURE])
        assertEquals(145 to listOf(1, 255), SyuFactoryProtocol.frame(p, SyuFactoryControl.CHARGE_CURRENT, 3))
        assertEquals(145 to listOf(2, 254), SyuFactoryProtocol.frame(p, SyuFactoryControl.CHARGE_TEMPERATURE, 0))
        assertEquals(145 to listOf(2, 255), SyuFactoryProtocol.frame(p, SyuFactoryControl.CHARGE_TEMPERATURE, 29))
        assertNull(SyuFactoryProtocol.frame(p, SyuFactoryControl.CHARGE_TEMPERATURE, 30))
        assertNull(SyuFactoryProtocol.frame(17, SyuFactoryControl.CHARGE_CURRENT, 1))
        assertTrue(SyuFactoryProtocol.decode(p, mapOf(299 to 240, 300 to 6, 301 to 60, 302 to 2, 303 to 11)).isEmpty())
    }

    @Test fun `ambient and seat commands cannot cross profiles`() {
        assertEquals(109 to listOf(1, 2), SyuFactoryProtocol.frame(4260138, SyuFactoryControl.AMBIENT_PALETTE, 1))
        assertEquals(1 to listOf(152, 2), SyuFactoryProtocol.frame(1769874, SyuFactoryControl.SEAT_PRESET, 2))
        assertNull(SyuFactoryProtocol.frame(4260138, SyuFactoryControl.SEAT_PRESET, 2))
        assertNull(SyuFactoryProtocol.frame(1769874, SyuFactoryControl.SEAT_PRESET, 3))
        assertEquals(0, SyuFactoryProtocol.decode(4260138, mapOf(270 to 1))[SyuFactoryControl.AMBIENT_PALETTE])
        assertTrue(SyuFactoryProtocol.decode(4260138, mapOf(270 to 0)).isEmpty())
        assertTrue(SyuFactoryProtocol.decode(1769874, mapOf(200 to 255)).isEmpty())
    }

    @Test fun `energy direction and percentage reject unknown values and other dialects`() {
        assertNull(decodeEnergy(655520, mapOf(312 to 1, 321 to 50)))
        assertEquals(SyuEnergyTelemetry(50, SyuEnergyDirection.CHARGING), decodeEnergy(655377, mapOf(312 to 1, 321 to 50)))
        assertEquals(SyuEnergyTelemetry(null, null), decodeEnergy(655377, mapOf(312 to 0, 321 to 255)))
        assertEquals(SyuEnergyDirection.DISCHARGING, decodeEnergy(655377, mapOf(312 to 2))?.direction)
    }
}
