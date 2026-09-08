package com.cabin.ui.settings

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.CabinManager
import com.cabin.platform.labelRes
import com.cabin.platform.LocalTeyesKeyRouter
import com.cabin.platform.TeyesAppShortcuts
import com.cabin.platform.TeyesAppearance
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.platform.TeyesShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun TeyesFeaturesScreen(
    manager: CabinManager,
    vehicle: TeyesClimateState = TeyesClimateState(),
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val preferences = remember { TeyesFeaturePreferences.get(context) }
    val profile by preferences.profile.collectAsStateWithLifecycle()
    val router = LocalTeyesKeyRouter.current
    val keyStatus = router?.status?.collectAsStateWithLifecycle()?.value.orEmpty()
    var mappings by remember { mutableStateOf(preferences.mappedKeys()) }
    var phones by remember(manager) { mutableStateOf(manager.pairedDevices) }
    var shortcutPicker by remember { mutableStateOf<TeyesShortcut?>(null) }
    val shortcutRevision by preferences.revision.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<TeyesLaunchableApp>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(false) }
    var name by remember(profile.slot, profile.name) { mutableStateOf(profile.name) }
    var learningAction by remember { mutableStateOf<TeyesKeyAction?>(null) }
    var learningToken by remember { mutableStateOf(0) }
    var feedback by remember(profile.slot) { mutableStateOf("") }
    LaunchedEffect(keyStatus, shortcutRevision) {
        mappings = preferences.mappedKeys()
        if (router?.isLearning != true) learningAction = null
    }
    LaunchedEffect(learningAction, learningToken) {
        if (learningAction != null) {
            delay(15_000)
            router?.cancelLearning()
            learningAction = null
        }
    }
    LaunchedEffect(shortcutPicker) {
        if (shortcutPicker != null) {
            loadingApps = true
            apps = emptyList()
            try {
                apps = withContext(Dispatchers.IO) { TeyesAppShortcuts.available(context) }
            } finally {
                loadingApps = false
            }
        }
    }
    DisposableEffect(manager) {
        val listener = CabinManager.DeviceListener { phones = it }
        manager.addDeviceListener(listener)
        manager.refreshDeviceList()
        onDispose {
            manager.removeDeviceListener(listener)
            router?.cancelLearning()
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 920.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.teyes_setup), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.teyes_setup_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSection(stringResource(R.string.teyes_driver_profile), stringResource(R.string.teyes_driver_profile_detail)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { slot ->
                        FilterChip(selected = slot == profile.slot, onClick = {
                            preferences.select(slot)
                        }, label = { Text(stringResource(if (slot == profile.slot) R.string.teyes_driver_slot_active else R.string.teyes_driver_slot, slot + 1)) }, modifier = Modifier.heightIn(min = 56.dp))
                    }
                }
                OutlinedTextField(value = name, onValueChange = {
                    name = it.take(32)
                    feedback = ""
                }, label = {
                    Text(stringResource(R.string.teyes_profile_name))
                }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = {
                    Text(
                        if (name != profile.name) stringResource(R.string.teyes_unsaved_name, name.length) else stringResource(R.string.teyes_saved_name, profile.name),
                    )
                })
                FilledTonalButton(onClick = {
                    preferences.update { it.copy(name = name) }
                    feedback = resources.getString(R.string.teyes_profile_name_saved)
                }, enabled = name != profile.name && name.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_save_name)) }
                if (feedback.isNotEmpty()) SettingsNotice(feedback)
                SettingsToggle(stringResource(R.string.teyes_compact_launch), profile.compactOnLaunch, stringResource(R.string.teyes_compact_launch_detail)) { value ->
                    preferences.update { it.copy(compactOnLaunch = value) }
                }
            }
            SettingsDisclosure(
                stringResource(R.string.teyes_preferred_phone),
                phones.firstOrNull { it.btMac == profile.preferredPhone }?.name ?: profile.preferredPhone.ifEmpty { stringResource(R.string.teyes_adapter_chooses) },
            ) {
                Text(stringResource(R.string.teyes_preferred_wireless, phones.firstOrNull { it.btMac == profile.preferredPhone }?.name ?: profile.preferredPhone.ifEmpty { stringResource(R.string.teyes_adapter_default) }))
                Text(stringResource(R.string.teyes_preferred_phone_detail))
                SettingsChoice(stringResource(R.string.teyes_adapter_default), profile.preferredPhone.isEmpty(), { preferences.update { it.copy(preferredPhone = "") } })
                phones.forEach { phone ->
                    SettingsChoice(phone.name, profile.preferredPhone == phone.btMac, { preferences.update { it.copy(preferredPhone = phone.btMac) } })
                }
                if (phones.isEmpty()) SettingsNotice(stringResource(R.string.teyes_load_phones))
            }
            SettingsSection(stringResource(R.string.teyes_appearance), stringResource(R.string.teyes_appearance_current, stringResource(profile.appearance.labelRes))) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TeyesAppearance.entries.forEach { appearance ->
                        FilterChip(selected = profile.appearance == appearance, onClick = {
                            preferences.update { it.copy(appearance = appearance) }
                        }, label = { Text(stringResource(appearance.labelRes)) }, modifier = Modifier.heightIn(min = 56.dp))
                    }
                }
                Text(stringResource(R.string.teyes_appearance_detail))
                Text(stringResource(R.string.teyes_night_brightness, (profile.nightBrightness * 100).toInt()))
                Slider(value = profile.nightBrightness, onValueChange = {
                        value ->
                    preferences.update { it.copy(nightBrightness = value) }
                }, valueRange = 0.1f..1f, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = resources.getString(R.string.teyes_night_brightness_accessibility) })
            }
            SettingsSection(stringResource(R.string.label_audio), stringResource(R.string.teyes_audio_detail)) {
                Text(stringResource(R.string.teyes_media_volume))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    FilledTonalButton(
                        onClick = { adjustVolume(context, AudioManager.ADJUST_LOWER) },
                        enabled = !audio.isVolumeFixed,
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.teyes_volume_lower)) }
                    FilledTonalButton(onClick = {
                        adjustVolume(context, AudioManager.ADJUST_TOGGLE_MUTE)
                    }, enabled = !audio.isVolumeFixed, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_mute)) }
                    FilledTonalButton(
                        onClick = { adjustVolume(context, AudioManager.ADJUST_RAISE) },
                        enabled = !audio.isVolumeFixed,
                        modifier = Modifier.heightIn(min = 56.dp),
                    ) { Text(stringResource(R.string.teyes_volume_higher)) }
                    if (audio.isVolumeFixed) Text(stringResource(R.string.teyes_volume_firmware))
                }
                Text(stringResource(R.string.teyes_projection_music, (profile.mediaGain * 100).toInt()))
                Slider(value = profile.mediaGain, onValueChange = {
                        value ->
                    preferences.update { it.copy(mediaGain = value) }
                }, enabled = manager.supportsProjectionGain, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = resources.getString(R.string.teyes_projection_music_accessibility) })
                Text(stringResource(R.string.teyes_navigation_prompts, (profile.navigationGain * 100).toInt()))
                Slider(value = profile.navigationGain, onValueChange = {
                        value ->
                    preferences.update { it.copy(navigationGain = value) }
                }, enabled = manager.supportsProjectionGain, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = resources.getString(R.string.teyes_navigation_prompts_accessibility) })
                if (!manager.supportsProjectionGain) SettingsNotice(stringResource(R.string.teyes_audio_unavailable))
                Text(stringResource(R.string.teyes_audio_gain_detail))
            }
            ObdSettingsSection(vehicle)
            SettingsDisclosure(stringResource(R.string.teyes_camera_recovery), stringResource(R.string.teyes_camera_recovery_detail)) {
                SettingsToggle(
                    stringResource(R.string.teyes_recover_overlays),
                    profile.recoverOverlays,
                    stringResource(R.string.teyes_recover_overlays_detail),
                ) { value ->
                    preferences.update {
                        it.copy(recoverOverlays = value)
                    }
                }
                SettingsToggle(
                    stringResource(R.string.teyes_retry_wake),
                    profile.resumeOnWake,
                    stringResource(R.string.teyes_retry_wake_detail),
                ) { value ->
                    preferences.update {
                        it.copy(resumeOnWake = value)
                    }
                }
            }
            SettingsDisclosure(stringResource(R.string.teyes_steering_shortcuts), androidx.compose.ui.res.pluralStringResource(R.plurals.teyes_learned_buttons, mappings.size, mappings.size), onCollapse = {
                learningAction = null
                router?.cancelLearning()
            }) {
                Text(stringResource(R.string.teyes_steering_detail))
                if (keyStatus.isNotEmpty()) SettingsNotice(keyStatus)
                if (router == null) SettingsNotice(stringResource(R.string.teyes_steering_unavailable))
                TeyesKeyAction.entries.forEach { action ->
                    TextButton(onClick = {
                        learningAction = action
                        learningToken++
                        router?.learn(action)
                    }, enabled = router != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_learn_action, stringResource(action.labelRes))) }
                }
                if (learningAction != null) {
                    TextButton(onClick = {
                        learningAction = null
                        router?.cancelLearning()
                    }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_cancel_learning)) }
                }
                mappings.forEach { (code, action) ->
                    TextButton(onClick = {
                        preferences.mapKey(code, null)
                        mappings = preferences.mappedKeys()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.teyes_remove_mapping, KeyEvent.keyCodeToString(code), stringResource(action.labelRes)))
                    }
                }
            }
            SettingsDisclosure(stringResource(R.string.teyes_accessory_shortcuts), stringResource(R.string.teyes_accessory_shortcuts_summary)) {
                Text(stringResource(R.string.teyes_accessory_shortcuts_detail))
                // Preserve legacy backup data, but never offer a generic OBD app path.
                TeyesShortcut.entries.filter { it != TeyesShortcut.OBD }.forEach { kind ->
                    val selected = remember(shortcutRevision, kind) { preferences.shortcut(kind) }
                    Text(stringResource(kind.labelRes), style = MaterialTheme.typography.titleMedium)
                    Text(selected?.substringBefore('/') ?: stringResource(R.string.state_not_configured), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { shortcutPicker = kind },
                            modifier = Modifier.heightIn(min = 56.dp),
                        ) { Text(if (selected == null) stringResource(R.string.teyes_choose_app) else stringResource(R.string.teyes_change_app)) }
                        TextButton(onClick = {
                            if (!TeyesAppShortcuts.launch(
                                    context,
                                    selected,
                                )
                            ) {
                                Toast.makeText(context, resources.getString(R.string.teyes_app_unavailable), Toast.LENGTH_LONG).show()
                            }
                        }, enabled = selected != null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_open)) }
                        TextButton(onClick = {
                            preferences.setShortcut(kind, null)
                        }, enabled = selected != null, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_clear)) }
                    }
                    HorizontalDivider()
                }
                Text(
                    stringResource(R.string.teyes_accessory_limits),
                )
            }
            TeyesConfigurationTools(preferences)
        }
    }
    shortcutPicker?.let { kind ->
        AlertDialog(
            onDismissRequest = { shortcutPicker = null },
            title = { Text(stringResource(R.string.teyes_choose_kind_app, stringResource(kind.labelRes))) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (loadingApps) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.teyes_finding_apps))
                    } else if (apps.isEmpty()) {
                        Text(stringResource(R.string.teyes_no_apps))
                    }
                    apps.forEach { app ->
                        TextButton(onClick = {
                            preferences.setShortcut(kind, app.component)
                            shortcutPicker = null
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text("${app.label}\n${app.component.substringBefore('/')}")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shortcutPicker = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun adjustVolume(
    context: Context,
    direction: Int,
) {
    try {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.teyes_volume_controls), Toast.LENGTH_SHORT).show()
    }
}
