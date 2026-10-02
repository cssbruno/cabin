package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun VehicleReadingsTools(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val prefs = remember { vehicleToolsPreferences(context) }
    val values = rememberAutomationValues("cabin_vehicle_tools")
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Switch(values["readOnly"] == true, { prefs.edit().putBoolean("readOnly", it).apply() }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_read_only) })
        Text(stringResource(R.string.gv_read_only))
    }
    Text(stringResource(R.string.gv_read_only_detail), style = MaterialTheme.typography.bodySmall)
    UtilityChoice(stringResource(R.string.gv_climate_timeout), (values["climateTimeout"] as? Int ?: 10).toString()) { close ->
        listOf(0, 5, 10, 20, 30, 60).forEach { seconds -> DropdownMenuItem(text = { Text(if(seconds == 0) stringResource(R.string.gv_never) else "$seconds s") }, onClick = { prefs.edit().putInt("climateTimeout", seconds).apply(); close() }) }
    }
    SettingsDisclosure(stringResource(R.string.gv_freshness), stringResource(R.string.gv_live_provenance), searchLabels = emptySet()) {
        listOf(89 to R.string.vehicle_speed, 90 to R.string.vehicle_engine_speed, 137 to R.string.vehicle_oil_life, 181 to R.string.vehicle_oil_service, 25 to R.string.gv_temperature, 29 to R.string.climate_title).forEach { (code, label) ->
            val age = vehicle.fieldAgesMs[code]
            val status = when { !vehicle.connected -> R.string.telemetry_disconnected; age == null -> R.string.compat_waiting; code !in vehicle.availableCodes -> R.string.telemetry_stale; else -> R.string.telemetry_live }
            Text(stringResource(label) + ": " + stringResource(status) + if(age == null) "" else " · ${age / 1000}s")
        }
    }
}
