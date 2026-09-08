package com.cabin.navigation

import com.cabin.navigation.compose.ComposedIconStore
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavigationRouteLifecycleTest {
    private var wasBitmapComposeEnabled = true

    @Before
    fun setUp() {
        wasBitmapComposeEnabled = ComposedIconStore.bitmapComposeEnabled
        ComposedIconStore.setBitmapComposeEnabled(false)
        NavigationStateManager.clear()
        ShadowSystemClock.advanceBy(Duration.ofMillis(100))
    }

    @After
    fun tearDown() {
        NavigationStateManager.clear()
        ComposedIconStore.setBitmapComposeEnabled(wasBitmapComposeEnabled)
    }

    @Test
    fun `disconnect cannot restore the previous route from a status or distance update`() {
        loadRoute("Old Road", 1)
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 100))
        assertEquals("Old Road", NavigationStateManager.state.value.roadName)

        NavigationStateManager.clear()
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1))
        NavigationStateManager.onNaviJson(mapOf("NaviRemainDistance" to 500))

        assertNull(ComposedIconStore.currentRoute())
        assertNull(NavigationStateManager.state.value.roadName)
        assertEquals(0, NavigationStateManager.state.value.maneuverType)
        assertEquals(500, NavigationStateManager.state.value.remainDistance)
    }

    @Test
    fun `fresh route works after a disconnected session`() {
        loadRoute("Old Road", 1)
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1))
        NavigationStateManager.clear()

        loadRoute("New Road", 2)
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 300))

        assertEquals("New Road", NavigationStateManager.state.value.roadName)
        assertEquals(2, NavigationStateManager.state.value.maneuverType)
    }

    @Test
    fun `hard reroute keeps new guidance on subsequent distance only updates`() {
        loadRoute("Old Road", 1)
        NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 100))
        NavigationStateManager.onNaviJson(
            mapOf("NaviManeuverType" to 2, "NaviRoadName" to "Changed Road", "NaviRemainDistance" to 250),
        )
        assertEquals("Changed Road", NavigationStateManager.state.value.roadName)

        NavigationStateManager.onNaviJson(mapOf("NaviRemainDistance" to 200))

        assertNull(ComposedIconStore.currentRoute())
        assertEquals("Changed Road", NavigationStateManager.state.value.roadName)
        assertEquals(2, NavigationStateManager.state.value.maneuverType)
        assertEquals(200, NavigationStateManager.state.value.remainDistance)

        loadRoute("Replacement Road", 3)
        NavigationStateManager.onNaviJson(mapOf("NaviRemainDistance" to 180))
        assertEquals("Replacement Road", NavigationStateManager.state.value.roadName)
    }

    @Test
    fun `both navigation stop signals invalidate a loaded route`() {
        for (status in listOf(0, 2)) {
            loadRoute("Old Road", 1)
            NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1))
            NavigationStateManager.onNaviJson(mapOf("NaviStatus" to status))
            NavigationStateManager.onNaviJson(mapOf("NaviStatus" to 1, "NaviRemainDistance" to 100))
            assertNull(ComposedIconStore.currentRoute())
            assertNull(NavigationStateManager.state.value.roadName)
        }
    }

    private fun loadRoute(road: String, maneuverType: Int) {
        val fields = ByteArrayOutputStream()
        DataOutputStream(fields).use { output ->
            output.writeShort(5)
            output.writeShort(3)
            output.writeByte(maneuverType)
            val name = road.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            output.writeShort(4 + name.size)
            output.writeShort(4)
            output.write(name)
        }
        val frame = ByteArrayOutputStream()
        DataOutputStream(frame).use { output ->
            output.writeShort(0x4040)
            output.writeShort(18 + fields.size())
            output.writeShort(0x5202)
            output.write(byteArrayOf(0, 6, 0, 0, 0, 42, 0, 6, 0, 1))
            output.writeShort(0)
            output.write(fields.toByteArray())
            output.writeByte(0)
        }
        val hex = frame.toByteArray().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(1, ComposedIconStore.populateFromIap2m(hex))
    }
}
