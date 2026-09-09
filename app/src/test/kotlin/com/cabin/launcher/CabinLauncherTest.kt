package com.cabin.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.BuildConfig
import com.cabin.CabinApp
import com.cabin.CabinManager
import com.cabin.platform.TeyesClimateState
import com.cabin.ui.settings.DisplayMode
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class CabinLauncherTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: CabinManager
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LauncherPreferences.FILE, 0).edit().clear().commit()
        context.getSharedPreferences("carlink_dashboard_v1", 0).edit().clear().commit()
        manager = CabinManager(context)
    }
    @After fun release() = runBlocking { manager.releaseAndWait() }

    @Test fun `launcher opens projection and app drawer without starting a session`() {
        var opened = 0
        compose.setContent {
            CabinTheme(darkTheme = true) {
                CabinLauncher(manager, TeyesClimateState(), false, { opened++ }, {}, null, {}, { it() })
            }
        }
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNode(hasText("CarPlay") and hasClickAction()).performClick()
        assertEquals(1, opened)
        assertFalse(manager.projectionSessionRequested)
        screenshot("launcher-home")
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNodeWithText("Apps").performScrollTo().performClick()
        compose.onNodeWithText("Search installed apps").assertIsDisplayed().performTextInput("missing app")
        compose.onNodeWithText("No matching apps.").assertIsDisplayed()
        assertFalse(manager.projectionSessionRequested)
    }

    @Test fun `known movement disables app browsing and settings`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), true, {}, {}, null, {}, { it() }) }
        }
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNodeWithText("Apps").assertIsNotEnabled()
        compose.onNodeWithText("Settings").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Edit layout").assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "pt-rBR-w1024dp-h600dp-land-mdpi")
    fun `Portuguese Home remains operable with enlarged text`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CabinTheme(darkTheme = true) {
                    Box(Modifier.width(800.dp).height(480.dp)) {
                        CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() })
                    }
                }
            }
        }
        compose.onNodeWithTag("launcher-pages").assertIsDisplayed().assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithText("Apps").performScrollTo().performClick()
        compose.onNodeWithText("Buscar apps instalados").assertIsDisplayed()
        screenshot("launcher-portuguese-drawer")
    }

    @Test fun `short landscape keeps app search accessible`() {
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(480.dp).height(240.dp)) {
                    CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() })
                }
            }
        }
        compose.onNodeWithTag("launcher-pages").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Search installed apps").assertIsDisplayed()
    }

    @Test fun `driving surface keeps primary actions visible and customization separate`() {
        compose.setContent {
            CabinTheme {
                CabinLauncher(manager, TeyesClimateState(), true, {}, {}, null, {}, { it() })
            }
        }
        compose.onNodeWithTag("module-PROJECTION-1").assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        compose.onNodeWithContentDescription("Phone assistant").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Customize").assertDoesNotExist()
        compose.onNodeWithText("Add widget").assertDoesNotExist()
        screenshot("launcher-driving")
    }

    @Test fun `Home route keeps a cold projection manager idle`() {
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        compose.setContent {
            CabinTheme {
                CabinApp(manager, null, DisplayMode.SYSTEM_UI_VISIBLE, homeRequest = 1L, onResetCluster = {})
            }
        }
        assertPage("Main menu")
        compose.runOnIdle { assertFalse(manager.projectionSessionRequested) }
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNode(hasText("CarPlay") and hasClickAction()).performClick()
        compose.onNodeWithText("Connection help").assertIsDisplayed()
        assertPage("CarPlay")
        compose.onNodeWithTag("launcher-pages").performTouchInput { swipeLeft() }
        assertPage("Main menu")
        compose.onNodeWithTag("module-PROJECTION-1").assertIsDisplayed()
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNode(hasText("CarPlay") and hasClickAction()).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertPage("Main menu")
        compose.runOnIdle { assertFalse(manager.projectionSessionRequested) }
    }

    @Test fun `swipe pages follow main apps settings and return`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() }) }
        }
        assertPage("Main menu")
        compose.onNodeWithTag("launcher-pages").performTouchInput { swipeLeft() }
        assertPage("Apps")
        compose.onNodeWithText("Search installed apps").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeLeft() }
        assertPage("Settings")
        compose.onNodeWithText("Customize").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipeRight() }
        assertPage("Apps")
    }

    @Test fun `Widgets page hosts gauges and Android widget setup`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() }) }
        }
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNodeWithText("Widgets").performScrollTo().performClick()
        assertPage("Widgets")
        compose.onNodeWithText("Customize").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose vehicle widgets").assertIsDisplayed()
        compose.onNodeWithText("Oil service distance").performClick()
        assertTrue(VehicleGauge.SERVICE in LauncherPreferences(compose.activity).state.value.gauges)
        screenshot("launcher-widget-page")
    }

    @Test fun `Widgets remains available while moving with customization disabled`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), true, {}, {}, null, {}, { it() }) }
        }
        compose.onNodeWithTag("launcher-pages").performClick()
        compose.onNodeWithText("Widgets").performScrollTo().performClick()
        assertPage("Widgets")
        compose.onNodeWithText("Customize").assertIsNotEnabled()
        compose.onNodeWithText("Add widget").assertDoesNotExist()
    }

    @Test fun `live projection module resizes the same native surface without connecting`() {
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
        val guard = com.cabin.platform.TeyesDrivingGuard().apply { confirmParked() }
        compose.setContent {
            CabinTheme(darkTheme = true) {
                CabinApp(manager, null, DisplayMode.SYSTEM_UI_VISIBLE, homeRequest = 1L, drivingGuard = guard, onResetCluster = {})
            }
        }
        fun surfaces(view: android.view.View): List<com.cabin.ui.components.VideoSurfaceView> =
            if (view is com.cabin.ui.components.VideoSurfaceView) listOf(view)
            else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) } else emptyList()
        compose.waitForIdle()
        val initial = surfaces(compose.activity.window.decorView).single()
        val before = compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot
        val opening = compose.onNodeWithTag("module-PROJECTION-1").fetchSemanticsNode().boundsInRoot
        assertEquals(opening.width, before.width, 1f)
        assertEquals(opening.top, before.top, 1f)
        var nativeTouches = 0
        val originalCallback = initial.callback!!
        compose.runOnIdle {
            initial.callback = object : com.cabin.ui.components.VideoSurfaceView.Callback by originalCallback {
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
                    nativeTouches++
                    return originalCallback.onTouchEvent(event)
                }
            }
        }
        // The taller module can letterbox above the live video; tap within the native view.
        val nativePosition = IntArray(2)
        compose.runOnIdle { initial.getLocationInWindow(nativePosition) }
        val point = androidx.compose.ui.geometry.Offset(
            nativePosition[0] + initial.width * 0.25f - opening.left,
            nativePosition[1] + initial.height * 0.5f - opening.top,
        )
        compose.onNodeWithTag("module-PROJECTION-1").performTouchInput { click(point) }
        screenshot("launcher-modular-default")
        compose.runOnIdle { assertTrue("Touches must reach the actual SurfaceView", nativeTouches > 0); initial.callback = originalCallback }
        compose.onNodeWithText("No phone media playing").assertDoesNotExist()
        compose.onNodeWithText("No fresh reading").assertDoesNotExist()
        compose.onNodeWithText("Edit layout").assertDoesNotExist()
        screenshot("launcher-modular-default")
        assertFalse(manager.projectionSessionRequested)
        compose.onNodeWithTag("resize-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithTag("module-PROJECTION-1").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(width * 0.25f, height * 0.5f))
            down(1, androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.5f))
            moveTo(0, androidx.compose.ui.geometry.Offset(width * 0.375f, height * 0.5f), 100)
            moveTo(1, androidx.compose.ui.geometry.Offset(width * 0.625f, height * 0.5f), 100)
            up(0); up(1)
        }
        val pinched = compose.onNodeWithTag("module-PROJECTION-1").fetchSemanticsNode().boundsInRoot
        assertTrue("Pinch must resize the module", pinched.width < opening.width)
        assertSame(initial, surfaces(compose.activity.window.decorView).single())
        compose.onNodeWithTag("resize-1").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(120f, 110f), 500)
            up()
        }
        assertTrue(compose.onNodeWithTag("module-PROJECTION-1").fetchSemanticsNode().boundsInRoot.width > pinched.width)
        compose.onNodeWithTag("module-PROJECTION-1").performClick()
        compose.onNodeWithText("2 × 2").performScrollTo().performClick()
        compose.onAllNodesWithText("Done").onLast().performClick()
        compose.onNodeWithContentDescription("Done").performClick()
        val after = compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width < before.width)
        assertSame(initial, surfaces(compose.activity.window.decorView).single())
        assertFalse(manager.projectionSessionRequested)
        screenshot("launcher-live-module")
        compose.onNodeWithTag("projection-fullscreen").assertHeightIsAtLeast(56.dp).performClick()
        val expanded = compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot
        assertTrue(expanded.width > after.width)
        assertTrue(expanded.height > after.height)
        compose.onNodeWithTag("launcher-pages").assertDoesNotExist()
        assertSame(initial, surfaces(compose.activity.window.decorView).single())
        compose.onNodeWithContentDescription("Exit full screen").performClick()
        val restored = compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot
        assertEquals(after, restored)
        assertSame(initial, surfaces(compose.activity.window.decorView).single())
        assertFalse(manager.projectionSessionRequested)
        compose.onNodeWithTag("page-dot-1").performClick()
        compose.onNodeWithTag("module-PROJECTION-1").assertDoesNotExist()
        compose.onNodeWithTag("page-dot-0").performClick()
        assertSame(initial, surfaces(compose.activity.window.decorView).single())
    }

    @Test fun `short dashboards paginate individual modules without vertical scrolling`() {
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(480.dp).height(240.dp)) {
                    CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() })
                }
            }
        }
        compose.onNodeWithTag("module-PROJECTION-1").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        compose.onNodeWithTag("page-dot-1").performClick()
        compose.onNodeWithTag("module-MEDIA-2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag("module-PROJECTION-1").assertDoesNotExist()
    }

    @Test fun `comfort widgets render missing data and open existing climate controls`() {
        var opened = false
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(480.dp).height(240.dp)) {
                    VehicleComfortWidget(DashboardModule.CLIMATE, TeyesClimateState()) { opened = true }
                }
            }
        }
        compose.onNodeWithText("A/C —").assertIsDisplayed()
        compose.onNodeWithContentDescription("Driver").assertTextContains("—")
        compose.onNodeWithContentDescription("Passenger").assertTextContains("—")
        compose.onNodeWithContentDescription("Open climate controls").assertHeightIsAtLeast(56.dp).performClick()
        assertTrue(opened)
        screenshot("launcher-ac-widget")
    }

    @Test fun `long press edits widgets and corner drag resizes with CarPlay handle in edit mode`() {
        compose.setContent {
            CabinTheme {
                CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() })
            }
        }
        compose.onNodeWithTag("module-SPEED-3").performTouchInput { longClick(center) }
        compose.onNodeWithContentDescription("Done").assertIsDisplayed()
        compose.onNodeWithTag("resize-3").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag("resize-1").assertIsDisplayed()
        compose.onNodeWithTag("page-dot-1").performClick()
        val before = compose.onNodeWithTag("module-OIL-5").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("resize-5").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(-270f, 0f), 500)
            up()
        }
        val after = compose.onNodeWithTag("module-OIL-5").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width < before.width)
        assertEquals(before.left, after.left, 1f)
        compose.onNodeWithContentDescription("Done").performClick()
        compose.onNodeWithTag("resize-5").assertDoesNotExist()
    }

    @Test fun `two finger resizing widgets does not drag their position`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() }) }
        }
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithTag("page-dot-1").performClick()
        val widget = compose.onNodeWithTag("module-RPM-4")
        val before = widget.fetchSemanticsNode().boundsInRoot
        widget.performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(width * 0.25f, height * 0.5f))
            down(1, androidx.compose.ui.geometry.Offset(width * 0.75f, height * 0.5f))
            moveTo(0, androidx.compose.ui.geometry.Offset(width * 0.375f, height * 0.5f), 100)
            moveTo(1, androidx.compose.ui.geometry.Offset(width * 0.625f, height * 0.5f), 100)
            up(0); up(1)
        }
        val after = widget.fetchSemanticsNode().boundsInRoot
        assertTrue(after.width < before.width)
        assertTrue(after.height < before.height)
        assertEquals(before.left, after.left, 1f)
        assertEquals(before.top, after.top, 1f)
    }

    @Test fun `moving prevents long press widget editing`() {
        compose.setContent {
            CabinTheme {
                CabinLauncher(manager, TeyesClimateState(), true, {}, {}, null, {}, { it() })
            }
        }
        compose.onNodeWithTag("module-SPEED-3").performTouchInput { longClick(center) }
        compose.onNodeWithContentDescription("Done").assertDoesNotExist()
        compose.onNodeWithTag("resize-3").assertDoesNotExist()
    }

    @Test fun `normal startup opens dashboard and settings return to the same layout`() {
        val guard = com.cabin.platform.TeyesDrivingGuard().apply { confirmParked() }
        compose.setContent {
            CabinTheme(darkTheme = true) {
                CabinApp(manager, null, DisplayMode.SYSTEM_UI_VISIBLE, drivingGuard = guard, onResetCluster = {})
            }
        }
        compose.onNodeWithTag("module-PROJECTION-1").assertIsDisplayed()
        val before = compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot
        assertFalse(manager.projectionSessionRequested)
        compose.onNodeWithTag("dashboard-settings").performClick()
        compose.onNodeWithTag("dashboard-settings-panel").assertIsDisplayed()
        val panel = compose.onNodeWithTag("dashboard-settings-panel").fetchSemanticsNode().boundsInRoot
        assertTrue(panel.width < 1024f)
        compose.onNodeWithTag("launcher-pages").assertDoesNotExist()
        screenshot("launcher-integrated-settings")
        compose.onNodeWithTag("dashboard-settings-dismiss").performTouchInput { click(androidx.compose.ui.geometry.Offset(24f, 200f)) }
        compose.onNodeWithTag("dashboard-settings-panel").assertDoesNotExist()
        compose.onNodeWithTag("module-PROJECTION-1").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("persistent-projection-frame").fetchSemanticsNode().boundsInRoot)
        assertFalse(manager.projectionSessionRequested)
    }

    @Test fun `AC widget sends controls and waits for real feedback while refresh stays available`() {
        val state = androidx.compose.runtime.mutableStateOf(TeyesClimateState(
            connected = true, health = com.cabin.platform.TeyesTelemetryHealth.LIVE,
            profileId = 262465, availableCodes = setOf(30, 35, 25, 31, 33),
            controlsAvailable = true, fanLevel = 3, ac = false, leftTemperature = 22, rightTemperature = 23))
        var acCommand: Boolean? = null
        var fanCommand: Int? = null
        var refreshes = 0
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(480.dp).height(260.dp)) {
                    VehicleComfortWidget(DashboardModule.CLIMATE, state.value,
                        ClimateWidgetActions({ acCommand = it }, { fanCommand = it }, { refreshes++ })) {}
                }
            }
        }
        compose.onNodeWithText("A/C off").performClick()
        assertEquals(true, acCommand)
        compose.onNodeWithText("A/C off").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(ac = true) }
        compose.onNodeWithText("A/C on").assertIsDisplayed()
        compose.onNodeWithContentDescription("Increase fan speed").performClick()
        assertEquals(4, fanCommand)
        compose.runOnIdle { state.value = state.value.copy(health = com.cabin.platform.TeyesTelemetryHealth.STALE, controlsAvailable = false) }
        compose.onNodeWithContentDescription("Increase fan speed").assertIsNotEnabled()
        compose.onNodeWithText("A/C —").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Refresh A/C data").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun `AC widget adapts from wide to tall with controls inside its bounds`() {
        val size = androidx.compose.runtime.mutableStateOf(480.dp to 180.dp)
        val state = TeyesClimateState(connected = true, health = com.cabin.platform.TeyesTelemetryHealth.LIVE,
            profileId = 262465, availableCodes = setOf(30, 35, 25, 31, 33),
            controlsAvailable = true, fanLevel = 3, leftTemperature = 22, rightTemperature = 23)
        var command: Int? = null
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(size.value.first).height(size.value.second)) {
                    VehicleComfortWidget(DashboardModule.CLIMATE, state,
                        ClimateWidgetActions(onAc = {}, onFan = { command = it }, onRefresh = {})) {}
                }
            }
        }
        fun checkControls(tag: String) {
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            listOf("Open climate controls", "Increase fan speed", "Decrease fan speed", "Select fan speed").forEach { label ->
                val control = compose.onNodeWithContentDescription(label).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
                val rect = control.fetchSemanticsNode().boundsInRoot
                assertTrue("$label must fit in $tag", rect.left >= bounds.left && rect.top >= bounds.top &&
                    rect.right <= bounds.right && rect.bottom <= bounds.bottom)
            }
            compose.onNodeWithContentDescription("Increase fan speed").performClick()
            assertEquals(4, command)
        }
        checkControls("ac-horizontal")
        screenshot("launcher-ac-horizontal")
        compose.runOnIdle { size.value = 220.dp to 480.dp }
        checkControls("ac-vertical")
        screenshot("launcher-ac-vertical")
        compose.runOnIdle { size.value = 180.dp to 400.dp }
        checkControls("ac-vertical")
        compose.runOnIdle { size.value = 240.dp to 132.dp }
        compose.onNodeWithTag("ac-compact").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open climate controls").assertIsDisplayed()
        compose.onNodeWithText("A/C off").assertIsDisplayed()
    }

    @Test fun `unified AC includes airflow and recirculation and waits for feedback`() {
        val state = androidx.compose.runtime.mutableStateOf(TeyesClimateState(
            connected = true, health = com.cabin.platform.TeyesTelemetryHealth.LIVE,
            profileId = 262465, availableCodes = setOf(30, 35, 25, 31, 33, 73, 21),
            controlsAvailable = true, fanLevel = 3, leftTemperature = 44, rightTemperature = 46,
            blowBody = true, recirculating = true))
        var requested: com.cabin.platform.TeyesAirflowMode? = null
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(480.dp).height(240.dp)) {
                    AcWidget(state.value, ClimateWidgetActions(onAirflow = { requested = it })) {}
                }
            }
        }
        val airflow = compose.onNodeWithContentDescription("Airflow")
        airflow.assertIsDisplayed().performClick()
        compose.onNodeWithText("Face + feet").performClick()
        assertEquals(com.cabin.platform.TeyesAirflowMode.BODY_FOOT, requested)
        airflow.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Face"))
        compose.onNodeWithContentDescription("Recirculation").assertTextContains("ON")
        compose.runOnIdle { state.value = state.value.copy(blowFoot = true) }
        airflow.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Face + feet"))
        airflow.performClick()
        compose.runOnIdle { state.value = state.value.copy(health = com.cabin.platform.TeyesTelemetryHealth.STALE) }
        compose.onNodeWithText("Screen + feet").assertDoesNotExist()
        airflow.assertIsNotEnabled()
        compose.onNodeWithContentDescription("Recirculation").assertTextContains("—")
    }

    @Test fun `widget picker offers one AC entry instead of individual climate functions`() {
        compose.setContent {
            CabinTheme { CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() }) }
        }
        compose.onNodeWithContentDescription("Edit layout").performClick()
        compose.onNodeWithContentDescription("Add module").performClick()
        compose.onNodeWithText("A/C").performScrollTo().assertIsDisplayed()
        listOf("Fan", "Rear climate", "Seats", "Defrost", "Driver temperature", "Passenger temperature", "Airflow", "Recirculation").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun `route audio and pinned apps widgets fit wide tall and compact cards`() {
        val module = androidx.compose.runtime.mutableStateOf(DashboardModule.ROUTE_OVERVIEW)
        val size = androidx.compose.runtime.mutableStateOf(480.dp to 160.dp)
        val preferences = LauncherPreferences(compose.activity)
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(size.value.first).height(size.value.second)) {
                    when (module.value) {
                        DashboardModule.ROUTE_OVERVIEW -> RouteOverviewWidget(com.cabin.navigation.NavigationState(), false, 1000)
                        DashboardModule.AUDIO_CONTROL -> AudioControlWidget()
                        else -> PinnedAppsWidget(preferences, false) { it() }
                    }
                }
            }
        }
        for (dimensions in listOf(480.dp to 160.dp, 180.dp to 400.dp, 100.dp to 100.dp)) {
            for (kind in listOf(DashboardModule.ROUTE_OVERVIEW, DashboardModule.AUDIO_CONTROL, DashboardModule.PINNED_APPS)) {
                compose.runOnIdle { size.value = dimensions; module.value = kind }
                compose.onNodeWithTag("widget-${kind.name}").assertIsDisplayed()
                if (kind == DashboardModule.AUDIO_CONTROL) compose.onNodeWithContentDescription("Mute / unmute").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
            }
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
    }

    @Test fun `audio widget changes the Android media volume and reflects the result`() {
        val audio = compose.activity.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 3, 0)
        compose.setContent {
            CabinTheme { Box(Modifier.width(480.dp).height(240.dp)) { AudioControlWidget() } }
        }
        compose.onNodeWithContentDescription("Volume +").assertIsEnabled().performClick()
        val increased = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        assertTrue(increased > 3)
        compose.onNodeWithText("$increased / ${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Volume −").performClick()
        assertEquals(3, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
    }

    @Test fun `ten additional widgets fit horizontal vertical and smallest cards`() {
        val module = androidx.compose.runtime.mutableStateOf(DashboardModule.DATE)
        val size = androidx.compose.runtime.mutableStateOf(480.dp to 140.dp)
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(size.value.first).height(size.value.second)) {
                    AdditionalDashboardWidget(module.value, TeyesClimateState(), CabinManager.State.DISCONNECTED)
                }
            }
        }
        assertEquals(10, additionalDashboardModules.size)
        for (dimensions in listOf(480.dp to 140.dp, 180.dp to 400.dp, 100.dp to 100.dp)) {
            for (kind in additionalDashboardModules) {
                compose.runOnIdle { size.value = dimensions; module.value = kind }
                compose.onNodeWithTag("widget-${kind.name}").assertIsDisplayed()
                val title = compose.activity.getString(kind.title())
                compose.onAllNodesWithContentDescription(title).onFirst().assertIsDisplayed()
                if (kind in setOf(DashboardModule.DRIVER_TEMPERATURE, DashboardModule.PASSENGER_TEMPERATURE,
                        DashboardModule.AIRFLOW, DashboardModule.RECIRCULATION, DashboardModule.HOOD, DashboardModule.TRUNK)) {
                    compose.onNodeWithText("—").assertIsDisplayed()
                }
            }
        }
    }

    @Test fun `assistant widget is enabled only with a streaming phone`() {
        val phone = androidx.compose.runtime.mutableStateOf(CabinManager.State.DISCONNECTED)
        var invoked = 0
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(180.dp).height(240.dp)) {
                    AdditionalDashboardWidget(DashboardModule.ASSISTANT, TeyesClimateState(), phone.value) { invoked++ }
                }
            }
        }
        val button = compose.onNode(hasContentDescription("Phone assistant") and hasClickAction())
        button.assertIsNotEnabled()
        compose.runOnIdle { phone.value = CabinManager.State.DEVICE_CONNECTED }
        button.assertIsNotEnabled()
        compose.runOnIdle { phone.value = CabinManager.State.STREAMING }
        button.assertIsEnabled().performClick()
        assertEquals(1, invoked)
        compose.runOnIdle { phone.value = CabinManager.State.DISCONNECTED }
        button.assertIsNotEnabled()
    }

    @Test fun `fan widget selects an exact speed and retains reported feedback`() {
        val state = androidx.compose.runtime.mutableStateOf(TeyesClimateState(
            connected = true, health = com.cabin.platform.TeyesTelemetryHealth.LIVE,
            profileId = 262465, availableCodes = setOf(35), fanControlsAvailable = true, fanLevel = 3))
        var command: Int? = null
        compose.setContent {
            CabinTheme {
                Box(Modifier.width(250.dp).height(240.dp)) {
                    VehicleComfortWidget(DashboardModule.FAN, state.value, ClimateWidgetActions(onFan = { command = it })) {}
                }
            }
        }
        compose.onNodeWithContentDescription("Select fan speed").performClick()
        compose.onNodeWithContentDescription("Fan 6/7").performClick()
        assertEquals(6, command)
        compose.onNodeWithText("Fan 3/7").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(fanLevel = 6) }
        compose.onNodeWithText("Fan 6/7").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select fan speed").performClick()
        compose.runOnIdle { state.value = state.value.copy(health = com.cabin.platform.TeyesTelemetryHealth.STALE) }
        compose.onNodeWithContentDescription("Fan 7/7").assertDoesNotExist()
        compose.onNodeWithContentDescription("Increase fan speed").assertIsNotEnabled()
    }

    @Test fun `long press drag swaps widgets and a second drag uses their new positions`() {
        compose.setContent {
            CabinTheme {
                CabinLauncher(manager, TeyesClimateState(), false, {}, {}, null, {}, { it() })
            }
        }
        compose.onNodeWithTag("page-dot-1").performClick()
        val first = compose.onNodeWithTag("module-RPM-4").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("module-OIL-5").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("module-RPM-4").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(androidx.compose.ui.geometry.Offset(second.left - first.left, 0f), 500)
            up()
        }
        assertEquals(second.left, compose.onNodeWithTag("module-RPM-4").fetchSemanticsNode().boundsInRoot.left, 2f)
        assertEquals(first.left, compose.onNodeWithTag("module-OIL-5").fetchSemanticsNode().boundsInRoot.left, 2f)
        compose.onNodeWithTag("module-RPM-4").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(first.left - second.left, 0f), 500)
            up()
        }
        assertEquals(first.left, compose.onNodeWithTag("module-RPM-4").fetchSemanticsNode().boundsInRoot.left, 2f)
        compose.onNodeWithContentDescription("Done").performClick()
        screenshot("launcher-fine-grid")
    }

    private fun assertPage(label: String) {
        compose.onNodeWithTag("launcher-pages").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, label))
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val buildType = BuildConfig.BUILD_TYPE
            val file = File("build/reports/ui/$buildType/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
