package com.cabin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.platform.SyuSoundConnection
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyuSoundScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `diagnostics stay collapsed and clear stale readings on disconnect`() {
        val state = mutableStateOf(SyuSoundConnection(connected = true, moduleId = 11,
            samples = mapOf(11 to listOf(1), 1000 to listOf(10))))
        compose.setContent {
            CabinTheme { SyuSoundContent(state.value, false, { _, _ -> error("Read only") }, null) }
        }
        compose.onNodeWithTag("sound-diagnostics-report").assertDoesNotExist()
        compose.onNodeWithTag("sound-diagnostics-toggle").performClick()
        compose.onNodeWithText("Reported DSP profile: AKM7604 (ID 11)", substring = true).assertExists()
        compose.onNodeWithText("Loudness: Fresh reading received", substring = true).assertExists()
        compose.onNodeWithText("Balance: No fresh reading", substring = true).assertExists()
        compose.onNodeWithText("EQ bands with fresh readings: 1", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(connected = false) }
        compose.onNodeWithText("SYU service: Disconnected", substring = true).assertExists()
        compose.onNodeWithText("Reported DSP profile: Unknown", substring = true).assertExists()
        compose.onNodeWithText("EQ bands with fresh readings: 0", substring = true).assertExists()
    }

    @Test fun `unknown profile preserves its reported ID without claiming support`() {
        compose.setContent {
            CabinTheme { SyuSoundContent(SyuSoundConnection(connected = true, moduleId = 13),
                false, { _, _ -> error("Read only") }, null) }
        }
        compose.onNodeWithTag("sound-diagnostics-toggle").performClick()
        compose.onNodeWithText("Reported DSP profile: Unknown (ID 13)", substring = true).assertExists()
        compose.onNodeWithText("Balance: Not mapped in Cabin", substring = true).assertExists()
        compose.onNodeWithText("Copy report").performClick()
        compose.onNodeWithText("Copied").assertExists()
    }

    @Test fun `unsupported hardware shows factory fallback without invented controls`() {
        var factoryLaunches = 0
        compose.setContent {
            CabinTheme {
                SyuSoundContent(SyuSoundConnection(connected = true, moduleId = 999), false,
                    onChange = { _, _ -> error("Unsupported hardware must not send commands") },
                    onFactory = { factoryLaunches++ }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Use the factory equalizer for this sound system.").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithText("Factory equalizer").performClick()
        assertEquals(1, factoryLaunches)
    }

    @Test fun `moving prevents factory access and explains why`() {
        var factoryLaunches = 0
        compose.setContent {
            CabinTheme {
                SyuSoundContent(SyuSoundConnection(), true, onChange = { _, _ -> },
                    onFactory = { factoryLaunches++ }, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Park to adjust sound.").assertIsDisplayed()
        compose.onNodeWithText("Factory equalizer").assertIsNotEnabled().performClick()
        assertEquals(0, factoryLaunches)
    }

    @Test fun `supported settings send selected values and hide detailed bands by default`() {
        val edits = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            CabinTheme {
                SyuSoundContent(SyuSoundConnection(connected = true, moduleId = 11,
                    samples = mapOf(10 to listOf(0), 11 to listOf(0), 8 to listOf(8, 8),
                        1000 to listOf(10))), false,
                    onChange = { key, value -> edits += key to value }, onFactory = null,
                    modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("sound-eq.0").assertDoesNotExist()
        compose.onNodeWithTag("sound-preset").performClick()
        compose.onNodeWithTag("sound-preset-2").performClick()
        assertEquals(listOf("preset" to 2), edits)
        compose.onNodeWithTag("sound-loudness").performClick()
        assertEquals("loudness" to 1, edits.last())
        compose.onNodeWithText("Advanced").performClick()
        compose.onNodeWithTag("sound-eq.0").assertExists()
    }

    @Test fun `pending hardware confirmation disables matching control only`() {
        compose.setContent {
            CabinTheme {
                SyuSoundContent(SyuSoundConnection(connected = true, moduleId = 11,
                    samples = mapOf(10 to listOf(0), 11 to listOf(0)), pending = setOf("loudness")),
                    false, onChange = { _, _ -> }, onFactory = null, modifier = Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("sound-loudness").assertIsNotEnabled()
        compose.onNodeWithTag("sound-preset").assertIsEnabled()
        compose.onNodeWithText("Applying…").assertIsDisplayed()
    }

    @Test fun `portrait sound dialog uses whole screen and has accessible close`() {
        val open = mutableStateOf(true)
        compose.setContent {
            CabinTheme {
                if (open.value) SyuSoundScreen(SyuSoundConnection(), false,
                    onChange = { _, _ -> }, onClose = { open.value = false })
            }
        }
        val panel = compose.onNodeWithTag("syu-sound-screen").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Dialog should fill portrait width", panel.width >= 350f)
        org.junit.Assert.assertTrue("Dialog should fill portrait height", panel.height >= 750f)
        compose.onNodeWithContentDescription("Close sound settings").performClick()
        compose.onNodeWithTag("syu-sound-screen").assertDoesNotExist()
    }
}
