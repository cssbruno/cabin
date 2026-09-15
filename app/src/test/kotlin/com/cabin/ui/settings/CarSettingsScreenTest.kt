package com.cabin.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.cabin.launcher.ClimateWidgetActions
import com.cabin.platform.*
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w1000dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarSettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `replacement service and diagnostics belong to Cabin package`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val service = context.packageManager.getServiceInfo(
            android.content.ComponentName(context, com.cabin.hardware.ReplacementService::class.java), 0)
        assertEquals(context.packageName, service.packageName)
        val cabinInfo = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(cabinInfo.uid, service.applicationInfo.uid)
        assertEquals("com.cabin.hardware.permission.CONNECT", service.permission)
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.reports", 0)
        assertEquals(context.packageName, checkNotNull(provider).packageName)
        assertEquals(false, provider.exported)
    }
    @Test fun `diagnostics opens inside Cabin without a standalone app`() {
        compose.setContent { CabinTheme {
            androidx.compose.foundation.layout.Column { VehicleDiagnosticExport(TeyesClimateState()) }
        } }
        compose.onNodeWithText("Diagnostics").performClick()
        compose.runOnIdle {
            val intent = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(compose.activity.packageName, intent.component?.packageName)
            assertEquals("com.cabin.hardware.LabActivity", intent.component?.className)
            val info = compose.activity.packageManager.getActivityInfo(checkNotNull(intent.component), 0)
            assertEquals(false, info.exported)
        }
    }
    @Test fun `Honda panel selection uses parked guard and waits for vehicle feedback`() {
        val writes = mutableListOf<Pair<SyuFactoryControl, Int>>()
        var pending: (() -> Unit)? = null
        val vehicle = TeyesClimateState(connected = true, profileId = 0x40141,
            syuVehicle = SyuVehicleTelemetry(factoryControls = mapOf(SyuFactoryControl.HONDA_PANEL_CONFIG to 0)))
        compose.setContent {
            CabinTheme { CarSettingsScreen(vehicle, false,
                ClimateWidgetActions(onFactoryControl = { control, value -> writes += control to value }),
                { pending = it }, null) }
        }
        compose.onNodeWithText("Honda instrument panel").performScrollTo().performClick()
        compose.onNodeWithText("Type 1").performScrollTo().performClick()
        compose.onNodeWithText("Type 3").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<SyuFactoryControl, Int>>(), writes)
            checkNotNull(pending).invoke()
            assertEquals(listOf(SyuFactoryControl.HONDA_PANEL_CONFIG to 2), writes)
        }
        compose.onNodeWithText("Type 1").assertIsDisplayed()
    }

    private fun camera() = TeyesClimateState(connected = true, profileId = 131114,
        syuVehicle = SyuVehicleTelemetry(factoryControls = mapOf(SyuFactoryControl.CAMERA_MODE to 0)))

    @Test fun `RZC units are native Cabin choices rather than an on off switch`() {
        val writes = mutableListOf<Pair<SyuFactoryControl, Int>>()
        var pending: (() -> Unit)? = null
        val vehicle = TeyesClimateState(connected = true, profileId = 0x10012a,
            syuVehicle = SyuVehicleTelemetry(factoryControls = mapOf(SyuFactoryControl.HONDA_DISTANCE_UNITS to 0)))
        compose.setContent { CabinTheme { CarSettingsScreen(vehicle, false,
            ClimateWidgetActions(onFactoryControl = { control, value -> writes += control to value }),
            { pending = it }, null) } }
        compose.onNodeWithText("Honda instrument panel").performScrollTo().performClick()
        compose.onNodeWithText("km/h · km").performScrollTo().performClick()
        compose.onNodeWithText("mph · miles").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<Pair<SyuFactoryControl, Int>>(), writes)
            checkNotNull(pending).invoke()
            assertEquals(listOf(SyuFactoryControl.HONDA_DISTANCE_UNITS to 1), writes)
        }
        compose.onNodeWithText("km/h · km").assertIsDisplayed()
        compose.onNodeWithText("Type 1").assertDoesNotExist()
    }

    @Test fun `camera selection uses parked guard and waits for confirmed feedback`() {
        val writes = mutableListOf<Int>()
        var pending: (() -> Unit)? = null
        compose.setContent {
            CabinTheme { CarSettingsScreen(camera(), false,
                ClimateWidgetActions(onFactoryControl = { _, value -> writes += value }), { pending = it }, null) }
        }
        openCamera()
        compose.onNodeWithText("Wide").performScrollTo().performClick()
        compose.onNodeWithText("Standard").performClick()
        compose.runOnIdle { assertEquals(emptyList<Int>(), writes); checkNotNull(pending).invoke(); assertEquals(listOf(1), writes) }
        compose.onNodeWithText("Wide").assertIsDisplayed()
    }

    @Test fun `disconnect dismisses choices and disables controls`() {
        var vehicle by mutableStateOf(camera())
        compose.setContent { CabinTheme { CarSettingsScreen(vehicle, false, ClimateWidgetActions(onFactoryControl = { _, _ -> }), { it() }, null) } }
        openCamera()
        compose.onNodeWithText("Wide").performScrollTo().performClick()
        compose.runOnIdle { vehicle = vehicle.copy(connected = false) }
        compose.onNodeWithText("Standard").assertDoesNotExist()
        compose.onNodeWithText("Waiting for feedback").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `missing feedback and motion disable supported controls`() {
        var vehicle by mutableStateOf(camera().copy(syuVehicle = SyuVehicleTelemetry()))
        var moving by mutableStateOf(false)
        compose.setContent { CabinTheme { CarSettingsScreen(vehicle, moving, ClimateWidgetActions(onFactoryControl = { _, _ -> }), { it() }, null) } }
        openCamera()
        compose.onNodeWithText("Waiting for feedback").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { vehicle = camera(); moving = true }
        compose.onNodeWithText("Wide").assertIsNotEnabled()
    }

    @Test fun `deferred selection cannot cross vehicle profiles`() {
        var vehicle by mutableStateOf(camera())
        var pending: (() -> Unit)? = null
        var writes = 0
        compose.setContent { CabinTheme { CarSettingsScreen(vehicle, false, ClimateWidgetActions(onFactoryControl = { _, _ -> writes++ }), { pending = it }, null) } }
        openCamera()
        compose.onNodeWithText("Wide").performScrollTo().performClick()
        compose.onNodeWithText("Standard").performClick()
        compose.runOnIdle { vehicle = camera().copy(profileId = 131109) }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(pending).invoke(); assertEquals(0, writes) }
    }

    @Test fun `unknown profile shows limitation and retains personal settings on narrow screens`() {
        compose.setContent {
            CabinTheme { Box(Modifier.width(360.dp).height(480.dp)) {
                CarSettingsScreen(TeyesClimateState(), false, ClimateWidgetActions(), {}, null)
            } }
        }
        openCamera()
        compose.onNodeWithText("No verified mapping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("My car").performScrollTo().performClick()
        compose.onNodeWithText("Vehicle compatibility").performScrollTo().assertIsDisplayed()
    }

    @Test fun `car settings overview is readable on a head unit`() {
        compose.setContent { CabinTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.width(800.dp).height(600.dp)) { CarSettingsScreen(camera(), false, ClimateWidgetActions(), {}, null) }
            }
        } }
        compose.onNodeWithText("Car Settings").assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/debug/car-settings.png")
            checkNotNull(file.parentFile).mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun openCamera() { compose.onNodeWithText("Parking & camera").performScrollTo().performClick() }
}
