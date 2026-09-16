package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsSelected
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

    @Test fun `Carlink settings persist and exclude CCPA configuration`() {
        compose.activity.getSharedPreferences("carlink_settings", 0).edit().clear().commit()
        compose.setContent { CabinTheme { NativeCarPlaySettings() } }
        compose.onNodeWithText("60 FPS").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("5 GHz").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("carlink-auto-connect").performScrollTo().performClick()
        compose.runOnIdle {
            val settings = com.cabin.carlink.CarlinkSettings.read(compose.activity)
            assertEquals(60, settings.fps)
            assertEquals(1, settings.band)
            assertFalse(settings.autoConnect)
        }
        compose.onNodeWithText("Recording noise reduction").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Car logo").performScrollTo().performClick()
        compose.onNodeWithText("Default logo").performClick()
        compose.onNodeWithText("Configure Adapter").assertDoesNotExist()
        compose.onNodeWithText("Reset Decoder").assertDoesNotExist()
    }

    @Test fun `CCPA selection disables native connection commands`() {
        com.cabin.platform.CarPlayBackendSelection.select(compose.activity, com.cabin.platform.CarPlayBackend.DONGLE)
        compose.setContent { CabinTheme { NativeCarPlaySettings() } }
        compose.onNodeWithText("Retry").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Enable wireless").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Connect phone").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `native configuration stays inside Cabin and retry targets its service`() {
        com.cabin.platform.CarPlayBackendSelection.select(compose.activity, com.cabin.platform.CarPlayBackend.JOYING)
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
