package com.cabin.ui.settings

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.platform.*
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1000dp-h1200dp-mdpi")
class SteeringSettingsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(): Pair<TeyesFeaturePreferences, TeyesKeyRouter> {
        val context = compose.activity
        TeyesFeaturePreferences::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("teyes_features_v1", 0).edit().clear().commit()
        val prefs = TeyesFeaturePreferences.get(context)
        val router = TeyesKeyRouter(prefs) { error("Learning must not execute actions") }
        compose.setContent {
            CabinTheme {
                CompositionLocalProvider(LocalTeyesKeyRouter provides router) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SteeringSettingsPanel() }
                }
            }
        }
        compose.onNodeWithText("Steering-wheel shortcuts").performClick()
        return prefs to router
    }

    @Test fun `learn then edit and remove a button through the editor`() {
        val (prefs, router) = show()
        compose.onNodeWithText("Assign a button").performClick()
        compose.onNodeWithText("Back", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertTrue(router.isLearning)
            router.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1))
            router.dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1))
            assertEquals(TeyesKeyAction.BACK, prefs.keyAction(KeyEvent.KEYCODE_F1))
        }
        compose.onNodeWithText("Change action").performScrollTo().performClick()
        compose.onNodeWithText("Do nothing").performClick()
        compose.runOnIdle { assertEquals(TeyesKeyAction.NONE, prefs.keyAction(KeyEvent.KEYCODE_F1)) }
        compose.onNodeWithText("Remove mapping").performScrollTo().performClick()
        compose.runOnIdle { assertNull(prefs.keyAction(KeyEvent.KEYCODE_F1)) }
    }

    @Test fun `reset requires confirmation and removes both press types`() {
        val (prefs, _) = show()
        compose.runOnIdle {
            prefs.mapKey(KeyEvent.KEYCODE_F1, TeyesKeyAction.BACK)
            prefs.mapKeyApp(KeyEvent.KEYCODE_F1, "example.maps/.Main", true)
        }
        compose.onNodeWithText("Reset button mappings").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(prefs.keyAction(KeyEvent.KEYCODE_F1)) }
        compose.onAllNodesWithText("Reset button mappings").filter(hasClickAction()).onLast().performClick()
        compose.runOnIdle {
            assertNull(prefs.keyAction(KeyEvent.KEYCODE_F1))
            assertNull(prefs.keyApp(KeyEvent.KEYCODE_F1, true))
        }
    }
}
