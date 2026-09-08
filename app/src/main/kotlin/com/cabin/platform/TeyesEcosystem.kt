package com.cabin.platform

import com.cabin.platform.obd.ObdSnapshot
import org.json.JSONArray
import org.json.JSONObject

data class TeyesReading(val value: Double, val source: String)

data class TeyesVehicleReadings(
    val speed: TeyesReading?,
    val rpm: TeyesReading?,
    val coolant: TeyesReading?,
    val voltage: TeyesReading?,
    /** Legacy compatibility flag. External OBD is never a source and this is always false. */
    val obdFresh: Boolean = false,
)

/** Only documented existing SYU fields; the controller expires each field before publishing. */
fun teyesVehicleReadings(vehicle: TeyesClimateState): TeyesVehicleReadings {
    fun syuValue(
        code: Int,
        value: Int?,
        validRange: IntRange,
    ): TeyesReading? =
        value?.takeIf {
            vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE && code in vehicle.availableCodes && it in validRange
        }?.let { TeyesReading(it.toDouble(), "TEYES/SYU") }
    return TeyesVehicleReadings(
        speed = syuValue(89, vehicle.speedKph, 0..400),
        rpm = syuValue(90, vehicle.engineRpm, 0..10_000),
        coolant = null,
        voltage = null,
    )
}

/** Old callers remain source-compatible, but external snapshots are deliberately ignored. */
@Suppress("UNUSED_PARAMETER")
fun teyesVehicleReadings(
    vehicle: TeyesClimateState,
    obd: ObdSnapshot,
    nowMs: Long,
): TeyesVehicleReadings = teyesVehicleReadings(vehicle)

/** Deliberate export allowlist: no phone MACs, routes, location, media titles or raw vendor payloads. */
object TeyesDiagnostics {
    fun encode(
        projection: ProjectionHealthSnapshot,
        vehicle: TeyesClimateState,
        configuredShortcutCount: Int,
        androidApi: Int,
    ): String =
        JSONObject()
            .put("schemaVersion", 2)
            .put("androidApi", androidApi)
            .put("projectionState", projection.connection.name)
            .put("disconnectTransitionsIncludingUserStop", projection.disconnectTransitions)
            .put("lastStreamReturnDurationMs", projection.lastRecoveryDurationMs ?: JSONObject.NULL)
            .put("appVersion", com.cabin.BuildConfig.VERSION_NAME)
            .put("appVersionCode", com.cabin.BuildConfig.VERSION_CODE)
            .put("buildFlavor", "cabin")
            .put("debugBuild", com.cabin.BuildConfig.DEBUG)
            .put("vehicleBinderConnected", vehicle.connected)
            .put("vehicleDataSource", "TEYES/SYU")
            .put("vehicleProfileId", vehicle.profileId)
            .put("vehicleDataLayout", vehicle.vehicleDataLayout.name)
            .put("vehicleCodeNamespace", "Carlink normalized fields")
            .put("vehicleHealth", vehicle.health.name)
            .put("climateControlsSupported", vehicle.controlsSupported)
            .put("climateControlsAvailable", vehicle.controlsAvailable)
            .put("availableVehicleCodes", JSONArray(vehicle.availableCodes.sorted()))
            .put("configuredShortcutCount", configuredShortcutCount.coerceIn(0, TeyesShortcut.entries.size))
            .put(
                "connectionEvents",
                JSONArray(
                    projection.events.takeLast(ProjectionHealthStore.MAX_EVENTS).map {
                        JSONObject().put("elapsedMs", it.elapsedMs).put("kind", it.kind.name)
                    },
                ),
            )
            .put(
                "streamEndSnapshots",
                JSONArray(
                    projection.incidents.takeLast(ProjectionHealthStore.MAX_INCIDENTS).map { incident ->
                        JSONObject().put("elapsedMs", incident.elapsedMs).put("nextState", incident.nextState.name)
                            .put(
                                "events",
                                JSONArray(
                                    incident.events.takeLast(ProjectionHealthStore.MAX_EVENTS).map {
                                        JSONObject().put("elapsedMs", it.elapsedMs).put("kind", it.kind.name)
                                    },
                                ),
                            )
                            .put(
                                "transitions",
                                JSONArray(
                                    incident.transitions.takeLast(ProjectionHealthStore.MAX_EVENTS).map {
                                        JSONObject().put("elapsedMs", it.elapsedMs).put("state", it.state.name)
                                    },
                                ),
                            )
                    },
                ),
            )
            .put(
                "transitions",
                JSONArray(
                    projection.transitions.takeLast(ProjectionHealthStore.MAX_EVENTS).map {
                        JSONObject().put("elapsedMs", it.elapsedMs).put("state", it.state.name)
                    },
                ),
            )
            .put("notice", "Software observations only. No hardware compatibility certification. No raw vehicle commands.")
            .toString(2)

    @Suppress("UNUSED_PARAMETER")
    fun encode(
        projection: ProjectionHealthSnapshot,
        vehicle: TeyesClimateState,
        obd: ObdSnapshot,
        configuredShortcutCount: Int,
        androidApi: Int,
    ): String = encode(projection, vehicle, configuredShortcutCount, androidApi)
}
