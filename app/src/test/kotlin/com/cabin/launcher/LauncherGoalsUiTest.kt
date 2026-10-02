@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cabin.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.theme.CabinTheme
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
class LauncherGoalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var preferences: LauncherPreferences
    private val apps = listOf(TeyesLaunchableApp("example.radio/.Main", "Radio"), TeyesLaunchableApp("example.maps/.Main", "Maps"))
    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(LauncherAppLibrary.FILE, LauncherPreferences.FILE).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        preferences = LauncherPreferences(context)
    }
    @Test fun `bulk pin preview commits together and one undo restores all`() {
        compose.setContent { CabinTheme { Box(Modifier.size(1024.dp, 600.dp)) { LauncherAppDrawer(apps, preferences, {}, {}, {}) } } }
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Select several apps").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Radio").performClick()
        compose.onNodeWithContentDescription("Maps").performClick()
        compose.onNodeWithText("Pin to Home").performClick()
        assertTrue(preferences.state.value.favorites.isEmpty())
        compose.onNodeWithText("Confirm").performClick()
        assertEquals(2, preferences.state.value.favorites.size)
        compose.onNodeWithText("Undo app changes").performClick()
        assertTrue(preferences.state.value.favorites.isEmpty())
    }
    @Test fun `folder name draft survives recreation and only save commits`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CabinTheme { LauncherFolderDialog(preferences.library, {}) } }
        compose.onNodeWithText("Folder name").performTextInput("Travel")
        assertTrue(preferences.library.state.value.folders.isEmpty())
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Travel").assertExists()
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("Travel"), preferences.library.state.value.folders.values.toList())
    }
    @Test fun `reorder draft survives recreation while arrow controls preserve saved order`() {
        preferences.bulk(apps.map { it.component }.toSet(), LauncherBulkAction.PIN)
        var applied: List<String>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CabinTheme { PinnedReorderDialog(preferences.state.value.favorites, apps, preferences.library.state.value, { applied = it }, {}) } }
        compose.onNodeWithContentDescription("Move Radio down").performClick()
        assertEquals(apps.map { it.component }, preferences.state.value.favorites)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save").performClick()
        assertEquals(apps.reversed().map { it.component }, applied)
    }
    @Test fun `package changes refresh only while observer remains composed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var visible by mutableStateOf(true)
        var changes = 0
        compose.setContent { if (visible) ObserveLauncherPackages(context) { changes++ } }
        compose.runOnIdle { context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.parse("package:example.radio"))) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, changes); visible = false }
        compose.waitForIdle()
        compose.runOnIdle { context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:example.radio"))) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, changes) }
    }
    @Test fun `widget resize has bounded keyboard and screen reader actions`() {
        var width = 0
        var height = 0
        compose.setContent { CabinTheme { WidgetResizeHandle(DashboardTile(1, DashboardModule.CLOCK, 0, 0, 0, 2, 2),
            80.dp, 80.dp, onResize = { w, h -> width = w; height = h }) } }
        val handle = compose.onNodeWithTag("resize-1")
        handle.assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        handle.performSemanticsAction(SemanticsActions.RequestFocus)
        handle.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(3, width); assertEquals(2, height) }
        val actions = handle.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(4, actions.size)
        compose.runOnIdle { assertTrue(actions.first { it.label == "Increase height" }.action()); assertEquals(3, height) }
    }

    @Test fun `short drawer keeps search options and app actions reachable at double font size`() {
        preferences.library.showPackages(true)
        compose.setContent { CabinTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.size(480.dp, 180.dp)) { LauncherAppDrawer(listOf(apps[0]), preferences, {}, {}, {}) }
            }
        } }
        compose.onNodeWithContentDescription("App library options").assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Manage").assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("example.radio").assertIsDisplayed()
        compose.runOnIdle { preferences.library.setCompact(true) }
        compose.onNodeWithContentDescription("Manage").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("example.radio").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

}
