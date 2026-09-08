package com.cabin.platform

import com.cabin.CabinManager
import com.cabin.platform.obd.ObdPhase
import com.cabin.platform.obd.ObdSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TeyesEcosystemTest {
    @Test fun `connection recorder export is bounded and contains only structured observations`() {
        var now = 0L
        val store = ProjectionHealthStore { now }
        repeat(80) {
            store.connection(CabinManager.State.STREAMING)
            store.media("PRIVATE_SONG", "PRIVATE_ARTIST", true)
            store.status("PRIVATE_STATUS")
            store.event(ProjectionEventKind.USB_DETACHED)
            now += 1000
            store.connection(CabinManager.State.DISCONNECTED)
        }
        val encoded = TeyesDiagnostics.encode(store.state.value, TeyesClimateState(), 0, 29)
        val report = org.json.JSONObject(encoded)
        assertEquals(ProjectionHealthStore.MAX_EVENTS, report.getJSONArray("connectionEvents").length())
        assertEquals(ProjectionHealthStore.MAX_INCIDENTS, report.getJSONArray("streamEndSnapshots").length())
        assertTrue(report.has("appVersionCode"))
        assertFalse(encoded.contains("PRIVATE_"))
    }

    @Test fun `unknown speed needs confirmation and missing speed cannot clear movement`() {
        val guard = TeyesDrivingGuard()
        assertFalse(guard.state.value.parkedConfirmed)
        assertTrue(guard.confirmParked())
        guard.observe(20.0)
        assertFalse(guard.state.value.parkedConfirmed)
        assertFalse(guard.confirmParked())
        guard.observe(null)
        assertTrue(guard.state.value.moving)
        assertTrue(guard.confirmParked())
        assertFalse(guard.state.value.moving)
        guard.observe(20.0)
        guard.observe(0.0)
        assertFalse(guard.state.value.moving)
        assertFalse(guard.state.value.parkedConfirmed)
        assertTrue(guard.confirmParked())
    }

    @Test fun `nonfinite and negative speeds cannot unlock prior motion`() {
        val guard = TeyesDrivingGuard()
        guard.observe(30.0)
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            guard.observe(speed)
            assertTrue(guard.state.value.moving)
            assertFalse(guard.state.value.speedKnown)
        }
    }

    @Test fun `only fresh TEYES subsystem fields supply vehicle readings`() {
        val vehicle = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE, availableCodes = setOf(89, 90), speedKph = 35, engineRpm = 800)
        val obd = ObdSnapshot(phase = ObdPhase.STREAMING, speedKph = 33.0, updatedAtElapsedMs = 1_000)
        assertEquals("TEYES/SYU", teyesVehicleReadings(vehicle).speed?.source)
        assertEquals(800.0, teyesVehicleReadings(vehicle).rpm?.value)
        assertNull(teyesVehicleReadings(vehicle.copy(availableCodes = emptySet()), obd, 2_000).speed)
        assertNull(teyesVehicleReadings(vehicle.copy(connected = false), obd, 2_000).speed)
    }

    @Test fun `external OBD can never provide fallback values even when fresh`() {
        val obd = ObdSnapshot(phase = ObdPhase.STREAMING, speedKph = 0.0, engineRpm = 700.0, coolantCelsius = 80.0, ecuVoltage = 14.0, updatedAtElapsedMs = 1_000)
        for (time in listOf(0L, 2_000L, 10_000L)) {
            val readings = teyesVehicleReadings(TeyesClimateState(), obd, time)
            assertNull(readings.speed)
            assertNull(readings.rpm)
            assertNull(readings.coolant)
            assertNull(readings.voltage)
            assertFalse(readings.obdFresh)
        }
    }

    @Test fun `stale disconnected and invalid SYU speed never become a false parked reading`() {
        val live = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE, availableCodes = setOf(89, 90), speedKph = 35, engineRpm = 800)
        val guard = TeyesDrivingGuard()
        guard.observe(teyesVehicleReadings(live).speed?.value)
        for (unavailable in listOf(
            live.copy(health = TeyesTelemetryHealth.STALE, speedKph = 0),
            live.copy(connected = false, speedKph = 0),
            live.copy(availableCodes = emptySet(), speedKph = 0),
            live.copy(speedKph = -1),
            live.copy(speedKph = 401),
        )) {
            val speed = teyesVehicleReadings(unavailable).speed?.value
            assertNull(speed)
            guard.observe(speed)
            assertTrue(guard.state.value.moving)
            assertFalse(guard.state.value.speedKnown)
            assertFalse(guard.state.value.parkedConfirmed)
        }
        assertEquals(0.0, teyesVehicleReadings(live.copy(speedKph = 0)).speed?.value)
        assertNull(teyesVehicleReadings(live.copy(engineRpm = 10_001)).rpm)
    }

    @Test fun `health export excludes personal and freeform fields`() {
        val report =
            TeyesDiagnostics.encode(
                ProjectionHealthSnapshot(connection = CabinManager.State.STREAMING, title = "PRIVATE_SONG", artist = "PRIVATE_ARTIST", status = "PRIVATE_STATUS"),
                TeyesClimateState(profileId = 262465),
                ObdSnapshot(selectedAddress = "AA:BB:CC:DD:EE:FF", message = "PRIVATE_ADAPTER_NAME"),
                100,
                28,
            )
        for (secret in listOf("PRIVATE_SONG", "PRIVATE_ARTIST", "PRIVATE_STATUS", "PRIVATE_ADAPTER_NAME", "AA:BB:CC:DD:EE:FF")) assertFalse(report.contains(secret))
        assertTrue(report.contains("262465"))
        assertTrue(report.contains("configuredShortcutCount"))
        val decoded = org.json.JSONObject(report)
        assertEquals("TEYES/SYU", decoded.getString("vehicleDataSource"))
        assertEquals("LEGACY", decoded.getString("vehicleDataLayout"))
        assertEquals("Carlink normalized fields", decoded.getString("vehicleCodeNamespace"))
        assertEquals(2, decoded.getInt("schemaVersion"))
        assertFalse(report.contains("obdPhase"))
        assertFalse(report.contains("obdSupportedPids"))
    }
}
