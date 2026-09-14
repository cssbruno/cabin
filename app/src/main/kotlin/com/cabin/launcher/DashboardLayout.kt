package com.cabin.launcher

import android.content.Context
import com.cabin.platform.TeyesVehicleDataLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

const val DASHBOARD_COLUMNS = 8
const val DASHBOARD_ROWS = 4

private fun DashboardTile.finerGrid(): DashboardTile {
    require(x in 0..3 && y in 0..1 && width in 1..4 && height in 1..2 && x + width <= 4 && y + height <= 2)
    return copy(x = x * 2, y = y * 2, width = width * 2, height = height * 2)
}

enum class DashboardModule { PROJECTION, MEDIA, NAVIGATION, RPM, OIL, SERVICE, DOORS, CLIMATE, FAN, REAR_CLIMATE, SEATS, DEFROST, CLOCK, WIDGET, DRIVER_TEMPERATURE, PASSENGER_TEMPERATURE, AIRFLOW, RECIRCULATION, HOOD, TRUNK, CAN_CONNECTION, DATE, PHONE_CONNECTION, ASSISTANT, ROUTE_OVERVIEW, AUDIO_CONTROL, PINNED_APPS, TRIP_CONSUMPTION, HYBRID_BATTERY, VEHICLE_LIGHTING, TIRE_PRESSURE, FACTORY_AMPLIFIER, CAMERA_MODE, MIRROR_SETTINGS, PARKING_SETTINGS, VEHICLE_ALERTS, TIRE_HISTORY, TRIP_HISTORY, VEHICLE_OVERVIEW, ENERGY_FLOW, CHARGING_SETTINGS, AMBIENT_LIGHTING, SEAT_PRESET }
/** Legacy kinds remain readable; new layouts expose one climate widget. */
internal val climateDashboardModules = setOf(
    DashboardModule.CLIMATE, DashboardModule.FAN, DashboardModule.REAR_CLIMATE,
    DashboardModule.SEATS, DashboardModule.DEFROST, DashboardModule.DRIVER_TEMPERATURE,
    DashboardModule.PASSENGER_TEMPERATURE, DashboardModule.AIRFLOW, DashboardModule.RECIRCULATION,
)
internal val dashboardPickerModules = DashboardModule.entries.filter {
    it != DashboardModule.WIDGET && (it !in climateDashboardModules || it == DashboardModule.CLIMATE)
}

data class DashboardTile(val id: Int, val module: DashboardModule, val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val widgetId: Int = 0)
data class DashboardLayout(val pages: Int = 2, val tiles: List<DashboardTile> = listOf(
    DashboardTile(1, DashboardModule.PROJECTION, 0, 0, 0, 3, 2),
    DashboardTile(2, DashboardModule.MEDIA, 0, 3, 0, 1, 1),
    DashboardTile(4, DashboardModule.RPM, 1, 0, 0, 2, 1),
    DashboardTile(5, DashboardModule.OIL, 1, 2, 0, 2, 1),
    DashboardTile(6, DashboardModule.SERVICE, 1, 0, 1, 2, 1),
    DashboardTile(7, DashboardModule.NAVIGATION, 1, 2, 1, 2, 1),
).map { it.finerGrid() })

/** Eight columns by four rows. Reject overlap, overflow and duplicate live projection surfaces. */
internal fun validDashboard(layout: DashboardLayout): Boolean {
    if (layout.pages !in 1..6 || layout.tiles.size > 32 || layout.tiles.map { it.id }.distinct().size != layout.tiles.size ||
        layout.tiles.count { it.module == DashboardModule.PROJECTION } > 1) return false
    val widgetIds = layout.tiles.filter { it.module == DashboardModule.WIDGET }.map { it.widgetId }
    if (widgetIds.distinct().size != widgetIds.size) return false
    for (tile in layout.tiles) {
        if (tile.id <= 0 || tile.page !in 0 until layout.pages || tile.width !in 1..DASHBOARD_COLUMNS || tile.height !in 1..DASHBOARD_ROWS ||
            tile.x < 0 || tile.y < 0 || tile.x + tile.width > DASHBOARD_COLUMNS || tile.y + tile.height > DASHBOARD_ROWS ||
            (tile.module == DashboardModule.WIDGET && tile.widgetId <= 0)) return false
        if (layout.tiles.any { other -> other.id != tile.id && other.page == tile.page &&
            tile.x < other.x + other.width && other.x < tile.x + tile.width &&
            tile.y < other.y + other.height && other.y < tile.y + tile.height }) return false
    }
    return true
}

/** Bounded packing on one page; a failed drop leaves the saved layout untouched. */
internal fun dashboardDropLayout(layout: DashboardLayout, id: Int, x: Int, y: Int): DashboardLayout? {
    val source = layout.tiles.firstOrNull { it.id == id } ?: return null
    val target = source.copy(x = x, y = y)
    if (x < 0 || y < 0 || x + target.width > DASHBOARD_COLUMNS || y + target.height > DASHBOARD_ROWS) return null
    fun overlaps(a: DashboardTile, b: DashboardTile) = a.x < b.x + b.width && b.x < a.x + a.width &&
        a.y < b.y + b.height && b.y < a.y + a.height
    val neighbors = layout.tiles.filter { it.id != id && it.page == source.page }
    val collisions = neighbors.filter { overlaps(target, it) }
    val simple = layout.copy(tiles = layout.tiles.map {
        when {
            it.id == id -> target
            collisions.size == 1 && it.id == collisions[0].id -> it.copy(x = source.x, y = source.y)
            else -> it
        }
    })
    if (validDashboard(simple)) return simple

    fun mask(tile: DashboardTile): Long {
        var bits = 0L
        for (row in tile.y until tile.y + tile.height) for (col in tile.x until tile.x + tile.width) {
            bits = bits or (1L shl (row * DASHBOARD_COLUMNS + col))
        }
        return bits
    }
    // Larger rectangles first avoids trapping a large widget behind several small ones.
    val ordered = neighbors.sortedByDescending { it.width * it.height }
    val candidates = ordered.map { tile ->
        (0..DASHBOARD_ROWS - tile.height).flatMap { row ->
            (0..DASHBOARD_COLUMNS - tile.width).map { col -> tile.copy(x = col, y = row) }
        }.sortedBy { kotlin.math.abs(it.x - tile.x) + kotlin.math.abs(it.y - tile.y) }
            .map { it to mask(it) }
    }
    val placed = mutableMapOf(id to target)
    var attempts = 0
    fun pack(index: Int, occupied: Long): Boolean {
        if (index == ordered.size) return true
        if (++attempts > 20_000) return false
        for ((tile, bits) in candidates[index]) {
            if (bits and occupied != 0L) continue
            placed[tile.id] = tile
            if (pack(index + 1, occupied or bits)) return true
            placed.remove(tile.id)
        }
        return false
    }
    if (!pack(0, mask(target))) return null
    return layout.copy(tiles = layout.tiles.map { placed[it.id] ?: it }).takeIf(::validDashboard)
}

enum class DashboardPreset { COMMUTE, NAVIGATION, PARKING, GLANCE }

data class DashboardHistory(val canUndo: Boolean = false, val canRedo: Boolean = false)

class DashboardPreferences(context: Context, driver: Int, vehicle: Int, dialect: TeyesVehicleDataLayout) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_dashboard_v1", Context.MODE_PRIVATE)
    private val key = "$driver.$vehicle.${dialect.name}"
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val undoLayouts = ArrayDeque<DashboardLayout>()
    private val redoLayouts = ArrayDeque<DashboardLayout>()
    private val mutableHistory = MutableStateFlow(DashboardHistory())
    val history = mutableHistory.asStateFlow()
    private fun updateHistory() {
        mutableHistory.value = DashboardHistory(undoLayouts.isNotEmpty(), redoLayouts.isNotEmpty())
    }
    fun undo(): Boolean {
        val previous = undoLayouts.removeLastOrNull() ?: return false
        redoLayouts.addLast(state.value)
        save(previous, record = false)
        updateHistory()
        return true
    }
    fun redo(): Boolean {
        val next = redoLayouts.removeLastOrNull() ?: return false
        undoLayouts.addLast(state.value)
        save(next, record = false)
        updateHistory()
        return true
    }
    /** Append a preset so existing pages and hosted widgets remain recoverable. */
    fun addPreset(preset: DashboardPreset, available: Set<DashboardModule>): Boolean {
        val current = state.value
        if (current.pages >= 6) return false
        val modules = when (preset) {
            DashboardPreset.COMMUTE -> listOf(DashboardModule.MEDIA, DashboardModule.CLIMATE, DashboardModule.NAVIGATION, DashboardModule.CLOCK)
            DashboardPreset.NAVIGATION -> listOf(DashboardModule.NAVIGATION, DashboardModule.MEDIA)
            DashboardPreset.PARKING -> listOf(DashboardModule.CAMERA_MODE, DashboardModule.PARKING_SETTINGS, DashboardModule.TIRE_PRESSURE, DashboardModule.DOORS)
            DashboardPreset.GLANCE -> listOf(DashboardModule.NAVIGATION)
        }.filter { it in available }
        if (modules.isEmpty()) return false
        val firstId = (current.tiles.maxOfOrNull { it.id } ?: 0) + 1
        val tiles = modules.mapIndexed { index, module ->
            val width = if (modules.size == 1) 8 else 4
            val height = if (modules.size <= 2) 4 else 2
            DashboardTile(firstId + index, module, current.pages, index % 2 * 4, index / 2 * 2, width, height)
        }
        return save(current.copy(pages = current.pages + 1, tiles = current.tiles + tiles))
    }
    private fun save(layout: DashboardLayout, record: Boolean = true): Boolean {
        if (!validDashboard(layout)) return false
        if (layout == state.value) return true
        if (record) {
            undoLayouts.addLast(state.value)
            if (undoLayouts.size > 40) undoLayouts.removeFirst()
            redoLayouts.clear()
        }
        val tiles = JSONArray().apply { layout.tiles.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("kind", t.module.name); put("page", t.page); put("x", t.x); put("y", t.y)
            put("w", t.width); put("h", t.height); put("widget", t.widgetId)
        }) } }
        prefs.edit().putString(key, JSONObject().put("gridVersion", 2).put("pages", layout.pages).put("tiles", tiles).toString()).apply()
        mutable.value = layout
        updateHistory()
        return true
    }
    fun addPage(): Boolean = save(state.value.copy(pages = state.value.pages + 1))
    fun remove(id: Int) = save(state.value.copy(tiles = state.value.tiles.filterNot { it.id == id }))
    fun move(id: Int, dx: Int, dy: Int): Boolean = save(state.value.copy(tiles = state.value.tiles.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }))
    /** Place the dragged tile first, then make room without resizing or losing other widgets. */
    fun drop(id: Int, x: Int, y: Int): Boolean =
        dashboardDropLayout(state.value, id, x, y)?.let { save(it) } ?: false

    fun resize(id: Int, width: Int, height: Int): Boolean {
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return false
        return place(tile.copy(width = width, height = height), tile.page)
    }
    /** Dragging a corner never relocates the tile or its neighbors to make space. */
    fun resizeInPlace(id: Int, width: Int, height: Int, allowProjection: Boolean = false): Boolean {
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return false
        if (tile.module == DashboardModule.PROJECTION && !allowProjection) return false
        return save(state.value.copy(tiles = state.value.tiles.map {
            if (it.id == id) it.copy(width = width, height = height) else it
        }))
    }
    fun movePage(id: Int, page: Int): Boolean {
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return false
        return place(tile, page)
    }
    fun add(module: DashboardModule, page: Int, widgetId: Int = 0): Boolean {
        val tile = DashboardTile((state.value.tiles.maxOfOrNull { it.id } ?: 0) + 1, module, page, 0, 0,
            if (module in setOf(DashboardModule.PROJECTION, DashboardModule.ROUTE_OVERVIEW, DashboardModule.AUDIO_CONTROL, DashboardModule.PINNED_APPS, DashboardModule.CLIMATE, DashboardModule.REAR_CLIMATE, DashboardModule.SEATS, DashboardModule.DEFROST)) 4 else if (module == DashboardModule.WIDGET) 1 else 2,
            if (module == DashboardModule.PROJECTION) 4 else if (module == DashboardModule.WIDGET) 1 else 2, widgetId)
        return place(tile, page)
    }
    private fun place(tile: DashboardTile, page: Int): Boolean {
        if (page !in 0..5) return false
        val base = state.value.copy(pages = maxOf(state.value.pages, page + 1), tiles = state.value.tiles.filterNot { it.id == tile.id })
        val positions = listOf(tile.x to tile.y) + (0 until DASHBOARD_ROWS).flatMap { y -> (0 until DASHBOARD_COLUMNS).map { x -> x to y } }
        for ((x, y) in positions) {
            val next = base.copy(tiles = base.tiles + tile.copy(page = page, x = x, y = y))
            if (validDashboard(next)) return save(next)
        }
        return false
    }
    private fun read(): DashboardLayout = try {
        val raw = prefs.all[key] as? String
        if (raw == null || raw.length > 16384) DashboardLayout() else {
            val json = JSONObject(raw); require(json.optInt("gridVersion", 1) in 1..2); val array = json.getJSONArray("tiles")
            require(array.length() <= 32)
            DashboardLayout(json.getInt("pages"), (0 until array.length()).mapNotNull { i ->
                val t = array.getJSONObject(i)
                if (t.getString("kind") == "SPEED") return@mapNotNull null
                DashboardTile(t.getInt("id"), DashboardModule.valueOf(t.getString("kind")), t.getInt("page"), t.getInt("x"), t.getInt("y"), t.getInt("w"), t.getInt("h"), t.optInt("widget", 0)).let { if (json.optInt("gridVersion", 1) == 1) it.finerGrid() else it }
            }).takeIf(::validDashboard) ?: DashboardLayout()
        }
    } catch (_: Exception) { DashboardLayout() }
}
