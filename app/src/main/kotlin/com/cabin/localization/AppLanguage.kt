package com.cabin.localization

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/** Supported translations; empty tag follows the head unit's language. */
object AppLanguage {
    /** Native names stay recognizable even when the current language is unfamiliar. */
    val names = linkedMapOf(
        "en" to "English", "pt" to "Português", "es" to "Español",
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano",
    )
    val supported = listOf("") + names.keys
    fun selected(context: Context): String {
        val raw = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
        } else context.getSharedPreferences("app_language_v1", Context.MODE_PRIVATE).getString("language", "").orEmpty()
        return supported.firstOrNull { it == raw || (it.isNotEmpty() && raw.startsWith("$it-")) } ?: ""
    }
    fun select(context: Context, language: String) {
        require(language in supported)
        context.getSharedPreferences("app_language_v1", Context.MODE_PRIVATE).edit().putString("language", language).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language)
        }
    }
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = selected(context)
        if (tag.isEmpty()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(config)
    }
}

/** Services and receivers outlive Activities and must resolve the current selection per message. */
fun Context.localizedString(@androidx.annotation.StringRes id: Int, vararg arguments: Any): String =
    AppLanguage.wrap(this).getString(id, *arguments)
