package com.cabin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.ProjectionControlSide
import com.cabin.platform.TeyesAppearance
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesShortcut
import androidx.compose.ui.unit.dp
import com.cabin.platform.TeyesConfigurationBackup
import com.cabin.platform.TeyesConfigurationSnapshot
import com.cabin.platform.TeyesFeaturePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SAF access stays limited to the individual document the driver explicitly chooses. */
@Composable
fun TeyesConfigurationTools(
    preferences: TeyesFeaturePreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusError by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<TeyesConfigurationSnapshot?>(null) }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && !busy) {
                busy = true
                scope.launch {
                    try {
                        val snapshot = preferences.configurationSnapshot()
                        withContext(Dispatchers.IO) {
                            val content = TeyesConfigurationBackup.encode(snapshot).toByteArray(Charsets.UTF_8)
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Unable to open the destination." }.use { it.write(content) }
                        }
                        status = resources.getString(R.string.bu_backup_saved)
                        statusError = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = resources.getString(R.string.bu_backup_save_failed)
                        statusError = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && !busy) {
                busy = true
                scope.launch {
                    try {
                        preview =
                            withContext(Dispatchers.IO) {
                                checkNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open the backup." }
                                    .use(TeyesConfigurationBackup::read)
                            }
                        status = resources.getString(R.string.bu_backup_validated)
                        statusError = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        preview = null
                        status = resources.getString(R.string.bu_backup_rejected)
                        statusError = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
    SettingsDisclosure(stringResource(R.string.bu_backup_title), stringResource(R.string.bu_backup_subtitle), modifier = modifier) {
        Text(stringResource(R.string.bu_backup_description))
        SettingsNotice(stringResource(R.string.bu_backup_private))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { export.launch("cabin-teyes-settings.json") },
                enabled = !busy && preview == null,
                modifier = Modifier.heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.bu_save_backup)) }
            TextButton(onClick = {
                importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }, enabled = !busy && preview == null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bu_restore_backup)) }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.bu_working))
        }
        if (status.isNotEmpty()) SettingsNotice(status, error = statusError)
    }
    preview?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text(stringResource(R.string.bu_replace_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bu_replace_description))
                    snapshot.profiles.sortedBy { it.slot }.forEach { profile ->
                        Text(stringResource(R.string.bu_profile_preview, profile.slot + 1, profile.name,
                            appearanceLabel(profile.appearance), (profile.mediaGain * 100).toInt(), (profile.navigationGain * 100).toInt()))
                        Text(stringResource(R.string.bu_phone_preview, profile.preferredPhone.ifEmpty { resources.getString(R.string.bu_adapter_default) }, enabledLabel(profile.resumeOnWake)))
                        Text(stringResource(R.string.bu_profile_more, (profile.nightBrightness * 100).toInt(), enabledLabel(profile.recoverOverlays), enabledLabel(profile.compactOnLaunch)))
                    }
                    Text(stringResource(R.string.bu_mapping_count, snapshot.keys.size + snapshot.longKeys.size, snapshot.shortcuts.size))
                    snapshot.keys.toSortedMap().forEach { (code, action) ->
                        Text(stringResource(R.string.bu_mapping_preview, code, keyActionLabel(action)))
                    }
                    snapshot.longKeys.toSortedMap().forEach { (code, action) ->
                        Text(stringResource(R.string.layout_long_press) + ": " + stringResource(R.string.bu_mapping_preview, code, keyActionLabel(action)))
                    }
                    snapshot.shortcuts.forEach { (kind, component) -> Text("${shortcutLabel(kind)}: ${component.substringBefore('/')}") }
                    val presentation = snapshot.projection
                    val units = snapshot.measurementUnit
                    if (presentation != null && units != null) {
                        Text(stringResource(R.string.bu_projection_preview_title))
                        Text(stringResource(R.string.bu_projection_preview, enabledLabel(presentation.focusControls),
                            enabledLabel(presentation.vehicleHud), climateNoticeLabel(presentation.climateNoticeMode),
                            enabledLabel(presentation.returnWhenReady), stringResource(if (presentation.controlSide == ProjectionControlSide.LEFT) R.string.bu_side_left else R.string.bu_side_right)))
                        Text(stringResource(R.string.bu_units_preview, measurementUnitLabel(units)))
                    } else {
                        Text(stringResource(R.string.bu_legacy_preview))
                    }
                    Text(stringResource(R.string.bu_restore_effects))
                    Text(stringResource(R.string.bu_restore_parked))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val saved = withContext(Dispatchers.IO) { preferences.replaceConfiguration(snapshot) }
                            preview = null
                            status =
                                if (saved) {
                                    resources.getString(R.string.bu_restore_saved)
                                } else {
                                    resources.getString(R.string.bu_restore_storage_failed)
                                }
                            statusError = !saved
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = resources.getString(R.string.bu_restore_failed)
                            statusError = true
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bu_replace_settings)) }
            },
            dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.bu_keep_settings)) } },
        )
    }
}

@Composable
private fun enabledLabel(enabled: Boolean): String = stringResource(if (enabled) R.string.bu_on else R.string.bu_off)

@Composable
private fun appearanceLabel(appearance: TeyesAppearance): String = stringResource(when (appearance) {
    TeyesAppearance.SYSTEM -> R.string.bu_appearance_system
    TeyesAppearance.DAY -> R.string.bu_appearance_day
    TeyesAppearance.NIGHT -> R.string.bu_appearance_night
})

@Composable
private fun climateNoticeLabel(mode: ClimateNoticeMode): String = stringResource(when (mode) {
    ClimateNoticeMode.SUMMARY -> R.string.bu_notice_summary
    ClimateNoticeMode.PANEL -> R.string.bu_notice_panel
    ClimateNoticeMode.OFF -> R.string.bu_notice_off
})

@Composable
private fun shortcutLabel(shortcut: TeyesShortcut): String = stringResource(when (shortcut) {
    TeyesShortcut.EQUALIZER -> R.string.bu_shortcut_dsp
    TeyesShortcut.TPMS -> R.string.bu_shortcut_tpms
    TeyesShortcut.DASHCAM -> R.string.bu_shortcut_dashcam
    TeyesShortcut.RADIO -> R.string.tools_radio
    TeyesShortcut.BLUETOOTH_AUDIO -> R.string.tools_bluetooth
    TeyesShortcut.OBD -> R.string.bu_shortcut_obd
})

@Composable
private fun keyActionLabel(action: TeyesKeyAction): String = stringResource(when (action) {
    TeyesKeyAction.PLAY_PAUSE -> R.string.bu_action_play
    TeyesKeyAction.NEXT -> R.string.bu_action_next
    TeyesKeyAction.PREVIOUS -> R.string.bu_action_previous
    TeyesKeyAction.VOICE -> R.string.bu_action_assistant
    TeyesKeyAction.PAGE_NEXT -> R.string.layout_next_page
    TeyesKeyAction.PAGE_PREVIOUS -> R.string.layout_previous_page
    TeyesKeyAction.CLIMATE -> R.string.bu_action_climate
    TeyesKeyAction.VOLUME_UP -> R.string.vehicle_volume_up
    TeyesKeyAction.VOLUME_DOWN -> R.string.vehicle_volume_down
    TeyesKeyAction.MUTE -> R.string.vehicle_mute
})
