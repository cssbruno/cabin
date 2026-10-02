package com.cabin.ui.settings

import android.content.Intent
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun CarAutomationPanel(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val prefs = remember { automationPreferences(context) }
    val values = rememberAutomationValues()
    var forgetParking by remember(vehicle.profileId) { mutableStateOf(false) }
    var mapUnavailable by remember(vehicle.profileId) { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        prefs.edit().putLong("permissionRevision", System.currentTimeMillis()).apply()
    }
    SettingsDisclosure(stringResource(R.string.tools_automation), stringResource(R.string.tools_automation_detail), searchLabels = emptySet()) {
        listOf("quiet" to R.string.tools_quiet, "solar" to R.string.tools_solar, "parked" to R.string.tools_parking_location).forEach { (key, label) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Switch(checked = values[key] == true, onCheckedChange = { prefs.edit().putBoolean(key, it).apply() }, modifier = Modifier.semantics { contentDescription = context.getString(label) })
            }
        }
        if (values["solar"] == true || values["parked"] == true) TextButton(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
            Text(stringResource(R.string.tools_gps_permission))
        }
        if (values["quiet"] == true) {
            QuietHoursEditor(values["start"] as? Int ?: 22, values["end"] as? Int ?: 7) { start, end ->
                prefs.edit().putInt("start", start).putInt("end", end).apply()
            }
        }
        if (values["parked"] == true) {
            val noteKey = "parkingNote.${vehicle.profileId}"
            var note by rememberSaveable(vehicle.profileId) { mutableStateOf(values[noteKey] as? String ?: "") }
            val hasSavedParking = values[noteKey] is String || values["parking.${vehicle.profileId}"] is String
            var hadSavedParking by rememberSaveable(vehicle.profileId) { mutableStateOf(hasSavedParking) }
            LaunchedEffect(hasSavedParking) {
                // Explicit deletion or retention expiry also clears the private draft, including restored UI state.
                if(hadSavedParking && !hasSavedParking) note = ""
                hadSavedParking = hasSavedParking
            }
            OutlinedTextField(note, onValueChange = {
                note = it.take(160)

            }, label = { Text(stringResource(R.string.utility_parking_note)) },
                supportingText = { Text(stringResource(R.string.utility_parking_note_hint)) },
                enabled = vehicle.profileId > 0, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Row {
                TextButton(onClick = { prefs.edit().putString(noteKey, note).apply() }, enabled = vehicle.profileId > 0) { Text(stringResource(R.string.action_apply)) }
                TextButton(onClick = { note = values[noteKey] as? String ?: "" }) { Text(stringResource(R.string.action_cancel)) }
            }
            TextButton(onClick = {
                val allowed = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val manager = context.getSystemService(android.location.LocationManager::class.java)
                val outcome = if(!allowed) "permission" else if(!manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) "disabled" else "waiting"
                prefs.edit().putString("parkingCapture.${vehicle.profileId}", outcome).apply {
                    if(outcome == "waiting") { putInt("saveParking", vehicle.profileId); putLong("saveParkingTime", System.currentTimeMillis()) }
                    else { remove("saveParking"); remove("saveParkingTime") }
                }.apply()
            }, enabled = vehicle.profileId > 0) { Text(stringResource(R.string.tools_save_parked)) }
            (values["parkingCapture.${vehicle.profileId}"] as? String)?.let { outcome -> Text(stringResource(when(outcome) {
                "permission" -> R.string.gv_location_permission; "disabled" -> R.string.gv_location_disabled; "timeout" -> R.string.gv_location_timeout
                "success" -> R.string.gv_location_success; else -> R.string.tools_wait_location
            })) }
            (values["parkingAccuracy.${vehicle.profileId}"] as? Float)?.let { Text(stringResource(R.string.gv_accuracy, it.toInt())) }
            ParkingReminderControls(vehicle.profileId, values)
            val coordinate = values["parking.${vehicle.profileId}"] as? String
            val parts = coordinate?.split(',')?.mapNotNull(String::toDoubleOrNull)
            if (parts?.size == 2 && parts[0] in -90.0..90.0 && parts[1] in -180.0..180.0) {
                val savedAt = values["parkingTime.${vehicle.profileId}"] as? Long
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(savedAt) { while (true) { now = System.currentTimeMillis(); delay(60_000) } }
                if (savedAt != null && savedAt > 0) {
                    Text(stringResource(R.string.utility_parked_at, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt))))
                    if (now >= savedAt) Text(stringResource(R.string.utility_parked_elapsed, utilityDuration((now - savedAt) / 1000)))
                }
                TextButton(onClick = {
                    try { mapUnavailable = false; context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${parts[0]},${parts[1]}?q=${parts[0]},${parts[1]}"))) }
                    catch (_: RuntimeException) { mapUnavailable = true }
                }) { Text(stringResource(R.string.tools_find_parked)) }
                TextButton(onClick = { forgetParking = true }) { Text(stringResource(R.string.tools_forget_parked)) }
            }
        }
        if(mapUnavailable) SettingsNotice(stringResource(R.string.uxv_map_unavailable), error = true)
        PrivacyInventory(vehicle.profileId)
    }
    if(forgetParking) AlertDialog(onDismissRequest = { forgetParking = false },
        title = { Text(stringResource(R.string.tools_forget_parked)) },
        text = { Text(stringResource(R.string.uxv_forget_parking)) },
        confirmButton = { TextButton(onClick = {
            val profile = vehicle.profileId
            prefs.edit().apply { listOf("parking.", "parkingTime.", "parkingAccuracy.", "parkingCapture.", "parkingNote.").forEach { remove(it + profile) }; if(prefs.getInt("saveParking", -1) == profile) { remove("saveParking"); remove("saveParkingTime") } }.apply()
            ParkingReminder.cancel(context, profile)
            forgetParking = false
        }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { forgetParking = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable internal fun QuietHoursEditor(savedStart: Int, savedEnd: Int, onApply: (Int, Int) -> Unit) {
    var start by rememberSaveable(savedStart) { mutableStateOf(savedStart.toString()) }
    var end by rememberSaveable(savedEnd) { mutableStateOf(savedEnd.toString()) }
    val valid = start.toIntOrNull() in 0..23 && end.toIntOrNull() in 0..23
    val changed = start != savedStart.toString() || end != savedEnd.toString()
    OutlinedTextField(start, { start = it.take(2) }, label = { Text(stringResource(R.string.tools_start_hour)) }, isError = start.toIntOrNull() !in 0..23, singleLine = true)
    OutlinedTextField(end, { end = it.take(2) }, label = { Text(stringResource(R.string.tools_end_hour)) }, isError = end.toIntOrNull() !in 0..23, singleLine = true)
    if (changed) Text(stringResource(R.string.goal_draft_note))
    Row {
        TextButton({ onApply(start.toInt(), end.toInt()) }, enabled = changed && valid) { Text(stringResource(R.string.action_apply)) }
        TextButton({ start = savedStart.toString(); end = savedEnd.toString() }, enabled = changed) { Text(stringResource(R.string.action_cancel)) }
    }
}
