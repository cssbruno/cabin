package com.cabin.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.R
import com.cabin.logging.FileExportService
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LogFileViewer(file: File, store: LogFilesStore, onClose: () -> Unit) {
    var original by rememberSaveable(file.path) { mutableStateOf(false) }
    var query by rememberSaveable(file.path) { mutableStateOf("") }
    var tag by rememberSaveable(file.path) { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(LogSeverity.ALL) }
    var newest by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var controls by rememberSaveable { mutableStateOf(false) }
    var numbers by rememberSaveable { mutableStateOf(true) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var fontSize by rememberSaveable { mutableIntStateOf(14) }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember(file) { mutableStateOf(LogSearchResult()) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var exportName by rememberSaveable(file.path) { mutableStateOf<String?>(null) }
    var preparingExport by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    val context = LocalContext.current
    val resources = LocalResources.current
    val queue = remember(context) { com.cabin.logging.SupportExportQueue(context) }
    var recoveryRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        scope.launch {
            val pending = queue.pending().firstOrNull { it.id == exportName }
            try {
                if (uri != null && pending != null) {
                    val saved = FileExportService.writeFileToUri(context, uri, pending.file).isSuccess
                    exportStatus = resources.getString(if (saved) R.string.logx_export_done else R.string.logx_export_failed)
                    if (saved) queue.discard(pending.id)
                }
            } finally { exportName = null; recoveryRefresh++ }
        }
    }
    LaunchedEffect(file, query, tag, severity, newest, refresh, original, lifecycle) {
        if (original) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            busy = true
            failed = false
            try {
                delay(200)
                result = store.search(file, LogQuery(query, tag, severity, newest))
                // Schedule positioning for the next layout after the new data is applied.
                if (!follow || newest) scroll.requestScrollToItem(0)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true; result = LogSearchResult() }
            finally { busy = false }
        }
    }
    LaunchedEffect(file, follow, original, lifecycle) {
        if (follow && !original) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { delay(3_000); refresh++ }
        }
    }
    Dialog(onDismissRequest = { if (original) original = false else onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
          if (original) {
            OriginalLogFileContent(file, store, onClose = { original = false })
          } else BoxWithConstraints(Modifier.fillMaxSize()) {
            val controlsHeight = maxHeight * .55f
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.logs_close_viewer)) }
                }
                Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(query, { query = it.take(200) }, label = { Text(stringResource(R.string.logx_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(controls, { controls = !controls }, label = { Text(stringResource(R.string.logx_filters)) })
                        FilterChip(follow, { follow = !follow; if (follow) newest = true }, label = { Text(stringResource(R.string.logx_follow)) })
                        TextButton(onClick = { refresh++ }, enabled = !busy) { Text(stringResource(R.string.logs_refresh)) }
                        TextButton(onClick = { follow = false; original = true }) { Text(stringResource(R.string.logx_original)) }
                    }
                    if (controls) {
                        OutlinedTextField(tag, { tag = it.take(100) }, label = { Text(stringResource(R.string.logx_tag)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LogSeverity.entries.forEach { value ->
                                FilterChip(severity == value, { severity = value }, label = { Text(stringResource(value.label())) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(newest, { newest = !newest; if (!newest) follow = false }, label = { Text(stringResource(R.string.logx_newest)) })
                            FilterChip(numbers, { numbers = !numbers }, label = { Text(stringResource(R.string.logx_numbers)) })
                            FilterChip(wrap, { wrap = !wrap }, label = { Text(stringResource(R.string.logx_wrap)) })
                            TextButton({ fontSize = (fontSize - 2).coerceAtLeast(10) }, enabled = fontSize > 10) { Text(stringResource(R.string.logx_smaller)) }
                            TextButton({ fontSize = (fontSize + 2).coerceAtMost(24) }, enabled = fontSize < 24) { Text(stringResource(R.string.logx_larger)) }
                        }
                        Text(stringResource(R.string.logx_export_scope), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false, true).forEach { metadata ->
                                TextButton(enabled = !busy && result.lines.isNotEmpty() && exportName == null && !preparingExport, onClick = {
                                    val frozen = result.export(metadata)
                                    preparingExport = true; exportStatus = ""
                                    scope.launch {
                                        try {
                                            val prepared = queue.enqueueText(if (metadata) "cabin-log-metadata.txt" else "cabin-filtered-log.txt", frozen, "text/plain")
                                            exportName = prepared.id
                                            try { export.launch(prepared.name) }
                                            catch (_: ActivityNotFoundException) { exportName = null; exportStatus = resources.getString(R.string.uxf_export_saved_locally) }
                                            catch (_: SecurityException) { exportName = null; exportStatus = resources.getString(R.string.uxf_export_saved_locally) }
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { exportStatus = resources.getString(R.string.logx_export_failed) }
                                        finally { preparingExport = false; recoveryRefresh++ }
                                    }
                                }) { Text(stringResource(if (metadata) R.string.logx_metadata_export else R.string.logx_export)) }
                            }
                        }
                        if (exportStatus.isNotEmpty()) Text(exportStatus)
                        SupportExportRecovery(recoveryRefresh)
                    }
                }
                Text(stringResource(R.string.logx_count, result.lines.size, result.matched, result.scanned), style = MaterialTheme.typography.labelSmall)
                if (result.resultsLimited || result.inputLimited || result.linesShortened) Text(stringResource(R.string.logx_limited), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    failed -> Text(stringResource(R.string.logs_read_failed))
                    !busy && result.lines.isEmpty() -> Text(stringResource(R.string.logx_no_matches))
                    else -> SelectionContainer(Modifier.weight(1f)) {
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
                            items(result.lines, key = { it.number }) { line ->
                                Text(if (numbers) "${line.number}  ${line.text}" else line.text,
                                    fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, softWrap = wrap,
                                    color = when (line.severity) { "E" -> MaterialTheme.colorScheme.error; "W" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurface })
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

internal fun LogSeverity.label() = when (this) {
    LogSeverity.ALL -> R.string.logx_all
    LogSeverity.ERROR -> R.string.logx_errors
    LogSeverity.WARNING -> R.string.logx_warnings
    LogSeverity.INFO -> R.string.logx_info
    LogSeverity.DEBUG -> R.string.logx_debug
    LogSeverity.VERBOSE -> R.string.logx_verbose
}

@Composable
internal fun LogLibraryControls(query: String, onQuery: (String) -> Unit, order: LogFileOrder, onOrder: (LogFileOrder) -> Unit) {
    OutlinedTextField(query, { onQuery(it.take(100)) }, singleLine = true, label = { Text(stringResource(R.string.logx_file_search)) }, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogFileOrder.entries.forEach { value ->
            FilterChip(order == value, { onOrder(value) }, label = { Text(stringResource(when (value) {
                LogFileOrder.NEWEST -> R.string.logx_newest
                LogFileOrder.OLDEST -> R.string.logx_oldest
                LogFileOrder.LARGEST -> R.string.logx_largest
                LogFileOrder.NAME -> R.string.logx_name
            })) })
        }
    }
}
