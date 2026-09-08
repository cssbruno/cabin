package com.cabin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.CabinManager
import com.cabin.platform.ProjectionReadinessSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class ProjectionConnectionHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `stopping for tests needs parked acknowledgment again after window focus loss`() {
        lateinit var view: android.view.View
        var stops = 0
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                ProjectionConnectionHelp(
                    snapshot = ProjectionReadinessSnapshot(state = CabinManager.State.STREAMING, sessionRequested = true),
                    onConnect = {}, onRefresh = {}, onClose = {}, onOpenPermissions = {},
                    onStopSession = { stops++ }, sessionIdle = false,
                )
            }
        }
        compose.onNodeWithText("Stop session for audio tests").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I am parked and ready to configure or test").performScrollTo().performClick()
        compose.onNodeWithText("Stop session for audio tests").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            ReflectionHelpers.callInstanceMethod<Unit>(
                view.viewTreeObserver,
                "dispatchOnWindowFocusChange",
                ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, false),
            )
        }
        compose.onNodeWithText("Stop session for audio tests").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, stops) }
    }

    @Test
    fun `refresh and close do not trigger connection and explicit Connect runs once`() {
        var refreshes = 0
        var connections = 0
        var closes = 0
        compose.setContent {
            MaterialTheme {
                ProjectionConnectionHelp(
                    snapshot = ProjectionReadinessSnapshot(phoneConnectionAllowed = false),
                    onConnect = { connections++ },
                    onRefresh = { refreshes++ },
                    onClose = { closes++ },
                )
            }
        }
        compose.onNodeWithText("Refresh").assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Close").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(1, closes)
            assertEquals(0, connections)
        }
        compose.onNodeWithText("Connect phone").assertHeightIsAtLeast(56.dp).performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test
    fun `active phone session has no redundant Connect action`() {
        compose.setContent {
            MaterialTheme {
                ProjectionConnectionHelp(
                    snapshot = ProjectionReadinessSnapshot(state = CabinManager.State.STREAMING, sessionRequested = true),
                    onConnect = {},
                    onRefresh = {},
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Connect phone").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test
    fun `short large font panel keeps close visible while details scroll`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(480.dp).height(240.dp)) {
                        ProjectionConnectionHelp(
                            snapshot = ProjectionReadinessSnapshot(),
                            onConnect = {},
                            onRefresh = {},
                            onClose = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Connect phone").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Head-unit GPS · optional").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
