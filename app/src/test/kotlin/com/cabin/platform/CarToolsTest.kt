package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.diagnostics.vehicleDiagnosticReport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarToolsTest {
    private fun state(speed: Int?) = TeyesClimateState(connected = true, profileId = 262465, speedKph = speed, availableCodes = if (speed != null) setOf(89) else emptySet())
    @Test fun `trip integration excludes missing intervals and checkpoints survive restart`() {
        val saved = mutableListOf<RecordedTrip>()
        val recorder = TripRecorder { _, trip -> saved.add(trip) }
        recorder.observe(state(36), 0, 1000)
        recorder.observe(state(36), 1000, 2000)
        recorder.observe(state(36), 10_000, 11_000)
        recorder.finish()
        assertEquals(1, saved.size)
        assertEquals(.01, saved.single().kilometers, .000001)
        assertEquals(1, saved.single().seconds)
        saved.clear()
        recorder.observe(state(36), 0, 1000)
        for (second in 1..61) recorder.observe(state(36), second * 1000L, 1000 + second * 1000L)
        assertEquals(.6, saved.single().kilometers, .000001)
    }
    @Test fun `trip cost needs explicit fuel inputs and checkpoint updates are not duplicate trips`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = TripHistory(context)
        history.prefs.edit().clear().commit()
        history.save(17, RecordedTrip(1000, 2000, 10.0, 1, null))
        assertNull(history.read(17).single().estimatedCost)
        history.prefs.edit().putString("fuelRate", "8").putString("fuelPrice", "2.5").commit()
        history.save(17, RecordedTrip(1000, 3000, 20.0, 2, null))
        assertEquals(1, history.read(17).size)
        assertEquals(4.0, history.read(17).single().estimatedCost!!, .00001)
        assertTrue(history.read(18).isEmpty())
    }
    @Test fun `quiet hours cross midnight and equal endpoints cover all day`() {
        assertTrue(quietHours(23, 22, 7)); assertTrue(quietHours(6, 22, 7))
        assertFalse(quietHours(7, 22, 7)); assertFalse(quietHours(12, 22, 7))
        assertTrue(quietHours(15, 15, 15))
    }
    @Test fun `solar theme follows daylight and polar seasons`() {
        assertFalse(solarNight(Instant.parse("2026-03-20T12:00:00Z").toEpochMilli(), 0.0, 0.0))
        assertTrue(solarNight(Instant.parse("2026-03-20T00:00:00Z").toEpochMilli(), 0.0, 0.0))
        assertFalse(solarNight(Instant.parse("2026-06-21T00:00:00Z").toEpochMilli(), 89.0, 0.0))
        assertTrue(solarNight(Instant.parse("2026-12-21T12:00:00Z").toEpochMilli(), 89.0, 0.0))
    }
    @Test fun `diagnostic export excludes all untrusted strings and raw telemetry`() {
        val marker = "private-user-phone-token-location"
        val report = vehicleDiagnosticReport(TeyesClimateState(connected = true,
            controlUnavailableReason = marker, syuAir = SyuAirState(17, marker, mapOf(marker to 123), setOf(marker))))
        assertFalse(report.contains(marker))
        val json = org.json.JSONObject(report)
        assertEquals(1, json.getJSONObject("climate").getInt("supportedActions"))
        assertFalse(json.has("readings"))
    }
}
