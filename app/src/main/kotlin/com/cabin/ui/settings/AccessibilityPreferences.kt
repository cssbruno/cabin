package com.cabin.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

internal data class AccessibilityOptions(val reducedMotion: Boolean = false, val highContrast: Boolean = false)
internal val LocalReducedMotion = staticCompositionLocalOf { false }
internal const val ACCESSIBILITY_FILE = "cabin_accessibility_v1"
@Composable internal fun rememberAccessibilityOptions(): AccessibilityOptions {
    val context = LocalContext.current
    val prefs = remember(context) { context.applicationContext.getSharedPreferences(ACCESSIBILITY_FILE, Context.MODE_PRIVATE) }
    fun read() = AccessibilityOptions(prefs.all["reduced_motion"] == true, prefs.all["high_contrast"] == true)
    var value by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
@Composable internal fun AccessibilitySettingsSection() {
    val context = LocalContext.current
    val options = rememberAccessibilityOptions()
    SettingsSection(androidx.compose.ui.res.stringResource(com.cabin.R.string.goal_accessibility)) {
        SettingsToggle(androidx.compose.ui.res.stringResource(com.cabin.R.string.goal_reduce_motion), options.reducedMotion,
            androidx.compose.ui.res.stringResource(com.cabin.R.string.goal_reduce_motion_detail)) {
            context.getSharedPreferences(ACCESSIBILITY_FILE, 0).edit().putBoolean("reduced_motion", it).apply()
        }
        SettingsToggle(androidx.compose.ui.res.stringResource(com.cabin.R.string.goal_high_contrast), options.highContrast,
            androidx.compose.ui.res.stringResource(com.cabin.R.string.goal_high_contrast_detail)) {
            context.getSharedPreferences(ACCESSIBILITY_FILE, 0).edit().putBoolean("high_contrast", it).apply()
        }
    }
}
