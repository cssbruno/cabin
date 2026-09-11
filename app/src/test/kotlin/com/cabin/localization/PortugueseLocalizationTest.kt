package com.cabin.localization

import android.content.Context
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.platform.ProjectionOptionalCapability
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesKeyRouter
import com.cabin.platform.projectionReadinessPresentation
import com.cabin.ui.ProjectionBlackout
import com.cabin.ui.ProjectionToolsPanel
import com.cabin.ui.projectionClimateSummaryText
import com.cabin.ui.theme.CabinTheme
import com.cabin.ui.vehicleDoorWarning
import com.cabin.widget.CabinWidgetState
import com.cabin.widget.toRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "pt-rBR-w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortugueseLocalizationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun localizedContext(languageTag: String): Context =
        compose.activity.createConfigurationContext(
            Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(languageTag)) },
        )

    @Test
    fun `Portuguese projection tools expose settings`() {
        var settings = 0
        compose.setContent {
            CabinTheme {
                ProjectionToolsPanel(
                    onSettings = { settings++ },
                    onClose = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("Ferramentas de projeção").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(com.cabin.R.string.action_settings)).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, settings)
        }
    }

    @Test
    fun `Portuguese blackout remains accessible and wakes on tap`() {
        var wakes = 0
        compose.setContent { ProjectionBlackout(onWake = { wakes++ }) }
        compose.onNodeWithText("Toque para reativar · O áudio continua").assertIsDisplayed()
        // The action uses the clickable accessibility label, with visible localized text.
        compose.onNodeWithText("Toque para reativar · O áudio continua").performClick()
        compose.runOnIdle { assertEquals(1, wakes) }
    }

    @Test
    fun `readiness and vehicle notices resolve real Portuguese resources with arguments`() {
        val resources = localizedContext("pt-BR").resources
        val readiness = projectionReadinessPresentation(
            resources,
            ProjectionReadinessSnapshot(
                sessionRequested = true,
                state = CabinManager.State.STREAMING,
                microphone = ProjectionOptionalCapability.PERMISSION_NEEDED,
            ),
        )
        assertEquals("Conectado: Projeção", readiness.title)
        assertTrue(readiness.microphoneDetail.contains("Permita Microfone"))
        assertFalse(readiness.canConnectPhone)
        assertEquals(
            "Ar-condicionado ligado · Esquerda 22.0°C · Ventilador 3/7",
            projectionClimateSummaryText(
                resources,
                TeyesClimateState(connected = true, ac = true, fanLevel = 3, leftTemperature = 44, availableCodes = setOf(24, 29, 25, 33)),
            ),
        )
        assertEquals("ABERTO: PORTA DO MOTORISTA", vehicleDoorWarning(resources, TeyesClimateState(frontLeftDoorOpen = true)))
        assertEquals("Motorista 2 · Ativo", resources.getString(R.string.teyes_driver_slot_active, 2))
        assertEquals("1 botão configurado · configure com o carro estacionado", resources.getQuantityString(R.plurals.teyes_learned_buttons, 1, 1))
        assertEquals("3 botões configurados · configure com o carro estacionado", resources.getQuantityString(R.plurals.teyes_learned_buttons, 3, 3))
        assertEquals("Night brightness: 35% — Cabin window only", localizedContext("en-US").getString(R.string.teyes_night_brightness, 35))
        assertEquals("Brilho noturno: 35% — apenas a janela do Cabin", resources.getString(R.string.teyes_night_brightness, 35))
    }

    @Test
    fun `translated steering wheel status does not control learning lifecycle`() {
        val context = localizedContext("pt-BR")
        context.getSharedPreferences("teyes_features_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = TeyesFeaturePreferences(context)
        val router = TeyesKeyRouter(preferences) { error("Learning must not execute a projection action") }
        router.learn(TeyesKeyAction.NEXT)
        assertTrue(router.isLearning)
        assertTrue(router.status.value.contains("Pressione um botão"))
        assertTrue(router.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1)))
        assertFalse(router.isLearning)
        assertEquals(TeyesKeyAction.NEXT, preferences.keyAction(KeyEvent.KEYCODE_F1))
        assertTrue(router.status.value.startsWith("Salvo "))
        router.learn(TeyesKeyAction.VOICE)
        router.cancelPressedKeys()
        assertFalse(router.isLearning)
        assertTrue(router.status.value.startsWith("Configuração encerrada"))
    }

    @Test
    fun `widget discards status from old locale and renders Portuguese actions`() {
        val english = localizedContext("en-US")
        english.getSharedPreferences("carlink_widget_state", Context.MODE_PRIVATE).edit().clear().commit()
        CabinWidgetState.updateStatus(english, "Ready to connect")
        val portuguese = localizedContext("pt-BR")
        val model = CabinWidgetState.read(portuguese).toRenderModel(portuguese.resources)
        assertEquals("Pronto para conectar", model.status)
        assertEquals("CONECTAR", model.action)
        assertEquals("Conectar em segundo plano", model.actionDescription)
    }
}
