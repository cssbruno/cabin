package com.cabin.localization

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.cabin.ui.settings.LanguageSettingsSection
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h600dp-land-mdpi")
class LanguageSettingsSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `all six translated pickers remain readable and touchable with large text`() {
        val generation = mutableStateOf(0)
        val context = compose.activity
        AppLanguage.select(context, "en")
        compose.setContent {
            generation.value
            val localized = AppLanguage.wrap(context)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalDensity provides Density(density.density, 1.5f),
            ) {
                CabinTheme {
                    Box(Modifier.fillMaxSize()) { LanguageSettingsSection() }
                }
            }
        }
        val titles = mapOf("en" to "Language", "pt" to "Idioma", "es" to "Idioma", "fr" to "Langue", "de" to "Sprache", "it" to "Lingua")
        titles.forEach { (tag, title) ->
            compose.runOnIdle {
                AppLanguage.select(context, tag)
                generation.value++
            }
            compose.onNodeWithText(title).assertIsDisplayed()
            AppLanguage.names.values.forEach { name ->
                val node = compose.onNodeWithText(name).assertIsDisplayed().assertHasClickAction()
                assertTrue("Small touch target: $tag/$name", node.fetchSemanticsNode().boundsInRoot.height >= 56f)
            }
        }
        // The actual chip updates the stored choice; the host Activity recreates in production.
        compose.onNodeWithText("Español").performClick()
        assertEquals("es", AppLanguage.selected(context))
    }
}
