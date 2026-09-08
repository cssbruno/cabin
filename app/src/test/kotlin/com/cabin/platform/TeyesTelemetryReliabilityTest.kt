package com.cabin.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeyesTelemetryReliabilityTest {
    @Test
    fun `documented Civic variants use their own fresh AC and fan fields`() {
        listOf(1048874, 1114410, 196906, 262442).forEach { profile ->
            assertTrue(TeyesClimateControlPolicy.supports(profile))
            assertTrue(TeyesClimateControlPolicy.canControl(true, profile, mapOf(24 to 1, 29 to 3)))
            assertFalse(TeyesClimateControlPolicy.canControl(true, profile, mapOf(30 to 1, 35 to 3)))
            assertFalse(TeyesClimateControlPolicy.canControl(false, profile, mapOf(24 to 1, 29 to 3)))
            assertFalse(TeyesClimateControlPolicy.canControl(true, profile, mapOf(24 to 2, 29 to 3)))
            assertFalse(TeyesClimateControlPolicy.canControl(true, profile, mapOf(24 to 1, 29 to 8)))
            assertFalse(TeyesClimateControlPolicy.canControl(true, profile, mapOf(24 to 1)))
        }
        // Same decoder family does not establish the vehicle's command interface.
        assertFalse(TeyesClimateControlPolicy.supports((99 shl 16) or 298))
    }

    @Test
    fun `Civic 0298 settings cannot leak into unrelated vehicle readings`() {
        val raw = mapOf(1000 to 1048874, 24 to 1, 29 to 3, 25 to 44, 36 to 1, 89 to 80, 137 to 1, 94 to 3)
        assertEquals(
            mapOf(1000 to 1048874, 24 to 1, 29 to 3, 25 to 44, 89 to 80),
            TeyesClimateControlPolicy.climateValues(1048874, raw),
        )
        assertEquals(raw, TeyesClimateControlPolicy.climateValues(262465, raw))
    }

    @Test
    fun `legacy motion is preserved while reference seat fields never become motion`() {
        val raw = mapOf(89 to 72, 90 to 2500, 149 to 72, 151 to 2500)
        assertEquals(mapOf(89 to 72, 90 to 2500), TeyesClimateControlPolicy.climateValues(1048874, raw))
        assertTrue(TeyesClimateControlPolicy.climateValues(1048874, raw, TeyesVehicleDataLayout.CIVIC_0298).isEmpty())
    }

    @Test
    fun `reference climate and doors normalize without leaking colliding raw fields`() {
        val raw =
            mapOf(
                1000 to 1048874, 11 to 1, 21 to 4, 18 to 1, 19 to 0, 20 to 1,
                0 to 0, 1 to 1, 2 to 0, 3 to 0, 4 to 1, 5 to 0, 36 to 1, 137 to 1,
            )
        val normalized = TeyesClimateControlPolicy.climateValues(1048874, raw, TeyesVehicleDataLayout.CIVIC_0298)
        assertEquals(
            mapOf(
                1000 to 1048874, 24 to 1, 29 to 4, 28 to 1, 26 to 0, 27 to 1,
                36 to 0, 37 to 1, 38 to 0, 39 to 0, 40 to 1, 41 to 0,
            ),
            normalized,
        )
        assertTrue(TeyesClimateControlPolicy.canControl(true, 1048874, normalized))
        assertFalse(normalized.containsKey(137))
        assertFalse(normalized.containsKey(33))
    }

    @Test
    fun `reference rejects invalid flags and incomplete service metadata`() {
        for (raw in listOf(
            mapOf(181 to 800),
            mapOf(179 to 0, 181 to 800),
            mapOf(179 to 2, 180 to 0, 181 to 800),
            mapOf(179 to 0, 180 to 2, 181 to 800),
            mapOf(179 to 0, 180 to 0, 181 to -1),
        )) {
            assertTrue(TeyesClimateControlPolicy.climateValues(1048874, raw, TeyesVehicleDataLayout.CIVIC_0298).isEmpty())
        }
        val invalid = mapOf(0 to -1, 1 to 255, 11 to 2, 21 to 8, 18 to 255)
        assertTrue(TeyesClimateControlPolicy.climateValues(1048874, invalid, TeyesVehicleDataLayout.CIVIC_0298).isEmpty())
    }

    @Test
    fun `service distance is never oil percent and stays profile and dialect scoped`() {
        val raw = mapOf(179 to 1, 180 to 1, 181 to 50, 137 to 80)
        for (profile in listOf(1048874, 1114410, 196906, 262442)) {
            assertEquals(
                mapOf(179 to 1, 180 to 1, 181 to 50),
                TeyesClimateControlPolicy.climateValues(profile, raw, TeyesVehicleDataLayout.CIVIC_0298),
            )
            assertTrue(TeyesClimateControlPolicy.climateValues(profile, raw).isEmpty())
        }
        assertEquals(mapOf(137 to 80), TeyesClimateControlPolicy.climateValues(262465, raw, TeyesVehicleDataLayout.CIVIC_0298))
    }

    @Test
    fun `reference door and service freshness are independent and metadata cannot outlive samples`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(1, 1, 0L)
        samples.update(179, 0, 0L)
        samples.update(180, 1, 0L)
        samples.update(181, 500, 1000L)

        fun normalized(now: Long) = TeyesClimateControlPolicy.climateValues(1048874, samples.snapshot(now), TeyesVehicleDataLayout.CIVIC_0298)
        assertEquals(1, normalized(29_999L)[37])
        assertFalse(normalized(30_000L).containsKey(37))
        assertEquals(500, normalized(299_999L)[181])
        assertFalse(normalized(300_000L).containsKey(181))
        samples.clear()
        assertTrue(normalized(300_001L).isEmpty())
    }

    @Test
    fun `Civic control permission expires with climate samples`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(24, 1, 0L)
        samples.update(29, 3, 0L)
        assertTrue(TeyesClimateControlPolicy.canControl(true, 1048874, samples.snapshot(59_999L)))
        assertFalse(TeyesClimateControlPolicy.canControl(true, 1048874, samples.snapshot(60_000L)))
    }

    @Test
    fun `unknown profile never enables climate writes`() {
        val values = mapOf(30 to 1, 35 to 3)
        assertTrue(TeyesClimateControlPolicy.canControl(true, 262465, values))
        listOf(0, 298, -1, 262466).forEach { profile ->
            assertFalse(TeyesClimateControlPolicy.canControl(true, profile, values))
        }
    }

    @Test
    fun `disconnected stale or invalid climate samples disable writes`() {
        assertFalse(TeyesClimateControlPolicy.canControl(false, 262465, mapOf(30 to 1, 35 to 3)))
        assertFalse(TeyesClimateControlPolicy.canControl(true, 262465, emptyMap()))
        assertFalse(TeyesClimateControlPolicy.canControl(true, 262465, mapOf(30 to 1)))
        assertFalse(TeyesClimateControlPolicy.canControl(true, 262465, mapOf(30 to 5, 35 to 3)))
        assertFalse(TeyesClimateControlPolicy.canControl(true, 262465, mapOf(30 to 1, 35 to 8)))
    }

    @Test
    fun `motion expires independently of continuing climate callbacks`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(89, 72, 1_000L)
        samples.update(90, 2_500, 1_000L)
        samples.update(24, 1, 5_999L)
        assertEquals(72, samples.snapshot(5_999L)[89])
        assertNull(samples.snapshot(6_000L)[89])
        assertNull(samples.snapshot(6_000L)[90])
        assertEquals(1, samples.snapshot(6_000L)[24])
    }

    @Test
    fun `stale door data is unavailable not a current closed reading`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(37, 0, 0)
        samples.update(38, 1, 0)
        assertEquals(0, samples.snapshot(29_999L)[37])
        assertFalse(samples.snapshot(30_000L).containsKey(37))
        assertFalse(samples.snapshot(30_000L).containsKey(38))
    }

    @Test
    fun `invalid vehicle values never become available`() {
        val samples = TeyesTelemetryFreshness()
        mapOf(89 to -1, 90 to 10_001, 137 to 101, 36 to 255).forEach { (code, value) ->
            samples.update(code, value, 100L)
        }
        assertTrue(samples.snapshot(100L).isEmpty())
    }

    @Test
    fun `oil life lasts longer than rapidly changing measurements`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(137, 80, 0L)
        samples.update(89, 40, 0L)
        assertEquals(80, samples.snapshot(299_999L)[137])
        assertNull(samples.snapshot(299_999L)[89])
        assertNull(samples.snapshot(300_000L)[137])
    }

    @Test
    fun `repeated same sample refreshes age without indicating a change`() {
        val samples = TeyesTelemetryFreshness()
        assertFalse(samples.update(89, 40, 0L))
        assertFalse(samples.update(89, 40, 4_000L))
        assertEquals(40, samples.snapshot(5_000L)[89])
        assertTrue(samples.update(89, 41, 5_000L))
    }

    @Test
    fun `disconnect clears every sample and its timestamp`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(1000, 262465, 0L)
        samples.update(89, 100, 0L)
        samples.clear()
        assertTrue(samples.snapshot(1L).isEmpty())
        assertNull(samples.lastUpdateElapsedRealtimeMs)
    }

    @Test
    fun `profile persists within connection but backward clocks invalidate samples`() {
        val samples = TeyesTelemetryFreshness()
        samples.update(1000, 262465, 100L)
        assertEquals(262465, samples.snapshot(1_000_000L)[1000])
        assertTrue(samples.snapshot(99L).isEmpty())
    }

    @Test
    fun `initial unchanged and non climate callbacks never open panel`() {
        val policy = TeyesClimatePopupPolicy()
        policy.reset(100L)
        assertFalse(policy.shouldShow(true, true, 101L))
        assertFalse(policy.shouldShow(false, true, 3_000L))
        assertFalse(policy.shouldShow(true, false, 3_000L))
        assertTrue(policy.shouldShow(true, true, 3_000L))
    }

    @Test
    fun `climate burst is debounced and reconnect resets popup suppression`() {
        val policy = TeyesClimatePopupPolicy()
        policy.reset(0L)
        assertTrue(policy.shouldShow(true, true, 2_000L))
        assertFalse(policy.shouldShow(true, true, 2_599L))
        assertTrue(policy.shouldShow(true, true, 2_600L))
        policy.reset(3_000L)
        assertFalse(policy.shouldShow(true, true, 3_100L))
        assertTrue(policy.shouldShow(true, true, 5_000L))
    }

    @Test
    fun `rebind uses bounded exponential backoff and can reset after stability`() {
        val policy = TeyesTelemetryReconnectPolicy()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), List(5) { policy.nextDelayMs() })
        repeat(10) { assertNull(policy.nextDelayMs()) }
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }
}
