package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class SyuHondaPanelProtocolTest {
    @Test fun `vendor keys and one based settings match source`() {
        assertEquals(106 to listOf(16, 1), SyuFactoryProtocol.frame(0x40141, SyuFactoryControl.HONDA_TURN_BY_TURN, 1))
        assertEquals(106 to listOf(14, 2), SyuFactoryProtocol.frame(0xD0141, SyuFactoryControl.HONDA_PANEL_CONFIG, 2))
        assertEquals(106 to listOf(1, 4), SyuFactoryProtocol.frame(0x60141, SyuFactoryControl.HONDA_OUTSIDE_TEMP, 3))
        assertEquals(106 to listOf(4, 1), SyuFactoryProtocol.frame(0xC0141, SyuFactoryControl.HONDA_ALARM_VOLUME, 0))
    }
    @Test fun `stock visibility gates survive profile changes`() {
        assertNull(SyuFactoryProtocol.frame(0x60141, SyuFactoryControl.HONDA_TURN_BY_TURN, 1))
        assertNull(SyuFactoryProtocol.frame(0x60141, SyuFactoryControl.HONDA_SPEED_TIPS, 1))
        assertEquals(106 to listOf(9, 1), SyuFactoryProtocol.frame(0x60141, SyuFactoryControl.HONDA_REVERSE_TONE, 1))
        assertNull(SyuFactoryProtocol.frame(0x40141, SyuFactoryControl.HONDA_REVERSE_TONE, 1))
        assertEquals(106 to listOf(13, 1), SyuFactoryProtocol.frame(0xB0141, SyuFactoryControl.HONDA_TRAFFIC_SIGNS, 1))
        assertNull(SyuFactoryProtocol.frame(0xC0141, SyuFactoryControl.HONDA_TRAFFIC_SIGNS, 1))
        for (profile in listOf(0, 0x141, 0xE0141, 0x12012a, 17)) {
            assertNull(SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_PANEL_CONFIG, 0))
        }
    }
    @Test fun `missing invalid and out of range feedback never becomes a setting`() {
        assertTrue(SyuFactoryProtocol.decode(0x40141, emptyMap()).isEmpty())
        assertTrue(SyuFactoryProtocol.decode(0x40141, mapOf(69 to 0, 70 to 255, 71 to -1, 72 to 8, 109 to 2, 111 to 3)).isEmpty())
        val decoded = SyuFactoryProtocol.decode(0x40141, mapOf(69 to 1, 70 to 3, 71 to 2, 72 to 4, 109 to 0, 111 to 2))
        assertEquals(0, decoded[SyuFactoryControl.HONDA_ALARM_VOLUME])
        assertEquals(2, decoded[SyuFactoryControl.HONDA_TRIP_B_RESET])
        assertEquals(1, decoded[SyuFactoryControl.HONDA_TRIP_A_RESET])
        assertEquals(3, decoded[SyuFactoryControl.HONDA_OUTSIDE_TEMP])
        assertEquals(0, decoded[SyuFactoryControl.HONDA_TURN_BY_TURN])
        assertEquals(2, decoded[SyuFactoryControl.HONDA_PANEL_CONFIG])
        assertFalse(SyuFactoryProtocol.decode(0x60141, mapOf(109 to 1)).containsKey(SyuFactoryControl.HONDA_TURN_BY_TURN))
    }
    @Test fun `supported fields are subscribed and writes stay in range`() {
        for (profile in SyuHondaPanelProtocol.profiles) {
            val controls = SyuFactoryProtocol.controls(profile)
            assertTrue(SyuVehicleProtocol.codes(profile).containsAll(controls.map { it.field }))
            for (control in controls) {
                assertNull(SyuFactoryProtocol.frame(profile, control, -1))
                assertNull(SyuFactoryProtocol.frame(profile, control, control.maximum + 1))
                for (value in 0..control.maximum) {
                    val frame = SyuFactoryProtocol.frame(profile, control, value)!!
                    assertEquals(value, SyuFactoryProtocol.decode(profile, mapOf(control.field to frame.second[1]))[control])
                }
            }
        }
    }

    @Test fun `RZC Civic uses its own units and tachometer dialect`() {
        for (profile in listOf(0x10012a, 0x11012a, 0x29012a)) {
            assertEquals(setOf(77, 78, 87), SyuFactoryProtocol.codes(profile))
            assertEquals(105 to listOf(21, 1), SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_DISTANCE_UNITS, 1))
            assertEquals(105 to listOf(22, 0), SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_TACHOMETER_DISPLAY, 0))
            assertEquals(105 to listOf(35, 1), SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_TACHOMETER_SETTING, 1))
            assertNull(SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_PANEL_CONFIG, 1))
            assertTrue(SyuFactoryProtocol.decode(profile, mapOf(77 to 0x101, 78 to -1, 87 to 2)).isEmpty())
        }
        assertNull(SyuFactoryProtocol.frame(0x40141, SyuFactoryControl.HONDA_DISTANCE_UNITS, 1))
    }

    @Test fun `BNR retains the stock hidden units restriction`() {
        for (profile in listOf(0x6012a, 0x7012a, 0x8012a, 0x9012a, 0xa012a, 0xb012a, 0xf012a, 0x28012a)) {
            assertEquals(setOf(78, 87), SyuFactoryProtocol.codes(profile))
            assertNull(SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_DISTANCE_UNITS, 1))
            assertEquals(105 to listOf(22, 1), SyuFactoryProtocol.frame(profile, SyuFactoryControl.HONDA_TACHOMETER_DISPLAY, 1))
        }
    }
}
