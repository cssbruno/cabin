package com.cabin.ui.settings

import android.content.ActivityNotFoundException
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
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.logging.FileExportService
import com.cabin.logging.PendingSupportExport
import com.cabin.logging.SupportExportQueue
import kotlinx.coroutines.launch

@Composable
internal fun SupportExportRecovery(refresh: Int = 0) {
    val context = LocalContext.current
    val queue = remember(context) { SupportExportQueue(context) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(emptyList<PendingSupportExport>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var discarding by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pickerUnavailable by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        scope.launch {
            busy = true
            try {
                val item = queue.pending().firstOrNull { it.id == selected }
                if (uri != null && (item == null || !item.ready)) failed = true
                if (uri != null && item != null && item.ready) {
                    failed = FileExportService.writeFileToUri(context, uri, item.file).isFailure
                    if (!failed) queue.discard(item.id)
                }
            } finally { selected = null; busy = false; pending = queue.pending() }
        }
    }
    LaunchedEffect(refresh) { pending = queue.pending() }
    if (pending.isNotEmpty()) OutlinedCard(Modifier.fillMaxWidth().settingsSearchAnchor(stringResource(R.string.lxg_pending_exports))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lxg_pending_exports), style = MaterialTheme.typography.titleMedium)
            if (failed) Text(stringResource(R.string.logx_export_failed))
            if (pickerUnavailable) Text(stringResource(R.string.ux_support_picker_unavailable), color = MaterialTheme.colorScheme.error)
            pending.forEach { item ->
                Text(item.name)
                if (!item.ready) Text(stringResource(R.string.lxg_preparation_interrupted))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.ready) TextButton(enabled = !busy && selected == null, onClick = {
                        selected = item.id; pickerUnavailable = false; failed = false
                        try { export.launch(item.name) }
                        catch (_: ActivityNotFoundException) { selected = null; pickerUnavailable = true }
                        catch (_: SecurityException) { selected = null; pickerUnavailable = true }
                    }) { Text(stringResource(R.string.lxg_resume)) }
                    TextButton(enabled = !busy && selected == null, onClick = { discarding = item.id }) { Text(stringResource(R.string.lxg_discard)) }
                }
            }
        }
    }
    pending.firstOrNull { it.id == discarding }?.let { item ->
        AlertDialog(onDismissRequest = { discarding = null },
            title = { Text(stringResource(R.string.ux_support_discard_title)) },
            text = { Text(stringResource(R.string.ux_support_discard_message, item.name), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                discarding = null
                scope.launch { busy = true; try { queue.discard(item.id); pending = queue.pending() } finally { busy = false } }
            }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.lxg_discard)) } },
            dismissButton = { TextButton({ discarding = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
