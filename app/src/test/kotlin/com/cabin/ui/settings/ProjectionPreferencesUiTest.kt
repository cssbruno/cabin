package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.BuildConfig
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.ProjectionControlSide
import com.cabin.platform.ProjectionPreferencesState
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Android/Compose layout and semantics; no manager or vehicle service is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectionPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `control position changes independently of climate notices`() {
        val harness = PreferencesHarness()
        showPreferences(harness, isTeyes = true)
        compose.onNodeWithText("Right side").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Left side").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Right side").assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(ProjectionControlSide.LEFT, harness.state.controlSide)
            assertEquals(ClimateNoticeMode.SUMMARY, harness.state.climateNoticeMode)
        }
    }

    @Test
    fun `default controls match the active build flavor`() {
        val harness = PreferencesHarness()
        showPreferences(harness, isTeyes = BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE)

        assertFullyReachable("Focus view")
        compose.onNodeWithText("Focus view").assertIsOn()
        if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) {
            compose.onNodeWithText("Return to projection when ready").performScrollTo().assertIsOn()
            compose.onNodeWithText("Vehicle speed HUD").performScrollTo().assertIsOff()
            assertManualClimateOnly()
            compose.onNodeWithText("Open climate panel").assertDoesNotExist()
        } else {
            compose.onNodeWithText("Return to projection when ready").assertDoesNotExist()
            compose.onNodeWithText("Vehicle speed HUD").assertDoesNotExist()
            compose.onNodeWithText("A/C update notices").assertDoesNotExist()
            compose.onNodeWithText("Summary · recommended").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(ProjectionPreferencesState(), harness.state)
            assertTrue(harness.events.isEmpty())
        }
    }

    @Test
    fun `each setting invokes only its callback and automatic climate choices are absent`() {
        val harness = PreferencesHarness()
        showPreferences(harness, isTeyes = true)

        assertFullyReachable("Focus view")
        compose.onNodeWithText("Focus view").performClick().assertIsOff()
        assertFullyReachable("Return to projection when ready")
        compose.onNodeWithText("Return to projection when ready").performClick().assertIsOff()
        assertFullyReachable("Vehicle speed HUD")
        compose.onNodeWithText("Vehicle speed HUD").performClick().assertIsOn()

        compose.onNodeWithText("Open climate panel").assertDoesNotExist()

        compose.runOnIdle {
            assertEquals(
                listOf("focus:false", "return:false", "hud:true"),
                harness.events,
            )
            assertEquals(ProjectionPreferencesState(false, true, ClimateNoticeMode.SUMMARY, false), harness.state)
        }
    }

    @Test
    fun `narrow large font layout keeps every toggle and radio row fully reachable`() {
        showPreferences(PreferencesHarness(), isTeyes = true, width = 360, height = 480, fontScale = 1.5f)
        allControlLabels.forEach(::assertFullyReachable)
        assertManualClimateOnly()
    }

    @Test
    fun `short landscape layout scrolls every control into a full tap target`() {
        showPreferences(PreferencesHarness(), isTeyes = true, width = 480, height = 240)
        allControlLabels.forEach(::assertFullyReachable)
        assertManualClimateOnly()
    }

    private fun showPreferences(
        harness: PreferencesHarness,
        isTeyes: Boolean,
        width: Int = 800,
        height: Int = 480,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CabinTheme(darkTheme = fontScale == 1f) {
                    Box(Modifier.width(width.dp).height(height.dp).testTag(VIEWPORT)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            ProjectionPreferencesContent(
                                state = harness.state,
                                isTeyes = isTeyes,
                                onFocusControls = { value ->
                                    harness.events.add("focus:$value")
                                    harness.state = harness.state.copy(focusControls = value)
                                },
                                onVehicleHud = { value ->
                                    harness.events.add("hud:$value")
                                    harness.state = harness.state.copy(vehicleHud = value)
                                },
                                onClimateNoticeMode = { mode ->
                                    harness.events.add("notice:${mode.name}")
                                    harness.state = harness.state.copy(climateNoticeMode = mode)
                                },
                                onReturnWhenReady = { value ->
                                    harness.events.add("return:$value")
                                    harness.state = harness.state.copy(returnWhenReady = value)
                                },
                                onControlSide = { side -> harness.state = harness.state.copy(controlSide = side) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertFullyReachable(label: String) {
        val node = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val unclipped = node.getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        assertTrue("$label must be fully inside its viewport: $bounds in $viewport", bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
        assertTrue("$label must not be only a clipped sliver: $bounds", bounds.height >= 55f)
        // This suite uses mdpi, so px and dp match even when fontScale is increased.
        assertTrue("The complete $label row must be visible: $bounds vs $unclipped", bounds.height + 1f >= (unclipped.bottom - unclipped.top).value)
        assertTrue("$label must fit the available width: $bounds in $viewport", bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f)
    }

    private fun assertManualClimateOnly() {
        compose.onNodeWithText("No automatic notices. Open A/C manually whenever needed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("climate_notice_choices").assertDoesNotExist()
    }

    private class PreferencesHarness {
        var state by mutableStateOf(ProjectionPreferencesState())
        val events = mutableListOf<String>()
    }

    companion object {
        private const val VIEWPORT = "projection_preferences_viewport"
        private val allControlLabels =
            listOf("Focus view", "Return to projection when ready", "Vehicle speed HUD")
    }
}
