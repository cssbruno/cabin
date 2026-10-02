package com.cabin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherAppDrawerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var preferences: LauncherPreferences
    private val radio = TeyesLaunchableApp("example.radio/.Main", "Rádio FM")
    private val maps = TeyesLaunchableApp("example.maps/.Main", "Maps")
    private var refreshes = 0

    @Before fun reset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LauncherAppLibrary.FILE, 0).edit().clear().commit()
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().clear().commit()
        preferences = LauncherPreferences(context)
    }

    private fun show() {
        compose.setContent { CabinTheme { Box(Modifier.size(1024.dp, 600.dp)) {
            LauncherAppDrawer(listOf(radio, maps), preferences, { refreshes++ }, {}, {})
        } } }
    }

    @Test fun `drawer searches clears filters and refreshes without losing query focus`() {
        preferences.pin(radio.component)
        show()
        compose.onNodeWithText("Search installed apps").performTextInput("radio")
        compose.onNodeWithText("Search installed apps").performTextInput(" fm")
        compose.onNodeWithText("Rádio FM").assertIsDisplayed()
        compose.onNodeWithText("Maps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Maps").assertIsDisplayed()
        screenshot("launcher-library")
        compose.onNodeWithText("Pinned").performClick()
        compose.onNodeWithText("Maps").assertDoesNotExist()
        compose.onNodeWithContentDescription("Refresh apps").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun `hidden apps can be reviewed and restored through their management dialog`() {
        preferences.library.hide(radio.component, true)
        show()
        compose.onNodeWithText("Rádio FM").assertDoesNotExist()
        compose.onNodeWithText("Hidden").performClick()
        compose.onNodeWithText("Rádio FM").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage").performClick()
        compose.onNodeWithText("Restore to app list").performScrollTo().performClick()
        compose.onNodeWithText("All").performClick()
        compose.onNodeWithText("Rádio FM").assertIsDisplayed()
        assertTrue(preferences.library.state.value.hidden.isEmpty())
    }

    @Test fun `bulk clearing pins requires explicit confirmation and keeps installed apps`() {
        preferences.pin(radio.component)
        show()
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithText("Clear all pinned apps").performScrollTo().performClick()
        assertEquals(listOf(radio.component), preferences.state.value.favorites)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(radio.component), preferences.state.value.favorites)
        compose.onNodeWithText("Clear all pinned apps").performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Done").performClick()
        assertTrue(preferences.state.value.favorites.isEmpty())
        compose.onNodeWithText("Rádio FM").assertIsDisplayed()
    }

    @Test fun `history is explicitly enabled and turning it off requests deletion confirmation`() {
        show()
        compose.onNodeWithContentDescription("App library options").performClick()
        compose.onNodeWithContentDescription("Remember app launches").performScrollTo().performClick()
        assertTrue(preferences.library.state.value.recordHistory)
        compose.runOnIdle { preferences.library.recordLaunch(radio.component, 100) }
        compose.onNodeWithContentDescription("Remember app launches").performScrollTo().performClick()
        assertTrue(preferences.library.state.value.usage.isNotEmpty())
        compose.onNodeWithText("Confirm").performClick()
        assertFalse(preferences.library.state.value.recordHistory)
        assertTrue(preferences.library.state.value.usage.isEmpty())
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/${com.cabin.BuildConfig.BUILD_TYPE}/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
