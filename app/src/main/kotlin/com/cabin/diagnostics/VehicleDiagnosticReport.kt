package com.cabin.diagnostics

import com.cabin.BuildConfig
import com.cabin.platform.TeyesClimateState
import org.json.JSONObject

/** Allowlist-only export: no log text, coordinates, names, addresses, tokens or command payloads. */
internal fun vehicleDiagnosticReport(state: TeyesClimateState): String = JSONObject().apply {
    put("format", "cabin-vehicle-diagnostics-v1")
    put("version", BuildConfig.VERSION_NAME)
    put("androidApi", android.os.Build.VERSION.SDK_INT)
    put("profile", state.profileId)
    put("layout", state.vehicleDataLayout.name)
    put("fytFirmwareVersion", state.fytFirmwareVersion)
    put("fytFirmwareSha256", state.fytFirmwareSha256)
    put("connected", state.connected)
    put("health", state.health.name)
    put("freshCanonicalFieldCount", state.availableCodes.size)
    put("climate", JSONObject().apply {
        put("supportedActions", state.syuAir?.actions?.size ?: 0)
        put("readyActions", if (state.connected) state.syuAir?.actions?.count { state.syuAir.canSend(it) } ?: 0 else 0)
    })
    put("factory", JSONObject().apply {
        put("supportedControls", state.syuVehicle.factoryCapabilities.size)
        put("freshControls", if (state.connected) state.syuVehicle.factoryControls.size else 0)
    })
}.toString(2)
