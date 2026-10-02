package com.cabin.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.cabin.R
import com.cabin.platform.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ParkingReminderControls(profile: Int, values: Map<String, *>) {
    val context = LocalContext.current; val prefs = remember { automationPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true) { now = System.currentTimeMillis(); delay(30_000) } }
    val notify = remember(revision, now) { ParkingReminder.canNotify(context) }
    Text(stringResource(R.string.gv_parking_reminder), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.gv_reminder_delay), style = MaterialTheme.typography.bodySmall)
    if(!notify) {
        Text(stringResource(R.string.gv_notification_needed))
        TextButton(onClick = { if(Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        } }) { Text(stringResource(R.string.uxv_notification_settings)) }
    }
    FlowRow {
        listOf(15, 30, 60, 120).forEach { minutes -> TextButton(onClick = { ParkingReminder.schedule(context, profile, System.currentTimeMillis() + minutes * 60_000L) }, enabled = profile > 0) { Text("$minutes min") } }
    }
    (values["parkingExpires.$profile"] as? Long)?.let { due ->
        Text(if(due <= now) stringResource(R.string.gv_parking_expired) else utilityDuration((due - now) / 1000))
        TextButton(onClick = { ParkingReminder.cancel(context, profile) }) { Text(stringResource(R.string.action_cancel)) }
    }
    UtilityChoice(stringResource(R.string.gv_parking_retention), (values["parkingRetention.$profile"] as? Int ?: 0).let { if(it == 0) stringResource(R.string.gv_until_cleared) else stringResource(R.string.utility_days, it) }) { close ->
        listOf(0, 1, 7, 30).forEach { days -> DropdownMenuItem(text = { Text(if(days == 0) stringResource(R.string.gv_until_cleared) else stringResource(R.string.utility_days, days)) }, onClick = {
            prefs.edit().putInt("parkingRetention.$profile", days).apply(); expireParking(prefs); close()
        }) }
    }
}

@Composable
internal fun PrivacyInventory(profile: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = remember { com.cabin.logging.SupportExportQueue(context) }
    var exports by remember { mutableStateOf(emptyList<com.cabin.logging.PendingSupportExport>()) }
    val bookmarks = remember { LogBookmarks(context) }
    val bookmarkValues = rememberAutomationValues("cabin_log_bookmarks")
    val connections = remember { ConnectionHistory(context) }
    val connectionValues = rememberAutomationValues("connection_summaries_v1")
    val driver by TeyesFeaturePreferences.get(context).profile.collectAsState()
    val libraryValues = rememberAutomationValues(com.cabin.launcher.LauncherAppLibrary.FILE)
    val library = remember(driver.slot) { com.cabin.launcher.LauncherAppLibrary(context, driver.slot) }
    val libraryState = remember(driver.slot, libraryValues) {
        com.cabin.launcher.LauncherAppLibrary(context, driver.slot).state.value
    }
    val trips = remember { TripHistory(context) }; val tires = remember { TireHistory(context) }
    val tripValues = rememberAutomationValues("cabin_trips")
    val parking = rememberAutomationValues()
    var revision by remember { mutableIntStateOf(0) }
    var clear by remember(profile, driver.slot) { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) { exports = queue.pending() }
    val prefs = remember { automationPreferences(context) }
    val ledger = remember(profile) { MaintenanceLedger(trips.prefs, profile) }
    SettingsDisclosure(stringResource(R.string.gv_privacy), stringResource(R.string.gv_local_only), searchLabels = emptySet()) {
        Text(stringResource(R.string.gv_privacy_trips, trips.read(profile).size, trips.retention(profile)))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(trips.recording(profile), { trips.setRecording(profile, it) }, enabled = profile > 0,
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_record_trips) })
            Text(stringResource(R.string.gv_record_trips))
        }
        TextButton(onClick = { clear = "trips" }, enabled = trips.read(profile).isNotEmpty()) { Text(stringResource(R.string.tools_clear_trips)) }
        val tireCount = remember(profile, revision) { tires.read(profile).size }
        Text(stringResource(R.string.gv_privacy_tires, tireCount))
        TextButton(onClick = { clear = "tires" }, enabled = tireCount > 0) { Text(stringResource(R.string.history_clear)) }
        Text(stringResource(R.string.gv_privacy_parking, if(parking["parking.$profile"] is String) 1 else 0))
        TextButton(onClick = { clear = "parking" }, enabled = parking["parking.$profile"] is String) { Text(stringResource(R.string.tools_forget_parked)) }
        Text(stringResource(R.string.gv_privacy_service, ledger.history().size))
        TextButton(onClick = { clear = "service" }, enabled = ledger.history().isNotEmpty()) { Text(stringResource(R.string.action_delete)) }
        Text(stringResource(R.string.gv_privacy_profiles))
        Text(stringResource(R.string.gv_privacy_launcher, driver.name, libraryState.usage.size))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(libraryState.recordHistory, { if(!it && libraryState.usage.isNotEmpty()) clear = "disable_launcher" else library.recordHistory(it) },
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_record_launcher) })
            Text(stringResource(R.string.gv_record_launcher))
        }
        TextButton(onClick = { clear = "launcher" }, enabled = libraryState.usage.isNotEmpty()) { Text(stringResource(R.string.gv_clear_launcher)) }
        Text(stringResource(R.string.gv_privacy_bookmarks, remember(bookmarkValues) { bookmarks.list().size }))
        TextButton(onClick = { clear = "bookmarks" }, enabled = bookmarks.list().isNotEmpty()) { Text(stringResource(R.string.action_delete)) }
        Text(stringResource(R.string.gv_privacy_exports, exports.size))
        TextButton(onClick = { clear = "exports" }, enabled = exports.isNotEmpty()) { Text(stringResource(R.string.action_delete)) }
        Text(stringResource(R.string.gv_privacy_logs))
        Text(stringResource(R.string.gv_privacy_connections, remember(connectionValues) { connections.read().size }, connections.retentionDays))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(connections.enabled, { if(!it && connections.read().isNotEmpty()) clear = "disable_connections" else connections.enabled = it },
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_connection_recording) }); Text(stringResource(R.string.gv_connection_recording))
        }
        TextButton(onClick = { clear = "connections" }, enabled = connections.read().isNotEmpty()) { Text(stringResource(R.string.action_delete)) }
    }
    clear?.let { category ->
        val categoryName = stringResource(when(category) {
            "trips" -> R.string.tools_trips; "tires" -> R.string.history_title; "parking" -> R.string.tools_parking_location
            "service" -> R.string.uxv_service_history; "launcher" -> R.string.gv_clear_launcher
            "bookmarks" -> R.string.lxg_bookmarks; "exports" -> R.string.uxv_prepared_exports
            else -> R.string.hub_connection_history
        })
        AlertDialog(onDismissRequest = { clear = null }, title = { Text(when(category) {
            "disable_launcher" -> stringResource(R.string.app_library_disable_history)
            "disable_connections" -> stringResource(R.string.uxv_stop_connection_history)
            else -> stringResource(R.string.uxv_delete_category, categoryName)
        }) }, text = { Text(stringResource(when(category) {
        "disable_launcher" -> R.string.app_library_disable_history_note
        "disable_connections" -> R.string.uxv_stop_connection_history_detail
        "launcher" -> R.string.gv_delete_launcher_confirm
        "connections", "bookmarks", "exports" -> R.string.gv_delete_global_confirm
        else -> R.string.gv_delete_confirm
    })) },
        confirmButton = { TextButton(onClick = {
            when(category) {
                "disable_launcher" -> library.recordHistory(false)
                "disable_connections" -> connections.enabled = false
                "launcher" -> library.clearHistory()
                "connections" -> connections.clear()
                "bookmarks" -> bookmarks.list().forEach { bookmarks.remove(it.key) }
                "exports" -> scope.launch { exports.forEach { queue.discard(it.id) }; exports = queue.pending() }
                "trips" -> trips.clear(profile); "tires" -> tires.clear(profile); "service" -> ledger.clearHistory()
                "parking" -> { prefs.edit().apply { listOf("parking.", "parkingTime.", "parkingNote.", "parkingAccuracy.", "parkingCapture.").forEach { remove(it + profile) }; if(prefs.getInt("saveParking", -1) == profile) { remove("saveParking"); remove("saveParkingTime") } }.apply(); ParkingReminder.cancel(context, profile) }
            }
            revision++; clear = null
        }) { Text(stringResource(R.string.action_delete)) } }, dismissButton = { TextButton(onClick = { clear = null }) { Text(stringResource(R.string.action_cancel)) } }) }
}
