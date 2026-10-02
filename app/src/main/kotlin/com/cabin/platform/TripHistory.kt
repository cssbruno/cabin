package com.cabin.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RecordedTrip(val start: Long, val end: Long, val kilometers: Double, val seconds: Long, val estimatedCost: Double?,
    val label: String = "", val note: String = "", val fuelRate: Double? = null, val fuelPrice: Double? = null, val currency: String = "")

/** Distance is integrated from fresh verified speed; missing intervals are never filled. */
internal class TripRecorder(private val save: (Int, RecordedTrip) -> Unit) {
    private var profile = 0
    private var start = 0L
    private var lastElapsed = 0L
    private var lastWall = 0L
    private var lastSaved = 0L
    private var lastSpeed = 0
    private var stoppedAt: Long? = null
    private var kilometers = 0.0
    private var milliseconds = 0L
    fun observe(state: TeyesClimateState, elapsed: Long, wall: Long) {
        val speed = state.speedKph?.takeIf { state.connected && 89 in state.availableCodes && it in 0..400 }
        if (profile != state.profileId || speed == null || (start != 0L && (elapsed - lastElapsed !in 0..5_000 || wall < lastWall))) finish()
        if (speed == null) return
        if (start == 0L) {
            if (speed == 0 || state.profileId <= 0) return
            profile = state.profileId; start = wall; lastSaved = elapsed; lastElapsed = elapsed; lastWall = wall; lastSpeed = speed
            return
        }
        val dt = elapsed - lastElapsed
        kilometers += (lastSpeed + speed) / 2.0 * dt / 3_600_000
        milliseconds += dt
        lastElapsed = elapsed; lastWall = wall; lastSpeed = speed
        if (elapsed - lastSaved >= 60_000 && kilometers >= .01) {
            save(profile, RecordedTrip(start, lastWall, kilometers, milliseconds / 1000, null))
            lastSaved = elapsed
        }
        if (speed == 0) {
            if (stoppedAt == null) stoppedAt = elapsed
            if (elapsed - stoppedAt!! >= 120_000) finish()
        } else stoppedAt = null
    }
    fun abandon() { start = 0; stoppedAt = null; kilometers = 0.0; milliseconds = 0 }
    fun finish() {
        if (start != 0L && kilometers >= .01 && milliseconds > 0) save(profile, RecordedTrip(start, lastWall, kilometers, milliseconds / 1000, null))
        start = 0; stoppedAt = null; kilometers = 0.0; milliseconds = 0
    }
}

internal class TripHistory(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences("cabin_trips", Context.MODE_PRIVATE)
    fun save(profile: Int, trip: RecordedTrip) = synchronized(storageLock) {
        if (profile <= 0 || !recording(profile) || !valid(trip) || trip.start.toString() in deleted(profile)) return@synchronized
        val previous = read(profile).firstOrNull { it.start == trip.start }
        val saved = estimate(profile, trip.copy(label = previous?.label ?: trip.label, note = previous?.note ?: trip.note))
        write(profile, read(profile).filterNot { it.start == trip.start } + saved)
    }

    fun recording(profile: Int): Boolean = prefs.all["recording.$profile"] as? Boolean ?: true
    fun setRecording(profile: Int, enabled: Boolean) { if(profile > 0) prefs.edit().putBoolean("recording.$profile", enabled).apply() }
    fun fuelValue(profile: Int, key: String): String? = (prefs.all["$key.$profile"] ?: prefs.all[key]) as? String
    fun estimate(profile: Int, trip: RecordedTrip): RecordedTrip {
        val rate = fuelValue(profile, "fuelRate"); val price = fuelValue(profile, "fuelPrice")
        return trip.copy(estimatedCost = tripFuelEstimate(trip.kilometers, rate, price), fuelRate = rate?.replace(',', '.')?.toDoubleOrNull(),
            fuelPrice = price?.replace(',', '.')?.toDoubleOrNull(), currency = fuelValue(profile, "fuelCurrency").orEmpty())
    }
    fun annotate(profile: Int, start: Long, label: String, note: String) = synchronized(storageLock) {
        require(label in setOf("", "personal", "business") && note.length <= 500)
        write(profile, read(profile).map { if(it.start == start) it.copy(label = label, note = note.filterNot { c -> c.isISOControl() && c != '\n' }) else it })
    }
    fun deleteSelected(profile: Int, starts: Set<Long>): List<RecordedTrip> = synchronized(storageLock) {
        val removed = read(profile).filter { it.start in starts }
        removed.forEach { delete(profile, it.start) }; removed
    }
    fun restoreSelected(profile: Int, trips: List<RecordedTrip>) = synchronized(storageLock) { trips.forEach { restore(profile, it) } }

    fun retention(profile: Int): Int = (prefs.all["retention.$profile"] as? Int)?.takeIf { it in retentionChoices } ?: 100

    /** The chosen cap applies to future recording and immediately to this vehicle. */
    fun setRetention(profile: Int, limit: Int) = synchronized(storageLock) {
        if (profile <= 0 || limit !in retentionChoices) return@synchronized
        prefs.edit().putInt("retention.$profile", limit).apply()
        write(profile, read(profile))
    }

    fun delete(profile: Int, start: Long) = synchronized(storageLock) {
        if (profile <= 0) return@synchronized
        val removed = (deleted(profile) + start.toString()).sortedBy { it.toLongOrNull() ?: 0 }.takeLast(100).toSet()
        prefs.edit().putStringSet("deleted.$profile", removed).apply()
        write(profile, read(profile).filterNot { it.start == start })
    }

    fun restore(profile: Int, trip: RecordedTrip) = synchronized(storageLock) {
        if (profile <= 0 || !valid(trip)) return@synchronized
        prefs.edit().putStringSet("deleted.$profile", deleted(profile) - trip.start.toString()).apply()
        write(profile, read(profile).filterNot { it.start == trip.start } + trip)
    }

    fun clear(profile: Int) = synchronized(storageLock) {
        if (profile <= 0) return@synchronized
        val removed = (deleted(profile) + read(profile).map { it.start.toString() }).sortedBy { it.toLongOrNull() ?: 0 }.takeLast(100).toSet()
        prefs.edit().putStringSet("deleted.$profile", removed).remove("trips.$profile").apply()
    }

    fun recalculateCosts(profile: Int): Boolean = synchronized(storageLock) {
        if (profile <= 0 || tripFuelEstimate(1.0, fuelValue(profile, "fuelRate"), fuelValue(profile, "fuelPrice")) == null) return@synchronized false
        write(profile, read(profile).map { estimate(profile, it) })
        true
    }

    private fun deleted(profile: Int): Set<String> = (prefs.all["deleted.$profile"] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()

    private fun write(profile: Int, trips: List<RecordedTrip>) {
        val points = trips.sortedBy { it.start }.takeLast(retention(profile))
        prefs.edit().putString("trips.$profile", JSONArray().apply {
            points.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("km", it.kilometers).put("seconds", it.seconds).put("cost", it.estimatedCost ?: JSONObject.NULL).put("label", it.label).put("note", it.note).put("fuelRate", it.fuelRate ?: JSONObject.NULL).put("fuelPrice", it.fuelPrice ?: JSONObject.NULL).put("currency", it.currency)) }
        }.toString()).apply()
    }
    fun read(profile: Int): List<RecordedTrip> = try {
        val raw = if (profile > 0) prefs.all["trips.$profile"] as? String else null
        if (raw == null || raw.length > 200_000) emptyList() else {
            val array = JSONArray(raw); require(array.length() <= 100)
            (0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                RecordedTrip(j.getLong("start"), j.getLong("end"), j.getDouble("km"), j.getLong("seconds"), if (j.isNull("cost")) null else j.getDouble("cost"), j.optString("label"), j.optString("note"), if(j.isNull("fuelRate")) null else j.optDouble("fuelRate").takeIf { it.isFinite() }, if(j.isNull("fuelPrice")) null else j.optDouble("fuelPrice").takeIf { it.isFinite() }, j.optString("currency")).also {
                    require(valid(it))
                }
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun valid(trip: RecordedTrip): Boolean = trip.label in setOf("", "personal", "business") && trip.note.length <= 500 && trip.currency.length <= 3 && trip.start > 0 && trip.end >= trip.start && trip.kilometers.isFinite() &&
        trip.kilometers in 0.0..100000.0 && trip.seconds in 0..31_536_000L &&
        (trip.estimatedCost == null || trip.estimatedCost.isFinite() && trip.estimatedCost >= 0)

    companion object {
        val retentionChoices = listOf(25, 50, 100)
        private val storageLock = Any()
    }
}
