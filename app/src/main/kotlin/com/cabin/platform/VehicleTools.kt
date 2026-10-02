package com.cabin.platform

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

internal fun vehicleToolsPreferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences("cabin_vehicle_tools", Context.MODE_PRIVATE)
enum class ClimateCommandStatus { NONE, WAITING, CONFIRMED, TIMED_OUT, CANCELLED }
internal class ClimateCommandTracker {
    var status = ClimateCommandStatus.NONE; private set
    private var profile = 0
    private var started = 0L
    private var expected: Map<Int, Int> = emptyMap()
    fun begin(profile: Int, now: Long, expected: Map<Int, Int>) { this.profile = profile; started = now; this.expected = expected; status = ClimateCommandStatus.WAITING }
    fun observe(profile: Int, now: Long, values: Map<Int, Int>, received: Map<Int, Long>): ClimateCommandStatus {
        if(status != ClimateCommandStatus.WAITING) return status
        status = when {
            profile != this.profile -> ClimateCommandStatus.CANCELLED
            expected.isNotEmpty() && expected.all { (code, value) -> values[code] == value && (received[code] ?: Long.MIN_VALUE) > started } -> ClimateCommandStatus.CONFIRMED
            now - started >= 5_000 -> ClimateCommandStatus.TIMED_OUT
            else -> status
        }
        return status
    }
    fun cancel() { if(status == ClimateCommandStatus.WAITING) status = ClimateCommandStatus.CANCELLED }
}

internal fun compatibilitySnapshot(state: TeyesClimateState): String = JSONObject().apply {
    put("schema", 1); put("savedAt", System.currentTimeMillis()); put("profile", state.profileId); put("firmware", state.fytFirmwareVersion); put("layout", state.vehicleDataLayout.name)
    put("capabilities", state.fytPublishedFields.sorted().joinToString(",")); put("freshFeedback", state.availableCodes.sorted().joinToString(",")); put("actions", state.fytActions.map { it.name }.sorted().joinToString(","))
    put("climate", state.controlsAvailable); put("tires", state.syuVehicle.tires.count { it.pressureKpa != null }); put("connected", state.connected)
}.toString()
internal enum class CompatibilityChangeKind { ADDED, MISSING, CHANGED, UNCHANGED, FEEDBACK }
internal data class CompatibilityChange(val field: String, val before: String?, val after: String?) {
    val kind: CompatibilityChangeKind get() = when { this.field == "freshFeedback" || this.field == "connected" -> CompatibilityChangeKind.FEEDBACK; before == null -> CompatibilityChangeKind.ADDED; after == null -> CompatibilityChangeKind.MISSING; before == after -> CompatibilityChangeKind.UNCHANGED; else -> CompatibilityChangeKind.CHANGED }
}
internal fun compareCompatibility(before: String, after: String): List<CompatibilityChange> {
    require(before.length <= 16_384 && after.length <= 16_384)
    val a = JSONObject(before); val b = JSONObject(after); require(a.getInt("schema") == 1 && b.getInt("schema") == 1)
    return (a.keys().asSequence().toSet() + b.keys().asSequence().toSet()).sorted().flatMap { key ->
        if(key in setOf("capabilities", "actions")) {
            val old = a.optString(key).split(',').filter { it.isNotBlank() }.toSet(); val new = b.optString(key).split(',').filter { it.isNotBlank() }.toSet()
            (old + new).sorted().map { value -> CompatibilityChange("$key · $value", value.takeIf { it in old }, value.takeIf { it in new }) }
        } else listOf(CompatibilityChange(key, if(a.has(key)) a.get(key).toString() else null, if(b.has(key)) b.get(key).toString() else null))
    }
}
