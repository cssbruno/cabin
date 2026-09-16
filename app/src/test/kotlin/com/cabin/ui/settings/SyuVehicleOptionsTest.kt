package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cabin.R
import com.cabin.platform.FytSyuReading
import com.cabin.platform.TeyesClimateState
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyuVehicleOptionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val initial = TeyesClimateState(connected = true, profileId = 262442,
        fytSyuReadings = listOf(FytSyuReading("Cabin", 61, setOf(61), text = "Medium",
            options = mapOf(2 to "Medium", 3 to "High"), label = "Light sensitivity")))

    @Test fun `trip reset requires confirmation and invalidates on profile change`() {
        val action = com.cabin.platform.FytVehicleAction.RESET_TRIP_SINCE_START
        val state = mutableStateOf(initial.copy(fytActions = setOf(action)))
        val sent = mutableListOf<Pair<Int, com.cabin.platform.FytVehicleAction>>()
        compose.setContent { CabinTheme { ObdSettingsSection(state.value, onSyuAction = { p, a -> sent += p to a }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_syu_readings)).performClick()
        compose.onNodeWithText("Reset data since start").performClick()
        compose.runOnIdle { assertEquals(0, sent.size) }
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(listOf(262442 to action), sent) }
        compose.onNodeWithText("Reset data since start").performClick()
        compose.runOnIdle { state.value = state.value.copy(profileId = 17) }
        compose.onNodeWithText("Reset").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun `calibration confirmation names the requested action and sends nothing before confirmation`() {
        val action = com.cabin.platform.FytVehicleAction.CALIBRATE_TIRE_PRESSURE
        val sent = mutableListOf<Pair<Int, com.cabin.platform.FytVehicleAction>>()
        compose.setContent { CabinTheme { ObdSettingsSection(initial.copy(fytActions = setOf(action)), onSyuAction = { p, a -> sent += p to a }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_syu_readings)).performClick()
        compose.onNodeWithText("Calibrate tire pressure monitor").performClick()
        compose.onNodeWithText("Start tire pressure monitor calibration?").assertExists()
        compose.runOnIdle { assertEquals(0, sent.size) }
        compose.onNodeWithText("Calibrate").performClick()
        compose.runOnIdle { assertEquals(listOf(262442 to action), sent) }
    }

    @Test fun `language picker has no assumed current value and rejects changed profile`() {
        val choice = com.cabin.platform.FytVehicleChoice.LANGUAGE
        val state = mutableStateOf(initial.copy(fytChoices = mapOf(choice to mapOf(1 to "English", 2 to "简体中文"))))
        val sent = mutableListOf<Triple<Int, com.cabin.platform.FytVehicleChoice, Int>>()
        compose.setContent { CabinTheme { ObdSettingsSection(state.value, onSyuChoice = { p, c, v -> sent += Triple(p, c, v) }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_syu_readings)).performClick()
        compose.onNodeWithText("Vehicle language").performClick()
        compose.runOnIdle { assertEquals(0, sent.size) }
        compose.onNodeWithText("English").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(262442, choice, 1)), sent) }
        compose.onNodeWithText("Vehicle language").performClick()
        compose.runOnIdle { state.value = state.value.copy(profileId = 17) }
        compose.onNodeWithText("English").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, sent.size) }
    }

    @Test fun `option picker sends selected value with original profile`() {
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        compose.setContent { CabinTheme { ObdSettingsSection(initial) { p, f, v -> sent += Triple(p, f, v) } } }
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_syu_readings)).performClick()
        compose.onNodeWithText("Medium").performClick()
        compose.onNodeWithText("High").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(262442, 61, 3)), sent) }
    }

    @Test fun `profile change disables already opened option picker`() {
        val state = mutableStateOf(initial)
        val sent = mutableListOf<Triple<Int, Int, Int>>()
        compose.setContent { CabinTheme { ObdSettingsSection(state.value) { p, f, v -> sent += Triple(p, f, v) } } }
        compose.onNodeWithText(compose.activity.getString(R.string.vehicle_syu_readings)).performClick()
        compose.onNodeWithText("Medium").performClick()
        compose.runOnIdle { state.value = initial.copy(profileId = 983338) }
        compose.onNodeWithText("High").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(emptyList<Triple<Int, Int, Int>>(), sent) }
    }
}
