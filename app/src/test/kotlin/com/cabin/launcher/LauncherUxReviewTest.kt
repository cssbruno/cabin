package com.cabin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.platform.AndroidPlayer
import com.cabin.platform.CabinMediaSessions
import com.cabin.platform.ProjectionHealthSnapshot
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherUxReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var preferences: LauncherPreferences
    private var manager: CabinManager? = null
    private val apps = (1..12).map { TeyesLaunchableApp("example.app$it/.Main", "App ${it.toString().padStart(2, '0')}") }

    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(LauncherAppLibrary.FILE, LauncherPreferences.FILE).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        preferences = LauncherPreferences(context)
        CabinMediaSessions.mutable.value = emptyList()
    }

    @After fun release() {
        CabinMediaSessions.mutable.value = emptyList()
        runBlocking { manager?.releaseAndWait() }
    }

    @Test fun `populated narrow drawer preserves search width and page navigation at double text size`() {
        var refreshes = 0
        compose.setContent { CabinTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(320.dp, 480.dp)) { LauncherAppDrawer(apps, preferences, { refreshes++ }, {}, {}) }
            }
        } }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertWidthIsAtLeast(200.dp)
        assertTrue("Search must leave room for app rows at 200% text", compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height <= 112f)
        compose.onNodeWithContentDescription("Page 1 / 12").assertWidthIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Page 2 / 12").performClick()
        compose.onNodeWithText("App 02").assertIsDisplayed()
        screenshot("ux-launcher-narrow-populated-200")
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Refresh apps").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.onNode(hasSetTextAction()).performTextInput("App 11")
        compose.onNode(hasText("App 11") and hasClickAction() and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 2 / 12").assertDoesNotExist()
    }

    @Test fun `empty filter offers a direct route back to installed apps`() {
        preferences.library.setFilter(LauncherAppFilter.PINNED)
        compose.setContent { CabinTheme { LauncherAppDrawer(apps, preferences, {}, {}, {}) } }
        compose.onNodeWithText("No apps in this view. Choose another filter or show all apps.").assertIsDisplayed()
        compose.onNodeWithText("Show all apps").assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("App 01").assertIsDisplayed()
        assertEquals(LauncherAppFilter.ALL, preferences.library.state.value.filter)
    }

    @Test fun `empty recent search does not instruct user to enable already enabled history`() {
        preferences.library.recordHistory(true)
        preferences.library.recordLaunch(apps.first().component)
        preferences.library.setFilter(LauncherAppFilter.RECENT)
        compose.setContent { CabinTheme { LauncherAppDrawer(apps, preferences, {}, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("unmatched")
        compose.onNodeWithText("No matching apps.").assertIsDisplayed()
        compose.onNodeWithText("No recorded app launches. Enable launch history in app library options, then open an app from Cabin.").assertDoesNotExist()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("App 01").assertIsDisplayed()
    }

    @Test fun `bulk app selection makes the full labelled row a large checkbox target`() {
        compose.setContent { CabinTheme { LauncherAppDrawer(apps.take(2), preferences, {}, {}, {}) } }
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Select several apps").performScrollTo().performClick()
        val row = compose.onNodeWithContentDescription("App 01")
        row.assertHeightIsAtLeast(64.dp).assertWidthIsAtLeast(200.dp).assertIsOff()
        compose.onNodeWithText("App 01").performClick()
        row.assertIsOn()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test fun `clear pins dialog accurately advertises and preserves undo`() {
        preferences.pin(apps.first().component)
        compose.setContent { CabinTheme { LauncherAppDrawer(apps, preferences, {}, {}, {}) } }
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Clear all pinned apps").performScrollTo().performClick()
        compose.onNodeWithText("Remove every pinned app for this driver? Apps stay installed. Undo app changes restores the pins.").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Done").performClick()
        assertTrue(preferences.state.value.favorites.isEmpty())
        compose.onNodeWithText("Undo app changes").performClick()
        assertEquals(listOf(apps.first().component), preferences.state.value.favorites)
    }

    @Test fun `changing vehicle preferences dismisses pending drawer actions without committing them`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences.pin(apps.first().component)
        val active = mutableStateOf(preferences)
        compose.setContent { CabinTheme { LauncherAppDrawer(apps, active.value, {}, {}, {}) } }
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Clear all pinned apps").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").assertIsDisplayed()
        compose.runOnIdle { active.value = LauncherPreferences(context, driverSlot = 0, vehicleProfileId = 1048874) }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
        compose.onNodeWithText("Clear all pinned apps").assertDoesNotExist()
        assertEquals(listOf(apps.first().component), active.value.state.value.favorites)
    }

    @Test fun `idle media explains how to begin and projection controls follow actual connection`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = CabinManager(context)
        val health = mutableStateOf(ProjectionHealthSnapshot())
        compose.setContent { CabinTheme { Box(Modifier.size(308.dp, 308.dp)) { UniversalMediaWidget(manager!!, health.value, false) { it() } } } }
        compose.onNodeWithText("Ready for music").assertIsDisplayed()
        compose.onNodeWithText("Play on your phone, or choose a media source above.").assertIsDisplayed()
        screenshot("ux-launcher-idle-media")
        compose.onNodeWithContentDescription("Choose media source").assertHeightIsAtLeast(56.dp).assertIsEnabled()
        listOf("Play", "Previous track", "Next track", "Phone assistant").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsNotEnabled().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        }
        compose.runOnIdle { health.value = ProjectionHealthSnapshot(connection = CabinManager.State.STREAMING, title = "Current song", artist = "Artist", playing = true) }
        compose.onNodeWithText("Current song").assertIsDisplayed()
        compose.onNodeWithText("Ready for music").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        compose.onNodeWithContentDescription("Phone assistant").assertIsEnabled()
    }

    @Test fun `Android media source stays usable without phone and unavailable session is explained`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = CabinManager(context)
        CabinMediaSessions.mutable.value = listOf(AndroidPlayer("example.music", "Local song", "Local artist", true))
        compose.setContent { CabinTheme { Box(Modifier.size(308.dp, 308.dp)) { UniversalMediaWidget(manager!!, ProjectionHealthSnapshot(), false) { it() } } } }
        compose.onNodeWithContentDescription("Choose media source").performClick()
        compose.onNodeWithText("Music app").performClick()
        compose.onNodeWithText("Local song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsEnabled()
        compose.onNodeWithContentDescription("Phone assistant").assertIsNotEnabled()
        compose.runOnIdle { CabinMediaSessions.mutable.value = emptyList() }
        compose.onNodeWithContentDescription("Play").assertIsNotEnabled()
        compose.onNodeWithText("Ready for music").assertIsDisplayed()
    }

    @Test fun `compact idle media label and controls fit the actual small dashboard tile at double text size`() {
        manager = CabinManager(ApplicationProvider.getApplicationContext())
        compose.setContent { CabinTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(188.dp, 188.dp)) { UniversalMediaWidget(manager!!, ProjectionHealthSnapshot(), false) { it() } }
            }
        } }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("No music", useUnmergedTree = true).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        screenshot("ux-launcher-compact-idle-media-200")
        val result = layout.single()
        assertFalse("The complete idle state must fit at 200% text: size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
            "lines=${result.lineCount}, widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, " +
            "lineEnds=${(0 until result.lineCount).map { result.getLineEnd(it) }}, ellipsized=${(0 until result.lineCount).map { result.isLineEllipsized(it) }}, " +
            "node=${compose.onNodeWithText("No music", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}", result.hasVisualOverflow)
        compose.onNodeWithContentDescription("Choose media source").assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/${com.cabin.BuildConfig.BUILD_TYPE}/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
