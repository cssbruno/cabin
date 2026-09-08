package com.cabin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class SettingsInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `advanced sections hide controls until explicitly opened and cancel on collapse`() {
        var cancellations = 0
        compose.setContent {
            MaterialTheme {
                SettingsDisclosure("Button setup", "Optional", onCollapse = { cancellations++ }) {
                    Text("Learn button")
                }
            }
        }
        compose.onNodeWithText("Learn button").assertDoesNotExist()
        compose.onNodeWithText("Button setup").assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Learn button").assertIsDisplayed()
        compose.onNodeWithText("Button setup").performClick()
        compose.onNodeWithText("Learn button").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun `whole toggle row changes exactly once and exposes accessible state`() {
        var changes = 0
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            MaterialTheme {
                SettingsToggle("Recover video", checked, "Applies immediately") {
                    changes++
                    checked = it
                }
            }
        }
        compose.onNodeWithText("Recover video").assertIsOff().assertHeightIsAtLeast(56.dp).performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test
    fun `adapter choice is selectable through its label and disabled choices do not apply`() {
        var selected = ""
        compose.setContent {
            var current by remember { mutableStateOf("") }
            MaterialTheme {
                Column {
                    SettingsChoice("My adapter", current == "active", {
                        selected = "active"
                        current = "active"
                    })
                    SettingsChoice("Unavailable adapter", false, { selected = "disabled" }, enabled = false)
                }
            }
        }
        compose.onNodeWithText("My adapter").assertHeightIsAtLeast(56.dp).performClick().assertIsSelected()
        compose.onNodeWithText("Unavailable adapter").performClick()
        compose.runOnIdle { assertEquals("active", selected) }
    }
}
