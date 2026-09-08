package com.cabin.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesVehicleDataLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardLayoutTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun prefs(vehicle: Int = 1, driver: Int = 0) = DashboardPreferences(context, driver, vehicle, TeyesVehicleDataLayout.LEGACY)
    @Before fun reset() { context.getSharedPreferences("carlink_dashboard_v1", 0).edit().clear().commit() }
    @Test fun `every module supports all grid sizes and persists its dimensions`() {
        DashboardModule.entries.forEach { module ->
            for (height in 1..2) for (width in 1..4) {
                reset()
                val p = prefs()
                if (module == DashboardModule.PROJECTION) {
                    assertTrue(p.movePage(1, 2))
                } else assertTrue(p.add(module, 2, if (module == DashboardModule.WIDGET) 51 else 0))
                val id = p.state.value.tiles.first { it.module == module && it.page == 2 }.id
                assertTrue("$module $width x $height", p.resize(id, width, height))
                val saved = prefs().state.value.tiles.first { it.id == id }
                assertEquals(width, saved.width)
                assertEquals(height, saved.height)
            }
        }
    }

    @Test fun `corner resizing rejects overlap and never edits CarPlay`() {
        val p = prefs()
        val original = p.state.value
        assertFalse(p.resizeInPlace(4, 3, 1))
        assertFalse(p.resizeInPlace(1, 2, 2))
        assertEquals(original, p.state.value)
        assertTrue(p.resizeInPlace(5, 1, 1))
        assertEquals(original.tiles.filter { it.id != 5 }, p.state.value.tiles.filter { it.id != 5 })
        assertEquals(2, p.state.value.tiles.first { it.id == 5 }.x)
        assertEquals(1, prefs().state.value.tiles.first { it.id == 5 }.width)
    }

    @Test fun `grid rejects overlap overflow and duplicate projection`() {
        val defaults = DashboardLayout()
        assertTrue(validDashboard(defaults))
        assertFalse(validDashboard(defaults.copy(tiles = defaults.tiles + DashboardTile(20, DashboardModule.CLOCK, 0, 0, 0, 1, 1))))
        assertFalse(validDashboard(defaults.copy(tiles = listOf(DashboardTile(1, DashboardModule.MEDIA, 0, 3, 1, 2, 1)))))
        assertFalse(validDashboard(defaults.copy(tiles = defaults.tiles + DashboardTile(21, DashboardModule.PROJECTION, 2, 0, 0, 2, 2), pages = 3)))
    }
    @Test fun `resize retains neighbors and failed mutations retain layout`() {
        val p = prefs()
        val before = p.state.value
        assertFalse(p.resize(1, 4, 2))
        assertEquals(before, p.state.value)
        assertTrue(p.resize(1, 2, 2))
        assertEquals(before.tiles.filter { it.id != 1 }, p.state.value.tiles.filter { it.id != 1 })
        assertFalse(p.move(1, -1, 0))
        assertEquals(2, p.state.value.tiles.first { it.id == 1 }.width)
    }
    @Test fun `projection moves to new page and layout persists per vehicle and driver`() {
        val p = prefs()
        assertTrue(p.movePage(1, 2))
        assertTrue(p.resize(1, 4, 2))
        assertEquals(3, prefs().state.value.pages)
        assertEquals(2, prefs().state.value.tiles.first { it.id == 1 }.page)
        assertEquals(4, prefs().state.value.tiles.first { it.id == 1 }.width)
        assertEquals(0, prefs(2).state.value.tiles.first { it.id == 1 }.page)
        assertEquals(0, prefs(driver = 1).state.value.tiles.first { it.id == 1 }.page)
    }
    @Test fun `a hosted Android widget is not duplicated into two modules`() {
        val p = prefs()
        assertTrue(p.add(DashboardModule.WIDGET, 2, 51))
        val before = p.state.value
        assertFalse(p.add(DashboardModule.WIDGET, 2, 51))
        assertEquals(before, p.state.value)
    }
    @Test fun `module additions page cap and corrupted storage are bounded`() {
        val p = prefs()
        assertFalse(p.add(DashboardModule.PROJECTION, 2))
        assertTrue(p.add(DashboardModule.DOORS, 2))
        assertFalse(p.add(DashboardModule.WIDGET, 3, 0))
        repeat(3) { assertTrue(p.addPage()) }
        assertFalse(p.addPage())
        context.getSharedPreferences("carlink_dashboard_v1", 0).edit().putString("0.1.LEGACY", "bad json").commit()
        assertEquals(DashboardLayout(), prefs().state.value)
    }
}
