package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cabin.R
import java.io.File
import kotlinx.coroutines.CancellationException

@Composable
internal fun LogFileViewer(file: File, store: LogFilesStore, onClose: () -> Unit) {
    var offsets by remember(file) { mutableStateOf(listOf(0L)) }
    var refresh by remember { mutableIntStateOf(0) }
    var page by remember(file) { mutableStateOf<LogPage?>(null) }
    var busy by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    LaunchedEffect(file, offsets, refresh) {
        busy = true
        failed = false
        page = null
        try {
            page = store.page(file, offsets.last())
            scroll.scrollToItem(0)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !busy && offsets.size > 1, onClick = { offsets = offsets.dropLast(1) }) { Text(stringResource(R.string.logs_previous)) }
                    TextButton(enabled = !busy, onClick = { refresh++ }) { Text(stringResource(R.string.logs_refresh)) }
                    TextButton(enabled = !busy && page?.hasMore == true, onClick = { offsets = offsets + requireNotNull(page).nextOffset }) { Text(stringResource(R.string.logs_next)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.logs_close_viewer)) }
                }
                when {
                    busy -> CircularProgressIndicator()
                    failed -> Text(stringResource(R.string.logs_read_failed))
                    page?.text.isNullOrEmpty() -> Text(stringResource(R.string.logs_empty_page))
                    else -> SelectionContainer(Modifier.weight(1f)) {
                        // Bound individual text layouts as well as the disk read.
                        val lines = remember(page) { requireNotNull(page).text.lineSequence().flatMap { it.chunked(2000).ifEmpty { listOf("") } }.toList() }
                        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(lines) { _, line ->
                                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
