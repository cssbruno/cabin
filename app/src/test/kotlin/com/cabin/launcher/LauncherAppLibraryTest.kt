package com.cabin.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesLaunchableApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LauncherAppLibraryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val radio = TeyesLaunchableApp("example.radio/.Main", "Rádio FM")
    private val maps = TeyesLaunchableApp("example.maps/.Main", "Maps")
    private val music = TeyesLaunchableApp("example.music/.Main", "Music")
    private val apps = listOf(radio, maps, music)

    @Before fun reset() {
        context.getSharedPreferences(LauncherAppLibrary.FILE, 0).edit().clear().commit()
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().clear().commit()
    }

    @Test fun `multiword search ignores accents and keeps original names searchable after rename`() {
        assertEquals(listOf(radio), filterLauncherApps(apps, "  FM radio "))
        assertEquals(listOf(radio), filterLauncherApps(apps, "example radio fm"))
        assertTrue(filterLauncherApps(apps, "radio maps").isEmpty())
        val state = LauncherLibraryState(names = mapOf(radio.component to "Road music"))
        assertEquals("Road music", launcherLibraryApps(apps, "radio road", emptyList(), state).single().label)
    }

    @Test fun `sorts by pinned order names recency and counts with stable name ties`() {
        val usage = mapOf(radio.component to LauncherAppUsage(200, 1), maps.component to LauncherAppUsage(100, 5))
        fun sorted(sort: LauncherAppSort) = launcherLibraryApps(apps.reversed(), "", listOf(radio.component),
            LauncherLibraryState(sort = sort, usage = usage)).map { it.component }
        assertEquals(listOf(radio.component, maps.component, music.component), sorted(LauncherAppSort.PINNED))
        assertEquals(listOf(maps.component, music.component, radio.component), sorted(LauncherAppSort.NAME))
        assertEquals(listOf(radio.component, music.component, maps.component), sorted(LauncherAppSort.NAME_DESCENDING))
        assertEquals(listOf(radio.component, maps.component, music.component), sorted(LauncherAppSort.RECENT))
        assertEquals(listOf(maps.component, radio.component, music.component), sorted(LauncherAppSort.FREQUENT))
    }

    @Test fun `hidden apps are excluded from normal pinned and recent results and can be restored`() {
        val library = LauncherAppLibrary(context, 0)
        library.hide(radio.component, true)
        library.recordHistory(true)
        library.recordLaunch(radio.component, 100)
        fun shown(filter: LauncherAppFilter) = launcherLibraryApps(apps, "", listOf(radio.component), library.state.value.copy(filter = filter))
        assertEquals(listOf(maps, music), shown(LauncherAppFilter.ALL))
        assertTrue(shown(LauncherAppFilter.PINNED).isEmpty())
        assertTrue(shown(LauncherAppFilter.RECENT).isEmpty())
        assertEquals(listOf(radio), shown(LauncherAppFilter.HIDDEN))
        library.hide(radio.component, false)
        assertEquals(listOf(radio), shown(LauncherAppFilter.PINNED))
        assertEquals(listOf(radio), shown(LauncherAppFilter.RECENT))
    }

    @Test fun `all library choices persist separately for each driver`() {
        val library = LauncherAppLibrary(context, 1)
        library.setSort(LauncherAppSort.NAME_DESCENDING)
        library.setFilter(LauncherAppFilter.PINNED)
        library.showPackages(true)
        library.hide(radio.component, true)
        library.rename(maps.component, "  Navegação  ")
        library.recordHistory(true)
        library.recordLaunch(maps.component, 100)
        val restored = LauncherAppLibrary(context, 1).state.value
        assertEquals(library.state.value, restored)
        assertEquals("Navegação", restored.names[maps.component])
        assertEquals(LauncherLibraryState(), LauncherAppLibrary(context, 2).state.value)
        library.rename(maps.component, " ")
        assertEquals("Maps", library.state.value.label(maps))
    }

    @Test fun `history is opt in and disabling erases it without losing customizations`() {
        val library = LauncherAppLibrary(context, 0)
        library.recordLaunch(radio.component, 50)
        assertTrue(library.state.value.usage.isEmpty())
        library.recordHistory(true)
        library.recordLaunch(radio.component, 100)
        library.recordLaunch(radio.component, 200)
        assertEquals(LauncherAppUsage(200, 2), library.state.value.usage[radio.component])
        library.rename(radio.component, "FM")
        library.recordHistory(false)
        library.recordLaunch(radio.component, 300)
        assertTrue(LauncherAppLibrary(context, 0).state.value.usage.isEmpty())
        assertEquals("FM", library.state.value.names[radio.component])
        library.recordHistory(true)
        library.recordLaunch(maps.component, 400)
        library.clearHistory()
        assertTrue(library.state.value.recordHistory)
        assertTrue(library.state.value.usage.isEmpty())
    }

    @Test fun `corrupt library entries cannot inject invalid components names or usage`() {
        context.getSharedPreferences(LauncherAppLibrary.FILE, 0).edit().putString("driver.0",
            """{"sort":"UNKNOWN","recordHistory":true,"hidden":["bad","example.radio/.Main"],"names":{"bad":"Oops","example.maps/.Main":42},"usage":{"example.radio/.Main":{"last":100,"count":-1}}}""").commit()
        val state = LauncherAppLibrary(context, 0).state.value
        assertEquals(LauncherAppSort.PINNED, state.sort)
        assertEquals(setOf(radio.component), state.hidden)
        assertTrue(state.names.isEmpty())
        assertTrue(state.usage.isEmpty())
    }

    @Test fun `bulk pin tools preserve order driver isolation and widgets`() {
        val preferences = LauncherPreferences(context, 0)
        apps.forEach { preferences.pin(it.component) }
        preferences.addWidget(1)
        preferences.moveToEdge(music.component, true)
        assertEquals(listOf(music.component, radio.component, maps.component), preferences.state.value.favorites)
        preferences.moveToEdge(music.component, false)
        assertEquals(apps.map { it.component }, preferences.state.value.favorites)
        preferences.removeMissingFavorites(setOf(radio.component, music.component))
        assertEquals(listOf(radio.component, music.component), preferences.state.value.favorites)
        val otherDriver = LauncherPreferences(context, 1)
        otherDriver.pin(maps.component)
        preferences.clearFavorites()
        assertTrue(preferences.state.value.favorites.isEmpty())
        assertEquals(listOf(maps.component), otherDriver.state.value.favorites)
        assertEquals(listOf(1), preferences.state.value.widgets)
    }
}
