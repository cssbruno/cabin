package com.cabin.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.cabin.BuildConfig
import com.cabin.platform.TeyesLaunchableApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherPreferencesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().clear().commit() }

    @Test fun `vehicle profiles and decoder layouts keep independent gauge choices`() {
        val first = LauncherPreferences(context, 0, 1048874)
        first.toggleGauge(VehicleGauge.SPEED)
        first.toggleGauge(VehicleGauge.SERVICE)
        assertFalse(VehicleGauge.SPEED in LauncherPreferences(context, 0, 1048874).state.value.gauges)
        assertTrue(VehicleGauge.SPEED in LauncherPreferences(context, 0, 262465).state.value.gauges)
        assertTrue(VehicleGauge.SPEED in LauncherPreferences(context, 1, 1048874).state.value.gauges)
        assertTrue(VehicleGauge.SPEED in LauncherPreferences(context, 0, 1048874, com.cabin.platform.TeyesVehicleDataLayout.CIVIC_0298).state.value.gauges)
        assertTrue(VehicleGauge.SERVICE in LauncherPreferences(context, 0, 1048874).state.value.gauges)
    }

    @Test fun `obsolete external OBD gauge choices are discarded`() {
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().putString("gauges.0", "[\"COOLANT\",\"VOLTAGE\",\"RPM\"]").commit()
        assertEquals(listOf(VehicleGauge.RPM), LauncherPreferences(context, 0, 262465).state.value.gauges)
    }

    @Test fun `gauge choices persist independently for each driver`() {
        val first = LauncherPreferences(context, 0)
        first.toggleGauge(VehicleGauge.SPEED)
        first.toggleGauge(VehicleGauge.SERVICE)
        assertFalse(VehicleGauge.SPEED in LauncherPreferences(context, 0).state.value.gauges)
        assertTrue(VehicleGauge.SERVICE in LauncherPreferences(context, 0).state.value.gauges)
        assertTrue(VehicleGauge.SPEED in LauncherPreferences(context, 1).state.value.gauges)
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().putString("gauges.0", "[\"UNKNOWN\",\"RPM\",\"RPM\"]").commit()
        assertEquals(listOf(VehicleGauge.RPM), LauncherPreferences(context, 0).state.value.gauges)
    }

    @Test fun `pins survive recreation remain ordered and belong to their driver`() {
        val first = LauncherPreferences(context, 0)
        first.pin("example.radio/.Main")
        first.pin("example.maps/.Main")
        first.pin("example.radio/.Main")
        first.move("example.maps/.Main", -1)
        assertEquals(listOf("example.maps/.Main", "example.radio/.Main"), LauncherPreferences(context, 0).state.value.favorites)
        assertTrue(LauncherPreferences(context, 1).state.value.favorites.isEmpty())
        first.unpin("example.maps/.Main")
        assertEquals(listOf("example.radio/.Main"), LauncherPreferences(context, 0).state.value.favorites)
    }

    @Test fun `pins and widget ids are bounded and widget sizing survives recreation`() {
        val prefs = LauncherPreferences(context)
        repeat(20) { prefs.pin("example.app$it/.Main") }
        repeat(10) { prefs.addWidget(it + 1) }
        prefs.resizeWidget(1, 320)
        assertEquals(8, prefs.state.value.favorites.size)
        assertEquals(listOf(1, 2, 3), prefs.state.value.widgets)
        assertEquals(320, LauncherPreferences(context, 2).state.value.widgetHeights[1])
        prefs.removeWidget(1)
        assertEquals(listOf(2, 3), LauncherPreferences(context).state.value.widgets)
    }

    @Test fun `corrupt preferences cannot create unbounded tiles or invalid ids`() {
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit()
            .putString("favorites.0", "[null,12,\"bad\",\"example.good/.Main\",\"example.good/.Main\"]")
            .putString("widgets", "[null,\"4\",-1,0,2,2]").commit()
        val prefs = LauncherPreferences(context)
        assertEquals(listOf("example.good/.Main"), prefs.state.value.favorites)
        assertEquals(listOf(2), prefs.state.value.widgets)
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().putString("favorites.0", "x".repeat(10_000)).commit()
        assertTrue(LauncherPreferences(context).state.value.favorites.isEmpty())
    }

    @Test fun `app icon and HOME open dashboard but explicit projection stays explicit`() {
        assertTrue(LauncherIntents.opensDashboard(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertTrue(LauncherIntents.opensDashboard(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertTrue(LauncherIntents.opensDashboard(null))
        assertFalse(LauncherIntents.opensDashboard(Intent("com.carlink.action.SHOW_FULLSCREEN_PROJECTION")))
        assertFalse(LauncherIntents.opensDashboard(Intent("com.carlink.action.SHOW_COMPACT_PROJECTION")))
    }

    @Test fun `only HOME or the explicit Home alias selects the launcher route`() {
        assertTrue(LauncherIntents.isHome(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))
        assertTrue(LauncherIntents.isHome(LauncherIntents.open(context)))
        assertFalse(LauncherIntents.isHome(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)))
        assertFalse(LauncherIntents.isHome(Intent("com.carlink.action.SHOW_FULLSCREEN_PROJECTION")))
        assertFalse(LauncherIntents.isHome(null))
    }

    @Test fun `Home role is exposed only in the conventional Android variant`() {
        val component = ComponentName(context.packageName, LauncherIntents.ALIAS)
        val activity = try { context.packageManager.getActivityInfo(component, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            assertNotNull(activity)
            assertEquals("com.cabin.MainActivity", activity!!.targetActivity)
            assertTrue(activity.exported)
        } else assertNull(activity)
    }

    @Test fun `app search uses labels and packages and cannot fabricate activities`() {
        val apps = listOf(TeyesLaunchableApp("example.joying.radio/.Main", "Rádio"), TeyesLaunchableApp("example.maps/.Main", "Maps"))
        assertEquals(listOf(apps[0]), filterLauncherApps(apps, " JOYING "))
        assertEquals(listOf(apps[0]), filterLauncherApps(apps, "ráDIO"))
        assertTrue(filterLauncherApps(apps, "uninstalled").isEmpty())
    }

    @Test fun `launcher never shows disconnected stale or future navigation as live`() {
        assertTrue(launcherGuidanceFresh(true, true, 10_000, 11_000))
        assertFalse(launcherGuidanceFresh(false, true, 10_000, 11_000))
        assertFalse(launcherGuidanceFresh(true, false, 10_000, 11_000))
        assertFalse(launcherGuidanceFresh(true, true, null, 11_000))
        assertFalse(launcherGuidanceFresh(true, true, 12_000, 11_000))
        assertFalse(launcherGuidanceFresh(true, true, 10_000, 40_001))
    }
}
