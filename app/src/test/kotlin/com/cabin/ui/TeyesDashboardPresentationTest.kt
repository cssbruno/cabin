package com.cabin.ui

import com.cabin.CabinManager
import com.cabin.navigation.NavigationState
import com.cabin.platform.ProjectionHealthSnapshot
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesReading
import com.cabin.platform.TeyesShortcut
import com.cabin.platform.TeyesVehicleReadings
import com.cabin.platform.obd.ObdSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class TeyesDashboardPresentationTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test
    fun `header scrolls on short narrow or enlarged text screens so actions stay reachable`() {
        assertFalse(teyesHubHeaderScrolls(800f, 480f, 1f))
        assertTrue(teyesHubHeaderScrolls(800f, 240f, 1f))
        assertTrue(teyesHubHeaderScrolls(320f, 480f, 1f))
        assertTrue(teyesHubHeaderScrolls(800f, 480f, 1.5f))
        assertTrue(teyesHubHeaderScrolls(320f, 240f, 2f))
    }

    @Test
    fun `grid adapts to head units and enlarged text without exceeding three columns`() {
        assertEquals(1, teyesHubColumnCount(320f, 1f))
        assertEquals(2, teyesHubColumnCount(800f, 1f))
        assertEquals(3, teyesHubColumnCount(1280f, 1f))
        assertEquals(3, teyesHubColumnCount(2560f, 1f))
        assertEquals(1, teyesHubColumnCount(800f, 1.5f))
        assertEquals(2, teyesHubColumnCount(1280f, 1.5f))
    }

    @Test
    fun `missing and invalid readings never masquerade as a stopped vehicle`() {
        assertEquals("—", teyesReadingValue(null, 0))
        assertEquals("—", teyesReadingValue(TeyesReading(Double.NaN, "SYU"), 0))
        assertEquals("—", teyesReadingValue(TeyesReading(Double.POSITIVE_INFINITY, "SYU"), 0))
        assertEquals("0", teyesReadingValue(TeyesReading(0.0, "SYU"), 0))
        assertEquals("800", teyesReadingValue(TeyesReading(800.0, "SYU"), 0))
    }

    @Test
    fun `known movement disables setup but signal loss allows only confirmation path`() {
        assertFalse(model().copy(moving = true, speedKnown = true).canConfigure)
        assertTrue(model().copy(moving = true, speedKnown = false).canConfigure)
        assertTrue(model().copy(moving = false, speedKnown = false).canConfigure)
    }

    @Test
    fun `guidance requires active streaming and recent nonfuture phone data`() {
        val live =
            model().copy(
                projection = ProjectionHealthSnapshot(connection = CabinManager.State.STREAMING),
                navigation = NavigationState(status = 1, lastUpdateElapsedRealtimeMs = 1_000),
                nowMs = 31_000,
            )
        assertTrue(live.guidanceFresh)
        assertFalse(live.copy(nowMs = 31_001).guidanceFresh)
        assertFalse(live.copy(nowMs = 999).guidanceFresh)
        assertFalse(live.copy(navigation = live.navigation.copy(lastUpdateElapsedRealtimeMs = null)).guidanceFresh)
        assertFalse(live.copy(navigation = live.navigation.copy(lastUpdateElapsedRealtimeMs = -1)).guidanceFresh)
        assertFalse(live.copy(navigation = live.navigation.copy(status = 0)).guidanceFresh)
        assertFalse(live.copy(projection = ProjectionHealthSnapshot()).guidanceFresh)
    }

    @Test
    fun `vehicle health counts only reported FYT fields and excludes profile metadata`() {
        assertEquals("Waiting for FYT vehicle service", teyesVehicleFieldSummary(testResources, TeyesClimateState(availableCodes = setOf(89, 90))))
        assertEquals("0 available FYT vehicle fields", teyesVehicleFieldSummary(testResources, TeyesClimateState(connected = true, availableCodes = setOf(1000))))
        assertEquals(
            "3 available FYT vehicle fields",
            teyesVehicleFieldSummary(testResources, TeyesClimateState(connected = true, availableCodes = setOf(89, 90, 137, 1000))),
        )
        assertEquals("Projection ready", teyesConnectionHeadline(testResources, CabinManager.State.STREAMING))
    }

    @Test
    fun `dashboard temperatures require both fresh temperature and unit`() {
        val civic = TeyesClimateState(connected = true, profileId = 1048874, leftTemperature = 44, rightTemperature = 46)
        assertEquals("—", teyesDashboardTemperature(civic.copy(availableCodes = setOf(25, 31)), left = true))
        assertEquals("—", teyesDashboardTemperature(civic.copy(availableCodes = setOf(33)), left = false))
        val fresh = civic.copy(availableCodes = setOf(25, 31, 33))
        assertEquals(formatClimateTemperature(44, false), teyesDashboardTemperature(fresh, left = true))
        assertEquals(formatClimateTemperature(46, false), teyesDashboardTemperature(fresh, left = false))
        assertEquals("—", teyesDashboardTemperature(fresh.copy(connected = false), left = true))
    }

    @Test
    fun `door summary distinguishes missing partial and complete reports`() {
        val civic = TeyesClimateState(connected = true, profileId = 1048874)
        assertEquals("Door data unavailable · No fresh FYT reading", teyesDoorStatus(testResources, civic))
        assertEquals(
            "Reported doors closed · Some door readings unavailable",
            teyesDoorStatus(testResources, civic.copy(availableCodes = setOf(37))),
        )
        assertEquals("All reported doors closed", teyesDoorStatus(testResources, civic.copy(availableCodes = (36..41).toSet())))
        assertEquals("DRIVER DOOR OPEN", teyesDoorStatus(testResources, civic.copy(availableCodes = setOf(37), frontLeftDoorOpen = true)))
        assertEquals(
            "Door data unavailable · Vehicle service disconnected",
            teyesDoorStatus(testResources, civic.copy(connected = false, frontLeftDoorOpen = true)),
        )
    }

    @Test
    fun `old external OBD shortcut mappings never appear among vehicle app actions`() {
        val mappings =
            mapOf(
                TeyesShortcut.OBD to "external.adapter.app/.MainActivity",
                TeyesShortcut.EQUALIZER to "teyes.vendor.equalizer/.MainActivity",
                TeyesShortcut.DASHCAM to null,
                TeyesShortcut.TPMS to " ",
            )
        assertEquals(mapOf(TeyesShortcut.EQUALIZER to "teyes.vendor.equalizer/.MainActivity"), teyesVisibleShortcuts(mappings))
        assertTrue(teyesVisibleShortcuts(mapOf(TeyesShortcut.OBD to "legacy.app/.MainActivity")).isEmpty())
    }

    private fun model() =
        TeyesDashboardUiState(
            profileName = "Driver",
            projection = ProjectionHealthSnapshot(),
            vehicle = TeyesClimateState(),
            readings = TeyesVehicleReadings(null, null, null, null, false),
            obd = ObdSnapshot(),
            navigation = NavigationState(),
            nowMs = 0,
            shortcuts = emptyMap(),
            moving = false,
            speedKnown = false,
            climatePanelAvailable = false,
        )
}
