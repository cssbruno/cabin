package com.cabin.platform

import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class TripPeriod(val days: Long?) { ALL(null), TODAY(1), WEEK(7), MONTH(30) }
internal enum class TripSort { NEWEST, DISTANCE, DURATION, COST }

internal data class TripSummary(val count: Int, val kilometers: Double, val seconds: Long, val knownCost: Double?, val costCount: Int) {
    val averageKph: Double? get() = if (seconds > 0) kilometers * 3600 / seconds else null
}

internal fun selectTrips(
    trips: List<RecordedTrip>,
    period: TripPeriod,
    sort: TripSort,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<RecordedTrip> {
    val earliest = period.days?.let {
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(it - 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val selected = trips.filter { earliest == null || it.start in earliest..now }
    return when (sort) {
        TripSort.NEWEST -> selected.sortedByDescending { it.start }
        TripSort.DISTANCE -> selected.sortedWith(compareByDescending<RecordedTrip> { it.kilometers }.thenByDescending { it.start })
        TripSort.DURATION -> selected.sortedWith(compareByDescending<RecordedTrip> { it.seconds }.thenByDescending { it.start })
        TripSort.COST -> selected.sortedWith(compareByDescending<RecordedTrip> { it.estimatedCost ?: -1.0 }.thenByDescending { it.start })
    }
}

internal fun summarizeTrips(trips: List<RecordedTrip>): TripSummary {
    val costs = trips.mapNotNull { it.estimatedCost }
    return TripSummary(trips.size, trips.sumOf { it.kilometers }, trips.sumOf { it.seconds }, costs.takeIf { it.isNotEmpty() }?.sum(), costs.size)
}

/** Locale-independent numeric columns, no notes, coordinates or vehicle identifiers. */
internal fun tripCsv(trips: List<RecordedTrip>): String = buildString {
    append("start_utc,end_utc,distance_km,duration_seconds,estimated_cost\r\n")
    trips.forEach {
        append(Instant.ofEpochMilli(it.start)).append(',').append(Instant.ofEpochMilli(it.end)).append(',')
        append(it.kilometers).append(',').append(it.seconds).append(',').append(it.estimatedCost ?: "").append("\r\n")
    }
}

internal fun tripFuelEstimate(kilometers: Double, rate: String?, price: String?): Double? {
    val liters = rate?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in .1..100.0 } ?: return null
    val cost = price?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() && it in .001..10000.0 } ?: return null
    return (kilometers / 100 * liters * cost).takeIf { it.isFinite() && it >= 0 }
}

/** Date-only reminders remain independent of the vehicle's own service counter. */
internal class MaintenanceSchedule(private val prefs: SharedPreferences, private val profile: Int) {
    fun scheduled(): LocalDate? = date("maintenance.$profile")
    fun completed(): LocalDate? = date("maintenanceCompleted.$profile")
    fun interval(): Int = (prefs.all["maintenanceInterval.$profile"] as? Int)?.takeIf { it in intervals } ?: 90
    fun setInterval(days: Int) { if (profile > 0 && days in intervals) prefs.edit().putInt("maintenanceInterval.$profile", days).apply() }
    fun schedule(date: LocalDate) { if (profile > 0) prefs.edit().putString("maintenance.$profile", date.toString()).apply() }
    fun snooze(days: Long, today: LocalDate = LocalDate.now()) {
        if (days !in listOf(7L, 30L)) return
        val due = scheduled() ?: return
        schedule(maxOf(due, today).plusDays(days))
    }
    fun complete(today: LocalDate = LocalDate.now()) {
        if (profile > 0) prefs.edit().putString("maintenanceCompleted.$profile", today.toString())
            .putString("maintenance.$profile", today.plusDays(interval().toLong()).toString()).apply()
    }
    private fun date(key: String): LocalDate? = try { (prefs.all[key] as? String)?.let(LocalDate::parse) } catch (_: Exception) { null }
    companion object { val intervals = listOf(30, 90, 180, 365) }
}


/** Inclusive local dates become a half-open instant interval, including daylight-saving transitions. */
internal data class TripDateRange(val start: LocalDate, val end: LocalDate) {
    init { require(!end.isBefore(start)) }
    fun contains(time: Long, zone: ZoneId): Boolean = time >= start.atStartOfDay(zone).toInstant().toEpochMilli() &&
        time < end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}
internal fun filterTripRange(trips: List<RecordedTrip>, range: TripDateRange?, label: String = "", zone: ZoneId = ZoneId.systemDefault()): List<RecordedTrip> =
    trips.filter { (range == null || range.contains(it.start, zone)) && (label.isBlank() || it.label == label) }
internal fun monthSummary(trips: List<RecordedTrip>, month: java.time.YearMonth, zone: ZoneId = ZoneId.systemDefault()): TripSummary =
    summarizeTrips(filterTripRange(trips, TripDateRange(month.atDay(1), month.atEndOfMonth()), zone = zone))
internal fun tripCsvDetailed(trips: List<RecordedTrip>, includeNotes: Boolean): String = buildString {
    append("start_utc,end_utc,distance_km,duration_seconds,estimated_cost,currency,fuel_l_per_100km,fuel_price,label")
    if(includeNotes) append(",note")
    append("\r\n")
    trips.forEach { t ->
        append(Instant.ofEpochMilli(t.start)).append(',').append(Instant.ofEpochMilli(t.end)).append(',').append(t.kilometers).append(',')
            .append(t.seconds).append(',').append(t.estimatedCost ?: "").append(',').append(t.currency).append(',')
            .append(t.fuelRate ?: "").append(',').append(t.fuelPrice ?: "").append(',').append(t.label)
        if(includeNotes) append(',').append(csvText(t.note))
        append("\r\n")
    }
}
internal fun csvText(value: String): String = "\"" + (if(value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'" + value else value).replace("\"", "\"\"") + "\""
