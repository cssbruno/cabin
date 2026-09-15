package com.cabin.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.BuildConfig
import com.cabin.CabinManager
import com.cabin.ui.SettingsScreen
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: CabinManager

    @Before fun setUp() {
        manager = CabinManager(ApplicationProvider.getApplicationContext<Context>())
    }

    @After fun tearDown() = runBlocking { manager.releaseAndWait() }

    @Test
    fun `launcher retains display settings and excludes dongle actions even when attached`() {
        com.cabin.test.attachCarPlayDongle(compose.activity)
        compose.setContent {
            CabinTheme {
                SettingsScreen(manager, null, {}, {}, initialTab = SettingsTab.CONTROL)
            }
        }
        compose.onNodeWithText("Test Cluster Display").assertDoesNotExist()
        compose.onNodeWithContentDescription("Test secondary cluster display").assertDoesNotExist()
        compose.onNodeWithText("Display Mode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reset Decoder").assertDoesNotExist()
        compose.onNodeWithContentDescription("Configure adapter").assertDoesNotExist()
        compose.onNodeWithText("Disconnect Adapter").assertDoesNotExist()
    }

    @Test
    fun `short landscape navigation scrolls without hiding back and exit`() {
        var backs = 0
        compose.setContent {
            CabinTheme(darkTheme = true) {
                Box(Modifier.width(800.dp).height(240.dp)) {
                    SettingsScreen(manager, null, { backs++ }, {}, initialTab = SettingsTab.CONTROL)
                }
            }
        }
        compose.onNodeWithText("Launcher").performScrollTo().assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("Exit app").assertIsDisplayed()
        saveScreenshot("settings-short-landscape")
        compose.onNodeWithText("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `narrow navigation keeps return and safe exit confirmation accessible`() {
        var backs = 0
        compose.setContent {
            CabinTheme(darkTheme = false) {
                Box(Modifier.width(480.dp).height(320.dp)) {
                    SettingsScreen(manager, null, { backs++ }, {}, initialTab = SettingsTab.CONTROL)
                }
            }
        }
        compose.onNodeWithText("Launcher").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("Back to Cabin").assertIsDisplayed()
        compose.onNodeWithText("Exit app").assertIsDisplayed().performClick()
        compose.onNodeWithText("Stop and exit").assertIsDisplayed()
        compose.onNodeWithText("Keep running").performClick()
        saveScreenshot("settings-narrow-light")
        compose.onNodeWithContentDescription("Back to Cabin").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun `CarPlay groups connection and display controls inside launcher settings`() {
        com.cabin.test.attachCarPlayDongle(compose.activity)
        compose.setContent {
            CabinTheme(darkTheme = true) {
                SettingsScreen(manager, null, {}, {}, initialTab = SettingsTab.PHONES, embedded = true)
            }
        }
        compose.onNodeWithText("CarPlay").assertIsSelected()
        compose.onNodeWithText("Display & controls").assertIsSelected()
        compose.onNodeWithText("Reset Decoder").performScrollTo().assertIsDisplayed()
        saveScreenshot("settings-carplay-integrated")
        compose.onNodeWithText("Connection").performClick()
        compose.onNodeWithText("Connection").assertIsSelected()
        saveScreenshot("settings-carplay-connection")
        compose.onNodeWithText("Display & controls").performClick()
        compose.onNodeWithText("Display & controls").assertIsSelected()
    }

    @Test fun `change device shortcut opens connection directly`() {
        com.cabin.test.attachCarPlayDongle(compose.activity)
        compose.setContent {
            CabinTheme(darkTheme = true) {
                SettingsScreen(manager, null, {}, {}, initialTab = SettingsTab.PHONES,
                    embedded = true, initialCarPlayConnection = true)
            }
        }
        compose.onNodeWithText("Connection").assertIsSelected()
        compose.onNodeWithText("Display & controls").performClick()
        compose.onNodeWithText("Display & controls").assertIsSelected()
    }

    @Test fun `car settings can be opened from the navigation rail`() {
        compose.setContent {
            CabinTheme { SettingsScreen(manager, null, {}, {}, initialTab = SettingsTab.CONTROL) }
        }
        compose.onNodeWithText("Car Settings").performScrollTo().performClick()
        compose.onNodeWithText("Lights").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Parking & camera").performScrollTo().assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val buildType = BuildConfig.BUILD_TYPE
            val destination = File("build/reports/ui/$buildType/$name.png")
            checkNotNull(destination.parentFile).mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
