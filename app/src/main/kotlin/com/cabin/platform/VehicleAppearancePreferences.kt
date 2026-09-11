package com.cabin.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class VehicleBodyStyle { SEDAN, HATCHBACK, SUV, PICKUP }
enum class VehiclePaint(val argb: Long) {
    SILVER(0xFFABB4BE), WHITE(0xFFF2F4F6), BLACK(0xFF525A65),
    BLUE(0xFF498EDB), RED(0xFFCF525D), GREEN(0xFF529C81),
}
data class VehicleAppearance(val body: VehicleBodyStyle = VehicleBodyStyle.SEDAN, val paint: VehiclePaint = VehiclePaint.SILVER)

/** Appearance has no effect on the selected CAN protocol or vehicle commands. */
class VehicleAppearancePreferences(context: Context, private val profile: Int) {
    internal val preferences = context.applicationContext.getSharedPreferences("cabin_vehicle_appearance", Context.MODE_PRIVATE)
    fun read() = VehicleAppearance(
        VehicleBodyStyle.entries.firstOrNull { it.name == preferences.all["$profile.body"] } ?: VehicleBodyStyle.SEDAN,
        VehiclePaint.entries.firstOrNull { it.name == preferences.all["$profile.paint"] } ?: VehiclePaint.SILVER,
    )
    fun update(appearance: VehicleAppearance) {
        preferences.edit().putString("$profile.body", appearance.body.name).putString("$profile.paint", appearance.paint.name).apply()
    }
}

@Composable
internal fun rememberVehicleAppearance(profile: Int): Pair<VehicleAppearance, (VehicleAppearance) -> Unit> {
    val context = LocalContext.current
    val prefs = remember(context, profile) { VehicleAppearancePreferences(context, profile) }
    var value by remember(prefs) { mutableStateOf(prefs.read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = prefs.read() }
        prefs.preferences.registerOnSharedPreferenceChangeListener(listener)
        value = prefs.read()
        onDispose { prefs.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value to { prefs.update(it); value = it }
}
