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
import com.cabin.CabinManager.ProjectionAction
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
                ProjectionToolsPanel(false, "Siri", {}, {}, null, null, {}, {}, "", Modifier.fillMaxSize(), onScreenOff = { blanked++ })
            }
        }
        compose.onNodeWithText("Screen off · keep audio").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
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
    fun `quick phone controls invoke only their supplied projection callbacks`() {
        val actions = mutableListOf<ProjectionAction>()
        var recoveries = 0
        var closed = false
        compose.setContent {
            CabinTheme(darkTheme = true) {
                ProjectionToolsPanel(
                    playing = true,
                    voiceLabel = "Siri",
                    onAction = { actions.add(it) },
                    onRecoverPicture = { recoveries++ },
                    onHub = null,
                    onClimate = null,
                    onSettings = {},
                    onClose = { closed = true },
                    status = "",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("Siri").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("projection-tools-dark")
        compose.onNodeWithText("Siri").performClick()
        compose.onNodeWithText("Pause").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Previous").performScrollTo().performClick()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Recover picture").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed().performClick()
        compose.onNodeWithText("Vehicle Hub").assertDoesNotExist()
        compose.onNodeWithText("A/C").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(ProjectionAction.VOICE, ProjectionAction.PLAY_PAUSE, ProjectionAction.PREVIOUS, ProjectionAction.NEXT), actions)
            assertEquals(1, recoveries)
            assertTrue(closed)
        }
    }

    @Test
    fun `short large font viewport keeps close pinned and secondary actions reachable`() {
        var settings = 0
        var climate = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CabinTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).height(240.dp)) {
                        ProjectionToolsPanel(
                            playing = false,
                            voiceLabel = "Voice assistant",
                            onAction = {},
                            onRecoverPicture = {},
                            onHub = {},
                            onClimate = { climate++ },
                            onSettings = { settings++ },
                            onClose = {},
                            status = "Video recovery requested. Your phone stays connected.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        val closeBounds = compose.onNodeWithContentDescription("Close projection tools").fetchSemanticsNode().boundsInRoot
        assertTrue("Pinned Close must be fully visible: $closeBounds", closeBounds.height >= 55f && closeBounds.top >= 0f && closeBounds.bottom <= 240f)
        assertFullyVisibleInShortViewport("Voice assistant")
        saveScreenshot("projection-tools-short-large-font")
        compose.onNodeWithText("Play").performScrollTo()
        assertFullyVisibleInShortViewport("Play")
        compose.onNodeWithText("A/C").performScrollTo()
        assertFullyVisibleInShortViewport("A/C")
        compose.onNodeWithText("A/C").performClick()
        compose.onNodeWithText("Settings").performScrollTo()
        assertFullyVisibleInShortViewport("Settings")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithContentDescription("Close projection tools").assertIsDisplayed()
        val finalCloseBounds = compose.onNodeWithContentDescription("Close projection tools").fetchSemanticsNode().boundsInRoot
        assertEquals("Scrolling must not move the pinned Close action", closeBounds, finalCloseBounds)
        compose.runOnIdle {
            assertEquals(1, climate)
            assertEquals(1, settings)
        }
    }

    @Test
    fun `blank voice label falls back to an accessible action name`() {
        compose.setContent {
            CabinTheme {
                ProjectionToolsPanel(false, "", {}, {}, null, null, {}, {}, "", Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithText("Voice assistant").assertIsDisplayed()
        compose.onNodeWithText("Play").assertIsDisplayed()
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
