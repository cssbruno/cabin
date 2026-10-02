package com.cabin.platform

import android.util.JsonReader
import android.util.JsonToken
import com.cabin.launcher.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.time.LocalDate

/** Parse limits apply before JSONObject allocation, including embedded JSON preference values. */
internal fun validateBackupJson(text: String) {
    require(text.length <= PortableConfigurationBackup.MAX_BYTES * 2)
    var nodes = 0
    fun walk(reader: JsonReader, depth: Int) {
        require(depth <= 12 && ++nodes <= 30_000)
        when(reader.peek()) {
            JsonToken.BEGIN_OBJECT -> { reader.beginObject(); val names = hashSetOf<String>(); while(reader.hasNext()) { val key = reader.nextName(); require(key.length <= 512 && names.add(key)); walk(reader, depth + 1) }; reader.endObject() }
            JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while(reader.hasNext()) walk(reader, depth + 1); reader.endArray() }
            JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= PortableConfigurationBackup.MAX_BYTES * 2)
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> error("Invalid backup JSON")
        }
    }
    JsonReader(StringReader(text)).use { reader -> reader.isLenient = false; walk(reader, 0); require(reader.peek() == JsonToken.END_DOCUMENT) }
}

internal fun validatePortableSection(section: String, values: Map<String, Any>) {
    fun text(value: Any): String = (value as? String ?: error("Text preference required")).also { require(it.length <= 512_000) }
    fun bool(value: Any) { require(value is Boolean) }
    fun integer(value: Any, allowed: IntRange) { require(value is Int && value in allowed) }
    fun strings(value: Any, maximum: Int): List<String> {
        val raw = text(value); validateBackupJson(raw); val array = JSONArray(raw); require(array.length() <= maximum)
        return (0 until array.length()).map { array.get(it) as? String ?: error("Text array required") }
    }
    fun obj(value: Any): JSONObject = text(value).let { validateBackupJson(it); JSONObject(it) }
    fun array(value: Any, max: Int): JSONArray = text(value).let { validateBackupJson(it); JSONArray(it).also { a -> require(a.length() <= max) } }
    values.forEach { (key, value) ->
        when(section) {
            "drivers" -> when {
                Regex("driver\\.[0-2]\\.(name|phone|appearance)").matches(key) -> text(value)
                Regex("driver\\.[0-2]\\.(brightness|media|nav)").matches(key) -> require(value is Float && value.isFinite() && value in 0f..1f)
                Regex("driver\\.[0-2]\\.(wake|overlays|compact)").matches(key) -> bool(value)
                Regex("(key|longKey|keyApp|longKeyApp)\\.[0-9]+").matches(key) -> { require(TeyesKeyRouter.isMappable(key.substringAfter('.').toInt())); text(value) }
                key.startsWith("shortcut.") -> { require(key.substringAfter('.') in TeyesShortcut.entries.map { it.name }); require(TeyesConfigurationBackup.validComponent(text(value))) }
                else -> error("Unknown driver preference")
            }
            "projection" -> {
                val base = if(key.startsWith("driver.")) { require(Regex("driver\\.[0-2]\\.[a-z_]+").matches(key)); key.substringAfterLast('.') } else key
                when(base) {
                    "focus_controls", "vehicle_hud", "return_when_ready" -> bool(value)
                    "climate_notice_mode" -> require(text(value) in ClimateNoticeMode.entries.map { it.name })
                    "control_side" -> require(text(value) in ProjectionControlSide.entries.map { it.name })
                    "hide_seconds" -> require(value is Int && value in listOf(0, 5, 10, 20))
                    "blackout_minutes" -> require(value is Int && value in listOf(0, 1, 5, 15, 30))
                    "bezel" -> integer(value, 0..10)
                    "tool_order" -> { val parts = text(value).split(',').filter { it.isNotEmpty() }; require(parts.size <= 3 && parts.distinct().size == parts.size && parts.all { it in setOf("phone", "settings", "blackout") }) }
                    else -> error("Unknown projection preference")
                }
            }
            "units" -> { require(key == "unit"); require(text(value) in MeasurementUnit.entries.map { it.name }) }
            "launcher" -> when {
                Regex("favorites\\.[0-2]").matches(key) -> require(strings(value, 8).all(::validLauncherComponent))
                Regex("gauges\\.[0-2](\\.vehicle\\.[0-9]+\\.[A-Z0-9_]+)?").matches(key) -> require(strings(value, 32).all { it in VehicleGauge.entries.map { e -> e.name } })
                else -> error("Unknown launcher preference")
            }
            "library" -> {
                require(Regex("driver\\.[0-2]").matches(key)); val j = obj(value)
                val known = setOf("sort", "filter", "showPackages", "recordHistory", "hidden", "names", "folders", "appFolders", "selectedFolder", "compact", "recentDays", "usage")
                require(j.keys().asSequence().all { it in known })
                if(j.has("sort")) require(j.get("sort") is String && j.getString("sort") in LauncherAppSort.entries.map { it.name })
                if(j.has("filter")) require(j.get("filter") is String && j.getString("filter") in LauncherAppFilter.entries.map { it.name })
                listOf("showPackages", "recordHistory", "compact").filter(j::has).forEach { require(j.get(it) is Boolean) }
                if(j.has("recentDays")) require(j.get("recentDays") is Int && j.getInt("recentDays") in 1..365)
                j.optJSONArray("hidden")?.let { require(it.length() <= 256); repeat(it.length()) { i -> require(validLauncherComponent(it.get(i) as? String ?: error("Invalid component"))) } }
                if(j.has("hidden")) require(j.get("hidden") is JSONArray)
                listOf("names", "folders", "appFolders").filter(j::has).forEach { field ->
                    val map = j.getJSONObject(field); require(map.length() <= if(field == "folders") 32 else 256)
                    map.keys().asSequence().forEach { name ->
                        val label = map.get(name) as? String ?: error("Invalid library value")
                        require(label.length in 1..60 && label.none(Char::isISOControl))
                        require(if(field == "folders") name.length in 1..60 else validLauncherComponent(name))
                        if(field == "appFolders") require(j.optJSONObject("folders")?.has(label) == true)
                    }
                }
                if(j.has("selectedFolder") && !j.isNull("selectedFolder")) require(j.get("selectedFolder") is String && j.optJSONObject("folders")?.has(j.getString("selectedFolder")) == true)
                if(j.has("usage")) require(j.getJSONObject("usage").length() == 0) { "Launch history is not portable configuration" }
            }
            "dashboard" -> {
                require(Regex("[0-2]\\.[0-9]+\\.[A-Z0-9_]+").matches(key)); val j = obj(value)
                require(j.keys().asSequence().all { it in setOf("gridVersion", "pages", "tiles", "pageNames", "selectedPage") })
                val version = if(j.has("gridVersion")) j.strictInt("gridVersion") else 1; require(version in 1..2)
                val a = j.getJSONArray("tiles"); require(a.length() <= 32)
                val tiles = (0 until a.length()).mapNotNull { i ->
                    val tile = a.getJSONObject(i); val kind = tile.get("kind") as? String ?: error("Invalid module")
                    require(kind != "WIDGET" && (!tile.has("widget") || tile.strictInt("widget") == 0)) { "Widget IDs belong to a different installation" }
                    if(kind == "SPEED") null else {
                        val factor = if(version == 1) 2 else 1
                        DashboardTile(tile.strictInt("id"), DashboardModule.valueOf(kind), tile.strictInt("page"), tile.strictInt("x") * factor, tile.strictInt("y") * factor, tile.strictInt("w") * factor, tile.strictInt("h") * factor)
                    }
                }
                val names = j.optJSONObject("pageNames")?.let { n -> n.keys().asSequence().associate { page -> (page.toIntOrNull() ?: error("Invalid page")) to (n.get(page) as? String ?: error("Invalid name")) } }.orEmpty()
                require(validDashboard(DashboardLayout(j.strictInt("pages"), tiles, names, if(j.has("selectedPage")) j.strictInt("selectedPage") else 0)))
            }
            "automation" -> when(key) {
                "quiet", "solar", "parked" -> bool(value)
                "start", "end" -> integer(value, 0..23)
                else -> { require(Regex("parkingRetention\\.[0-9]+").matches(key)); require(value is Int && value in listOf(0, 1, 7, 30)) }
            }
            "appearance" -> { require(Regex("[0-9]+\\.(body|paint)").matches(key)); require(text(value) in if(key.endsWith(".body")) VehicleBodyStyle.entries.map { it.name } else VehiclePaint.entries.map { it.name }) }
            "accessibility" -> { require(key in setOf("reduced_motion", "high_contrast")); bool(value) }
            "audio" -> if(key == "buffering") require(text(value) in com.cabin.audio.AudioBufferProfile.entries.map { it.name }) else {
                require(Regex("presets\\.[0-2]").matches(key)); val a = array(value, 8)
                repeat(a.length()) { i -> val preset = a.getJSONObject(i); require(preset.get("name") is String && preset.getString("name").length in 1..32)
                    listOf("media", "nav").forEach { field -> require(preset.get(field) is Number && preset.getDouble(field).isFinite() && preset.getDouble(field) in 0.0..1.0) }
                }
            }
            "vehicle" -> when {
                key == "readOnly" -> bool(value)
                key == "climateTimeout" -> require(value is Int && value in listOf(0, 5, 10, 20, 30, 60))
                key == "climateFavorites" || key == "airFavorites" -> { val actions = text(value).split(',').filter { it.isNotBlank() }; require(actions.size <= 6 && actions.distinct().size == actions.size)
                    require(actions.all { if(key == "climateFavorites") it in listOf("ac", "fan") + TeyesClimateSwitch.entries.map { e -> e.name } else Regex("C_AIR_[A-Z0-9_]{1,80}").matches(it) }) }
                Regex("favorites\\.[0-9]+").matches(key) -> require(value is Set<*> && value.size <= 6 && value.all { it is String && it in listOf("ac", "fan") + TeyesClimateSwitch.entries.map { e -> e.name } })
                else -> error("Unknown vehicle preference")
            }
            "trips" -> when {
                Regex("fuelRate(\\.[0-9]+)?").matches(key) -> require(text(value).replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in .1..100.0 } == true)
                Regex("fuelPrice(\\.[0-9]+)?").matches(key) -> require(text(value).replace(',', '.').toDoubleOrNull()?.let { it.isFinite() && it in .001..10000.0 } == true)
                Regex("fuelCurrency(\\.[0-9]+)?").matches(key) -> java.util.Currency.getInstance(text(value))
                Regex("recording\\.[0-9]+").matches(key) -> bool(value)
                Regex("retention\\.[0-9]+").matches(key) -> require(value is Int && value in TripHistory.retentionChoices)
                Regex("maintenance\\.[0-9]+").matches(key) -> LocalDate.parse(text(value))
                Regex("maintenanceInterval\\.[0-9]+").matches(key) -> require(value is Int && value in MaintenanceSchedule.intervals)
                Regex("maintenanceItems\\.[0-9]+").matches(key) -> {
                    val a = array(value, 30); val ids = hashSetOf<String>()
                    repeat(a.length()) { i -> val item = a.getJSONObject(i)
                        val id = item.get("id") as? String ?: error("Invalid reminder ID"); require(id.length in 1..64 && ids.add(id))
                        require(item.get("name") is String && item.getString("name").isNotBlank() && item.getString("name").length <= 60)
                        LocalDate.parse(item.get("due") as? String ?: error("Invalid date")); require(item.strictInt("interval") in 1..3650)
                        listOf("dueKm", "intervalKm").forEach { field -> if(!item.isNull(field)) require(item.get(field) is Number && item.getDouble(field).isFinite() && item.getDouble(field) in 0.0..10_000_000.0) }
                    }
                }
                else -> error("Unknown trip preference")
            }
            else -> error("Unknown portable section")
        }
    }
}
private fun JSONObject.strictInt(key: String): Int = get(key) as? Int ?: error("Integer required")
