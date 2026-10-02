package com.cabin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

@Composable
internal fun MaintenanceItemsPanel(profile: Int, history: TripHistory, values: Map<String, *>) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val ledger = remember(profile) { MaintenanceLedger(history.prefs, profile) }
    val items = remember(profile, values) { ledger.items() }
    var name by rememberSaveable(profile) { mutableStateOf("") }; var date by rememberSaveable(profile) { mutableStateOf(LocalDate.now().plusDays(90).toString()) }
    var interval by rememberSaveable(profile) { mutableStateOf("90") }; var kmInterval by rememberSaveable(profile) { mutableStateOf("") }
    var reading by rememberSaveable(profile) { mutableStateOf("") }; var readingDate by rememberSaveable(profile) { mutableStateOf(LocalDate.now().toString()) }
    var selected by rememberSaveable(profile) { mutableStateOf<String?>(null) }; var correctionId by rememberSaveable(profile) { mutableStateOf<String?>(null) }
    val correction = ledger.history().firstOrNull { it.id == correctionId }
    var note by rememberSaveable(profile) { mutableStateOf("") }; var cost by rememberSaveable(profile) { mutableStateOf("") }
    var serviceDate by rememberSaveable(profile) { mutableStateOf(LocalDate.now().toString()) }
    var error by remember(profile) { mutableStateOf(false) }
    var itemAttempted by remember(profile) { mutableStateOf(false) }
    var readingAttempted by remember(profile) { mutableStateOf(false) }
    var serviceAttempted by remember(profile) { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }; val dueFocus = remember { FocusRequester() }
    val intervalFocus = remember { FocusRequester() }; val distanceFocus = remember { FocusRequester() }
    val readingFocus = remember { FocusRequester() }; val readingDateFocus = remember { FocusRequester() }
    val serviceDateFocus = remember { FocusRequester() }; val costFocus = remember { FocusRequester() }
    val dueDay = runCatching { LocalDate.parse(date) }.getOrNull()
    val intervalDays = interval.toIntOrNull()?.takeIf { it in 1..3650 }
    val distance = maintenanceDecimal(kmInterval)?.takeIf { it > 0 }
    val odometer = ledger.odometer()
    val distanceError = when {
        kmInterval.isBlank() -> null
        distance == null || (odometer?.kilometers ?: 0.0) + distance > 10_000_000.0 -> R.string.uxvf_distance_error
        odometer == null -> R.string.uxvf_odometer_needed
        else -> null
    }
    val readingKm = maintenanceDecimal(reading)
    val readingDay = runCatching { LocalDate.parse(readingDate) }.getOrNull()
    val readingError = when {
        readingKm == null -> R.string.uxvf_decimal_error
        odometer != null && readingKm < odometer.kilometers -> R.string.uxvf_odometer_order
        else -> null
    }
    val readingDateError = when {
        readingDay == null -> R.string.uxvf_date_error
        readingDay.isAfter(LocalDate.now()) -> R.string.uxvf_future_reading
        odometer != null && readingDay.isBefore(odometer.date) -> R.string.uxvf_reading_date_order
        else -> null
    }
    var deleteId by remember(profile) { mutableStateOf<String?>(null) }
    var pendingCsv by rememberSaveable { mutableStateOf<String?>(null) }
    var exportStatus by remember { mutableStateOf<Int?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if(uri != null && csv != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(csv) } }.isSuccess }
            exportStatus = if(saved) R.string.uxv_service_export_saved else R.string.tools_export_failed
        } else if(uri != null) exportStatus = R.string.tools_export_failed
    }
    SettingsDisclosure(stringResource(R.string.tools_maintenance), stringResource(R.string.gv_manual), searchLabels = emptySet()) {
        if(items.isEmpty()) Text(stringResource(R.string.uxv_maintenance_empty), style = MaterialTheme.typography.bodySmall)
        items.forEach { item ->
            Text("${item.name} · ${item.due}", style = MaterialTheme.typography.titleMedium)
            item.dueKm?.let { Text(stringResource(R.string.gv_due_km, utilityNumber(it))) }
            if(item.due <= LocalDate.now() || item.dueKm?.let { (ledger.odometer()?.kilometers ?: -1.0) >= it } == true) Text(stringResource(R.string.alert_service_due))
            FlowRow {
                TextButton(onClick = { selected = item.id; correctionId = null; error = false; serviceAttempted = false; note = ""; cost = ""; serviceDate = LocalDate.now().toString() }) { Text(stringResource(R.string.utility_complete_service)) }
                TextButton(onClick = { ledger.snooze(item.id) }) { Text(stringResource(R.string.utility_snooze, 7)) }
                TextButton(onClick = { deleteId = item.id }) { Text(stringResource(R.string.action_delete)) }
            }
        }
        OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.gv_name)) }, singleLine = true,
            isError = itemAttempted && name.isBlank(), supportingText = { if(itemAttempted && name.isBlank()) Text(stringResource(R.string.uxvf_name_error)) },
            modifier = Modifier.fillMaxWidth().focusRequester(nameFocus))
        OutlinedTextField(date, { date = it.take(10) }, label = { Text(stringResource(R.string.uxvf_due_date)) }, singleLine = true,
            isError = itemAttempted && dueDay == null, supportingText = { if(itemAttempted && dueDay == null) Text(stringResource(R.string.uxvf_date_error)) },
            modifier = Modifier.fillMaxWidth().focusRequester(dueFocus))
        OutlinedTextField(interval, { interval = it.take(4) }, label = { Text(stringResource(R.string.gv_interval_days)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = itemAttempted && intervalDays == null,
            supportingText = { if(itemAttempted && intervalDays == null) Text(stringResource(R.string.uxvf_interval_error)) }, modifier = Modifier.fillMaxWidth().focusRequester(intervalFocus))
        OutlinedTextField(kmInterval, { kmInterval = it.take(12) }, label = { Text(stringResource(R.string.gv_interval_km)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = itemAttempted && distanceError != null,
            supportingText = { Text(stringResource(if(itemAttempted) distanceError ?: R.string.uxvf_decimal_hint else R.string.uxvf_decimal_hint)) }, modifier = Modifier.fillMaxWidth().focusRequester(distanceFocus))
        TextButton(enabled = profile > 0 && items.size < 30, onClick = {
            itemAttempted = true; error = false
            when {
                name.isBlank() -> nameFocus.requestFocus()
                dueDay == null -> dueFocus.requestFocus()
                intervalDays == null -> intervalFocus.requestFocus()
                distanceError != null -> distanceFocus.requestFocus()
                else -> error = runCatching {
                    ledger.save(MaintenanceItem(UUID.randomUUID().toString(), name.trim(), dueDay, intervalDays,
                        if(kmInterval.isBlank()) null else checkNotNull(odometer).kilometers + checkNotNull(distance), if(kmInterval.isBlank()) null else distance))
                    name = ""; itemAttempted = false
                }.isFailure
            }
        }) { Text(stringResource(R.string.gv_add_item)) }
        HorizontalDivider()
        Text(stringResource(R.string.gv_manual_odometer))
        odometer?.let { Text("${it.date} · ${utilityNumber(it.kilometers)} km") }
        OutlinedTextField(reading, { reading = it.take(12) }, label = { Text("km") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = readingAttempted && readingError != null,
            supportingText = { Text(stringResource(if(readingAttempted) readingError ?: R.string.uxvf_decimal_hint else R.string.uxvf_decimal_hint)) }, modifier = Modifier.fillMaxWidth().focusRequester(readingFocus))
        OutlinedTextField(readingDate, { readingDate = it.take(10) }, label = { Text(stringResource(R.string.uxvf_odometer_date)) }, singleLine = true,
            isError = readingAttempted && readingDateError != null, supportingText = { if(readingAttempted) readingDateError?.let { Text(stringResource(it)) } },
            modifier = Modifier.fillMaxWidth().focusRequester(readingDateFocus))
        TextButton(onClick = {
            readingAttempted = true; error = false
            when {
                readingError != null -> readingFocus.requestFocus()
                readingDateError != null -> readingDateFocus.requestFocus()
                else -> error = runCatching { ledger.recordOdometer(ManualOdometer(checkNotNull(readingDay), checkNotNull(readingKm))); reading = ""; readingAttempted = false }.isFailure
            }
        }, enabled = profile > 0) { Text(stringResource(R.string.action_apply)) }
        HorizontalDivider()
        ledger.history().asReversed().forEach { entry ->
            Text("${entry.date} · ${entry.name} · ${entry.cost?.let(::utilityNumber).orEmpty()}")
            if(entry.note.isNotEmpty()) Text(entry.note)
            TextButton(onClick = { error = false; serviceAttempted = false; correctionId = entry.id; selected = entry.itemId; note = entry.note; cost = entry.cost?.toString().orEmpty(); serviceDate = entry.date.toString() }) { Text(stringResource(R.string.gv_correct)) }
        }
        TextButton(onClick = {
            pendingCsv = ledger.historyCsv()
            exportStatus = null
            try { export.launch("cabin-service-history.csv") } catch (_: RuntimeException) { pendingCsv = null; exportStatus = R.string.uxv_document_picker_missing }
        }, enabled = ledger.history().isNotEmpty()) { Text(stringResource(R.string.action_export)) }
        exportStatus?.let { SettingsNotice(stringResource(it), error = it != R.string.uxv_service_export_saved) }
        if(error) SettingsNotice(stringResource(R.string.gv_invalid), error = true)
    }
    selected?.let { id ->
        val serviceDay = runCatching { LocalDate.parse(serviceDate) }.getOrNull()
        val amount = maintenanceDecimal(cost)
        val invalidCost = cost.isNotBlank() && amount == null
        val available = if(correctionId != null) correction != null else items.any { it.id == id }
        // Text editors need a bounded body rather than AlertDialog intrinsic measurement,
        // especially on short head units with enlarged text or an on-screen keyboard.
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(.9f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if(correctionId == null) R.string.utility_complete_service else R.string.gv_correct), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(serviceDate, { serviceDate = it.take(10) }, label = { Text(stringResource(R.string.uxvf_service_date)) }, singleLine = true,
                            isError = serviceAttempted && serviceDay == null, supportingText = { if(serviceAttempted && serviceDay == null) Text(stringResource(R.string.uxvf_date_error)) },
                            modifier = Modifier.fillMaxWidth().focusRequester(serviceDateFocus))
                        OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.gv_note)) }, maxLines = 4, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(cost, { cost = it.take(12) }, label = { Text(stringResource(R.string.utility_cost_sort)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = serviceAttempted && invalidCost,
                            supportingText = { Text(stringResource(if(serviceAttempted && invalidCost) R.string.uxvf_decimal_error else R.string.uxvf_decimal_hint)) }, modifier = Modifier.fillMaxWidth().focusRequester(costFocus))
                        if(!available) SettingsNotice(stringResource(R.string.uxvf_record_missing), error = true)
                        if(error) SettingsNotice(stringResource(R.string.gv_invalid), error = true)
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(enabled = available, onClick = {
                            serviceAttempted = true; error = false
                            when {
                                serviceDay == null -> serviceDateFocus.requestFocus()
                                invalidCost -> costFocus.requestFocus()
                                else -> error = runCatching {
                                    // Re-read at apply time. A removed correction must never turn into a new completion.
                                    if(correctionId != null) {
                                        val original = checkNotNull(ledger.history().firstOrNull { it.id == correctionId })
                                        ledger.correct(original.copy(date = serviceDay, note = note, cost = amount))
                                    } else ledger.complete(id, note, amount, serviceDay)
                                    selected = null
                                }.isFailure
                            }
                        }) { Text(stringResource(R.string.action_apply)) }
                    }
                }
            }
        }
    }
    items.firstOrNull { it.id == deleteId }?.let { item ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.uxv_delete_maintenance, item.name)) },
            confirmButton = { TextButton(onClick = { ledger.delete(item.id); deleteId = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
