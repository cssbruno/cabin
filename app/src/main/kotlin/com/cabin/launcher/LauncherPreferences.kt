package com.cabin.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class LauncherLayout(val favorites: List<String> = emptyList(), val widgets: List<Int> = emptyList(), val widgetHeights: Map<Int, Int> = emptyMap(), val gauges: List<VehicleGauge> = listOf(VehicleGauge.RPM, VehicleGauge.OIL))

/** Favorites are per existing driver slot. Widget IDs belong to this installation only. */
class LauncherPreferences(context: Context, private val driverSlot: Int = 0,
    vehicleProfileId: Int = 0, vehicleLayout: com.cabin.platform.TeyesVehicleDataLayout = com.cabin.platform.TeyesVehicleDataLayout.LEGACY) {
    private val gaugeKey = if (vehicleProfileId > 0) "gauges.$driverSlot.vehicle.$vehicleProfileId.${vehicleLayout.name}" else "gauges.$driverSlot"
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state = mutableState.asStateFlow()

    fun pin(component: String) {
        require(validLauncherComponent(component))
        val current = read().favorites
        if (component in current || current.size >= MAX_FAVORITES) return
        saveFavorites(current + component)
    }
    fun unpin(component: String) = saveFavorites(read().favorites.filterNot { it == component })
    fun move(component: String, direction: Int) {
        require(direction == -1 || direction == 1)
        val list = read().favorites.toMutableList()
        val index = list.indexOf(component)
        val target = index + direction
        if (index < 0 || target !in list.indices) return
        list[index] = list[target]
        list[target] = component
        saveFavorites(list)
    }
    fun toggleGauge(gauge: VehicleGauge) {
        val current = read().gauges
        val next = if (gauge in current) current - gauge else current + gauge
        preferences.edit().putString(gaugeKey, JSONArray(next.map { it.name }).toString()).apply()
        refresh()
    }
    fun refresh() { mutableState.value = read() }
    fun addWidget(id: Int) {
        require(id > 0)
        val ids = read().widgets
        if (id !in ids && ids.size < MAX_WIDGETS) saveWidgets(ids + id)
    }
    fun removeWidget(id: Int) = saveWidgets(read().widgets.filterNot { it == id })
    fun resizeWidget(id: Int, heightDp: Int) {
        require(heightDp in 160..600)
        if (id !in read().widgets) return
        preferences.edit().putInt("widget_height.$id", heightDp).apply()
        refresh()
    }
    var pendingWidget: Int
        get() = (preferences.all["pending_widget"] as? Int ?: -1)
        set(value) { preferences.edit().putInt("pending_widget", value).apply() }

    private fun saveFavorites(values: List<String>) {
        preferences.edit().putString("favorites.$driverSlot", JSONArray(values).toString()).apply()
        refresh()
    }
    private fun saveWidgets(values: List<Int>) {
        preferences.edit().putString("widgets", JSONArray(values).toString()).apply()
        refresh()
    }
    private fun read(): LauncherLayout {
        val favoriteJson = preferences.all["favorites.$driverSlot"] as? String
        val widgetJson = preferences.all["widgets"] as? String
        val gaugeJson = (preferences.all[gaugeKey] ?: preferences.all["gauges.$driverSlot"]) as? String
        return LauncherLayout(
            gauges = if (gaugeJson == null) listOf(VehicleGauge.RPM, VehicleGauge.OIL)
                else boundedArray(gaugeJson).mapNotNull { name -> VehicleGauge.entries.firstOrNull { it.name == name } }.distinct(),
            favorites = boundedArray(favoriteJson).mapNotNull { it as? String }.filter(::validLauncherComponent).distinct().take(MAX_FAVORITES),
            widgets = boundedArray(widgetJson).mapNotNull { it as? Int }.filter { it > 0 }.distinct().take(MAX_WIDGETS),
            widgetHeights = boundedArray(widgetJson).mapNotNull { it as? Int }.filter { it > 0 }.take(MAX_WIDGETS).associateWith {
                (preferences.all["widget_height.$it"] as? Int ?: 200).coerceIn(160, 600)
            },
        )
    }
    companion object {
        const val FILE = "carlink_launcher_v1"
        const val MAX_FAVORITES = 8
        const val MAX_WIDGETS = 3
        private fun boundedArray(value: String?): List<Any> = try {
            if (value == null || value.length > 8192) emptyList() else JSONArray(value).let { json ->
                (0 until minOf(json.length(), 32)).map { json.get(it) }
            }
        } catch (_: Exception) { emptyList() }
    }
}

internal fun validLauncherComponent(value: String): Boolean = value.length <= 512 &&
    value.none { it.isISOControl() } && ComponentName.unflattenFromString(value)?.let {
        it.packageName.isNotBlank() && it.className.isNotBlank()
    } == true

object LauncherIntents {
    const val ALIAS = "com.carlink.launcher.CarlinkHome"
    fun isHome(intent: Intent?): Boolean = intent?.action == Intent.ACTION_MAIN &&
        (intent.hasCategory(Intent.CATEGORY_HOME) || intent.component?.className == ALIAS)
    fun opensDashboard(intent: Intent?): Boolean = isHome(intent) || intent == null ||
        (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER))
    fun open(context: Context) = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context.packageName, ALIAS))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
