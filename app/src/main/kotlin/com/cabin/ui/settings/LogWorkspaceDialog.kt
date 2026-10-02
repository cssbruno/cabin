package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.R
import com.cabin.logging.SupportExportQueue
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

private data class WorkspaceInputs(
    val text: String, val from: String, val until: String,
    val severity: LogSeverity, val files: Set<String>,
) {
    fun query(zone: String): WorkspaceQuery {
        fun parse(value: String): Long? {
            if (value.isBlank()) return null
            val local = try { LocalDateTime.parse(value.trim()) }
                catch (error: java.time.format.DateTimeParseException) { throw IllegalArgumentException("Invalid date/time", error) }
            val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
            require(offsets.size == 1)
            return local.toInstant(offsets.single()).toEpochMilli()
        }
        return WorkspaceQuery(text, severity, parse(from), parse(until), zone)
    }
}

@Composable
internal fun LogWorkspaceDialog(store: LogFilesStore, logging: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val queue = remember { SupportExportQueue(context) }
    val bookmarks = remember { LogBookmarks(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var files by remember { mutableStateOf(emptyList<LogFileSnapshot>()) }
    var selected by rememberSaveable(stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })) { mutableStateOf(emptySet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(LogSeverity.ALL) }
    val zone = remember { ZoneId.systemDefault().id }
    var appliedInputs by remember { mutableStateOf<WorkspaceInputs?>(null) }
    var liveResults by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var filesLoaded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyList<WorkspaceRow>()) }
    var cursor by remember { mutableStateOf<WorkspaceCursor?>(null) }
    var frozenSources by remember { mutableStateOf(emptyList<LogSource>()) }
    var frozenQuery by remember { mutableStateOf(WorkspaceQuery()) }
    var scanned by remember { mutableIntStateOf(0) }
    var unknown by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf(emptyMap<String, Int>()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var contextMatch by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var detail by remember { mutableStateOf(emptyList<WorkspaceRow>()) }
    var markList by remember { mutableStateOf(bookmarks.list()) }
    var showMarks by remember { mutableStateOf(false) }
    var exportRefresh by remember { mutableIntStateOf(0) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var chooseFiles by rememberSaveable { mutableStateOf(true) }
    val resultsScroll = rememberLazyListState()
    fun inputs() = WorkspaceInputs(query, from, until, severity, selected.toSet())
    val inputsChanged = appliedInputs?.let { it != inputs() } == true
    LaunchedEffect(inputsChanged, error, liveResults, follow) {
        if (inputsChanged || error != null || (liveResults && !follow)) resultsScroll.requestScrollToItem(0)
    }
    fun run(block: suspend () -> Unit) {
        val operation = ++generation
        job?.cancel()
        job = scope.launch {
            busy = true; error = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: LogSourceChanged) { error = R.string.lxg_source_changed; cursor = null; frozenSources = emptyList(); appliedInputs = null }
            catch (_: IllegalArgumentException) { error = R.string.lxg_invalid_range }
            catch (_: Exception) { error = R.string.logs_read_failed }
            finally { if (operation == generation) busy = false }
        }
    }
    fun accept(page: WorkspacePage, reset: Boolean) {
        hasSearched = true
        resultsScroll.requestScrollToItem(0)
        rows = page.rows; cursor = page.next
        scanned = (if (reset) 0 else scanned) + page.scanned
        unknown = (if (reset) 0 else unknown) + page.unknownTime
        val total = if (reset) mutableMapOf() else summary.toMutableMap()
        page.severities.forEach { (level, count) -> total[level] = (total[level] ?: 0) + count }
        summary = total
    }
    fun search() {
        if (busy || selected.isEmpty()) return
        val request = inputs()
        focus.clearFocus()
        follow = false
        run {
            val nextQuery = request.query(zone)
            val available = store.snapshot().files
            if (!available.map { it.file.path }.toSet().containsAll(request.files)) {
                files = available
                selected = selected.intersect(available.map { it.file.path }.toSet())
                throw LogSourceChanged()
            }
            val sources = withContext(Dispatchers.IO) {
                available.filter { it.file.path in request.files }.map { LogSource.capture(it.file) }
            }
            val page = withContext(Dispatchers.IO) { scanLogWorkspace(WorkspaceCursor(sources), nextQuery) }
            // Commit the export snapshot only when its matching results are ready.
            frozenQuery = nextQuery; frozenSources = sources; appliedInputs = request
            liveResults = false; chooseFiles = false
            accept(page, true)
        }
    }
    LaunchedEffect(store) {
        files = store.snapshot().files
        selected = selected.intersect(files.map { it.file.path }.toSet())
        filesLoaded = true
    }
    LaunchedEffect(logging) { if (!logging) follow = false }
    LaunchedEffect(follow, lifecycle) {
        if (follow && logging) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val offsets = mutableMapOf<String, Pair<Long, Int>>()
            val previousSources = mutableMapOf<String, LogSource>()
            val activeQuery = try { inputs().query(zone) } catch (_: Exception) { error = R.string.lxg_invalid_range; follow = false; return@repeatOnLifecycle }
            var session: String? = null
            // Live rows must never enable export of a previous, unrelated search.
            frozenSources = emptyList(); appliedInputs = null; liveResults = true; error = null
            rows = emptyList(); summary = emptyMap(); scanned = 0; unknown = 0; cursor = null
            while (isActive) {
                try {
                    val snapshot = store.snapshot().files
                    files = snapshot
                    selected = selected.intersect(snapshot.map { it.file.path }.toSet())
                    if (session == null) session = snapshot.maxByOrNull { it.modifiedAt }?.file?.name?.substringBeforeLast('_')
                    val latest = snapshot.filter { session != null && it.file.name.substringBeforeLast('_') == session }.sortedBy { it.modifiedAt }
                    for (file in latest) {
                        val source = withContext(Dispatchers.IO) { captureCompleteLog(file.file) }
                        if (previousSources[source.path]?.unchanged() == false) throw LogSourceChanged()
                        val previous = offsets[source.path] ?: (0L to 0)
                        if (previous.first > source.size) throw LogSourceChanged()
                        var next: WorkspaceCursor? = WorkspaceCursor(listOf(source), offset = previous.first, line = previous.second)
                        var line = previous.second
                        while (next != null) {
                            val page = withContext(Dispatchers.IO) { scanLogWorkspace(next!!, activeQuery) }
                            rows = (rows + page.rows).distinctBy { it.key }.takeLast(1000)
                            scanned += page.scanned; unknown += page.unknownTime; line += page.scanned
                            summary = (summary.keys + page.severities.keys).associateWith { (summary[it] ?: 0) + (page.severities[it] ?: 0) }
                            next = page.next
                        }
                        offsets[source.path] = source.size to line
                        previousSources[source.path] = source
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = R.string.lxg_source_changed; follow = false; break }
                delay(3000)
            }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val controlsHeight = (maxHeight - 104.dp).coerceAtLeast(0.dp) * .60f
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.lxg_workspace), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClose, Modifier.size(56.dp)) { Icon(Icons.Default.Close, stringResource(R.string.logs_close_viewer)) }
                    }
                    Column(Modifier.heightIn(max = controlsHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(query, { query = it.take(200) }, singleLine = true, enabled = !follow,
                            label = { Text(stringResource(R.string.logx_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (!follow) search() }), modifier = Modifier.fillMaxWidth())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(chooseFiles, { chooseFiles = !chooseFiles }, label = { Text(stringResource(R.string.lxg_files, selected.size)) })
                            FilterChip(showMarks, { showMarks = !showMarks }, label = { Text(stringResource(R.string.lxg_bookmarks)) })
                            FilterChip(follow, { follow = !follow; job?.cancel() }, enabled = logging && !busy, label = { Text(stringResource(R.string.lxg_follow_rotation)) })
                        }
                        if (chooseFiles) {
                            TextButton(enabled = files.isNotEmpty(), onClick = { selected = if (selected.size == files.size) emptySet() else files.map { it.file.path }.toSet() }) { Text(stringResource(R.string.lxg_select_all)) }
                            files.forEach { file ->
                                FilterChip(file.file.path in selected, { selected = if (file.file.path in selected) selected - file.file.path else selected + file.file.path }, label = { Text(file.file.name) })
                            }
                        }
                        if (showMarks && markList.isEmpty()) Text(stringResource(R.string.uxf_bookmarks_empty))
                        if (showMarks) markList.forEach { mark ->
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(modifier = Modifier.weight(1f), onClick = { run {
                                    if (files.none { it.file.canonicalPath == mark.source.path }) throw LogSourceChanged()
                                    val page = withContext(Dispatchers.IO) { scanLogWorkspace(WorkspaceCursor(listOf(mark.source), offset = mark.offset, line = mark.line - 1), WorkspaceQuery(), maxRows = 1) }
                                    detail = page.rows; contextMatch = page.rows.firstOrNull()?.key
                                } }) { Text("${File(mark.source.path).name}:${mark.line}") }
                                TextButton(onClick = { bookmarks.remove(mark.key); markList = bookmarks.list() }) { Text(stringResource(R.string.lxg_remove)) }
                            }
                        }
                        Text(stringResource(R.string.lxg_time_zone, zone))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(from, { from = it.take(30) }, singleLine = true, enabled = !follow, label = { Text(stringResource(R.string.lxg_from)) }, modifier = Modifier.widthIn(min = 180.dp).weight(1f))
                            OutlinedTextField(until, { until = it.take(30) }, singleLine = true, enabled = !follow, label = { Text(stringResource(R.string.lxg_until)) }, modifier = Modifier.widthIn(min = 180.dp).weight(1f))
                        }
                        Text(stringResource(R.string.lxg_range_format), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LogSeverity.entries.forEach { level ->
                                FilterChip(severity == level, { severity = level }, enabled = !follow, label = { Text(stringResource(level.label())) })
                            }
                            Button(onClick = ::search, enabled = !busy && selected.isNotEmpty()) { Text(stringResource(R.string.lxg_search)) }
                            if (cursor != null && !follow) OutlinedButton(enabled = !busy && !inputsChanged, onClick = { val next = cursor!!; run { accept(withContext(Dispatchers.IO) { scanLogWorkspace(next, frozenQuery) }, false) } }) { Text(stringResource(R.string.lxg_continue)) }
                            if (busy) TextButton({ job?.cancel() }) { Text(stringResource(R.string.update_cancel)) }
                        }
                        Text(stringResource(R.string.lxg_scan_limits), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.lxg_scan_scope, scanned, unknown))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(LogSeverity.ERROR, LogSeverity.WARNING, LogSeverity.INFO).forEach { level ->
                                TextButton(onClick = { severity = level; search() }, enabled = !busy && selected.isNotEmpty()) {
                                    Text("${stringResource(level.label())}: ${summary[level.name.take(1)] ?: 0}")
                                }
                            }
                        }
                        Text(stringResource(R.string.lxg_export_scope), style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false, true).forEach { metadata ->
                                TextButton(enabled = !busy && !follow && !inputsChanged && appliedInputs != null && frozenSources.isNotEmpty(), onClick = {
                                    val sources = frozenSources; val exportQuery = frozenQuery
                                    run {
                                        try {
                                            queue.enqueue(if (metadata) "cabin-metadata.txt" else "cabin-all-matches.txt", "text/plain") { out -> exportWorkspace(sources, exportQuery, metadata, out) }
                                        } finally { exportRefresh++ }
                                    }
                                }) { Text(stringResource(if (metadata) R.string.logx_metadata_export else R.string.lxg_export_all)) }
                            }
                        }
                        SupportExportRecovery(exportRefresh)
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.weight(1f), state = resultsScroll) {
                        if (inputsChanged || (liveResults && !follow)) item {
                            Text(stringResource(if (inputsChanged) R.string.uxf_search_changed else R.string.uxf_follow_search_required),
                                Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        error?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error) } }
                        if (rows.isEmpty() && !busy && error == null && filesLoaded) item {
                            val emptyLabel = when {
                                follow -> R.string.ux_review_logs_waiting
                                files.isEmpty() -> R.string.ux_review_logs_no_files
                                !hasSearched -> R.string.ux_review_logs_start
                                cursor != null -> R.string.ux_review_logs_page_empty
                                else -> R.string.ux_review_logs_empty
                            }
                            Text(stringResource(emptyLabel), Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                        items(rows, key = { it.key }) { row ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text("${File(row.source.path).name}:${row.line}", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodySmall) }
                                if (row.shortened) Text(stringResource(R.string.lxg_preview_shortened))
                                FlowRow {
                                    TextButton(enabled = !busy && !follow, onClick = { run { detail = withContext(Dispatchers.IO) { logContext(row) }; contextMatch = row.key } }) { Text(stringResource(R.string.lxg_context)) }
                                    TextButton(enabled = !follow, onClick = { bookmarks.toggle(row); markList = bookmarks.list() }) { Text(stringResource(if (markList.any { it.key == row.key }) R.string.lxg_remove_bookmark else R.string.lxg_bookmark)) }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
    if (detail.isNotEmpty()) AlertDialog(onDismissRequest = { detail = emptyList() }, title = { Text(stringResource(R.string.lxg_context)) },
        text = { Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.lxg_context_not_exported))
            detail.forEach { row ->
                Surface(color = if (row.key == contextMatch) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (row.key == contextMatch) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                    Text((if (row.key == contextMatch) "▶ " else "") + "${row.line}: ${row.text}", Modifier.fillMaxWidth().padding(4.dp))
                }
            }
        } }, confirmButton = { TextButton({ detail = emptyList() }) { Text(stringResource(R.string.logs_close_viewer)) } })
}
