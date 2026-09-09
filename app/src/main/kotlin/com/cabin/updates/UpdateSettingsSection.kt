package com.cabin.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.BuildConfig
import com.cabin.R
import kotlinx.coroutines.*

@Composable
internal fun UpdateSettingsSection() {
    val context = LocalContext.current
    val updater = remember(context) { GitHubUpdater.get(context) }
    val status by updater.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(updater.automatic) }
    var action by remember { mutableStateOf<Job?>(null) }
    var installFailed by remember { mutableStateOf(false) }
    var permissionNeeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = status.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING) || action?.isActive == true
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleLarge)
            Text("Cabin ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.update_automatic), Modifier.weight(1f))
                Switch(automatic, { automatic = it; updater.setAutomatic(it) })
            }
            val label = when (status.phase) {
                UpdatePhase.IDLE -> R.string.update_idle
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.CURRENT -> R.string.update_current
                UpdatePhase.AVAILABLE -> R.string.update_available
                UpdatePhase.DOWNLOADING -> R.string.update_downloading
                UpdatePhase.READY -> R.string.update_ready
                UpdatePhase.FAILED -> R.string.update_failed
            }
            Text(stringResource(label))
            status.release?.let { Text(it.versionName) }
            if (status.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { status.progress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${status.progress}%")
            }
            if (installFailed) Text(stringResource(R.string.update_install_failed))
            if (permissionNeeded) Text(stringResource(R.string.update_permission))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { installFailed = false; action = scope.launch { updater.check() } }) {
                    Text(stringResource(R.string.update_check))
                }
                if (status.release != null && status.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.FAILED)) {
                    Button(enabled = !busy, onClick = { action = scope.launch { updater.download() } }) { Text(stringResource(R.string.update_download)) }
                }
                if (status.phase == UpdatePhase.READY) Button(enabled = !busy, onClick = {
                    installFailed = false
                    action = scope.launch {
                        try {
                            if (!context.packageManager.canRequestPackageInstalls()) {
                                permissionNeeded = true
                                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                            } else {
                                permissionNeeded = false
                                context.startActivity(updater.installIntent())
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { installFailed = true }
                    }
                }) { Text(stringResource(R.string.update_install)) }
                if (busy && action?.isActive == true) TextButton({ action?.cancel() }) { Text(stringResource(R.string.update_cancel)) }
            }
        }
    }
}
