package com.cabin.navigation

import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavigationReceptionHealthTest {
    @Before
    fun setUp() {
        NavigationStateManager.clear()
        com.cabin.navigation.compose.ComposedIconStore.clear()
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    }

    @After
    fun tearDown() {
        NavigationStateManager.clear()
        com.cabin.navigation.compose.ComposedIconStore.clear()
    }

    @Test
    fun `active status alone does not invent zero distance or ETA`() {
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1))
        val state = NavigationStateManager.state.value
        assertTrue(state.isActive)
        assertEquals(SystemClock.elapsedRealtime(), state.lastUpdateElapsedRealtimeMs)
        assertFalse(state.hasManeuverDistance)
        assertFalse(state.hasEta)
    }

    @Test
    fun `explicit zero and incremental updates preserve field availability`() {
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 0))
        assertTrue(NavigationStateManager.state.value.hasManeuverDistance)
        assertFalse(NavigationStateManager.state.value.hasEta)
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
        NavigationStateManager.onNaviJson(mapOf("NaviTimeToDestination" to 0))
        val state = NavigationStateManager.state.value
        assertTrue(state.hasManeuverDistance)
        assertTrue(state.hasEta)
        assertEquals(0, state.remainDistance)
        assertEquals(0, state.timeToDestination)
        assertEquals(SystemClock.elapsedRealtime(), state.lastUpdateElapsedRealtimeMs)
    }

    @Test
    fun `repeated identical guidance refreshes receive timestamp`() {
        val update = mapOf("NaviStatus" to 1, "NaviRemainDistance" to 500)
        NavigationStateManager.onNaviJson(update)
        val first = NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        NavigationStateManager.onNaviJson(update)
        assertTrue(NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs!! > first!!)
    }

    @Test
    fun `unknown and empty payloads cannot keep guidance fresh`() {
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1))
        val stamp = NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        NavigationStateManager.onNaviJson(emptyMap())
        NavigationStateManager.onNaviJson(mapOf("UnrecognizedField" to 123))
        assertEquals(stamp, NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs)
    }

    @Test
    fun `invalid distance and ETA never become available`() {
        listOf(-1, Double.NaN, Double.POSITIVE_INFINITY, Int.MAX_VALUE.toLong() + 1L, "0", 1.5).forEach { invalid ->
            NavigationStateManager.onNaviJson(mapOf("NaviRemainDistance" to invalid, "NaviTimeToDestination" to invalid))
            assertFalse(NavigationStateManager.state.value.hasManeuverDistance)
            assertFalse(NavigationStateManager.state.value.hasEta)
            assertNull(NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs)
        }
    }

    @Test
    fun `Android Auto maneuver burst carries health through next step branch`() {
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviOrderType" to 5, "NaviTurnSide" to 1))
        ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        NavigationStateManager.onNaviJson(mapOf("NaviOrderType" to 6, "NaviRemainDistance" to 200, "NaviTimeToDestination" to 600))
        val state = NavigationStateManager.state.value
        assertTrue(state.hasNextStep)
        assertTrue(state.hasManeuverDistance)
        assertTrue(state.hasEta)
        assertEquals(200, state.remainDistance)
        assertEquals(SystemClock.elapsedRealtime(), state.lastUpdateElapsedRealtimeMs)
    }

    @Test
    fun `CarPlay and Android Auto stop signals reset receive health`() {
        listOf(0, 2).forEach { stop ->
            NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 50, "NaviTimeToDestination" to 100))
            NavigationStateManager.onNaviJson(mapOf("NaviStatus" to stop))
            assertNull(NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs)
            assertFalse(NavigationStateManager.state.value.hasManeuverDistance)
            assertFalse(NavigationStateManager.state.value.hasEta)
        }
    }

    @Test
    fun `disconnect resets navigation freshness and availability`() {
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 50, "NaviTimeToDestination" to 100))
        NavigationStateManager.clear()
        assertNull(NavigationStateManager.state.value.lastUpdateElapsedRealtimeMs)
        assertFalse(NavigationStateManager.state.value.hasManeuverDistance)
        assertFalse(NavigationStateManager.state.value.hasEta)
    }
}
