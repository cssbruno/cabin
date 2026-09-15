package com.cabin.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.platform.SyuRadioState
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1024dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyuRadioScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `radio tuning uses callbacks and preset overwrite needs explicit selection`() {
        val edits = mutableListOf<Pair<Int,Int?>>()
        compose.setContent { CabinTheme {
            RadioControls(SyuRadioState(true, mapOf(0 to 65536, 1 to 10170, 165536 to 9950)), false,
                { code, slot -> edits += code to slot })
        } }
        compose.onNodeWithText("101.70 MHz").assertIsDisplayed()
        compose.onNodeWithText("Seek up").performClick()
        assertEquals(listOf(5 to null), edits)
        compose.onNodeWithText("Presets").performClick()
        compose.onNodeWithText("1 · 99.50 MHz").performClick()
        assertEquals(7 to 65536, edits.last())
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Replace preset 1 with the current station?").assertIsDisplayed()
        assertEquals(2, edits.size)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, edits.size)
    }
    @Test fun `missing feedback disables tuning`() {
        compose.setContent { CabinTheme { RadioControls(SyuRadioState(true), false, { _, _ -> error("No reading") }) } }
        compose.onNodeWithText("Seek up").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for station readings…").assertIsDisplayed()
    }
    @Test fun `moving disables tuning even with live readings`() {
        compose.setContent { CabinTheme {
            RadioControls(SyuRadioState(true, mapOf(0 to 65536, 1 to 10170)), true, { _, _ -> error("Moving") })
        } }
        compose.onNodeWithText("Seek down").assertIsNotEnabled()
    }
}
