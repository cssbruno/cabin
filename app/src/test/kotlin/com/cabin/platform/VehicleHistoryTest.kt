package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleHistoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val state get() = TeyesClimateState(connected = true, profileId = 1376590,
        syuVehicle = SyuVehicleProtocol.decode(1376590, mapOf(146 to 80, 150 to 2)))
    @Before fun reset() { context.getSharedPreferences("cabin_tire_history", 0).edit().clear().commit() }
    @Test fun `history preserves missing wheels and rate limits without accepting disconnected data`() {
        val history = TireHistory(context)
        history.record(state, 0, 1000)
        history.record(state, 59_999, 60_999)
        history.record(state.copy(connected = false), 60_000, 61_000)
        history.record(state.copy(profileId = 17), 60_000, 61_000)
        assertEquals(1, history.read(1376590).size)
        assertEquals(220.0, history.read(1376590).single().tires[0].pressureKpa!!, 0.01)
        assertNull(history.read(1376590).single().tires[1].pressureKpa)
        history.record(state, 60_000, 61_000)
        assertEquals(2, TireHistory(context).read(1376590).size)
        assertTrue(history.read(17).isEmpty())
        history.clear(1376590)
        assertTrue(history.read(1376590).isEmpty())
    }
    @Test fun `history caps storage and recovers from malformed input`() {
        val history = TireHistory(context)
        repeat(725) { history.record(state, it * 60_000L, 1000 + it * 60_000L) }
        assertEquals(720, history.read(1376590).size)
        assertEquals(301_000L, history.read(1376590).first().time)
        context.getSharedPreferences("cabin_tire_history", 0).edit().putString("1376590", "broken").commit()
        assertTrue(history.read(1376590).isEmpty())
        history.record(state, 800 * 60_000L, 800 * 60_000L)
        assertEquals(1, history.read(1376590).size)
    }
    @Test fun `alerts ignore stale door flags and disconnected readings`() {
        val data = state.copy(hoodOpen = true, frontLeftDoorOpen = true, oilLifePercent = 0,
            oilServiceDistance = -1, availableCodes = setOf(36, 137, 181))
        val alerts = vehicleAlerts(data)
        assertEquals(4, alerts.size)
        assertTrue(alerts.any { it.detail == R.string.vehicle_tire_low })
        assertTrue(alerts.any { it.label == R.string.alert_hood })
        assertFalse(alerts.any { it.label == R.string.alert_front_left })
        assertTrue(vehicleAlerts(data.copy(connected = false)).isEmpty())
        assertTrue(vehicleAlerts(TeyesClimateState(connected = true, hoodOpen = true)).isEmpty())
    }
}
