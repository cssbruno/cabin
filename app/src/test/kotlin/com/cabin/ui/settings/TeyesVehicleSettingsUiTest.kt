package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.platform.MeasurementPreferences
import com.cabin.platform.MeasurementUnit
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.TeyesVehicleDataLayout
import com.cabin.ui.theme.CabinTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** These fixtures provide FYT state only: no Bluetooth service or external adapter exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeyesVehicleSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun selectMetricUnits() {
        MeasurementPreferences.get(compose.activity).select(MeasurementUnit.METRIC)
    }

    @Test
    fun `vehicle settings show FYT readings and expose no external adapter controls`() {
        compose.setContent {
            CabinTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ObdSettingsSection(liveVehicle())
                }
            }
        }
        compose.onNodeWithText("Source: FYT / SYU vehicle service").assertDoesNotExist()
        listOf("45 km/h", "850 RPM", "78 %").forEach { value ->
            compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
        }
        assertUnsupportedFieldsHidden()
        assertNoExternalAdapterControls()
    }

    @Test
    fun `narrow large font settings clear stale data and keep limitations reachable`() {
        var vehicle by mutableStateOf(liveVehicle())
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CabinTheme(darkTheme = false) {
                    Box(Modifier.width(360.dp).height(480.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ObdSettingsSection(vehicle)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("45 km/h").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { vehicle = vehicle.copy(health = TeyesTelemetryHealth.STALE, availableCodes = emptySet()) }
        compose.onNodeWithText("Waiting for fresh FYT readings").performScrollTo().assertIsDisplayed()
        listOf("45 km/h", "850 RPM", "78 %").forEach { value -> compose.onNodeWithText(value).assertDoesNotExist() }
        compose.onAllNodesWithText("—").assertCountEquals(4)
        assertUnsupportedFieldsHidden()
        assertNoExternalAdapterControls()
    }

    @Test
    fun `Civic maintenance distance is not displayed as oil life percent`() {
        compose.setContent {
            CabinTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ObdSettingsSection(
                        liveVehicle().copy(
                            profileId = 1048874,
                            availableCodes = setOf(89, 90, 179, 180, 181),
                            oilLifePercent = null,
                            oilServiceDistance = -250,
                            oilServiceDistanceMiles = true,
                        ),
                    )
                }
            }
        }
        listOf("45 km/h", "850 RPM", "Oil service distance", "-402 km").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("78 %").assertDoesNotExist()
        compose.onNodeWithText("Existing firmware (speed/RPM)").assertDoesNotExist()
        compose.onNodeWithText("Civic 0298 reference").assertDoesNotExist()
        assertUnsupportedFieldsHidden()
        assertNoExternalAdapterControls()
    }

    private fun assertUnsupportedFieldsHidden() {
        listOf("Coolant temperature · Unavailable", "ECU voltage · Unavailable", "Fault codes (DTC) · Unavailable").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    private fun assertNoExternalAdapterControls(hasLayoutSelector: Boolean = false) {
        listOf("Bluetooth", "ELM327", "Nearby devices", "paired adapters").forEach { text ->
            compose.onAllNodes(hasText(text, substring = true)).assertCountEquals(0)
        }
        listOf("Connect", "Disconnect", "Clear selection", "Load paired adapters", "Refresh adapters").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(if (hasLayoutSelector) 2 else 0)
    }

    private fun liveVehicle() =
        TeyesClimateState(
            connected = true,
            health = TeyesTelemetryHealth.LIVE,
            availableCodes = setOf(89, 90, 137),
            speedKph = 45,
            engineRpm = 850,
            oilLifePercent = 78,
        )
}
