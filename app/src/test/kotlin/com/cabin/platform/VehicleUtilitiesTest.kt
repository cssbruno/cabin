package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleUtilitiesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val history get() = TripHistory(context)
    @Before fun reset() { history.prefs.edit().clear().commit() }
    private fun trip(start: Long, km: Double = 1.0, seconds: Long = 60, cost: Double? = null) =
        RecordedTrip(start, start + seconds * 1000, km, seconds, cost)

    @Test fun `calendar filtering respects local midnight daylight saving and future samples`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T17:00:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val firstWeekDay = Instant.parse("2026-03-02T05:00:00Z").toEpochMilli()
        val trips = listOf(trip(midnight - 1), trip(midnight), trip(now + 1), trip(firstWeekDay), trip(firstWeekDay - 1))
        assertEquals(listOf(midnight), selectTrips(trips, TripPeriod.TODAY, TripSort.NEWEST, now, zone).map { it.start })
        assertEquals(listOf(midnight, midnight - 1, firstWeekDay), selectTrips(trips, TripPeriod.WEEK, TripSort.NEWEST, now, zone).map { it.start })
        assertEquals(5, selectTrips(trips, TripPeriod.ALL, TripSort.NEWEST, now, zone).size)
    }

    @Test fun `sort and summary preserve unknown costs and use weighted average speed`() {
        val trips = listOf(trip(1000, 1.0, 60), trip(2000, 3.0, 180, 2.5), trip(3000, 2.0, 360, 0.0))
        assertEquals(listOf(2000L, 3000L, 1000L), selectTrips(trips, TripPeriod.ALL, TripSort.COST).map { it.start })
        assertEquals(listOf(2000L, 3000L, 1000L), selectTrips(trips, TripPeriod.ALL, TripSort.DISTANCE).map { it.start })
        assertEquals(listOf(3000L, 2000L, 1000L), selectTrips(trips, TripPeriod.ALL, TripSort.DURATION).map { it.start })
        val summary = summarizeTrips(trips)
        assertEquals(6.0, summary.kilometers, .00001)
        assertEquals(600L, summary.seconds)
        assertEquals(36.0, summary.averageKph!!, .00001)
        assertEquals(2.5, summary.knownCost!!, .00001)
        assertEquals(2, summary.costCount)
        assertNull(summarizeTrips(emptyList()).averageKph)
        assertNull(summarizeTrips(listOf(trips[0])).knownCost)
    }

    @Test fun `CSV has explicit metric UTC columns and blank unknown costs`() {
        val csv = tripCsv(listOf(trip(1000, 1.25, 60), trip(2000, 2.5, 120, 3.75)))
        assertEquals("start_utc,end_utc,distance_km,duration_seconds,estimated_cost", csv.lineSequence().first())
        assertTrue(csv.contains("1970-01-01T00:00:01Z,1970-01-01T00:01:01Z,1.25,60,\r\n"))
        assertTrue(csv.endsWith(",2.5,120,3.75\r\n"))
        assertFalse(csv.contains("profile"))
        assertFalse(csv.contains("parking"))
    }

    @Test fun `deletion persists prevents checkpoint resurrection and undo restores original cost`() {
        history.prefs.edit().putString("fuelRate", "8").putString("fuelPrice", "2.5").commit()
        val sample = trip(1000)
        history.save(17, sample)
        history.save(18, sample)
        val saved = history.read(17).single()
        history.delete(17, sample.start)
        history.save(17, sample.copy(end = 121000, seconds = 120, kilometers = 2.0))
        assertTrue(history.read(17).isEmpty())
        assertEquals(1, history.read(18).size)
        history.restore(17, saved)
        assertEquals(saved, history.read(17).single())
        history.save(17, sample.copy(end = 121000, seconds = 120, kilometers = 2.0))
        assertEquals(2.0, history.read(17).single().kilometers, .00001)
        history.clear(17)
        history.save(17, sample)
        assertTrue(history.read(17).isEmpty())
        assertEquals(1, history.read(18).size)
    }

    @Test fun `retention evicts oldest records while preserving another car and rejecting invalid inputs`() {
        repeat(40) { history.save(17, trip((it + 1) * 1000L)) }
        history.save(18, trip(1000))
        history.setRetention(17, 25)
        assertEquals(25, history.read(17).size)
        assertEquals(16_000L, history.read(17).first().start)
        assertEquals(25, history.retention(17))
        assertEquals(100, history.retention(18))
        history.save(17, trip(41_000))
        assertEquals(17_000L, history.read(17).first().start)
        history.setRetention(17, -1)
        assertEquals(25, history.retention(17))
        history.save(0, trip(1000))
        history.save(17, trip(50_000, Double.NaN))
        assertTrue(history.read(0).isEmpty())
        assertEquals(25, history.read(17).size)
    }

    @Test fun `elapsed duration survives wall clock drift and legacy arrays remain readable`() {
        history.prefs.edit().putString("trips.17", """[{"start":1000,"end":60990,"km":1.0,"seconds":60,"cost":null}]""").commit()
        assertEquals(60L, history.read(17).single().seconds)
        history.save(17, RecordedTrip(1000, 60990, 1.0, 60, null))
        assertEquals(60L, history.read(17).single().seconds)
    }

    @Test fun `cost recalculation uses explicit inputs only and remains profile scoped`() {
        history.save(17, trip(1000, 20.0))
        history.save(18, trip(1000, 20.0))
        assertFalse(history.recalculateCosts(17))
        history.prefs.edit().putString("fuelRate", "8").putString("fuelPrice", "2,5").commit()
        assertTrue(history.recalculateCosts(17))
        assertEquals(4.0, history.read(17).single().estimatedCost!!, .00001)
        assertNull(history.read(18).single().estimatedCost)
        history.prefs.edit().putString("fuelPrice", "NaN").commit()
        assertFalse(history.recalculateCosts(17))
        assertEquals(4.0, history.read(17).single().estimatedCost!!, .00001)
        assertNull(tripFuelEstimate(2.0, ".01", "2"))
        assertNull(tripFuelEstimate(2.0, "10", "Infinity"))
    }

    @Test fun `maintenance snooze uses today for overdue dates and completion schedules configured interval`() {
        val today = LocalDate.of(2026, 9, 30)
        val schedule = MaintenanceSchedule(history.prefs, 17)
        schedule.schedule(today.minusDays(20))
        schedule.snooze(7, today)
        assertEquals(today.plusDays(7), schedule.scheduled())
        schedule.snooze(30, today)
        assertEquals(today.plusDays(37), schedule.scheduled())
        schedule.setInterval(180)
        schedule.complete(today)
        assertEquals(today, schedule.completed())
        assertEquals(today.plusDays(180), schedule.scheduled())
        assertNull(MaintenanceSchedule(history.prefs, 18).completed())
        schedule.setInterval(-1)
        assertEquals(180, schedule.interval())
        MaintenanceSchedule(history.prefs, 0).complete(today)
        assertNull(MaintenanceSchedule(history.prefs, 0).completed())
    }
}
