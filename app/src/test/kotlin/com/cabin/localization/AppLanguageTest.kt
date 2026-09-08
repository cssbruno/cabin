package com.cabin.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import com.cabin.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppLanguageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { context.getSharedPreferences("app_language_v1", 0).edit().clear().commit() }
    @Test fun `English and Portuguese can be selected without changing base configuration`() {
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags("en-US")) }
        val base = context.createConfigurationContext(config)
        AppLanguage.select(base, "pt")
        assertEquals("pt", AppLanguage.selected(base))
        assertEquals("Idioma", AppLanguage.wrap(base).getString(R.string.app_language_title))
        assertEquals("en", base.resources.configuration.locales[0].language)
        AppLanguage.select(base, "en")
        assertEquals("Language", AppLanguage.wrap(base).getString(R.string.app_language_title))
    }
    @Test fun `system option and invalid persisted tags fall back to head unit language`() {
        AppLanguage.select(context, "")
        assertSame(context, AppLanguage.wrap(context))
        context.getSharedPreferences("app_language_v1", 0).edit().putString("language", "unsupported").commit()
        assertEquals("", AppLanguage.selected(context))
        assertSame(context, AppLanguage.wrap(context))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `unsupported picker input is rejected`() { AppLanguage.select(context, "xx") }
    @Test fun `all six choices resolve translated resources and survive fresh contexts`() {
        val expected = mapOf("en" to "Language", "pt" to "Idioma", "es" to "Idioma", "fr" to "Langue", "de" to "Sprache", "it" to "Lingua")
        expected.forEach { (tag, title) ->
            AppLanguage.select(context, tag)
            val fresh = context.createConfigurationContext(Configuration(context.resources.configuration))
            assertEquals(tag, AppLanguage.selected(fresh))
            val localized = AppLanguage.wrap(fresh)
            assertEquals(title, localized.getString(R.string.app_language_title))
            assertEquals(tag, localized.resources.configuration.locales[0].language)
            // A long-lived service/receiver context must also pick up each new selection.
            assertEquals(title, context.localizedString(R.string.app_language_title))
        }
    }
    @Test
    @Config(sdk = [35])
    fun `Android 13 and newer use the system per-app language API`() {
        AppLanguage.names.keys.forEach { tag ->
            AppLanguage.select(context, tag)
            assertEquals(tag, AppLanguage.selected(context))
        }
        AppLanguage.select(context, "")
        assertEquals("", AppLanguage.selected(context))
    }
}
