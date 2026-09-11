package com.cabin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClimateCloseTimerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `touch resets countdown and holding prevents dismissal`() {
        var closes = 0
        lateinit var timer: ClimateCloseTimer
        compose.mainClock.autoAdvance = false
        compose.setContent {
            timer = rememberClimateCloseTimer { closes++ }
            Box(Modifier.size(240.dp).then(timer.touchModifier).testTag("climate-touch")) {
                ClimateCloseButton({ closes++ }, timer.remaining.value)
            }
        }
        compose.mainClock.advanceTimeBy(6_000)
        compose.runOnIdle { assertEquals(0, closes); assertTrue(timer.remaining.value < .5f) }
        val ring = compose.onNodeWithTag("climate-close-countdown").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithContentDescription("Close climate panel").fetchSemanticsNode().boundsInRoot
        assertTrue(ring.left < button.left && ring.right > button.right &&
            ring.top < button.top && ring.bottom > button.bottom)
        compose.onNodeWithTag("climate-touch").performTouchInput { down(bottomRight - androidx.compose.ui.geometry.Offset(10f, 10f)) }
        compose.mainClock.advanceTimeBy(12_000)
        compose.runOnIdle { assertEquals(0, closes); assertEquals(1f, timer.remaining.value) }
        compose.onNodeWithTag("climate-touch").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(9_000)
        compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.runOnIdle { assertEquals("pressed=${timer.pressed} remaining=${timer.remaining.value}", 1, closes) }
        compose.onNodeWithContentDescription("Close climate panel").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, closes) }
    }
}
