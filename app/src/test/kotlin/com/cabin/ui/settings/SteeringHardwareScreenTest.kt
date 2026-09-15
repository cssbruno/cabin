package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.platform.*
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1000dp-h1200dp-mdpi")
class SteeringHardwareScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `disconnected and moving states cannot start learning`() {
        var state by mutableStateOf(SteeringHardwareState())
        var moving by mutableStateOf(false)
        var starts = 0
        compose.setContent { CabinTheme { SteeringHardwareControls(state, moving, { starts++ }, { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText("Start learning").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(connected = true, feedbackSeen = true); moving = true }
        compose.onNodeWithText("Start learning").assertIsNotEnabled()
        compose.runOnIdle { moving = false }
        compose.onNodeWithText("Start learning").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }
    @Test fun `clear requires explicit confirmation and rechecks movement`() {
        var moving by mutableStateOf(false)
        var clears = 0
        val state = SteeringHardwareState(connected = true, feedbackSeen = true)
        compose.setContent { CabinTheme { SteeringHardwareControls(state, moving, {}, { _, _ -> }, {}, { clears++ }, {}) } }
        compose.onNodeWithText("Clear factory learning").performClick()
        compose.runOnIdle { assertEquals(0, clears); moving = true }
        compose.onAllNodesWithText("Clear factory learning").filter(hasClickAction()).onLast().assertIsNotEnabled()
        compose.runOnIdle { moving = false }
        compose.onAllNodesWithText("Clear factory learning").filter(hasClickAction()).onLast().performClick()
        compose.runOnIdle { assertEquals(1, clears) }
    }
}
