package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class FytStockMetricsTest {
    @Test fun `equivalent paths match independent of redundant branches`() {
        val expected = setOf("-3 when !reverse && right", "-3 when left && reverse")
        val observed = setOf("-3 when !left && !reverse && right", "-3 when left && right", "-3 when !right && left && reverse")
        assertTrue(FytStockMetrics.equivalent(observed, expected))
        assertFalse(FytStockMetrics.equivalent(observed - "-3 when left && right", expected))
        assertFalse(FytStockMetrics.equivalent(observed.map { it.replace("-3", "-2") }.toSet(), expected))
    }

    @Test fun `maintenance and temperatures require their live metadata`() {
        val profile = FytDetectedProfile(262442, mapOf(25 to 25, 31 to 31, 33 to 33, 179 to 135, 180 to 136, 181 to 137), "matched")
        assertEquals(mapOf(1000 to 262442), profile.normalize(mapOf(1000 to 262442, 25 to 44, 137 to 2000)))
        val raw = mapOf(1000 to 262442, 25 to 44, 31 to -3, 33 to 0, 135 to 0, 136 to 1, 137 to 2000)
        val normalized = profile.normalize(raw)
        assertEquals(44, normalized[25]); assertEquals(-3, normalized[31]); assertEquals(2000, normalized[181])
        assertFalse(normalized.containsKey(137))
        assertFalse(profile.normalize(raw + (135 to 7)).containsKey(181))
        assertFalse(profile.normalize(raw + (137 to -1)).containsKey(181))
        assertFalse(profile.normalize(raw + (33 to 2)).containsKey(25))
    }
}
