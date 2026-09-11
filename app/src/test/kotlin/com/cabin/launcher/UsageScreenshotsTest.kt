package com.cabin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.TeyesVehicleDataLayout
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Reproducible UI captures. CAN readings below are demonstration fixtures, not hardware measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w480dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: CabinManager
    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().clear().commit()
        context.getSharedPreferences("carlink_dashboard_v1", 0).edit().clear().commit()
        val dashboard = DashboardPreferences(context, 0, 262465, TeyesVehicleDataLayout.LEGACY)
        dashboard.state.value.tiles.forEach { dashboard.remove(it.id) }
        check(dashboard.add(DashboardModule.CLIMATE, 0))
        manager = CabinManager(context)
    }
    @After fun release() = runBlocking { manager.releaseAndWait() }
    @Test
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun `capture dashboard edit mode`() {
        val guard = com.cabin.platform.TeyesDrivingGuard().apply { confirmParked() }
        compose.setContent {
            CabinTheme(darkTheme = true) {
                com.cabin.CabinApp(manager, null, com.cabin.ui.settings.DisplayMode.SYSTEM_UI_VISIBLE,
                    homeRequest = 1L, drivingGuard = guard, onResetCluster = {})
            }
        }
        compose.onNodeWithContentDescription("Edit layout").performClick()
        capture("usage-edit-layout")
    }

    @Test fun `capture portrait climate usage with demonstration readings`() {
        val vehicle = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE, profileId = 262465,
            availableCodes = setOf(30, 35, 25, 31, 33, 73, 21), controlsAvailable = true,
            fanControlsAvailable = true, ac = true, fanLevel = 3, leftTemperature = 44, rightTemperature = 46,
            recirculating = true, blowBody = true)
        compose.setContent {
            CabinTheme(darkTheme = true) {
                com.cabin.CabinApp(manager, null, com.cabin.ui.settings.DisplayMode.SYSTEM_UI_VISIBLE,
                    homeRequest = 1L, climateState = vehicle, onSetClimateAc = {}, onSetClimateFan = {},
                    onSetClimateAirflow = {}, onRefreshClimate = {}, onResetCluster = {})
            }
        }
        capture("usage-ac-portrait")
    }

    @Test
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun `capture new mirror and parking widgets`() {
        renderFactory(17, mapOf(148 to 0x101, 149 to 0x100, 150 to 1, 151 to 0x101, 152 to 0x100,
            116 to 0x101, 117 to 0x104, 118 to 0x103, 119 to 0x105, 120 to 0x104),
            listOf(DashboardModule.MIRROR_SETTINGS, DashboardModule.PARKING_SETTINGS))
        capture("factory-mirrors-parking")
    }

    @Test
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun `capture new camera view widget`() {
        renderFactory(131114, mapOf(134 to 1), listOf(DashboardModule.CAMERA_MODE, DashboardModule.CLOCK))
        capture("factory-camera-mode")
    }

    @Test
    @Config(qualifiers = "w480dp-h800dp-port-mdpi")
    fun `capture new tire pressure widget portrait`() {
        renderFactory(1376590, mapOf(146 to 80, 147 to 82, 148 to 68, 149 to 81,
            150 to 0, 151 to 0, 152 to 2, 153 to 0), listOf(DashboardModule.TIRE_PRESSURE))
        capture("factory-tire-pressure-portrait")
    }

    private fun renderFactory(profile: Int, readings: Map<Int, Int>, modules: List<DashboardModule>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.cabin.platform.TeyesFeaturePreferences.get(context).select(0)
        val dashboard = DashboardPreferences(context, 0, profile, TeyesVehicleDataLayout.LEGACY)
        dashboard.state.value.tiles.forEach { dashboard.remove(it.id) }
        for (module in modules) {
            check(dashboard.add(module, 0))
            val tile = dashboard.state.value.tiles.last()
            check(dashboard.resize(tile.id, if (modules.size == 1) 8 else 4, 4))
        }
        val vehicle = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE,
            profileId = profile, syuVehicle = com.cabin.platform.SyuVehicleProtocol.decode(profile, readings))
        compose.setContent { CabinTheme(darkTheme = true) {
            androidx.compose.material3.Surface {
                ModularDashboard(manager, vehicle, false, LauncherPreferences(context), { it() }, {},
                    climateActions = ClimateWidgetActions(onFactoryControl = { _, _ -> }))
            }
        } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/ui/debug/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
