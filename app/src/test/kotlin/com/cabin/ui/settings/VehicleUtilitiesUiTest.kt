package com.cabin.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cabin.BuildConfig
import com.cabin.platform.RecordedTrip
import com.cabin.platform.TripHistory
import com.cabin.platform.rememberAutomationValues
import com.cabin.ui.theme.CabinTheme
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
@Config(sdk = [35], qualifiers = "en-w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleUtilitiesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var history: TripHistory
    @Before fun reset() {
        history = TripHistory(compose.activity)
        history.prefs.edit().clear().commit()
    }

    @Test fun `period filter deletion and undo update the displayed collection`() {
        val now = System.currentTimeMillis()
        history.save(17, RecordedTrip(now, now + 60000, 1.25, 60, null))
        history.save(17, RecordedTrip(now - 15 * 86400000L, now - 15 * 86400000L + 60000, 2.5, 60, null))
        showBrowser()
        compose.onNodeWithText("Trips in this period: 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All recorded trips").performScrollTo().performClick()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Trips in this period: 1").performScrollTo().assertIsDisplayed()
        screenshot("trip-history-browser")
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, history.read(17).size) }
        compose.onNodeWithText("Undo").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, history.read(17).size) }
        compose.onNodeWithText("Trips in this period: 1").performScrollTo().assertIsDisplayed()
    }

    @Test fun `pagination reaches older records and CSV preview explains exported information`() {
        val now = System.currentTimeMillis()
        repeat(11) { i ->
            val start = now - (i + 1) * 120000L
            history.save(17, RecordedTrip(start, start + 60000, (i + 1).toDouble(), 60, null))
        }
        showBrowser()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithText("Page 2 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export selected trips (CSV)").performScrollTo().performClick()
        compose.onNodeWithText("Save 11 trips", substring = true).assertIsDisplayed()
        screenshot("trip-csv-preview")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(11, history.read(17).size) }
    }

    private fun showBrowser() {
        compose.setContent {
            CabinTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    TripHistoryBrowser(history, 17, rememberAutomationValues("cabin_trips"))
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/${BuildConfig.BUILD_TYPE}/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
