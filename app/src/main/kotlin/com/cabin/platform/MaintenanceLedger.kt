package com.cabin.platform

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

internal data class MaintenanceItem(val id: String, val name: String, val due: LocalDate, val intervalDays: Int, val dueKm: Double? = null, val intervalKm: Double? = null)
internal data class ServiceEntry(val id: String, val itemId: String, val name: String, val date: LocalDate, val note: String = "", val cost: Double? = null)
internal data class ManualOdometer(val date: LocalDate, val kilometers: Double)

/** User-entered service records are separate from all CAN service counters. */
internal class MaintenanceLedger(private val prefs: SharedPreferences, private val profile: Int) {
    fun items(): List<MaintenanceItem> = runCatching {
        val raw = prefs.all["maintenanceItems.$profile"] as? String
        if(raw == null) {
            val old = MaintenanceSchedule(prefs, profile).scheduled()
            if(old == null) emptyList() else listOf(MaintenanceItem("legacy", "Service", old, MaintenanceSchedule(prefs, profile).interval()))
        } else array(raw, 30).map { j -> MaintenanceItem(j.getString("id"), j.getString("name"), LocalDate.parse(j.getString("due")), j.getInt("interval"),
            if(j.isNull("dueKm")) null else j.getDouble("dueKm"), if(j.isNull("intervalKm")) null else j.getDouble("intervalKm")) }.onEach(::validate)
    }.getOrDefault(emptyList())
    fun save(item: MaintenanceItem) {
        require(profile > 0); validate(item)
        val next = items().filterNot { it.id == item.id } + item; require(next.size <= 30)
        writeItems(next)
    }
    fun delete(id: String) = writeItems(items().filterNot { it.id == id })
    fun snooze(id: String, today: LocalDate = LocalDate.now()) { items().firstOrNull { it.id == id }?.let { save(it.copy(due = maxOf(it.due, today).plusDays(7))) } }
    fun complete(id: String, note: String, cost: Double?, today: LocalDate = LocalDate.now()) {
        val item = requireNotNull(items().firstOrNull { it.id == id }) { "Maintenance item no longer exists" }
        val entry = ServiceEntry(UUID.randomUUID().toString(), id, item.name, today, note, cost)
        validateEntry(entry)
        val entries = (history() + entry).takeLast(250)
        val next = items().map { if(it.id == id) it.copy(due = today.plusDays(it.intervalDays.toLong()), dueKm = it.intervalKm?.let { km -> odometer()?.kilometers?.plus(km) }) else it }
        prefs.edit().putString("serviceHistory.$profile", encodeHistory(entries)).putString("maintenanceItems.$profile", encodeItems(next)).apply()
    }
    fun history(): List<ServiceEntry> = runCatching { array(prefs.all["serviceHistory.$profile"] as? String ?: "[]", 250).map { j ->
        ServiceEntry(j.getString("id"), j.getString("item"), j.getString("name"), LocalDate.parse(j.getString("date")), j.optString("note"), if(j.isNull("cost")) null else j.getDouble("cost"))
    }.onEach(::validateEntry) }.getOrDefault(emptyList())
    fun correct(entry: ServiceEntry) { validateEntry(entry); require(history().any { it.id == entry.id }); prefs.edit().putString("serviceHistory.$profile", encodeHistory(history().map { if(it.id == entry.id) entry else it })).apply() }
    fun clearHistory() { prefs.edit().remove("serviceHistory.$profile").apply() }
    fun odometer(): ManualOdometer? = runCatching {
        val j = JSONObject(prefs.all["odometer.$profile"] as? String ?: return null)
        ManualOdometer(LocalDate.parse(j.getString("date")), j.getDouble("km")).also { require(it.kilometers.isFinite() && it.kilometers in 0.0..10_000_000.0) }
    }.getOrNull()
    fun recordOdometer(reading: ManualOdometer) {
        require(profile > 0 && reading.kilometers.isFinite() && reading.kilometers in 0.0..10_000_000.0 && !reading.date.isAfter(LocalDate.now()))
        odometer()?.let { require(!reading.date.isBefore(it.date) && reading.kilometers >= it.kilometers) }
        prefs.edit().putString("odometer.$profile", JSONObject().put("date", reading.date.toString()).put("km", reading.kilometers).toString()).apply()
    }
    fun historyCsv(): String = "date,item,note,cost\r\n" + history().joinToString("") { "${it.date},${csvText(it.name)},${csvText(it.note)},${it.cost ?: ""}\r\n" }
    private fun writeItems(items: List<MaintenanceItem>) { if(profile > 0) prefs.edit().putString("maintenanceItems.$profile", encodeItems(items)).apply() }
    private fun encodeItems(items: List<MaintenanceItem>) = JSONArray().apply { items.forEach { i -> put(JSONObject().put("id", i.id).put("name", i.name).put("due", i.due.toString()).put("interval", i.intervalDays).put("dueKm", i.dueKm ?: JSONObject.NULL).put("intervalKm", i.intervalKm ?: JSONObject.NULL)) } }.toString()
    private fun encodeHistory(items: List<ServiceEntry>) = JSONArray().apply { items.forEach { i -> put(JSONObject().put("id", i.id).put("item", i.itemId).put("name", i.name).put("date", i.date.toString()).put("note", i.note).put("cost", i.cost ?: JSONObject.NULL)) } }.toString()
    private fun array(raw: String, limit: Int): List<JSONObject> { require(raw.length <= 250_000); val a = JSONArray(raw); require(a.length() <= limit); return (0 until a.length()).map(a::getJSONObject) }
    private fun validate(i: MaintenanceItem) { require(i.id.length in 1..64 && i.name.isNotBlank() && i.name.length <= 60 && i.intervalDays in 1..3650); listOfNotNull(i.dueKm, i.intervalKm).forEach { require(it.isFinite() && it in 0.0..10_000_000.0) } }
    private fun validateEntry(i: ServiceEntry) { require(i.id.length in 1..64 && i.name.length in 1..60 && i.note.length <= 500 && (i.cost == null || i.cost.isFinite() && i.cost in 0.0..10_000_000.0)) }
}
