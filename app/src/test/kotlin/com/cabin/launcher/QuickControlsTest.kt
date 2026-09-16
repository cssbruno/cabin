package com.cabin.launcher

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `brightness changes both the visible dialog and activity window`() {
        compose.setContent {
            CabinTheme { QuickControls(false, {}, {}, null, {}) }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        compose.runOnIdle {
            assertEquals(0.3f, compose.activity.window.attributes.screenBrightness, 0.001f)
            assertEquals(0.3f, ShadowDialog.getLatestDialog().window!!.attributes.screenBrightness, 0.001f)
        }
    }
}
