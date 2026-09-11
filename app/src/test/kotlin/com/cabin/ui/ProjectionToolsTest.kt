package com.cabin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.BuildConfig
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectionToolsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `blackout and wake keep the projection content mounted`() {
        var blanked by mutableStateOf(false)
        var mounts = 0
        var disposals = 0
        compose.setContent {
            CabinTheme {
                ProjectionVisibilityLayers(blanked = blanked, onWake = { blanked = false }) {
                    DisposableEffect(Unit) {
                        mounts++
                        onDispose { disposals++ }
                    }
                    Button(onClick = { blanked = true }) { Text("Blank screen") }
                }
            }
        }
        compose.onNodeWithText("Blank screen").performClick()
        compose.onNodeWithText("Blank screen").assertDoesNotExist()
        compose.onNodeWithText("Tap to wake · Audio continues").performClick()
        compose.onNodeWithText("Blank screen").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, mounts)
            assertEquals(0, disposals)
        }
    }

    @Test
    fun `health report preview does not save until explicitly confirmed`() {
        var saves = 0
        var cancellations = 0
        compose.setContent {
            CabinTheme {
                HealthReportPreview("{\"vehicleProfileId\":1048874}", { saves++ }, { cancellations++ })
            }
        }
        compose.onNodeWithText("Review health report").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("Cancel").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(0, saves)
        }
        compose.onNodeWithText("Choose where to save").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `screen off action is explicit and reachable`() {
        var blanked = 0
        compose.setContent {
            CabinTheme {
                ProjectionToolsPanel({}, {}, Modifier.fillMaxSize(), onScreenOff = { blanked++ })
            }
        }
        compose.onNodeWithText("Screen off").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(1, blanked) }
    }

    @Test
    fun `blackout wakes without invoking covered controls`() {
        var wakes = 0
        var coveredClicks = 0
        compose.setContent {
            CabinTheme {
                ProjectionVisibilityLayers(blanked = true, onWake = { wakes++ }) {
                    Button(onClick = { coveredClicks++ }) { Text("Covered action") }
                }
            }
        }
        compose.onNodeWithText("Covered action").assertDoesNotExist()
        compose.onNodeWithText("Tap to wake · Audio continues").performClick()
        compose.runOnIdle {
            assertEquals(1, wakes)
            assertEquals(0, coveredClicks)
        }
    }

    @Test
    fun `simple menu exposes device switching without an extra controls layer`() {
        var devices = 0
        var settings = 0
        var screenOff = 0
        var closed = false
        compose.setContent {
            CabinTheme(darkTheme = true) {
                ProjectionToolsPanel(
                    onChangeDevice = { devices++ },
                    connectedDeviceName = "My iPhone",
                    onSettings = { settings++ },
                    onClose = { closed = true },
                    onScreenOff = { screenOff++ },
                )
            }
        }
        compose.onNodeWithText("Projection tools").assertDoesNotExist()
        compose.onNodeWithText("Change device").assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("My iPhone").assertIsDisplayed()
        compose.onNodeWithText("Audio continues").assertIsDisplayed()
        saveScreenshot("projection-tools-dark")
        compose.onNodeWithText("Settings").assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Screen off").assertIsDisplayed().performClick()
        listOf("Recover picture", "More controls", "Siri", "Previous", "Next", "Vehicle Hub", "A/C").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription("Close projection tools").performClick()
        compose.runOnIdle {
            assertEquals(1, devices)
            assertEquals(1, settings)
            assertEquals(1, screenOff)
            assertTrue(closed)
        }
    }

    @Test
    fun `short large font viewport keeps close pinned and secondary actions reachable`() {
        var settings = 0
        var screenOff = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CabinTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).height(240.dp)) {
                        ProjectionToolsPanel(
                            onSettings = { settings++ },
                            onClose = {},
                            onScreenOff = { screenOff++ },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val closeBounds = compose.onNodeWithContentDescription("Close projection tools").fetchSemanticsNode().boundsInRoot
        assertTrue("Pinned Close must be fully visible: $closeBounds", closeBounds.height >= 55f && closeBounds.top >= 0f && closeBounds.bottom <= 240f)
        compose.onNodeWithText("Screen off").performScrollTo()
        assertFullyVisibleInShortViewport("Screen off")
        compose.onNodeWithText("Screen off").performClick()
        saveScreenshot("projection-tools-short-large-font")
        compose.onNodeWithText("Settings").assertIsDisplayed()
        assertFullyVisibleInShortViewport("Settings")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed()
        val finalCloseBounds = compose.onNodeWithContentDescription("Close projection tools").fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must not move the pinned Close action", closeBounds, finalCloseBounds)
        compose.runOnIdle {
            assertEquals(1, settings)
            assertEquals(1, screenOff)
        }
    }

    @Test
    fun `device picker marks current phone and selects another without opening settings`() {
        val current = com.cabin.CabinManager.DeviceInfo("01", "My iPhone", "CarPlay")
        val other = com.cabin.CabinManager.DeviceInfo("02", "Other phone", "AndroidAuto")
        var selected: com.cabin.CabinManager.DeviceInfo? = null
        var backs = 0
        compose.setContent {
            CabinTheme(darkTheme = true) {
                ProjectionDevicePicker(listOf(current, other), current.btMac,
                    { selected = it }, { backs++ }, {})
            }
        }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("My iPhone").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
        saveScreenshot("projection-device-picker")
        compose.onNodeWithText("Other phone").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(other, selected) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test
    fun `empty device picker explains pairing and keeps close available`() {
        compose.setContent {
            CabinTheme { ProjectionDevicePicker(emptyList(), null, {}, {}, {}) }
        }
        compose.onNodeWithText("No paired wireless devices").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed()
    }

    private fun assertFullyVisibleInShortViewport(label: String) {
        val node = compose.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must remain a full touch target, not a clipped sliver: $bounds",
            bounds.height >= 55f && bounds.top >= 0f && bounds.bottom <= 240f,
        )
    }

    private fun saveScreenshot(name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val destination = File("build/reports/ui/${BuildConfig.BUILD_TYPE}/$name.png")
                checkNotNull(destination.parentFile).mkdirs()
                destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
