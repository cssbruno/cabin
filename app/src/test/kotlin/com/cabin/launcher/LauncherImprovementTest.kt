package com.cabin.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.platform.TeyesVehicleDataLayout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherImprovementTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val radio = TeyesLaunchableApp("example.radio/.Main", "Rádio FM")
    private val maps = TeyesLaunchableApp("example.maps/.Main", "Maps")
    @Before fun clear() {
        listOf(LauncherAppLibrary.FILE, LauncherPreferences.FILE, "carlink_dashboard_v1").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @Test fun `folders survive restart and remain driver isolated with removable membership`() {
        val library = LauncherAppLibrary(context, 1)
        val folder = library.createFolder("Travel")!!
        library.moveToFolder(maps.component, folder)
        library.renameFolder(folder, "Trips")
        val restored = LauncherAppLibrary(context, 1)
        assertEquals("Trips", restored.state.value.folders[folder])
        assertEquals(folder, restored.state.value.appFolders[maps.component])
        assertTrue(LauncherAppLibrary(context, 0).state.value.folders.isEmpty())
        restored.removeFolder(folder)
        assertTrue(restored.state.value.appFolders.isEmpty())
    }
    @Test fun `one undo restores a whole bulk action without undoing launch history`() {
        val prefs = LauncherPreferences(context, 1)
        prefs.library.recordHistory(true)
        prefs.bulk(setOf(radio.component, maps.component), LauncherBulkAction.PIN)
        prefs.library.recordLaunch(maps.component, 200)
        assertEquals(2, prefs.state.value.favorites.size)
        assertTrue(prefs.undoLibraryChange())
        assertTrue(prefs.state.value.favorites.isEmpty())
        assertEquals(1, prefs.library.state.value.usage[maps.component]?.launches)
        assertFalse(LauncherPreferences(context, 0).canUndo.value)
        prefs.bulk(setOf(radio.component, maps.component), LauncherBulkAction.HIDE)
        assertEquals(2, prefs.library.state.value.hidden.size)
        prefs.undoLibraryChange()
        assertTrue(prefs.library.state.value.hidden.isEmpty())
    }
    @Test fun `rename undo and bounded history preserve valid state`() {
        val prefs = LauncherPreferences(context)
        repeat(25) { prefs.library.rename(radio.component, "Radio $it") }
        var count = 0
        while (prefs.undoLibraryChange()) count++
        assertEquals(20, count)
        assertEquals("Radio 4", prefs.library.state.value.names[radio.component])
    }
    @Test fun `recent installation does not require recording launches and ignores future timestamps`() {
        val now = 100 * 86_400_000L
        val state = LauncherLibraryState(filter = LauncherAppFilter.INSTALLED, recentDays = 7)
        assertEquals(listOf(radio), launcherLibraryApps(listOf(radio, maps), "", emptyList(), state,
            mapOf(radio.component to now - 86_400_000, maps.component to now - 30 * 86_400_000), now))
        assertTrue(launcherLibraryApps(listOf(radio), "", emptyList(), state, mapOf(radio.component to now + 1), now).isEmpty())
    }
    @Test fun `accent matches keep source spelling and offsets`() {
        assertEquals(listOf(0..4), launcherMatchRanges("Rádio FM", "radio"))
        assertEquals(listOf(6..7, 0..4), launcherMatchRanges("Rádio FM", "fm radio"))
    }
    @Test fun `pin reorder requires same set and persists exact order`() {
        val prefs = LauncherPreferences(context)
        prefs.bulk(setOf(radio.component, maps.component), LauncherBulkAction.PIN)
        assertFalse(prefs.reorderFavorites(listOf(radio.component)))
        assertTrue(prefs.reorderFavorites(listOf(maps.component, radio.component)))
        assertEquals(listOf(maps.component, radio.component), LauncherPreferences(context).state.value.favorites)
        prefs.undoLibraryChange()
        assertEquals(listOf(radio.component, maps.component), prefs.state.value.favorites)
    }
    @Test fun `page reordering retains tile membership and selected page after restart`() {
        val prefs = DashboardPreferences(context, 1, 2, TeyesVehicleDataLayout.LEGACY)
        prefs.renamePage(0, "Drive")
        prefs.selectPage(0)
        val first = prefs.state.value.tiles.filter { it.page == 0 }.map { it.id }
        assertTrue(prefs.reorderPage(0, 1))
        val restored = DashboardPreferences(context, 1, 2, TeyesVehicleDataLayout.LEGACY).state.value
        assertEquals("Drive", restored.pageNames[1])
        assertEquals(1, restored.selectedPage)
        assertEquals(first, restored.tiles.filter { it.page == 1 }.map { it.id })
    }
    @Test fun `duplicate page never steals live widget binding or projection and assigns fresh tile IDs`() {
        val original = DashboardLayout(1, listOf(
            DashboardTile(1, DashboardModule.PROJECTION, 0, 0, 0, 2, 2),
            DashboardTile(2, DashboardModule.WIDGET, 0, 2, 0, 2, 2, widgetId = 42),
            DashboardTile(3, DashboardModule.CLOCK, 0, 4, 0, 2, 2)), mapOf(0 to "Drive"))
        val copy = dashboardDuplicatePage(original, 0)!!
        assertEquals(2, copy.pages)
        assertEquals(1, copy.tiles.count { it.module == DashboardModule.PROJECTION })
        assertEquals(listOf(42), copy.tiles.filter { it.module == DashboardModule.WIDGET }.map { it.widgetId })
        assertEquals(DashboardModule.CLOCK, copy.tiles.single { it.page == 1 }.module)
        assertEquals(4, copy.tiles.map { it.id }.distinct().size)
        assertTrue(validDashboard(copy))
    }
    @Test fun `unavailable shortcuts distinguish disabled removed and launch failure`() {
        val component = android.content.ComponentName("example.disabled", "example.disabled.Main")
        val info = android.content.pm.ActivityInfo().apply {
            packageName = component.packageName; name = component.className; enabled = false; exported = true
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = component.packageName; enabled = true }
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).addOrUpdateActivity(info)
        assertEquals(ShortcutAvailability.DISABLED, shortcutAvailability(context, component.flattenToString()))
        org.robolectric.Shadows.shadowOf(context.packageManager).removeActivity(component)
        assertEquals(ShortcutAvailability.REMOVED, shortcutAvailability(context, component.flattenToString()))
        val failedContext = object : android.content.ContextWrapper(context) {
            override fun getPackageManager(): android.content.pm.PackageManager = throw IllegalStateException("unavailable")
        }
        assertEquals(ShortcutAvailability.FAILED, shortcutAvailability(failedContext, component.flattenToString()))
    }

}
