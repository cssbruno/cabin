package com.cabin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Date

@Composable
internal fun TripHistoryBrowser(history: TripHistory, profile: Int, values: Map<String, *>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val units by MeasurementPreferences.get(context).unit.collectAsState()
    var period by rememberSaveable(profile) { mutableStateOf(TripPeriod.ALL) }
    var sort by rememberSaveable(profile) { mutableStateOf(TripSort.NEWEST) }
    var page by remember(profile, period, sort) { mutableIntStateOf(0) }
    var deleted by remember(profile) { mutableStateOf<List<RecordedTrip>>(emptyList()) }
    var selected by remember(profile) { mutableStateOf(setOf<Long>()) }
    var startDate by rememberSaveable(profile) { mutableStateOf("") }
    var endDate by rememberSaveable(profile) { mutableStateOf("") }
    var appliedStart by rememberSaveable(profile) { mutableStateOf("") }
    var appliedEnd by rememberSaveable(profile) { mutableStateOf("") }
    var label by rememberSaveable(profile) { mutableStateOf("") }
    var includeNotes by rememberSaveable(profile) { mutableStateOf(false) }
    var annotationStart by rememberSaveable(profile) { mutableStateOf<Long?>(null) }
    var note by rememberSaveable(profile) { mutableStateOf("") }
    var tripLabel by rememberSaveable(profile) { mutableStateOf("") }
    var bulkDelete by remember(profile) { mutableStateOf(false) }
    var recalculate by remember(profile) { mutableStateOf(false) }
    var compareMonth by rememberSaveable(profile) { mutableStateOf(java.time.YearMonth.now().minusMonths(1).toString()) }
    var secondMonth by rememberSaveable(profile) { mutableStateOf(java.time.YearMonth.now().toString()) }
    var pendingCsv by rememberSaveable { mutableStateOf<String?>(null) }
    var exportPreview by remember(profile) { mutableStateOf(false) }
    var clearConfirm by remember(profile) { mutableStateOf(false) }
    var retentionConfirm by remember(profile) { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<Int?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(60_000) } }
    val all = remember(profile, values["trips.$profile"]) { history.read(profile) }
    val range = remember(appliedStart, appliedEnd) { runCatching { TripDateRange(LocalDate.parse(appliedStart), LocalDate.parse(appliedEnd)) }.getOrNull() }
    val trips = remember(all, period, sort, now, range, label) { filterTripRange(selectTrips(all, if(range == null) period else TripPeriod.ALL, sort, now), range, label) }
    LaunchedEffect(trips) { selected = selected.intersect(trips.map { it.start }.toSet()) }
    val exportTrips = if(selected.isEmpty()) trips else trips.filter { it.start in selected }
    val summary = remember(trips) { summarizeTrips(trips) }
    val pageCount = ((trips.size + 9) / 10).coerceAtLeast(1)
    val visiblePage = page.coerceIn(0, pageCount - 1)
    LaunchedEffect(pageCount) { page = page.coerceIn(0, pageCount - 1) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri != null && csv == null) status = R.string.tools_export_failed
        if (uri != null && csv != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) } != null
                } catch (_: Exception) { false }
            }
            status = if (saved) R.string.utility_export_done else R.string.tools_export_failed
        }
    }
    Row {
        OutlinedTextField(startDate, { startDate = it.take(10) }, label = { Text(stringResource(R.string.gv_from_date)) }, modifier = Modifier.weight(1f), singleLine = true)
        OutlinedTextField(endDate, { endDate = it.take(10) }, label = { Text(stringResource(R.string.gv_to_date)) }, modifier = Modifier.weight(1f), singleLine = true)
    }
    Row {
        TextButton(onClick = { appliedStart = startDate; appliedEnd = endDate; page = 0; selected = emptySet() }, enabled = runCatching { TripDateRange(LocalDate.parse(startDate), LocalDate.parse(endDate)) }.isSuccess) { Text(stringResource(R.string.action_apply)) }
        TextButton(onClick = { startDate = ""; endDate = ""; appliedStart = ""; appliedEnd = ""; selected = emptySet(); page = 0 }) { Text(stringResource(R.string.gv_clear_range)) }
    }
    UtilityChoice(stringResource(R.string.gv_trip_label), tripLabelText(label)) { close ->
        listOf("", "personal", "business").forEach { candidate -> DropdownMenuItem(text = { Text(tripLabelText(candidate)) }, onClick = { label = candidate; selected = emptySet(); close() }) }
    }
    Text(stringResource(R.string.gv_saved_estimated), style = MaterialTheme.typography.bodySmall)
    UtilityChoice(stringResource(R.string.utility_period), stringResource(period.label())) { close ->
        TripPeriod.entries.forEach { candidate ->
            DropdownMenuItem(text = { Text(stringResource(candidate.label())) }, onClick = { period = candidate; close() })
        }
    }
    UtilityChoice(stringResource(R.string.utility_sort), stringResource(sort.label())) { close ->
        TripSort.entries.forEach { candidate ->
            DropdownMenuItem(text = { Text(stringResource(candidate.label())) }, onClick = { sort = candidate; close() })
        }
    }
    if (trips.isEmpty()) Text(stringResource(R.string.tools_no_trips)) else {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.utility_summary, summary.count), style = MaterialTheme.typography.titleMedium)
                Text(MeasurementFormatter.distance(summary.kilometers * 1000, units))
                Text(utilityDuration(summary.seconds))
                summary.averageKph?.let { Text(stringResource(R.string.utility_average, MeasurementFormatter.speed(it, units))) }
                trips.groupBy { it.currency }.forEach { (currency, records) ->
                    val costs = summarizeTrips(records)
                    costs.knownCost?.let { Text(stringResource(R.string.utility_known_cost, utilityNumber(it) + " " + currency, costs.costCount, costs.count)) }
                }
            }
        }
        trips.drop(visiblePage * 10).take(10).forEach { trip ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(trip.start)))
                    Text("${MeasurementFormatter.distance(trip.kilometers * 1000, units)} · ${utilityDuration(trip.seconds)}")
                    trip.estimatedCost?.let { Text(stringResource(R.string.tools_cost_row, utilityNumber(it) + " " + trip.currency)) }
                    FlowRow(verticalArrangement = Arrangement.Center) {
                        val tripSelectionLabel = stringResource(R.string.uxv_select_trip, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(trip.start)))
                        Checkbox(trip.start in selected, { selected = if(it) selected + trip.start else selected - trip.start }, modifier = Modifier.semantics { contentDescription = tripSelectionLabel })
                        TextButton(onClick = { annotationStart = trip.start; note = trip.note; tripLabel = trip.label }) { Text(stringResource(R.string.gv_note)) }
                        TextButton(onClick = { history.delete(profile, trip.start); selected = selected - trip.start; deleted = listOf(trip) }) { Text(stringResource(R.string.action_delete)) }
                    }
                    if(trip.label.isNotBlank()) Text(tripLabelText(trip.label))
                    if(trip.note.isNotBlank()) Text(trip.note)
                    if(trip.fuelRate != null && trip.fuelPrice != null) Text("${trip.fuelRate} L/100 km × ${trip.fuelPrice} ${trip.currency}/L")
                }
            }
        }
        if (pageCount > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { page = visiblePage - 1 }, enabled = visiblePage > 0) { Text(stringResource(R.string.utility_previous)) }
            Text(stringResource(R.string.utility_page, visiblePage + 1, pageCount), Modifier.padding(top = 12.dp))
            TextButton(onClick = { page = visiblePage + 1 }, enabled = visiblePage < pageCount - 1) { Text(stringResource(R.string.utility_next)) }
        }
    }
    if(deleted.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.utility_trip_deleted), Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { history.restoreSelected(profile, deleted); deleted = emptyList() }) { Text(stringResource(R.string.utility_undo)) }
        }
    }
    if(selected.isNotEmpty()) {
        Text(stringResource(R.string.gv_selected, selected.size))
        TextButton(onClick = { bulkDelete = true }) { Text(stringResource(R.string.action_delete)) }
        TextButton(onClick = { selected = emptySet() }) { Text(stringResource(R.string.action_cancel)) }
    }
    OutlinedButton(onClick = { exportPreview = true }, enabled = trips.isNotEmpty()) { Text(stringResource(R.string.utility_export)) }
    val fuelReady = tripFuelEstimate(1.0, history.fuelValue(profile, "fuelRate"), history.fuelValue(profile, "fuelPrice")) != null
    TextButton(onClick = { recalculate = true }, enabled = fuelReady && all.isNotEmpty()) {
        Text(stringResource(R.string.utility_recalculate))
    }
    UtilityChoice(stringResource(R.string.utility_retention), history.retention(profile).toString(), enabled = profile > 0) { close ->
        TripHistory.retentionChoices.forEach { limit ->
            DropdownMenuItem(text = { Text(limit.toString()) }, onClick = {
                if (all.size > limit) retentionConfirm = limit else history.setRetention(profile, limit)
                close()
            })
        }
    }
    TextButton(onClick = { clearConfirm = true }, enabled = all.isNotEmpty()) { Text(stringResource(R.string.tools_clear_trips)) }
    status?.let { Text(stringResource(it)) }
    if (exportPreview) AlertDialog(onDismissRequest = { exportPreview = false },
        title = { Text(stringResource(R.string.utility_export)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(stringResource(R.string.utility_export_detail, exportTrips.size)); Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(includeNotes, { includeNotes = it }, modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_include_notes) }); Text(stringResource(R.string.gv_include_notes)) } } },
        confirmButton = { TextButton(onClick = {
            exportPreview = false
            pendingCsv = tripCsvDetailed(exportTrips, includeNotes)
            try { export.launch("cabin-trips-${LocalDate.now()}.csv") }
            catch (_: RuntimeException) { pendingCsv = null; status = R.string.uxv_document_picker_missing }
        }) { Text(stringResource(R.string.action_export)) } },
        dismissButton = { TextButton(onClick = { exportPreview = false }) { Text(stringResource(R.string.action_cancel)) } })
    SettingsDisclosure(stringResource(R.string.gv_compare_months), stringResource(R.string.gv_retained_only), searchLabels = emptySet()) {
        OutlinedTextField(compareMonth, { compareMonth = it.take(7) }, label = { Text("YYYY-MM") }, singleLine = true)
        OutlinedTextField(secondMonth, { secondMonth = it.take(7) }, label = { Text("YYYY-MM") }, singleLine = true)
        listOf(compareMonth, secondMonth).forEach { value -> runCatching { java.time.YearMonth.parse(value) }.getOrNull()?.let { month ->
            val monthly = filterTripRange(all, TripDateRange(month.atDay(1), month.atEndOfMonth()))
            val totals = summarizeTrips(monthly)
            Text("$month · ${totals.count} · ${MeasurementFormatter.distance(totals.kilometers * 1000, units)} · ${utilityDuration(totals.seconds)}")
            monthly.groupBy { it.currency }.forEach { (currency, records) -> val known = summarizeTrips(records)
                Text(stringResource(R.string.utility_known_cost, utilityNumber(known.knownCost ?: 0.0) + " " + currency, known.costCount, known.count))
            }
        } }
    }
    if(recalculate || bulkDelete) AlertDialog(onDismissRequest = { recalculate = false; bulkDelete = false },
        title = { Text(stringResource(if(recalculate) R.string.utility_recalculate else R.string.action_delete)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.gv_selected, if(recalculate) all.size else trips.count { it.start in selected }))
            if(recalculate) all.forEach { trip -> Text("${DateFormat.getDateInstance().format(Date(trip.start))}: ${trip.estimatedCost ?: "—"} → ${history.estimate(profile, trip).estimatedCost ?: "—"} ${history.fuelValue(profile, "fuelCurrency").orEmpty()}") }
        } },
        confirmButton = { TextButton(onClick = {
            if(recalculate) { if(history.recalculateCosts(profile)) status = R.string.utility_cost_updated }
            else { deleted = history.deleteSelected(profile, trips.map { it.start }.toSet().intersect(selected)); selected = emptySet() }
            recalculate = false; bulkDelete = false
        }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = { recalculate = false; bulkDelete = false }) { Text(stringResource(R.string.action_cancel)) } })
    all.firstOrNull { it.start == annotationStart }?.let { trip -> AlertDialog(onDismissRequest = { annotationStart = null }, title = { Text(stringResource(R.string.gv_note)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UtilityChoice(stringResource(R.string.gv_trip_label), tripLabelText(tripLabel)) { close ->
                listOf("", "personal", "business").forEach { candidate -> DropdownMenuItem(text = { Text(tripLabelText(candidate)) }, onClick = { tripLabel = candidate; close() }) }
            }
            OutlinedTextField(note, { note = it.take(500) }, label = { Text(stringResource(R.string.gv_note)) }, maxLines = 4)
        }
    }, confirmButton = { TextButton(onClick = { history.annotate(profile, trip.start, tripLabel, note); annotationStart = null }) { Text(stringResource(R.string.action_apply)) } }, dismissButton = { TextButton(onClick = { annotationStart = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    if (clearConfirm || retentionConfirm != null) AlertDialog(onDismissRequest = { clearConfirm = false; retentionConfirm = null },
        title = { Text(stringResource(if (clearConfirm) R.string.tools_clear_trips else R.string.utility_retention)) },
        text = { Text(if (clearConfirm) stringResource(R.string.utility_clear_confirm, all.size)
            else stringResource(R.string.utility_retention_confirm, retentionConfirm ?: 100)) },
        confirmButton = { TextButton(onClick = {
            if (clearConfirm) { history.clear(profile); deleted = emptyList(); selected = emptySet() }
            else retentionConfirm?.let { history.setRetention(profile, it); deleted = emptyList(); selected = emptySet() }
            clearConfirm = false; retentionConfirm = null
        }) { Text(stringResource(R.string.action_apply)) } },
        dismissButton = { TextButton(onClick = { clearConfirm = false; retentionConfirm = null }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun UtilityChoice(label: String, value: String, enabled: Boolean = true, choices: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) { Text(value) }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) { choices { expanded = false } }
        }
    }
}

@Composable
internal fun utilityDuration(seconds: Long): String = stringResource(R.string.utility_duration, seconds / 3600, seconds % 3600 / 60, seconds % 60)
internal fun utilityNumber(value: Double): String = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(value)
private fun TripPeriod.label(): Int = when (this) {
    TripPeriod.ALL -> R.string.utility_all
    TripPeriod.TODAY -> R.string.utility_today
    TripPeriod.WEEK -> R.string.utility_week
    TripPeriod.MONTH -> R.string.utility_month
}
private fun TripSort.label(): Int = when (this) {
    TripSort.NEWEST -> R.string.utility_newest
    TripSort.DISTANCE -> R.string.utility_distance
    TripSort.DURATION -> R.string.utility_duration_sort
    TripSort.COST -> R.string.utility_cost_sort
}

@Composable private fun tripLabelText(value: String): String = stringResource(when(value) { "personal" -> R.string.gv_personal; "business" -> R.string.gv_business; else -> R.string.gv_all_labels })
