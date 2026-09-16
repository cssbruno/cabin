package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cabin.joying.JoyingCarPlayService
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class NativeCarPlaySettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `native configuration stays inside Cabin and retry targets its service`() {
        compose.setContent { CabinTheme { NativeCarPlaySettings() } }
        compose.onNodeWithText("Enable wireless").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use Cabin for CarPlay").assertDoesNotExist()
        compose.onNodeWithText("Carlink").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Car Link settings").assertDoesNotExist()
        compose.onNodeWithText("Restore stock service").assertDoesNotExist()
        val app = shadowOf(compose.activity.application)
        assertNull(app.nextStartedActivity)
        // Robolectric records bindService in the same intent queue as startService.
        val binding = app.nextStartedService
        assertEquals(JoyingCarPlayService::class.java.name, binding.component?.className)
        assertNull(binding.action)
        assertNull(app.nextStartedService)
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = app.nextStartedService
            assertEquals(JoyingCarPlayService.RETRY, intent.action)
            assertEquals(JoyingCarPlayService::class.java.name, intent.component?.className)
            assertNull(app.nextStartedActivity)
        }
    }
}
