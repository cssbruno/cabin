package com.cabin.platform

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class TireHistoryPoint(val time: Long, val tires: List<SyuTireReading>)

/** Local, bounded history of decoded telemetry. No inferred pressures or vehicle identifiers. */
internal class TireHistory(context: Context) {
    internal val prefs = context.applicationContext.getSharedPreferences("cabin_tire_history", Context.MODE_PRIVATE)
    private val lastRecorded = mutableMapOf<Int, Long>()

    fun record(state: TeyesClimateState, elapsed: Long, wallTime: Long = System.currentTimeMillis()) {
        synchronized(storageLock) {
            if (!state.connected || state.profileId !in SyuVehicleProtocol.tireProfiles || wallTime <= 0) return
            val tires = state.syuVehicle.tires
            if (tires.size != 4 || tires.none { it.pressureKpa != null || it.warning != null }) return
            if (lastRecorded[state.profileId]?.let { elapsed >= it && elapsed - it < 60_000 } == true) return
            val points = read(state.profileId).filter { it.time <= wallTime && wallTime - it.time <= 30L * 86400_000 }.takeLast(719) + TireHistoryPoint(wallTime, tires)
            val array = JSONArray()
            points.forEach { point -> array.put(JSONObject().put("time", point.time).put("tires", JSONArray().apply {
                point.tires.forEach { tire -> put(JSONArray().put(tire.pressureKpa ?: JSONObject.NULL).put(tire.warning ?: JSONObject.NULL)) }
            })) }
            prefs.edit().putString(state.profileId.toString(), array.toString()).apply()
            lastRecorded[state.profileId] = elapsed
        }
    }

    fun read(profile: Int): List<TireHistoryPoint> = try {
        if (profile !in SyuVehicleProtocol.tireProfiles) emptyList() else {
            val raw = prefs.all[profile.toString()] as? String
            if (raw == null || raw.length > 200_000) emptyList() else {
                val array = JSONArray(raw)
                require(array.length() <= 720)
                (0 until array.length()).map { i ->
                    val point = array.getJSONObject(i)
                    val time = point.getLong("time"); require(time > 0)
                    val tires = point.getJSONArray("tires"); require(tires.length() == 4)
                    TireHistoryPoint(time, (0..3).map { wheel ->
                        val tire = tires.getJSONArray(wheel); require(tire.length() == 2)
                        val pressure = if (tire.isNull(0)) null else tire.getDouble(0).also { require(it.isFinite() && it in 0.0..698.5) }
                        val warning = if (tire.isNull(1)) null else tire.getInt(1).also { require(it in 0..7) }
                        SyuTireReading(pressure, warning)
                    })
                }.also { points -> require(points.zipWithNext().all { (a, b) -> a.time <= b.time }) }
            }
        }
    } catch (_: Exception) { emptyList() }

    fun clear(profile: Int) = synchronized(storageLock) { prefs.edit().remove(profile.toString()).apply() }

    private companion object { val storageLock = Any() }
}
