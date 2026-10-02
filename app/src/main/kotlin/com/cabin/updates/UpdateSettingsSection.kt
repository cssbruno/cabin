package com.cabin.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.BuildConfig
import com.cabin.R
import com.cabin.ui.settings.settingsSearchAnchor
import com.cabin.logging.FileExportService
import com.cabin.logging.SupportExportQueue
import kotlinx.coroutines.*

@Composable
internal fun UpdateSettingsSection() {
    val context = LocalContext.current
    val updater = remember(context) { GitHubUpdater.get(context) }
    val status by updater.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(updater.automatic) }
    var previews by remember { mutableStateOf(updater.previews) }
    var unmetered by remember { mutableStateOf(updater.unmeteredOnly) }
    var skipped by remember { mutableStateOf(updater.skippedVersion) }
    var action by remember { mutableStateOf<Job?>(null) }
    var installError by remember { mutableStateOf<Int?>(null) }
    var permissionNeeded by remember { mutableStateOf(false) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var backupStatus by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingBackup by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val queue = remember { SupportExportQueue(context) }
    LaunchedEffect(action) {
        val running = action ?: return@LaunchedEffect
        running.join()
        // Job completion is not Compose state. Publish it so dialogs cannot stay disabled.
        if (action === running) action = null
    }
    val busy = status.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING) || action?.isActive == true
    fun install() {
        showBackup = false; installError = null
        action = scope.launch {
            try {
                if (updater.canInstallSilently) { permissionNeeded = false; updater.installSilently() }
                else if (context.checkSelfPermission("android.permission.INSTALL_PACKAGES") != android.content.pm.PackageManager.PERMISSION_GRANTED && !context.packageManager.canRequestPackageInstalls()) {
                    permissionNeeded = true
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else { permissionNeeded = false; context.startActivity(updater.installIntent()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { installError = if (e is UpdateException) e.errorRes else R.string.update_install_failed }
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        action = scope.launch {
            val saved = queue.pending().firstOrNull { it.id == pendingBackup }
            backupStatus = if (uri != null && saved != null && FileExportService.writeFileToUri(context, uri, saved.file).isSuccess) {
                queue.discard(saved.id); R.string.ux_backup_saved
            } else if (saved != null) R.string.ux_backup_local else R.string.ux_backup_failed
            pendingBackup = null
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().settingsSearchAnchor(stringResource(R.string.update_title))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
            Text("Cabin ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(automatic, role = Role.Switch) { automatic = it; updater.setAutomatic(it) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.update_automatic), Modifier.weight(1f).settingsSearchAnchor(stringResource(R.string.update_automatic)))
                Switch(automatic, null, Modifier.clearAndSetSemantics {})
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(unmetered, role = Role.Switch) { unmetered = it; updater.setUnmeteredOnly(it) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.ux_unmetered), Modifier.weight(1f).settingsSearchAnchor(stringResource(R.string.ux_unmetered)))
                Switch(unmetered, null, Modifier.clearAndSetSemantics {})
            }
            Text(stringResource(R.string.ux_channel), Modifier.settingsSearchAnchor(stringResource(R.string.ux_channel)), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to R.string.ux_stable, true to R.string.ux_preview).forEach { (preview, label) ->
                    FilterChip(previews == preview, enabled = !busy, onClick = {
                        previews = preview; updater.setPreviews(preview)
                        action = scope.launch { updater.check() }
                    }, label = { Text(stringResource(label)) })
                }
            }
            skipped?.let { version -> Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ux_skipped, version), Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { updater.clearSkipped(); skipped = null; action = scope.launch { updater.check(includeSkipped = true); skipped = updater.skippedVersion } }) { Text(stringResource(R.string.ux_show_skipped)) }
            } }
            val label = when (status.phase) {
                UpdatePhase.IDLE -> R.string.update_idle
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.CURRENT -> R.string.update_current
                UpdatePhase.AVAILABLE -> R.string.update_available
                UpdatePhase.DOWNLOADING -> R.string.update_downloading
                UpdatePhase.READY -> R.string.update_ready
                UpdatePhase.INSTALLING -> R.string.update_installing
                UpdatePhase.WAITING_NETWORK -> R.string.ux_metered_wait
                UpdatePhase.FAILED -> R.string.update_failed
            }
            Text(stringResource(status.errorRes ?: label), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = if (status.errorRes != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            status.release?.let { release ->
                Text(release.versionName + if (release.preview) " · ${stringResource(R.string.ux_preview)}" else "")
                if (release.versionName == BuildConfig.VERSION_NAME) Text(stringResource(R.string.ux_review_build_change, BuildConfig.VERSION_CODE, release.versionCode), style = MaterialTheme.typography.bodySmall)
                TextButton({ showNotes = true }) { Text(stringResource(R.string.ux_release_notes)) }
            }
            if (status.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${status.progress}%")
            }
            installError?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
            if (permissionNeeded) Text(stringResource(R.string.update_permission))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { installError = null; action = scope.launch { updater.check(includeSkipped = true); skipped = updater.skippedVersion } }) { Text(stringResource(R.string.update_check)) }
                if (status.release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED, UpdatePhase.WAITING_NETWORK)) {
                    Button(enabled = !busy, onClick = { action = scope.launch { updater.download() } }) { Text(stringResource(R.string.update_download)) }
                    if (status.phase == UpdatePhase.WAITING_NETWORK) OutlinedButton(enabled = !busy, onClick = { action = scope.launch { updater.download(overrideMetered = true) } }) { Text(stringResource(R.string.ux_download_anyway)) }
                }
                if (status.phase == UpdatePhase.READY) Button(enabled = !busy, onClick = { backupStatus = null; showBackup = true }) { Text(stringResource(R.string.update_install)) }
                if (status.release != null && !busy && status.phase != UpdatePhase.INSTALLING) TextButton(onClick = { updater.skipCurrent(); skipped = updater.skippedVersion }) { Text(stringResource(R.string.ux_skip)) }
                if (busy && action?.isActive == true) TextButton({ action?.cancel() }) { Text(stringResource(R.string.update_cancel)) }
            }
        }
    }
    if (showNotes) AlertDialog(onDismissRequest = { showNotes = false }, title = { Text(stringResource(R.string.ux_release_notes)) },
        text = { SelectionContainer { Text(status.release?.notes?.takeIf { it.isNotBlank() } ?: stringResource(R.string.ux_no_notes), Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton({ showNotes = false }) { Text(stringResource(R.string.logs_close_viewer)) } })
    if (showBackup) AlertDialog(onDismissRequest = { if (!busy) showBackup = false }, title = { Text(stringResource(R.string.ux_backup_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.ux_backup_detail))
            backupStatus?.let { Text(stringResource(it)) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }, confirmButton = { FlowRow {
            TextButton(enabled = !busy, onClick = {
                action = scope.launch {
                    try {
                        val snapshot = withContext(Dispatchers.IO) { com.cabin.platform.PortableConfigurationBackup(context).snapshot() }
                        val item = queue.enqueueText("cabin-before-update.json", snapshot)
                        pendingBackup = item.id; backupStatus = R.string.ux_backup_local
                        try { backupPicker.launch(item.name) }
                        catch (_: ActivityNotFoundException) { pendingBackup = null; backupStatus = R.string.ux_review_backup_picker_missing }
                        catch (_: SecurityException) { pendingBackup = null; backupStatus = R.string.ux_review_backup_picker_missing }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { backupStatus = R.string.ux_backup_failed }
                }
            }) { Text(stringResource(R.string.ux_save_backup)) }
            TextButton(enabled = !busy, onClick = ::install) { Text(stringResource(if (backupStatus == R.string.ux_backup_saved || backupStatus == R.string.ux_backup_local || backupStatus == R.string.ux_review_backup_picker_missing) R.string.update_install else R.string.ux_without_backup)) }
        } }, dismissButton = { TextButton(enabled = !busy, onClick = { showBackup = false }) { Text(stringResource(R.string.action_cancel)) } })
}
