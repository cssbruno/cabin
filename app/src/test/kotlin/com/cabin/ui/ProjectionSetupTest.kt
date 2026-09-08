package com.cabin.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.ProjectionSetupStep
import com.cabin.ui.settings.AudioSourceConfig
import com.cabin.ui.settings.MicSourceConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
class ProjectionSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `browsing every step and finishing never connects a phone or saves audio`() {
        var connects = 0
        var saves = 0
        var finishes = 0
        compose.setContent {
            var step by remember { mutableStateOf(ProjectionSetupStep.USB) }
            Content(step, onStep = { step = it }, onConnect = { connects++ }, onSaveAudio = { saves++ }, onComplete = { finishes++ })
        }
        repeat(4) { compose.onNodeWithText("Next step").performScrollTo().performClick() }
        compose.onNodeWithText("Finish guide").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, connects)
            assertEquals(0, saves)
            assertEquals(1, finishes)
        }
    }

    @Test
    fun `audio changes need parked confirmation and a separate explicit save`() {
        var saves = 0
        var route = AudioSourceConfig.ADAPTER
        compose.setContent {
            var parked by remember { mutableStateOf(false) }
            var audio by remember { mutableStateOf(AudioSourceConfig.ADAPTER) }
            Content(ProjectionSetupStep.AUDIO, parked = parked, onParked = { parked = it }, audio = audio,
                onAudio = { audio = it; route = it }, onSaveAudio = { saves++ })
        }
        compose.onNodeWithText("Vehicle Bluetooth").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save for next connection").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I am parked and ready to configure or test").performScrollTo().performClick()
        compose.onNodeWithText("Vehicle Bluetooth").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(AudioSourceConfig.BLUETOOTH, route)
            assertEquals(0, saves)
        }
        compose.onNodeWithText("Save for next connection").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    fun `close stays reachable with large font in a short viewport`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(480.dp).height(240.dp)) { Content(ProjectionSetupStep.AUDIO) }
            }
        }
        compose.onNodeWithText("Close guide").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Next step").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close guide").assertIsDisplayed()
    }

    @Test
    fun `permission action targets the current app Android settings page`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        openProjectionAppPermissions(context)
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    @Config(qualifiers = "pt-rBR-w800dp-h480dp-land-mdpi")
    fun `Portuguese setup exposes translated controls and the same parked requirement`() {
        compose.setContent { Content(ProjectionSetupStep.AUDIO) }
        compose.onNodeWithText("Guia de configuração").assertIsDisplayed()
        compose.onNodeWithText("Fechar guia").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Bluetooth do veículo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Salvar para a próxima conexão").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Próxima etapa").performScrollTo().assertIsDisplayed()
    }

    @Composable
    private fun Content(
        step: ProjectionSetupStep,
        parked: Boolean = false,
        audio: AudioSourceConfig = AudioSourceConfig.ADAPTER,
        onParked: (Boolean) -> Unit = {},
        onAudio: (AudioSourceConfig) -> Unit = {},
        onSaveAudio: () -> Unit = {},
        onConnect: () -> Unit = {},
        onComplete: () -> Unit = {},
        onStep: (ProjectionSetupStep) -> Unit = {},
    ) {
        MaterialTheme {
            ProjectionSetupScreen(step = step, snapshot = ProjectionReadinessSnapshot(), parked = parked,
                audio = audio, microphone = MicSourceConfig.APP, onParked = onParked, onAudio = onAudio,
                onMicrophone = {}, onSaveAudio = onSaveAudio, onConnect = onConnect, onRefresh = {},
                onOpenPermissions = {}, onStep = onStep, onClose = {}, onComplete = onComplete)
        }
    }
}
