package com.cabin.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import com.cabin.R

/** The exact frozen JSON is always copied/saved; search and section selection only change the preview. */
@Composable
internal fun HealthReportPreview(
    report: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember(report) { mutableStateOf("") }
    var section by remember(report) { mutableStateOf<String?>(null) }
    var sectionsExpanded by remember { mutableStateOf(false) }
    var copied by remember(report) { mutableStateOf(false) }
    val sections = remember(report) { healthReportSections(report) }
    val content = remember(report, section) { healthReportSection(report, section) }
    val lines = remember(content, query) { searchHealthReport(content, query) }
    // A full-size bounded dialog gives the searchable document a stable viewport on
    // short head units; wrap-content alert measurement can oscillate with the editor.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.health_review), style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.health_review_detail))
                    Text(stringResource(R.string.px_report_filter_detail))
                    OutlinedTextField(query, { query = it.take(200) }, singleLine = true,
                        label = { Text(stringResource(R.string.px_report_search)) }, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sections.isNotEmpty()) {
                            Box {
                                OutlinedButton(onClick = { sectionsExpanded = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                                    Text(section ?: stringResource(R.string.px_report_all_sections))
                                }
                                DropdownMenu(expanded = sectionsExpanded, onDismissRequest = { sectionsExpanded = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.px_report_all_sections)) },
                                        onClick = { section = null; sectionsExpanded = false })
                                    sections.forEach { key ->
                                        DropdownMenuItem(text = { Text(key) }, onClick = { section = key; sectionsExpanded = false })
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.health_review), report))
                            copied = clipboard != null
                        }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(if (copied) R.string.px_report_copied else R.string.px_report_copy))
                        }
                        if (query.isNotEmpty() || section != null) {
                            TextButton(onClick = { query = ""; section = null }, modifier = Modifier.heightIn(min = 56.dp)) {
                                Text(stringResource(R.string.px_clear_filters))
                            }
                        }
                    }
                    if (query.isNotBlank()) Text(stringResource(R.string.px_report_matches, lines.size))
                    if (lines.isEmpty()) Text(stringResource(R.string.px_report_no_matches))
                    else SelectionContainer { Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace) }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.health_choose_save)) }
                }
            }
        }
    }
}

@Composable
internal fun DurableHealthReportPreview(report: String, onClose: () -> Unit, onParkedAction: (() -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val queue = remember(context) { com.cabin.logging.SupportExportQueue(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pendingId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        scope.launch {
            val pending = queue.pending().firstOrNull { it.id == pendingId }
            if (uri != null && pending != null) {
                val result = com.cabin.logging.FileExportService.writeFileToUri(context, uri, pending.file)
                if (result.isSuccess) queue.discard(pending.id)
                android.widget.Toast.makeText(context, context.getString(if (result.isSuccess) R.string.logx_export_done else R.string.logx_export_failed), android.widget.Toast.LENGTH_LONG).show()
            }
            pendingId = null
            onClose()
        }
    }
    HealthReportPreview(report, onSave = { onParkedAction {
        if (!preparing) {
            preparing = true
            scope.launch {
                try {
                    val pending = queue.enqueueText("cabin-health.json", report)
                    pendingId = pending.id
                    picker.launch(pending.name)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { android.widget.Toast.makeText(context, R.string.logx_export_failed, android.widget.Toast.LENGTH_LONG).show() }
                finally { preparing = false }
            }
        }
    } }, onCancel = onClose)
}
