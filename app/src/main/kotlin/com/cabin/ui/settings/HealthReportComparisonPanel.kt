package com.cabin.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HealthReportComparisonPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf<String?>(null) }
    var second by remember { mutableStateOf<String?>(null) }
    var slot by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var pickerUnavailable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var differences by remember { mutableStateOf<List<ReportDifference>?>(null) }
    var showComparison by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf("changed") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; failed = false
            try {
                val report = readHealthReport(context, uri)
                if (slot == 0) first = report else second = report
                if (first != null && second != null) {
                    differences = withContext(Dispatchers.IO) { compareHealthReports(first!!, second!!) }
                    category = "changed"
                    showComparison = true
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true; differences = null }
            finally { busy = false }
        }
    }
    fun chooseReport(target: Int) {
        slot = target; pickerUnavailable = false
        try { picker.launch(arrayOf("application/json", "text/plain")) }
        catch (_: ActivityNotFoundException) { pickerUnavailable = true }
        catch (_: SecurityException) { pickerUnavailable = true }
    }
    OutlinedCard(Modifier.fillMaxWidth().settingsSearchAnchor(stringResource(R.string.lxg_compare_reports))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lxg_compare_reports), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { chooseReport(0) }) { Text(stringResource(if (first == null) R.string.lxg_first_report else R.string.lxg_replace_first)) }
                OutlinedButton(enabled = !busy, onClick = { chooseReport(1) }) { Text(stringResource(if (second == null) R.string.lxg_second_report else R.string.lxg_replace_second)) }
                if (differences != null) TextButton(enabled = !busy, onClick = { showComparison = true }) { Text(stringResource(R.string.ux_support_show_comparison)) }
                if (first != null || second != null) TextButton(enabled = !busy, onClick = { first = null; second = null; differences = null; showComparison = false; failed = false; pickerUnavailable = false; category = "changed" }) { Text(stringResource(R.string.ux_support_clear_reports)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(stringResource(R.string.lxg_report_invalid), color = MaterialTheme.colorScheme.error)
            if (pickerUnavailable) Text(stringResource(R.string.ux_support_picker_unavailable), color = MaterialTheme.colorScheme.error)
        }
    }
    differences?.takeIf { showComparison }?.let { list -> AlertDialog(onDismissRequest = { showComparison = false }, title = { Text(stringResource(R.string.lxg_compare_reports)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item(key = "categories") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("changed" to R.string.lxg_changed, "missing" to R.string.lxg_missing, "unchanged" to R.string.lxg_unchanged).forEach { (kind, label) ->
                    FilterChip(category == kind, { category = kind }, label = { Text("${stringResource(label)} (${list.count { it.kind == kind }})") })
                } }
            }
            val filtered = list.filter { it.kind == category }
            if (filtered.isEmpty()) item(key = "empty") { Text(stringResource(R.string.ux_support_no_category_rows), Modifier.padding(vertical = 12.dp)) }
            items(filtered, key = { it.field }) { item ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(item.field, style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.lxg_before, item.before?.take(2000) ?: "—"))
                    Text(stringResource(R.string.lxg_after, item.after?.take(2000) ?: "—"))
                    HorizontalDivider()
                }
            }
        } }, confirmButton = { TextButton({ showComparison = false }) { Text(stringResource(R.string.logs_close_viewer)) } }) }
}
