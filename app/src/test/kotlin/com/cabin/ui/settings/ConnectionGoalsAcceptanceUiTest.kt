package com.cabin.ui.settings

import android.content.Context
import android.os.SystemClock
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.platform.*
import com.cabin.protocol.AdapterConfig
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Acceptance of the non-hardware connection goals through the real settings and progress UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class ConnectionGoalsAcceptanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context get() = compose.activity.applicationContext
    private lateinit var manager: CabinManager
    @Before fun setup() {
        // A Robolectric application is recreated between cases; production singletons
        // must not retain SharedPreferences from the preceding application's context.
        ReflectionHelpers.setStaticField(TeyesFeaturePreferences::class.java, "instance", null)
        ReflectionHelpers.setStaticField(ProjectionPreferences::class.java, "instance", null)
        for (name in listOf("projection_presentation_v1", "teyes_features_v1", "audio_experience_v1", "connection_summaries_v1", "phone_connection_intent")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
        TeyesFeaturePreferences.get(context).endGuest()
        TeyesFeaturePreferences.get(context).select(0)
        CarPlayBackendSelection.select(context, CarPlayBackend.DONGLE)
        manager = CabinManager(context)
    }
    @After fun close() = runBlocking { manager.releaseAndWait() }
    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent { CabinTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } } }
    }
    private fun showAdvanced() {
        show { ConnectionExperienceSection(manager) }
        compose.onNodeWithText(label(R.string.gx_more_controls)).performScrollTo().performClick()
    }
    private fun progress(): MutableStateFlow<ConnectionProgress> = ReflectionHelpers.getField(manager, "mutableProgress")

    @Test fun idleProgressDoesNotReserveAnEmptyCardButFailuresRemainVisible() {
        show { Column(Modifier.testTag("progress-container")) { ConnectionProgressPanel(manager) } }
        compose.onNodeWithTag("progress-container").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(0f))
        compose.runOnIdle { progress().value = ConnectionProgress(failure = ConnectionFailure.ADAPTER_MISSING) }
        compose.onNodeWithText(label(R.string.gx_adapter_missing)).assertIsDisplayed()
        compose.runOnIdle { progress().value = ConnectionProgress(stage = ConnectionStage.STREAMING) }
        compose.onNodeWithTag("progress-container").assertHeightIsEqualTo(androidx.compose.ui.unit.Dp(0f))
    }

    @Test fun projectionSlidersExposeTheirPurposeAndAutomotiveTouchHeight() {
        showAdvanced()
        for (description in listOf(label(R.string.gx_bezel_value, 0), label(R.string.gx_media_gain, 100), label(R.string.gx_navigation_gain, 100))) {
            compose.onNodeWithContentDescription(description).performScrollTo().assertIsDisplayed()
                .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(56f))
        }
        compose.onNodeWithContentDescription(label(R.string.gx_media_gain, 100)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.5f) }
        compose.onNodeWithContentDescription(label(R.string.gx_media_gain, 50)).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo(0.5f, 0f..1f)))
        compose.onNodeWithContentDescription(label(R.string.gx_bezel_value, 0)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.gx_parked_preview)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(label(R.string.gx_bezel_value, 0)).performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3.6f) }
        compose.onNodeWithContentDescription(label(R.string.gx_bezel_value, 4)).assertIsDisplayed()
    }

    @Test fun typedFailuresRenderTheirSpecificRecoveryAndPermissionAction() {
        show { ConnectionProgressPanel(manager) }
        for (reason in listOf(ConnectionFailure.PERMISSION_NEEDED, ConnectionFailure.ADAPTER_MISSING,
            ConnectionFailure.WRITE_FAILED, ConnectionFailure.CORRUPT_PACKET, ConnectionFailure.PHONE_TIMEOUT)) {
            compose.runOnIdle { progress().value = ConnectionProgress(failure = reason) }
            compose.onNodeWithText(label(reason.message)).assertIsDisplayed()
            if (reason == ConnectionFailure.PERMISSION_NEEDED) compose.onNodeWithText(label(R.string.gx_request_usb)).assertIsEnabled()
            else compose.onNodeWithText(label(R.string.gx_request_usb)).assertDoesNotExist()
        }
    }
    @Test fun retryCountdownPausesAndResumesWithoutChangingPhoneSettings() {
        context.getSharedPreferences("phone_connection_intent", 0).edit().putString("phone.saved", "MANUAL").commit()
        progress().value = ConnectionProgress(retryAtMs = SystemClock.elapsedRealtime() + 60_000)
        show { ConnectionProgressPanel(manager) }
        compose.onNodeWithText("Next attempt in", substring = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gx_pause_retry)).performClick()
        compose.onNodeWithText("Next attempt in", substring = true).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.gx_resume_retry)).performClick()
        compose.runOnIdle {
            assertFalse(manager.connectionProgress.value.retriesPaused)
            assertEquals(PhoneConnectionPreference.MANUAL, manager.phonePreference("saved"))
            assertFalse(manager.projectionSessionRequested)
        }
    }
    @Test fun historyUiProvidesOptInRetentionDeletionAndDisableClearsStorage() {
        assertFalse(manager.connectionHistory.enabled)
        showAdvanced()
        compose.onNodeWithText(label(R.string.gx_history)).performScrollTo().assertIsOff().performClick()
        compose.runOnIdle { manager.connectionHistory.append(ConnectionSessionSummary(System.currentTimeMillis(), 42_000, ConnectionFailure.USB_DETACHED, 3000)) }
        compose.onAllNodesWithText(label(R.string.action_refresh)).onLast().performScrollTo().performClick()
        compose.onNodeWithText("duration 42 s", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gx_days, 1)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, manager.connectionHistory.retentionDays) }
        compose.onNodeWithText(label(R.string.gx_clear_history)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, ConnectionHistory(context).read().size) }
        compose.onNodeWithText(label(R.string.gx_clear_history)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.action_delete)).performClick()
        compose.runOnIdle {
            assertTrue(ConnectionHistory(context).read().isEmpty())
            manager.connectionHistory.append(ConnectionSessionSummary(System.currentTimeMillis(), 1000, ConnectionFailure.WRITE_FAILED, null))
        }
        compose.onNodeWithText(label(R.string.gx_history)).performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText(label(R.string.uxv_stop_connection_history)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertTrue(ConnectionHistory(context).enabled); assertEquals(1, ConnectionHistory(context).read().size) }
        compose.onNodeWithText(label(R.string.gx_history)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.action_delete)).performClick()
        compose.onNodeWithText(label(R.string.gx_history)).assertIsOff()
        compose.runOnIdle {
            assertFalse(ConnectionHistory(context).enabled)
            assertFalse(context.getSharedPreferences("connection_summaries_v1", 0).contains("sessions"))
        }
    }
    @Test fun driverSwitchRestoresSavedPresentationThroughRealPreferenceControls() {
        val preferences = ProjectionPreferences.getInstance(context)
        val drivers = TeyesFeaturePreferences.get(context)
        show {
            val state by preferences.state.collectAsState()
            ProjectionPreferencesContent(state, isTeyes = true, onFocusControls = preferences::setFocusControls,
                onVehicleHud = preferences::setVehicleHud, onClimateNoticeMode = preferences::setClimateNoticeMode,
                onReturnWhenReady = preferences::setReturnWhenReady, onControlSide = preferences::setControlSide, showMeasurements = false)
        }
        compose.onNodeWithText(label(R.string.projection_left_side)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.projection_focus)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.projection_vehicle_hud)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.projection_return_ready)).performScrollTo().performClick()
        compose.runOnIdle { preferences.setClimateNoticeMode(ClimateNoticeMode.PANEL); drivers.select(1) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, context.getSharedPreferences("teyes_features_v1", 0).getInt("active", -1))
        compose.waitUntil(3_000) { preferences.state.value.controlSide == ProjectionControlSide.RIGHT }
        compose.onNodeWithText(label(R.string.projection_right_side)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(label(R.string.projection_focus)).performScrollTo().assertIsOn()
        compose.runOnIdle { assertEquals(ClimateNoticeMode.SUMMARY, preferences.state.value.climateNoticeMode); drivers.select(0) }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        compose.waitUntil(3_000) { preferences.state.value.controlSide == ProjectionControlSide.LEFT }
        compose.onNodeWithText(label(R.string.projection_left_side)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(label(R.string.projection_focus)).performScrollTo().assertIsOff()
        compose.onNodeWithText(label(R.string.projection_vehicle_hud)).performScrollTo().assertIsOn()
        compose.onNodeWithText(label(R.string.projection_return_ready)).performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(ClimateNoticeMode.PANEL, ProjectionPreferences(context).state.value.climateNoticeMode) }
    }
    @Test fun toolsCanBeReorderedDeselectedAndResetWhileSupportedGainsStayEnabled() {
        showAdvanced()
        compose.onNodeWithText(label(R.string.gx_capability_dongle)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(label(R.string.gx_move_up))[2].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("phone", "blackout", "settings"), ProjectionPreferences.getInstance(context).state.value.toolOrder) }
        compose.onAllNodesWithText(label(R.string.projection_change_device)).onFirst().performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("blackout", "settings"), ProjectionPreferences.getInstance(context).state.value.toolOrder) }
        compose.onNodeWithText(label(R.string.gx_reset_tools)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("phone", "settings", "blackout"), ProjectionPreferences(context).state.value.toolOrder) }
        compose.onNodeWithText(label(R.string.gx_buffer_platform)).performScrollTo().assertIsEnabled()
    }
    @Test fun nativeBackendExplainsUnavailableFeaturesAndExcludesUsbTools() {
        CarPlayBackendSelection.select(context, CarPlayBackend.JOYING)
        show { ConnectionExperienceSection(manager) }
        compose.onNodeWithText(label(R.string.gx_capability_native)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gx_more_controls)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.gx_tool_shortcuts)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.gx_buffer_platform)).assertDoesNotExist()
    }
    @Test fun bluetoothTransferExplainsOwnershipAndDisablesCabinGainControls() {
        ReflectionHelpers.setField(manager, "config", AdapterConfig.DEFAULT.copy(audioTransferMode = true))
        showAdvanced()
        compose.onNodeWithText(label(R.string.gx_audio_adapter_owned)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gx_buffer_platform)).performScrollTo().assertIsNotEnabled()
    }

    @Test fun audioPresetDeletionNamesThePresetAndCannotCrossDriverChanges() {
        val audio = com.cabin.audio.AudioExperience(context)
        val drivers = TeyesFeaturePreferences.get(context)
        audio.save(0, com.cabin.audio.AudioGainPreset("Quiet", 0.3f, 0.7f))
        showAdvanced()
        compose.onNodeWithText(label(R.string.gx_delete)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.uxc_delete_preset, "Quiet")).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, audio.presets(0).size) }
        compose.onNodeWithText(label(R.string.gx_delete)).performScrollTo().performClick()
        compose.runOnIdle { drivers.select(1) }
        compose.waitUntil(3_000) { drivers.profile.value.slot == 1 }
        compose.onNodeWithText(label(R.string.uxc_delete_preset, "Quiet")).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, audio.presets(0).size); drivers.select(0) }
        compose.waitUntil(3_000) { drivers.profile.value.slot == 0 }
        compose.onNodeWithText(label(R.string.gx_delete)).performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.gx_delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle {
            assertTrue(audio.presets(0).isEmpty())
            assertEquals(1f, drivers.profile.value.mediaGain)
            assertEquals(1f, drivers.profile.value.navigationGain)
        }
    }
}
