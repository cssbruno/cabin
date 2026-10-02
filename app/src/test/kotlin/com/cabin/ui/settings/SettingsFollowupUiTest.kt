@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.ui.ProjectionDevicePicker
import com.cabin.ui.ProjectionQuickMenu
import com.cabin.ui.SettingsScreen
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFollowupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: CabinManager

    @Before fun setup() {
        compose.activity.getSharedPreferences("cabin_settings_navigation_v1", 0).edit().clear().commit()
        manager = CabinManager(compose.activity.applicationContext)
        ReflectionHelpers.callStaticMethod<Unit>(ShadowWindowManagerGlobal::class.java, "setInTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
    }
    @After fun release() = runBlocking { manager.releaseAndWait() }

    @Test fun `compact horizontal navigation reveals the restored active category without trapping user scroll`() {
        RuntimeEnvironment.setFontScale(2f)
        val restore = StateRestorationTester(compose)
        restore.setContent {
            CabinTheme { Box(Modifier.width(320.dp).height(420.dp)) {
                SettingsScreen(manager, null, {}, {}, initialTab = SettingsTab.TEYES, embedded = true)
            } }
        }
        assertDoubleFont(compose.onNodeWithText("FYT"))
        val active = compose.onNodeWithText("FYT").assertIsSelected().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Active category must fit inside the 320 dp viewport: $active", active.left >= 0f && active.right <= 320f)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("FYT").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("CarPlay").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        compose.onNodeWithText("CarPlay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Cabin").assertIsDisplayed()
    }

    @Test fun `settings search focuses its restored query and supports keyboard result selection at double text size`() {
        RuntimeEnvironment.setFontScale(2f)
        val restore = StateRestorationTester(compose)
        var selected: SettingsTab? = null
        restore.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            CabinTheme { SettingsSearchDialog(onDismiss = {}, onSelect = { tab, _ -> selected = tab }) }
        }
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("High contrast")
        assertDoubleFont(compose.onNode(hasSetTextAction()))
        restore.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("High contrast")
        assertDoubleFont(compose.onNode(hasSetTextAction()))
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithContentDescription("Close").assertIsFocused()
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.Tab); pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(SettingsTab.CONTROL, selected) }
    }

    @Test
    @Config(sdk = [35], qualifiers = "de-w800dp-h480dp-land-mdpi")
    fun `localized phone search stays compact and restores its query at double text size`() {
        RuntimeEnvironment.setFontScale(2f)
        val restore = StateRestorationTester(compose)
        val phone = CabinManager.DeviceInfo("01", "Familientelefon", "CarPlay")
        restore.setContent {
            CabinTheme { Box(Modifier.width(320.dp).height(400.dp)) {
                ProjectionDevicePicker(listOf(phone), null, {}, {}, {})
            } }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.px_find_phones)).performScrollTo().performClick()
        val label = compose.activity.getString(R.string.px_search_phones)
        val field = compose.onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
        assertDoubleFont(field)
        val fieldBounds = field.getUnclippedBoundsInRoot()
        assertTrue("Localized search must stay compact", fieldBounds.bottom - fieldBounds.top <= 112.dp)
        field.performTextInput("Familie")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription(label).performScrollTo().assertTextContains("Familie")
        assertDoubleFont(compose.onNodeWithContentDescription(label))
        val restoredBounds = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
        assertTrue("Restored search must stay compact", restoredBounds.bottom - restoredBounds.top <= 112.dp)
        compose.onNodeWithText("Familientelefon").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.projection_tools_close)).assertIsDisplayed()
    }

    @Test fun `system Back returns from phone choice to tools without closing the overlay`() {
        var closes = 0
        compose.setContent { CabinTheme {
            ProjectionQuickMenu(manager, { it() }, {}, { closes++ }, {})
        } }
        compose.onNodeWithText("Change device").performClick()
        compose.onNodeWithText("No paired wireless devices").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithContentDescription("Close projection tools").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun `keyboard inset keeps both search results reachable at double text size`() {
        RuntimeEnvironment.setFontScale(2f)
        var selected: String? = null
        compose.setContent { CabinTheme {
            SettingsSearchDialog(onDismiss = {}, onSelect = { _, label -> selected = label })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("maintenance")
        assertDoubleFont(compose.onNode(hasSetTextAction()))
        fun applyInsets(imeHeight: Int) = compose.runOnIdle {
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            ViewCompat.dispatchApplyWindowInsets(dialog, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeHeight))
                .setVisible(WindowInsetsCompat.Type.ime(), imeHeight > 0).build())
        }
        applyInsets(0)
        val results = compose.onNode(hasScrollToIndexAction())
        val fullBounds = results.getUnclippedBoundsInRoot()
        val imeHeight = 260
        var keyboardTop = 0
        compose.runOnIdle {
            val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            keyboardTop = dialog.height - imeHeight
        }
        applyInsets(imeHeight)
        val visibleBounds = results.getUnclippedBoundsInRoot()
        assertTrue("The result viewport must end above the keyboard", visibleBounds.bottom <= keyboardTop.dp)
        assertTrue("Opening the keyboard must reduce the result viewport", visibleBounds.bottom < fullBounds.bottom)
        assertTrue("At least one automotive touch target must fit", visibleBounds.bottom - visibleBounds.top >= 56.dp)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        listOf("Maintenance reminder", "Trips & maintenance").forEachIndexed { index, label ->
            results.performScrollToIndex(index)
            val row = compose.onNode(hasContentDescription("$label, Car Settings") and hasClickAction())
                .assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("The entire $label button must fit above the keyboard", bounds.top >= visibleBounds.top && bounds.bottom <= visibleBounds.bottom)
        }
        compose.onNode(hasContentDescription("Trips & maintenance, Car Settings") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("Trips & maintenance", selected) }
        applyInsets(0)
        val restoredBounds = results.getUnclippedBoundsInRoot()
        // Allow one dp of layout rounding when the keyboard closes.
        assertEquals("Restored viewport left", fullBounds.left.value, restoredBounds.left.value, 1f)
        assertEquals("Restored viewport top", fullBounds.top.value, restoredBounds.top.value, 1f)
        assertEquals("Restored viewport right", fullBounds.right.value, restoredBounds.right.value, 1f)
        assertEquals("Restored viewport bottom", fullBounds.bottom.value, restoredBounds.bottom.value, 1f)
    }

    private fun assertDoubleFont(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("The rendered text must expose its layout", layouts.isNotEmpty())
        layouts.forEach { assertEquals("Rendered font scale", 2f, it.layoutInput.density.fontScale, 0f) }
    }
}
