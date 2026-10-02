package com.cabin.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.ProjectionSetupCheck
import com.cabin.platform.ProjectionSetupStep
import com.cabin.ui.settings.AudioSourceConfig
import com.cabin.ui.settings.MicSourceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class ProjectionExtensionsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `device query combines words and platform without searching private addresses`() {
        val devices = listOf(
            CabinManager.DeviceInfo("private-address", "Zoe Phone", "CarPlay"),
            CabinManager.DeviceInfo("02", "Alice Phone", "AndroidAuto"),
            CabinManager.DeviceInfo("03", "Bob Phone", "CarPlay"),
        )
        assertEquals(listOf(devices[0]), filterProjectionDevices(devices, "  ZOE   carplay ", null, false))
        assertTrue(filterProjectionDevices(devices, "private-address", null, false).isEmpty())
        assertTrue(filterProjectionDevices(devices, "Zoe", "AndroidAuto", false).isEmpty())
        assertEquals(listOf(devices[2], devices[0]), filterProjectionDevices(devices, "", "CarPlay", true))
        assertEquals(devices, filterProjectionDevices(devices, "", null, false))
    }

    @Test
    fun `phone filters recover from an empty result and preserve explicit selection`() {
        val phone = CabinManager.DeviceInfo("01", "My phone", "CarPlay")
        var selected: CabinManager.DeviceInfo? = null
        compose.setContent { MaterialTheme { ProjectionDevicePicker(listOf(phone), null, { selected = it }, {}, {}) } }
        compose.onNodeWithText("Find and sort phones").performClick()
        compose.onNodeWithText("Search phone name or platform").performScrollTo().performTextInput("missing")
        compose.onNodeWithText("No phones match these filters.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithText("Clear filters").performScrollTo().performClick()
        compose.onNodeWithText("My phone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(phone, selected) }
    }

    @Test
    fun `report section and search leave the frozen source intact`() {
        val report = "{\n  \"events\": [\"USB\"],\n  \"status\": \"Ready\"\n}"
        assertEquals(listOf("events", "status"), healthReportSections(report))
        assertEquals(report, healthReportSection(report, null))
        assertEquals(report, healthReportSection(report, "missing"))
        assertEquals(listOf("  \"status\": \"Ready\""), searchHealthReport(report, " READY "))
        assertTrue(!healthReportSection(report, "events").contains("status"))
        assertTrue(searchHealthReport(report, "nothing").isEmpty())
        assertEquals("not json", healthReportSection("not json", "events"))
        assertTrue(healthReportSections("not json").isEmpty())
    }

    @Test
    fun `copying a filtered report copies the complete frozen report without saving`() {
        val report = "{\n  \"events\": [\"USB\"],\n  \"status\": \"Ready\"\n}"
        var saves = 0
        compose.setContent { MaterialTheme { HealthReportPreview(report, { saves++ }, {}) } }
        compose.onNodeWithText("Search report").performScrollTo().performTextInput("Ready")
        compose.onNodeWithText("Copy complete report").performScrollTo().performClick()
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(report, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(0, saves)
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h240dp-land-mdpi")
    fun `health report keeps save and cancel visible on a short screen with large text`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { HealthReportPreview("{\"status\":\"Ready\"}", {}, {}) }
            }
        }
        compose.onNodeWithText("Choose where to save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Copy complete report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose where to save").assertIsDisplayed()
    }

    @Test
    fun `setup jump checklist and restart require explicit user actions`() {
        var resets = 0
        compose.setContent {
            var step by remember { mutableStateOf(ProjectionSetupStep.USB) }
            var parked by remember { mutableStateOf(false) }
            var checks by remember { mutableStateOf(emptySet<ProjectionSetupCheck>()) }
            MaterialTheme {
                ProjectionSetupScreen(step, ProjectionReadinessSnapshot(), parked, AudioSourceConfig.ADAPTER, MicSourceConfig.APP,
                    onParked = { parked = it }, onAudio = {}, onMicrophone = {}, onSaveAudio = {}, onConnect = {},
                    onRefresh = {}, onOpenPermissions = {}, onStep = { step = it }, onClose = {}, onComplete = {},
                    verified = checks, onVerify = { check, selected -> checks = if (selected) checks + check else checks - check },
                    onRestart = { resets++; step = ProjectionSetupStep.USB; checks = emptySet() })
            }
        }
        compose.onNodeWithText("5. Guide complete").performScrollTo().performClick()
        compose.onNodeWithText("I heard phone audio through the car speakers").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I am parked and ready to configure or test").performScrollTo().performClick()
        compose.onNodeWithText("I heard phone audio through the car speakers").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithText("1 of 3 checks verified").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restart guide").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("Restart guide").performScrollTo().performClick()
        compose.onAllNodesWithText("Restart guide").onLast().performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test
    fun `touch targets and multitouch accept real pointer events and stop when disabled`() {
        var enabled by mutableStateOf(true)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ProjectionTouchDiagnostics(enabled) } } }
        compose.onNodeWithText("Touch targets").performScrollTo().performClick()
        projectionTouchTargets.forEach { target ->
            compose.onNodeWithContentDescription("Five touchscreen test targets").performScrollTo().performTouchInput {
                click(Offset(width * target.x, height * target.y))
            }
        }
        compose.onNodeWithText("All five targets responded.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Multi-touch test").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Multi-touch test area").performScrollTo().performTouchInput {
            down(0, center - Offset(40f, 0f))
            down(1, center + Offset(40f, 0f))
            up(0)
            up(1)
        }
        compose.onNodeWithText("Two or more simultaneous touches detected.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { enabled = false }
        compose.onNodeWithContentDescription("Multi-touch test area").assertDoesNotExist()
        compose.onNodeWithText("Touch targets").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `touch target hit testing rejects empty geometry and misses`() {
        assertNull(projectionTouchTargetAt(Offset(10f, 10f), 0f, 100f, 12f))
        assertNull(projectionTouchTargetAt(Offset(50f, 20f), 100f, 100f, 8f))
        assertEquals(0, projectionTouchTargetAt(Offset(10f, 10f), 100f, 100f, 8f))
        assertEquals(2, projectionTouchTargetAt(Offset(250f, 140f), 500f, 280f, 28f))
        assertEquals(4, projectionTouchTargetAt(Offset(90f, 90f), 100f, 100f, 8f))
    }
}
