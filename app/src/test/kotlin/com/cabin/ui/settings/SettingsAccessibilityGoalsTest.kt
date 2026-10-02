@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cabin.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.R
import com.cabin.ui.theme.CabinTheme
import com.cabin.ui.theme.cabinHighContrastScheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAccessibilityGoalsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `high contrast roles exceed normal text contrast target`() {
        listOf(false, true).forEach { dark ->
            val c = cabinHighContrastScheme(dark)
            listOf(c.onSurface to c.surface, c.onSurfaceVariant to c.surfaceContainerHighest,
                c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                c.onSecondary to c.secondary, c.onTertiary to c.tertiary,
                c.onError to c.error, c.onErrorContainer to c.errorContainer).forEach { (a, b) ->
                val ratio = (maxOf(a.luminance(), b.luminance()) + .05) / (minOf(a.luminance(), b.luminance()) + .05)
                assertTrue("Contrast $ratio, dark=$dark", ratio >= 4.5)
            }
        }
    }
    @Test fun `shared toggles stay reachable at double font scale and keyboard operable`() {
        var checked = false
        compose.setContent { CabinTheme {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.size(800.dp, 480.dp).verticalScroll(rememberScrollState())) {
                    SettingsToggle("Remember settings", false, "Keep this preference on this device", { checked = it })
                }
            }
        } }
        val toggle = compose.onNodeWithText("Remember settings")
        toggle.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        toggle.performSemanticsAction(SemanticsActions.RequestFocus)
        toggle.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(checked) }
    }
    @Test fun `search ignores accents and selects a concrete destination`() {
        val entry = SettingSearchEntry(SettingsTab.CONTROL, R.string.goal_accessibility)
        assertEquals(1, searchSettings(listOf(entry to "Acessibilidade e áudio"), "audio").size)
        assertTrue(searchSettings(listOf(entry to "Acessibilidade"), "unrelated").isEmpty())
        var selected: SettingsTab? = null
        compose.setContent { CabinTheme { SettingsSearchDialog(onDismiss = {}, onSelect = { tab, _ -> selected = tab }) } }
        compose.onNode(hasSetTextAction()).performTextInput("High contrast")
        compose.onNode(hasText("High contrast") and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(SettingsTab.CONTROL, selected) }
    }
    @Test fun `accessibility preferences persist and expose labeled toggle state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(ACCESSIBILITY_FILE, 0).edit().clear().commit()
        compose.setContent { CabinTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccessibilitySettingsSection() } } }
        compose.onNodeWithText("Reduce motion").performScrollTo().performClick()
        compose.onNodeWithText("Reduce motion").assertIsOn()
        compose.runOnIdle { assertTrue(context.getSharedPreferences(ACCESSIBILITY_FILE, 0).getBoolean("reduced_motion", false)) }
    }
    @Test fun `settings scroll restores after recreation and isolates driver vehicle and tab`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("cabin_settings_navigation_v1", 0).edit().clear().commit()
        var scopeKey by mutableStateOf("driver1.vehicle7.CAR")
        lateinit var scroll: ScrollState
        val restore = StateRestorationTester(compose)
        restore.setContent { CabinTheme {
            CompositionLocalProvider(LocalSettingsNavigationKey provides scopeKey) {
                scroll = rememberSettingsScrollState()
                Column(Modifier.height(200.dp).verticalScroll(scroll)) { repeat(60) { androidx.compose.material3.Text("Row $it") } }
            }
        } }
        compose.runOnIdle { runBlocking { scroll.scrollTo(400) } }
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(400, scroll.value); scopeKey = "driver2.vehicle7.CAR" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, scroll.value); scopeKey = "driver1.vehicle7.CONTROL" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, scroll.value); scopeKey = "driver1.vehicle7.CAR" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(400, scroll.value) }
    }
    @Test fun `offline section help explains the effect and recovery without leaving settings`() {
        compose.setContent { CabinTheme { SettingsSection("Display setting", "Keeps the display readable") {} } }
        compose.onNodeWithText("Help").performClick()
        compose.onAllNodesWithText("Keeps the display readable").onLast().assertIsDisplayed()
        compose.onNodeWithText("To recover,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This help works offline.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Display setting").assertIsDisplayed()
    }

    @Test fun `long offline help remains scrollable with double font scale`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { CabinTheme { SettingsSection("Display setting", "Keeps the display readable. ".repeat(30)) {} } }
        compose.onNodeWithText("Help").performClick()
        compose.onNodeWithText("To recover,", substring = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Close").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        compose.onNodeWithText("Close").assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Display setting").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h480dp-port-mdpi")
    fun `settings search preserves room for the query and close at double font scale`() {
        RuntimeEnvironment.setFontScale(2f)
        var closed = false
        compose.setContent { CabinTheme { SettingsSearchDialog(onDismiss = { closed = true }, onSelect = { _, _ -> }) } }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertWidthIsAtLeast(160.dp).performTextInput("NoSuchSetting")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        compose.onNodeWithText("No settings match.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun `quiet hour drafts survive recreation reject invalid hours and apply atomically`() {
        var applied: Pair<Int, Int>? = null
        val restore = StateRestorationTester(compose)
        restore.setContent { CabinTheme { Column(Modifier.verticalScroll(rememberScrollState())) { QuietHoursEditor(22, 7) { start, end -> applied = start to end } } } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val start = compose.onNodeWithText(context.getString(R.string.tools_start_hour))
        start.performTextReplacement("99")
        compose.onNodeWithText(context.getString(R.string.action_apply)).assertIsNotEnabled()
        assertNull(applied)
        start.performTextReplacement("21")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("21").assertExists()
        assertNull(applied)
        compose.onNodeWithText(context.getString(R.string.action_apply)).performScrollTo().performClick()
        assertEquals(21 to 7, applied)
    }
    @Test fun `adapter setting labels route to their exact dialog tab in every locale`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("en", "pt", "es", "fr", "de", "it").forEach { language ->
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val resources = context.createConfigurationContext(configuration).resources
            adapterSearchSections.forEach { (resource, tab) -> assertEquals(tab, adapterSearchTab(resources, resources.getString(resource))) }
        }
    }

    @Test fun `recomposer motion scale honors app preference and system disabled animations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(ACCESSIBILITY_FILE, 0)
        prefs.edit().clear().commit()
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 2f)
        CabinMotionDurationScale(context).use { scale ->
            assertEquals(2f, scale.scaleFactor)
            prefs.edit().putBoolean("reduced_motion", true).commit()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(0f, scale.scaleFactor)
            prefs.edit().putBoolean("reduced_motion", false).commit()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(2f, scale.scaleFactor)
            assertSame(scale, scale[androidx.compose.ui.MotionDurationScale])
        }
        android.provider.Settings.Global.putFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

}
