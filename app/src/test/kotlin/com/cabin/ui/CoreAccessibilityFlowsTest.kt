@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package com.cabin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.launcher.AcWidget
import com.cabin.launcher.ClimateWidgetActions
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.TeyesTemperatureZone
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
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreAccessibilityFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keyboardMode() {
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Unit>(
            org.robolectric.shadows.ShadowWindowManagerGlobal::class.java, "setInTouchMode",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
    }
    @Composable private fun LargeText(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        val inputMode = LocalInputModeManager.current
        SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
        CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { CabinTheme { content() } }
    }
    private fun pressEnter(node: SemanticsNodeInteraction) {
        node.performSemanticsAction(SemanticsActions.RequestFocus)
        node.performKeyInput { pressKey(Key.Enter) }
    }
    private fun focusLabels(steps: Int): Set<String> {
        val result = mutableSetOf<String>()
        repeat(steps) {
            compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().forEach { node ->
                result += node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                result += node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            }
        }
        return result
    }
    @Test fun `projection tools expose all keyboard destinations and 56dp controls at 200 percent`() {
        var settings = 0
        compose.setContent { LargeText { Column {
            Box(Modifier.width(360.dp).height(300.dp)) {
                ProjectionToolsPanel({ settings++ }, {}, Modifier.fillMaxSize(), onScreenOff = {}, onChangeDevice = {})
            }
            Button({}) { Text("Leave tools") }
        } } }
        val destinations = focusLabels(12)
        listOf("Close projection tools", "Change device", "Settings", "Screen off", "Leave tools").forEach { label ->
            assertTrue("Keyboard must reach $label; found $destinations", label in destinations)
        }
        listOf("Change device", "Settings", "Screen off").forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        }
        pressEnter(compose.onNodeWithText("Settings"))
        compose.runOnIdle { assertEquals(1, settings) }
    }
    @Test fun `health report save and cancel remain keyboard accessible with enlarged text`() {
        var saved = 0
        var cancelled = 0
        compose.setContent { LargeText { HealthReportPreview("{\"schemaVersion\":2,\"connection\":\"ready\"}", { saved++ }, { cancelled++ }) } }
        val destinations = focusLabels(16)
        assertTrue("Report save reachable: $destinations", "Choose where to save" in destinations)
        assertTrue("Report cancel reachable: $destinations", "Cancel" in destinations)
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Choose where to save").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        pressEnter(compose.onNodeWithText("Choose where to save"))
        pressEnter(compose.onNodeWithText("Cancel"))
        compose.runOnIdle { assertEquals(1, saved); assertEquals(1, cancelled) }
    }
    @Test fun `climate controls expose labeled keyboard actions and preserve feedback at 200 percent`() {
        val changes = mutableListOf<Pair<TeyesTemperatureZone, Boolean>>()
        val state = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE, profileId = 1048874,
            controlsAvailable = true, availableCodes = setOf(24, 25, 29, 31, 33), fanLevel = 3, leftTemperature = 44, rightTemperature = 46)
        compose.setContent { LargeText { Box(Modifier.width(480.dp).height(440.dp)) {
            AcWidget(state, ClimateWidgetActions(onAc = {}, onFan = {}, onTemperature = { zone, up -> changes += zone to up }), {})
        } } }
        val destinations = focusLabels(24)
        for (zone in listOf("Driver", "Passenger")) {
            for (direction in listOf("Increase", "Decrease")) {
                val label = "$direction $zone temperature"
                assertTrue("Keyboard must reach $label; found $destinations", label in destinations)
                compose.onNodeWithContentDescription(label).assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
            }
        }
        pressEnter(compose.onNodeWithContentDescription("Increase Driver temperature"))
        compose.runOnIdle { assertEquals(listOf(TeyesTemperatureZone.DRIVER to true), changes) }
        compose.onNodeWithText("22.0°C").assertIsDisplayed()
    }
}
