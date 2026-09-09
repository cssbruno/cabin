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

enum class DashboardModule { PROJECTION, MEDIA, NAVIGATION, SPEED, RPM, OIL, SERVICE, DOORS, CLIMATE, FAN, REAR_CLIMATE, SEATS, DEFROST, CLOCK, WIDGET }
data class DashboardTile(val id: Int, val module: DashboardModule, val page: Int, val x: Int, val y: Int, val width: Int, val height: Int, val widgetId: Int = 0)
data class DashboardLayout(val pages: Int = 2, val tiles: List<DashboardTile> = listOf(
    DashboardTile(1, DashboardModule.PROJECTION, 0, 0, 0, 3, 2),
    DashboardTile(2, DashboardModule.MEDIA, 0, 3, 0, 1, 1),
    DashboardTile(3, DashboardModule.SPEED, 0, 3, 1, 1, 1),
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

class DashboardPreferences(context: Context, driver: Int, vehicle: Int, dialect: TeyesVehicleDataLayout) {
    private val prefs = context.applicationContext.getSharedPreferences("carlink_dashboard_v1", Context.MODE_PRIVATE)
    private val key = "$driver.$vehicle.${dialect.name}"
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun save(layout: DashboardLayout): Boolean {
        if (!validDashboard(layout)) return false
        val tiles = JSONArray().apply { layout.tiles.forEach { t -> put(JSONObject().apply {
            put("id", t.id); put("kind", t.module.name); put("page", t.page); put("x", t.x); put("y", t.y)
            put("w", t.width); put("h", t.height); put("widget", t.widgetId)
        }) } }
        prefs.edit().putString(key, JSONObject().put("gridVersion", 2).put("pages", layout.pages).put("tiles", tiles).toString()).apply()
        mutable.value = layout
        return true
    }
    fun addPage(): Boolean = save(state.value.copy(pages = state.value.pages + 1))
    fun remove(id: Int) = save(state.value.copy(tiles = state.value.tiles.filterNot { it.id == id }))
    fun move(id: Int, dx: Int, dy: Int): Boolean = save(state.value.copy(tiles = state.value.tiles.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }))
    /** Drop into free space, or swap with one compatible widget. CarPlay is never dragged or displaced. */
    fun drop(id: Int, x: Int, y: Int): Boolean {
        val layout = state.value
        val source = layout.tiles.firstOrNull { it.id == id } ?: return false
        if (source.module == DashboardModule.PROJECTION) return false
        val target = source.copy(x = x, y = y)
        val overlaps = layout.tiles.filter { it.id != id && it.page == source.page &&
            target.x < it.x + it.width && it.x < target.x + target.width &&
            target.y < it.y + it.height && it.y < target.y + target.height }
        if (overlaps.size > 1 || overlaps.any { it.module == DashboardModule.PROJECTION }) return false
        val other = overlaps.singleOrNull()
        val next = layout.copy(tiles = layout.tiles.map {
            when (it.id) {
                id -> target
                other?.id -> it.copy(x = source.x, y = source.y)
                else -> it
            }
        })
        return save(next)
    }
    fun resize(id: Int, width: Int, height: Int): Boolean {
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return false
        return place(tile.copy(width = width, height = height), tile.page)
    }
    /** Dragging a corner never relocates the tile or its neighbors to make space. */
    fun resizeInPlace(id: Int, width: Int, height: Int): Boolean {
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return false
        if (tile.module == DashboardModule.PROJECTION) return false
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
            if (module in setOf(DashboardModule.PROJECTION, DashboardModule.WIDGET, DashboardModule.CLIMATE, DashboardModule.REAR_CLIMATE, DashboardModule.SEATS, DashboardModule.DEFROST)) 4 else 2,
            if (module in setOf(DashboardModule.PROJECTION, DashboardModule.WIDGET)) 4 else 2, widgetId)
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
            DashboardLayout(json.getInt("pages"), (0 until array.length()).map { i ->
                val t = array.getJSONObject(i)
                DashboardTile(t.getInt("id"), DashboardModule.valueOf(t.getString("kind")), t.getInt("page"), t.getInt("x"), t.getInt("y"), t.getInt("w"), t.getInt("h"), t.optInt("widget", 0)).let { if (json.optInt("gridVersion", 1) == 1) it.finerGrid() else it }
            }).takeIf(::validDashboard) ?: DashboardLayout()
        }
    } catch (_: Exception) { DashboardLayout() }
}
