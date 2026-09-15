package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class SyuSoundProtocolTest {
    @Test fun `unknown and ambiguous profiles never expose commands`() {
        for (id in listOf(null, 0, 1, 12, 13, 99)) {
            assertEquals(setOf(1), SyuSoundProtocol.fields(id))
            assertTrue(SyuSoundProtocol.controls(id, mapOf(11 to listOf(0))).isEmpty())
            assertNull(SyuSoundProtocol.command(id, mapOf(11 to listOf(0)), "loudness", 1))
        }
    }

    @Test fun `gain callbacks accumulate independently rather than replacing other bands`() {
        val first = SyuSoundProtocol.mergeSample(emptyMap(), 9, listOf(0, 10))
        val both = SyuSoundProtocol.mergeSample(first, 9, listOf(35, 17))
        assertEquals(setOf(1000, 1035), both.keys)
        assertEquals(listOf(0, 35), SyuSoundProtocol.controls(11, both).map { it.band })
        assertEquals(mapOf(1000 to listOf(10)), SyuSoundProtocol.normalizedSamples(9, listOf(0, 10)))
        assertEquals(first, SyuSoundProtocol.mergeSample(first, 9, listOf(99, 10)))
    }

    @Test fun `gain limits match selected chipset and require reported band`() {
        val samples = mapOf(1000 to listOf(20), 1035 to listOf(10))
        assertNotNull(SyuSoundProtocol.command(11, samples, "eq.0", 20))
        assertNull(SyuSoundProtocol.command(11, samples, "eq.0", 21))
        assertNull(SyuSoundProtocol.command(11, samples, "eq.1", 10))
        for (id in listOf(6, 7)) {
            assertNotNull(SyuSoundProtocol.command(id, samples, "eq.0", 24))
            assertNull(SyuSoundProtocol.command(id, samples, "eq.0", 25))
            assertNull(SyuSoundProtocol.command(id, samples, "eq.35", 10))
        }
        val cmd = SyuSoundProtocol.command(11, samples, "eq.35", 12)!!
        assertEquals(1, cmd.code)
        assertArrayEquals(intArrayOf(35, 12), cmd.ints)
    }

    @Test fun `balance and fader preserve other confirmed axis`() {
        val samples = mapOf(8 to listOf(5, 12))
        val balance = SyuSoundProtocol.command(11, samples, "balance", 16)!!
        assertEquals(3, balance.code)
        assertArrayEquals(intArrayOf(16, 12), balance.ints)
        assertArrayEquals(intArrayOf(5, 0), SyuSoundProtocol.command(7, samples, "fader", 0)!!.ints)
        assertNull(SyuSoundProtocol.command(11, samples, "balance", 17))
        assertTrue(SyuSoundProtocol.controls(11, mapOf(8 to listOf(5))).isEmpty())
        assertTrue(SyuSoundProtocol.controls(11, mapOf(8 to listOf(5, 99))).isEmpty())
    }

    @Test fun `preset and loudness frames use explicit values and exclude custom presets`() {
        val samples = mapOf(10 to listOf(0), 11 to listOf(1))
        val preset = SyuSoundProtocol.command(11, samples, "preset", 8)!!
        assertEquals(2, preset.code)
        assertArrayEquals(intArrayOf(8), preset.ints)
        val loudness = SyuSoundProtocol.command(11, samples, "loudness", 0)!!
        assertEquals(5, loudness.code)
        assertArrayEquals(intArrayOf(0), loudness.ints)
        assertNull(SyuSoundProtocol.command(11, samples, "preset", 9))
        assertNull(SyuSoundProtocol.command(11, samples, "loudness", 2))
    }

    @Test fun `subwoofer gain is limited to verified AKM profile`() {
        val samples = mapOf(26 to listOf(5))
        val command = SyuSoundProtocol.command(11, samples, "subwoofer.gain", 10)!!
        assertEquals(23, command.code)
        assertArrayEquals(intArrayOf(10), command.ints)
        assertNull(SyuSoundProtocol.command(11, samples, "subwoofer.gain", 11))
        assertNull(SyuSoundProtocol.command(11, samples, "subwoofer.gain", -1))
        assertNull(SyuSoundProtocol.command(6, samples, "subwoofer.gain", 5))
        assertEquals(mapOf(26 to listOf(5)), SyuSoundProtocol.normalizedSamples(26, listOf(5)))
    }

    @Test fun `invalid shape range and missing samples do not become zero controls`() {
        assertTrue(SyuSoundProtocol.controls(11, emptyMap()).isEmpty())
        assertTrue(SyuSoundProtocol.controls(11, mapOf(11 to listOf(4), 1000 to listOf(-1), 10 to listOf(99))).isEmpty())
        for ((field, ints) in listOf(9 to emptyList(), 8 to listOf(1), 11 to listOf(1, 0), 42 to listOf(1))) {
            assertTrue(SyuSoundProtocol.normalizedSamples(field, ints).isEmpty())
        }
    }
}
