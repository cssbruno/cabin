package com.cabin.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RecordedTrip(val start: Long, val end: Long, val kilometers: Double, val seconds: Long, val estimatedCost: Double?)

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
    fun finish() {
        if (start != 0L && kilometers >= .01 && milliseconds > 0) save(profile, RecordedTrip(start, lastWall, kilometers, milliseconds / 1000, null))
        start = 0; stoppedAt = null; kilometers = 0.0; milliseconds = 0
    }
}

internal class TripHistory(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences("cabin_trips", Context.MODE_PRIVATE)
    fun save(profile: Int, trip: RecordedTrip) {
        val rate = (prefs.all["fuelRate"] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in .1..100.0 }
        val price = (prefs.all["fuelPrice"] as? String)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in .001..10000.0 }
        val saved = trip.copy(estimatedCost = if (rate != null && price != null) trip.kilometers / 100 * rate * price else null)
        val points = read(profile).filterNot { it.start == trip.start }.takeLast(99) + saved
        prefs.edit().putString("trips.$profile", JSONArray().apply {
            points.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("km", it.kilometers).put("seconds", it.seconds).put("cost", it.estimatedCost ?: JSONObject.NULL)) }
        }.toString()).apply()
    }
    fun read(profile: Int): List<RecordedTrip> = try {
        val raw = prefs.all["trips.$profile"] as? String
        if (raw == null || raw.length > 100_000) emptyList() else {
            val array = JSONArray(raw); require(array.length() <= 100)
            (0 until array.length()).map { i ->
                val j = array.getJSONObject(i)
                RecordedTrip(j.getLong("start"), j.getLong("end"), j.getDouble("km"), j.getLong("seconds"), if (j.isNull("cost")) null else j.getDouble("cost")).also {
                    require(it.start > 0 && it.end >= it.start && it.kilometers.isFinite() && it.kilometers in 0.0..100000.0 && it.seconds >= 0)
                    require(it.estimatedCost == null || it.estimatedCost.isFinite() && it.estimatedCost >= 0)
                }
            }
        }
    } catch (_: Exception) { emptyList() }
}
