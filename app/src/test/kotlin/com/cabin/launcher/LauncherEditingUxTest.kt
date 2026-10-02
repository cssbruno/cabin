@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cabin.launcher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.cabin.R
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.theme.CabinTheme
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowWindowManagerGlobal
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1024dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherEditingUxTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var preferences: LauncherPreferences
    private var originalFontScale = 1f
    private val apps = (1..9).map { TeyesLaunchableApp("example.app$it/.Main", "App ${it.toString().padStart(2, '0')}") }
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)

    @Before fun reset() {
        originalFontScale = RuntimeEnvironment.getFontScale()
        listOf(LauncherPreferences.FILE, LauncherAppLibrary.FILE).forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        preferences = LauncherPreferences(context)
    }

    @After fun restoreFontScale() { RuntimeEnvironment.setFontScale(originalFontScale) }

    @Suppress("DEPRECATION")
    private fun enableLargeFont() {
        RuntimeEnvironment.setFontScale(2f)
        val resources = compose.activity.resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
    }

    private fun assertLargeDialogText(title: String) {
        compose.runOnIdle { assertEquals(2f, ShadowDialog.getLatestDialog().context.resources.configuration.fontScale, 0f) }
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals("Dialog text must use the configured system font scale", 2f, layout.single().layoutInput.density.fontScale, 0f)
    }

    @Test fun `full folder list preserves its draft and IME saves only after a slot is available`() {
        repeat(LauncherAppLibrary.MAX_FOLDERS) { preferences.library.createFolder("Folder ${it.toString().padStart(2, '0')}") }
        compose.setContent { CabinTheme { LauncherFolderDialog(preferences.library, {}) } }
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("Night travel")
        compose.onNodeWithText(label(R.string.ux_launcher_folder_capacity, LauncherAppLibrary.MAX_FOLDERS)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.app_library_save)).assertIsNotEnabled()
        input.performScrollTo().performImeAction()
        input.assertTextContains("Night travel")
        assertEquals(LauncherAppLibrary.MAX_FOLDERS, preferences.library.state.value.folders.size)
        compose.onAllNodesWithText(label(R.string.goal_remove_folder)).onFirst().performScrollTo().performClick()
        input.performScrollTo().assertTextContains("Night travel").performImeAction()
        assertEquals(LauncherAppLibrary.MAX_FOLDERS, preferences.library.state.value.folders.size)
        assertTrue("Draft must be committed once a folder slot exists", "Night travel" in preferences.library.state.value.folders.values)
        assertEquals("", input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test
    @Config(qualifiers = "de-w320dp-h640dp-port-mdpi")
    fun `German folder rename and remove remain reachable with double text`() {
        enableLargeFont()
        val id = preferences.library.createFolder("Urlaubsplanung")!!
        compose.setContent { CabinTheme { LauncherFolderDialog(preferences.library, {}) } }
        assertLargeDialogText(label(R.string.goal_folders))
        compose.onNodeWithContentDescription(label(R.string.ux_launcher_rename_folder, "Urlaubsplanung"))
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performScrollTo().assertTextContains("Urlaubsplanung").performTextReplacement("Reiseplanung")
        input.performImeAction()
        assertEquals("Reiseplanung", preferences.library.state.value.folders[id])
        compose.onNodeWithText(label(R.string.goal_remove_folder)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        screenshot("ux-folder-actions-german-200")
        compose.onNodeWithText(label(R.string.goal_remove_folder)).performClick()
        assertTrue(preferences.library.state.value.folders.isEmpty())
    }

    @Test fun `bulk pin blocks an oversized selection and then commits the whole reduced selection`() {
        apps.take(7).forEach { preferences.pin(it.component) }
        compose.setContent { CabinTheme { Box(Modifier.size(1024.dp, 700.dp)) { LauncherAppDrawer(apps, preferences, {}, {}, {}) } } }
        compose.onNodeWithContentDescription(label(R.string.app_library_options)).performClick()
        compose.onNodeWithText(label(R.string.goal_select_apps)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(apps[7].label).performClick()
        compose.onNodeWithContentDescription(apps[8].label).performClick()
        compose.onNodeWithText(label(R.string.launcher_pin)).performClick()
        compose.onNodeWithText(label(R.string.ux_launcher_pin_capacity, 1)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.app_library_confirm)).assertIsNotEnabled()
        assertEquals(apps.take(7).map { it.component }, preferences.state.value.favorites)
        compose.onNodeWithText(label(R.string.app_library_cancel)).performClick()
        compose.onNodeWithContentDescription(apps[8].label).performClick()
        compose.onNodeWithText(label(R.string.launcher_pin)).performClick()
        compose.onNodeWithText(label(R.string.app_library_confirm)).assertIsEnabled().performClick()
        assertEquals(apps.take(8).map { it.component }, preferences.state.value.favorites)
    }

    @Test
    @Config(qualifiers = "pt-w640dp-h360dp-land-mdpi")
    fun `Portuguese app rename scrolls at double text and Back discards while IME Done saves`() {
        enableLargeFont()
        compose.setContent { CabinTheme { LauncherAppDrawer(apps.take(1), preferences, {}, {}, {}) } }
        fun openRename() {
            compose.onNodeWithContentDescription(label(R.string.launcher_manage)).performClick()
            compose.onNodeWithText(label(R.string.app_library_rename)).performScrollTo().performClick()
        }
        openRename()
        assertLargeDialogText(label(R.string.app_library_rename))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput("Viagens")
        compose.onNodeWithText(label(R.string.app_library_rename_note)).performScrollTo().assertIsDisplayed()
        screenshot("ux-app-rename-portuguese-200")
        val keyboardTop = showDialogKeyboard(160)
        listOf(R.string.app_library_cancel, R.string.app_library_save).forEach { action ->
            val button = compose.onNodeWithText(label(action)).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            assertTrue("Rename actions must remain above the software keyboard", button.fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop)
        }
        val keyboardInput = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
        val visibleField = keyboardInput.fetchSemanticsNode().boundsInRoot
        val fullField = keyboardInput.getUnclippedBoundsInRoot()
        assertEquals("The entire outlined field must fit above the fixed actions at mdpi",
            (fullField.bottom - fullField.top).value, visibleField.height, 1f)
        assertTrue("The field must remain above the software keyboard", visibleField.bottom <= keyboardTop)
        compose.onNodeWithText(label(R.string.app_library_custom_name), useUnmergedTree = true).assertIsDisplayed()
        screenshot("ux-app-rename-portuguese-200-keyboard")
        @Suppress("DEPRECATION")
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        assertTrue(preferences.library.state.value.names.isEmpty())
        openRename()
        val savedName = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
        savedName.performScrollTo().performTextInput("Rádio guardado")
        savedName.performImeAction()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).assertDoesNotExist()
        assertEquals("Rádio guardado", preferences.library.state.value.names[apps.first().component])
    }

    @Test fun `reorder keyboard focus follows the chosen app through repeated moves`() {
        ReflectionHelpers.callStaticMethod<Unit>(ShadowWindowManagerGlobal::class.java, "setInTouchMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        var saved: List<String>? = null
        compose.setContent { CabinTheme {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            PinnedReorderDialog(apps.take(3).map { it.component }, apps, preferences.library.state.value, { saved = it }, {})
        } }
        val down = compose.onNodeWithContentDescription(label(R.string.ux_launcher_move_down, apps.first().label))
        down.assertHeightIsAtLeast(56.dp).assertWidthIsAtLeast(56.dp).performSemanticsAction(SemanticsActions.RequestFocus)
        down.performKeyInput { pressKey(Key.Enter) }
        down.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        assertNull(saved)
        compose.onNodeWithText(label(R.string.app_library_save)).performClick()
        assertEquals(listOf(apps[1].component, apps[2].component, apps[0].component), saved)
    }

    @Test
    @Config(qualifiers = "en-w640dp-h1200dp-port-mdpi")
    fun `long label drag at double text drops at the measured neighboring row`() {
        enableLargeFont()
        val lengthy = apps.take(4).map { it.copy(label = it.label + " International navigation and travel planning information with offline maps and favorite destinations for long journeys") }
        var saved: List<String>? = null
        compose.setContent { CabinTheme { PinnedReorderDialog(lengthy.map { it.component }, lengthy, preferences.library.state.value, { saved = it }, {}) } }
        assertLargeDialogText(label(R.string.goal_reorder_pins))
        val first = compose.onNodeWithTag("pin-reorder-${lengthy[0].component}")
        val second = compose.onNodeWithTag("pin-reorder-${lengthy[1].component}")
        val before = first.fetchSemanticsNode().boundsInRoot
        val destination = second.fetchSemanticsNode().boundsInRoot
        first.performTouchInput {
            down(Offset(20f, center.y))
            advanceEventTime(800)
            moveBy(Offset(0f, destination.center.y - before.center.y), 500)
            up()
        }
        assertNull(saved)
        screenshot("ux-pin-reorder-long-labels-200")
        compose.onNodeWithText(label(R.string.app_library_save)).performClick()
        assertEquals(listOf(lengthy[1].component, lengthy[0].component, lengthy[2].component, lengthy[3].component), saved)
    }

    private fun showDialogKeyboard(heightPx: Int): Float {
        var keyboardTop = 0f
        compose.runOnIdle {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            keyboardTop = (decor.height - heightPx).toFloat()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true).build()
            fun dispatch(view: View) {
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                    ViewCompat.dispatchApplyWindowInsets(view, insets)
                } else if (view is ViewGroup) {
                    repeat(view.childCount) { dispatch(view.getChildAt(it)) }
                }
            }
            dispatch(decor)
        }
        compose.waitForIdle()
        return keyboardTop
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/${com.cabin.BuildConfig.BUILD_TYPE}/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
