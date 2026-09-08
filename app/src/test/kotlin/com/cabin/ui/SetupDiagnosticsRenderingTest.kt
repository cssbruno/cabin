package com.cabin.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cabin.BuildConfig
import com.cabin.CabinManager
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.ProjectionSetupStep
import com.cabin.ui.settings.AudioSourceConfig
import com.cabin.ui.settings.MicSourceConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exportable software fixtures; no microphone, speaker or location provider is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupDiagnosticsRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "pt-rBR-w800dp-h480dp-land-mdpi")
    fun `Portuguese setup fits a short landscape display with large text`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(480.dp).height(300.dp)) {
                        ProjectionSetupScreen(step = ProjectionSetupStep.AUDIO, snapshot = ProjectionReadinessSnapshot(),
                            parked = false, audio = AudioSourceConfig.ADAPTER, microphone = MicSourceConfig.APP,
                            onParked = {}, onAudio = {}, onMicrophone = {}, onSaveAudio = {}, onConnect = {},
                            onRefresh = {}, onOpenPermissions = {}, onStep = {}, onClose = {}, onComplete = {})
                    }
                }
            }
        }
        compose.onNodeWithText("Fechar guia").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("setup-portuguese-large-font")
        compose.onNodeWithText("Salvar para a próxima conexão").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Próxima etapa").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fechar guia").assertIsDisplayed()
        saveScreenshot("setup-portuguese-actions")
    }

    @Test
    fun `connected help keeps exit reachable and audio tests unavailable`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(560.dp).height(360.dp)) {
                    ProjectionConnectionHelp(
                        snapshot = ProjectionReadinessSnapshot(state = CabinManager.State.STREAMING, sessionRequested = true),
                        onConnect = {}, onRefresh = {}, onClose = {}, onOpenPermissions = {}, onStopSession = {}, sessionIdle = false,
                    )
                }
            }
        }
        compose.onNodeWithText("Test speakers").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Close").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        saveScreenshot("diagnostics-connected-guard")
    }

    private fun saveScreenshot(name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val buildType = BuildConfig.BUILD_TYPE
            val destination = File("build/reports/ui/$buildType/$name.png")
            checkNotNull(destination.parentFile).mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
