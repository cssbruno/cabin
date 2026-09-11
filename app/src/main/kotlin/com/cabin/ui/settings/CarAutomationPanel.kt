package com.cabin.ui.settings

import android.content.Intent
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun CarAutomationPanel(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val prefs = remember { automationPreferences(context) }
    val values = rememberAutomationValues()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putLong("permissionRevision", System.currentTimeMillis()).apply()
    }
    SettingsDisclosure(stringResource(R.string.tools_automation), stringResource(R.string.tools_automation_detail)) {
        listOf("quiet" to R.string.tools_quiet, "solar" to R.string.tools_solar, "parked" to R.string.tools_parking_location).forEach { (key, label) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Switch(checked = values[key] == true, onCheckedChange = { prefs.edit().putBoolean(key, it).apply() })
            }
        }
        if (values["solar"] == true || values["parked"] == true) TextButton(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
            Text(stringResource(R.string.tools_gps_permission))
        }
        if (values["quiet"] == true) {
            for ((key, label) in listOf("start" to R.string.tools_start_hour, "end" to R.string.tools_end_hour)) {
                var value by remember(values[key]) { mutableStateOf((values[key] as? Int ?: if (key == "start") 22 else 7).toString()) }
                OutlinedTextField(value, onValueChange = { input ->
                    value = input.take(2)
                    value.toIntOrNull()?.takeIf { it in 0..23 }?.let { prefs.edit().putInt(key, it).apply() }
                }, label = { Text(stringResource(label)) }, isError = value.toIntOrNull() !in 0..23, singleLine = true)
            }
        }
        if (values["parked"] == true) {
            TextButton(onClick = { prefs.edit().putInt("saveParking", vehicle.profileId).putLong("saveParkingTime", System.currentTimeMillis()).apply() }) { Text(stringResource(R.string.tools_save_parked)) }
            if (values["saveParking"] == vehicle.profileId) Text(stringResource(R.string.tools_wait_location))
            val coordinate = values["parking.${vehicle.profileId}"] as? String
            val parts = coordinate?.split(',')?.mapNotNull(String::toDoubleOrNull)
            if (parts?.size == 2 && parts[0] in -90.0..90.0 && parts[1] in -180.0..180.0) {
                TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${parts[0]},${parts[1]}?q=${parts[0]},${parts[1]}"))) }
                    catch (_: RuntimeException) { }
                }) { Text(stringResource(R.string.tools_find_parked)) }
                TextButton(onClick = { prefs.edit().remove("parking.${vehicle.profileId}").remove("parkingTime.${vehicle.profileId}").remove("saveParking").remove("saveParkingTime").apply() }) { Text(stringResource(R.string.tools_forget_parked)) }
            }
        }
    }
}
