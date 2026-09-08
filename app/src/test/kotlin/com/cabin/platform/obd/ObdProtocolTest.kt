package com.cabin.platform.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdProtocolTest {
    @Test fun `standard readings decode with specified units`() {
        assertEquals(100.0, ObdProtocol.value("41 0D 64\r>", ObdPid.SPEED)!!, 0.001)
        assertEquals(1726.0, ObdProtocol.value("410C1AF8", ObdPid.RPM)!!, 0.001)
        assertEquals(85.0, ObdProtocol.value("41057D", ObdPid.COOLANT)!!, 0.001)
        assertEquals(13.8, ObdProtocol.value("414235E8", ObdPid.ECU_VOLTAGE)!!, 0.001)
    }

    @Test fun `echo and search status are ignored`() {
        assertEquals(16.0, ObdProtocol.value("010D\rSEARCHING...\r41 0d 10\r>", ObdPid.SPEED)!!, 0.001)
    }

    @Test fun `malformed negative headered or wrong PID responses cannot become telemetry`() {
        listOf("NO DATA", "?", "STOPPED", "7F 01 12", "410C0064", "7E8 03 41 0D 64", "410D6400", "410D6", "410DGG", "410D64\rCAN ERROR", "410D\t64")
            .forEach { assertNull(it, ObdProtocol.value(it, ObdPid.SPEED)) }
    }

    @Test fun `multiple agreeing ECUs allowed but disagreeing values unavailable`() {
        assertEquals(100.0, ObdProtocol.value("410D64\r410D64", ObdPid.SPEED)!!, 0.001)
        assertNull(ObdProtocol.value("410D64\r410D65", ObdPid.SPEED))
    }

    @Test fun `PID support uses exact bitmap bit order including next page`() {
        assertEquals(setOf(1, 5, 12, 13, 32), ObdProtocol.supportedPids("410088180001", 0))
        assertEquals(setOf(0x42), ObdProtocol.supportedPids("414040000000", 0x40))
        assertTrue(ObdProtocol.supportedPids("410000000000", 0).isEmpty())
    }

    @Test fun `ambiguous capabilities use intersection not union`() {
        assertEquals(setOf(13), ObdProtocol.supportedPids("410000180000\r410000080000", 0))
    }

    @Test fun `oversized or unsolicited responses rejected`() {
        assertNull(ObdProtocol.value("410D64".repeat(1000), ObdPid.SPEED))
        assertTrue(ObdProtocol.supportedPids("410088180001\rOK", 0).isEmpty())
        assertTrue(ObdProtocol.supportedPids("410088180001", 0x21).isEmpty())
    }

    @Test fun `read commands are fixed mode one only`() {
        assertEquals(setOf("0105", "010C", "010D", "0142"), ObdPid.entries.map { it.command }.toSet())
    }

    @Test fun `freshness rejects old future and disconnected samples`() {
        val snapshot = ObdSnapshot(phase = ObdPhase.STREAMING, updatedAtElapsedMs = 1_000, speedKph = 20.0)
        assertTrue(snapshot.isFresh(9_000))
        assertFalse(snapshot.isFresh(9_001))
        assertFalse(snapshot.isFresh(999))
        assertFalse(snapshot.copy(phase = ObdPhase.IDLE).isFresh(2_000))
        assertFalse(snapshot.copy(updatedAtElapsedMs = null).isFresh(2_000))
    }
}
