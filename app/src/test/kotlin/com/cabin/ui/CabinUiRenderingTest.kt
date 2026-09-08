package com.cabin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.BuildConfig
import com.cabin.CabinManager
import com.cabin.navigation.NavigationState
import com.cabin.platform.ProjectionHealthSnapshot
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesShortcut
import com.cabin.platform.TeyesVehicleReadings
import com.cabin.platform.obd.ObdSnapshot
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose layout/semantics checks, using a software Android renderer rather than vehicle hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CabinUiRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `connecting screen separates phone connect help and full restart`() {
        var connects = 0
        var restarts = 0
        var help = 0
        compose.setContent {
            CabinTheme(darkTheme = true) {
                ProjectionConnectionScreen(
                    CabinManager.State.CONNECTING, "Waiting for phone", false, false,
                    { connects++ }, {}, null, null, null, Modifier.fillMaxSize(),
                    onHelp = { help++ }, onRestart = { restarts++ },
                )
            }
        }
        compose.onNodeWithText("Connect phone").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, connects)
            assertEquals(0, restarts)
            assertEquals(0, help)
        }
        compose.onNodeWithText("Connection help").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, connects)
            assertEquals(0, restarts)
            assertEquals(1, help)
        }
        compose.onNodeWithText("Restart connection").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, connects)
            assertEquals(1, restarts)
            assertEquals(1, help)
        }
        saveScreenshot("connection-help-entry")
    }

    @Test
    fun `climate summary is a readable passive overlay`() {
        compose.setContent {
            CabinTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    ProjectionClimateSummary(
                        TeyesClimateState(
                            connected = true,
                            profileId = 262465,
                            availableCodes = setOf(30, 35, 25, 33),
                            ac = true,
                            fanLevel = 3,
                            leftTemperature = 44,
                        ),
                        modifier = Modifier.width(480.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("A/C on · Left 22.0°C · Fan 3/7").assertIsDisplayed()
        saveScreenshot("climate-quiet-summary")
    }

    @Test
    fun `connection action is readable and invokes the supplied callback`() {
        var reconnects = 0
        compose.setContent {
            CabinTheme(darkTheme = true) {
                ProjectionConnectionScreen(
                    CabinManager.State.DISCONNECTED, "Connect your USB adapter to get started.", false, false,
                    { reconnects++ }, {}, {}, null, null, Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("Connect phone").assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
        saveScreenshot("projection-dark")
    }

    @Test
    fun `short compact screen keeps actions reachable by scrolling`() {
        compose.setContent {
            CabinTheme(darkTheme = false) {
                Box(Modifier.width(480.dp).height(240.dp)) {
                    ProjectionConnectionScreen(
                        CabinManager.State.DISCONNECTED, "Adapter not connected", false, true,
                        {}, {}, null, null, {}, Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithText("Connect phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close panel").performScrollTo().assertIsDisplayed().assertIsEnabled()
        saveScreenshot("compact-light")
    }

    @Test
    fun `read only climate cannot send commands and close stays available`() {
        var commands = 0
        var closed = false
        compose.setContent {
            CabinTheme(darkTheme = true) {
                Box(Modifier.width(800.dp).height(240.dp)) {
                    ClimatePanel(
                        state = TeyesClimateState(),
                        onToggleAc = { commands++ },
                        onSetFan = { commands++ },
                        onSetAirflow = { commands++ },
                        onClose = { closed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.onNodeWithText("A/C —").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Increase fan speed").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close climate panel").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(0, commands)
            assertEquals(true, closed)
        }
        saveScreenshot("climate-read-only")
    }

    @Test
    fun `large font narrow connection screen keeps primary action reachable`() {
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 1.5f)) {
                CabinTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).height(480.dp)) {
                        ProjectionConnectionScreen(
                            CabinManager.State.DISCONNECTED, "Connect your USB adapter to get started.", false, false,
                            {}, {}, {}, null, null, Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Connect phone").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val bounds = compose.onNodeWithText("Connect phone").fetchSemanticsNode().boundsInRoot
        assertTrue("Primary action must be fully visible, not a clipped sliver: $bounds", bounds.height >= 55f && bounds.bottom <= 480f)
        saveScreenshot("connection-large-font")
        compose.onNodeWithText("Settings").performScrollTo().assertIsDisplayed()
        val settingsBounds = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        assertTrue("Settings must remain fully reachable: $settingsBounds", settingsBounds.height >= 55f && settingsBounds.bottom <= 480f)
    }

    @Test
    fun `hub renders honest empty data and preserves disabled media controls`() {
        compose.setContent {
            CabinTheme(darkTheme = true) { TeyesDashboardContent(hubModel(), hubActions()) }
        }
        compose.onNodeWithText("Connect phone").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("hub-dark")
        compose.onNodeWithTag("teyes_hub_grid").performScrollToKey("media")
        compose.onNodeWithContentDescription("Play music").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous track").assertIsNotEnabled()
        saveScreenshot("hub-media-climate")
    }

    @Test
    fun `connected vehicle readings can be refreshed only when parked`() {
        var moving by mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            CabinTheme {
                TeyesDashboardContent(
                    hubModel().copy(vehicle = TeyesClimateState(connected = true), moving = moving, speedKnown = true),
                    hubActions().copy(retryVehicle = { refreshes++ }),
                )
            }
        }
        compose.onNodeWithTag("teyes_hub_grid").performScrollToKey("health")
        compose.onNodeWithText("Health & compatibility").performScrollTo().performClick()
        compose.onNodeWithText("Refresh vehicle readings").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            moving = true
        }
        compose.onNodeWithText("Refresh vehicle readings").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Retry vehicle service").assertDoesNotExist()
    }

    @Test
    fun `large font short hub can reach primary action and compatibility details`() {
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 1.5f)) {
                CabinTheme(darkTheme = false) {
                    Box(Modifier.width(480.dp).height(240.dp)) {
                        TeyesDashboardContent(hubModel(), hubActions())
                    }
                }
            }
        }
        compose.onNodeWithTag("teyes_hub_grid").performScrollToKey("connection")
        compose.onNodeWithText("Connect phone").performScrollTo().assertIsDisplayed()
        saveScreenshot("hub-short-large-font")
        compose.onNodeWithTag("teyes_hub_grid").performScrollToKey("health")
        compose.onNodeWithText("Health & compatibility").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Save health report").performScrollTo().assertIsDisplayed()
    }

    private fun hubModel() =
        TeyesDashboardUiState(
            profileName = "Driver 1", projection = ProjectionHealthSnapshot(), vehicle = TeyesClimateState(),
            readings = TeyesVehicleReadings(null, null, null, null, false), obd = ObdSnapshot(), navigation = NavigationState(),
            nowMs = 1_000, shortcuts = TeyesShortcut.entries.associateWith { null }, moving = false, speedKnown = false,
            climatePanelAvailable = true,
        )

    private fun hubActions() = TeyesDashboardActions({}, {}, {}, {}, {}, {}, {}, {}, {})

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
