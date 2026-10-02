package com.cabin.ui.settings

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.audio.*
import com.cabin.platform.*
import kotlinx.coroutines.delay

/** Readable structured connection progress. One timer exists only while this panel is composed. */
@Composable
fun ConnectionProgressPanel(manager: CabinManager, modifier: Modifier = Modifier) {
    val progress by manager.connectionProgress.collectAsStateWithLifecycle()
    if (!progress.hasVisibleProgress()) return
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(progress.stage, progress.retryAtMs) {
        while (progress.stage.timeoutMs > 0 || progress.retryAtMs > 0) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress.failure != ConnectionFailure.NONE) Text(stringResource(progress.failure.message))
            if (progress.stage.timeoutMs > 0) Text(stringResource(R.string.gx_stage_timer,
                stringResource(progress.stage.label), ((now - progress.startedAtMs).coerceAtLeast(0) / 1000).toInt(), (progress.stage.timeoutMs / 1000).toInt()))
            if (progress.retryAtMs > 0) Text(stringResource(R.string.gx_retry_countdown, ((progress.retryAtMs - now).coerceAtLeast(0) / 1000 + 1).toInt()))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.retryAtMs > 0 || progress.retriesPaused) {
                    OutlinedButton(onClick = { manager.pauseRetries(!progress.retriesPaused) }, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(if (progress.retriesPaused) R.string.gx_resume_retry else R.string.gx_pause_retry))
                    }
                }
                if (progress.failure == ConnectionFailure.PERMISSION_NEEDED) {
                    Button(onClick = manager::requestPermissionAgain, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_request_usb)) }
                }
                if (progress.stage.timeoutMs > 0) OutlinedButton(onClick = { manager.stop() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_stop))
                }
            }
            if (progress.failure == ConnectionFailure.ADAPTER_CHOICE) AdapterChoices(manager)
        }
    }
}

internal fun ConnectionProgress.hasVisibleProgress(): Boolean =
    failure != ConnectionFailure.NONE || stage.timeoutMs > 0 || retryAtMs > 0 || retriesPaused

@Composable
private fun AdapterChoices(manager: CabinManager) {
    var revision by remember { mutableIntStateOf(0) }
    var rememberAdapter by rememberSaveable { mutableStateOf(false) }
    val choices = remember(revision) { manager.attachedAdapters() }
    ExperienceToggle(stringResource(R.string.gx_remember_adapter), rememberAdapter, stringResource(R.string.gx_adapter_identity_note)) { rememberAdapter = it }
    choices.forEach { choice ->
        OutlinedButton(onClick = { manager.selectAdapter(choice.name, rememberAdapter) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(choice.label) }
    }
    TextButton(onClick = { revision++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_refresh)) }
}

/** One settings entry point for connection controls and supported playback diagnostics. */
@Composable
fun ConnectionExperienceSection(cabinManager: CabinManager?) {
    val context = LocalContext.current
    val presentation = remember(context) { ProjectionPreferences.getInstance(context) }
    val settings by presentation.state.collectAsStateWithLifecycle()
    val audio = remember(context) { AudioExperience(context) }
    val drivers = remember(context) { TeyesFeaturePreferences.get(context) }
    val driver by drivers.profile.collectAsStateWithLifecycle()
    val dongle = CarPlayBackendSelection.allowsDongle(context)
    val trial = remember(context) { VideoSettingsTrial.get(context) }
    val trialState by trial.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.gx_experience_title), description = stringResource(R.string.gx_experience_detail)) {
        Text(stringResource(if (dongle) R.string.gx_capability_dongle else R.string.gx_capability_native))
        if (dongle) ExperienceToggle(stringResource(R.string.gx_more_controls), advanced, onChange = { advanced = it })
        if (advanced && dongle) {
            cabinManager?.let { manager ->
                ConnectionProgressPanel(manager)
                Text(stringResource(R.string.gx_adapters), style = MaterialTheme.typography.titleMedium)
                AdapterChoices(manager)
            }
            Text(stringResource(R.string.gx_driver_presentation, driver.name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.gx_controls_timeout))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 20).forEach { seconds ->
                    FilterChip(settings.controlHideSeconds == seconds, { presentation.setControlHideSeconds(seconds) },
                        label = { Text(if (seconds == 0) stringResource(R.string.gx_always_visible) else stringResource(R.string.gx_seconds, seconds)) },
                        modifier = Modifier.heightIn(min = 56.dp))
                }
            }
            Text(stringResource(R.string.gx_blackout_timer))
            Text(stringResource(R.string.gx_blackout_detail), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 5, 15, 30).forEach { minutes ->
                    FilterChip(settings.blackoutMinutes == minutes, { presentation.setBlackoutMinutes(minutes) },
                        label = { Text(if (minutes == 0) stringResource(R.string.gx_off) else stringResource(R.string.gx_minutes, minutes)) },
                        modifier = Modifier.heightIn(min = 56.dp))
                }
            }
            Text(stringResource(R.string.gx_tool_shortcuts), style = MaterialTheme.typography.titleMedium)
            listOf("phone", "settings", "blackout").forEach { action ->
                val label = stringResource(when (action) { "phone" -> R.string.projection_change_device; "settings" -> R.string.action_settings; else -> R.string.projection_screen_off_short })
                ExperienceToggle(label, action in settings.toolOrder, onChange = { selected ->
                    presentation.setToolOrder(if (selected) settings.toolOrder + action else settings.toolOrder - action)
                }, enabled = action != "settings")
            }
            settings.toolOrder.forEachIndexed { index, action ->
                val label = stringResource(when (action) { "phone" -> R.string.projection_change_device; "settings" -> R.string.action_settings; else -> R.string.projection_screen_off_short })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.weight(1f))
                    TextButton(onClick = {
                        val order = settings.toolOrder.toMutableList()
                        order[index] = order[index - 1]; order[index - 1] = action
                        presentation.setToolOrder(order)
                    }, enabled = index > 0, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_move_up)) }
                }
            }
            TextButton(onClick = { presentation.setToolOrder(listOf("phone", "settings", "blackout")) }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_reset_tools)) }
            var parked by rememberSaveable { mutableStateOf(false) }
            var bezel by rememberSaveable { mutableFloatStateOf(settings.bezelPercent.toFloat()) }
            ExperienceToggle(stringResource(R.string.gx_parked_preview), parked, onChange = { parked = it })
            val bezelLabel = stringResource(R.string.gx_bezel_value, bezel.toInt())
            Text(bezelLabel)
            ExperienceSlider(bezelLabel, bezel, { bezel = it }, valueRange = 0f..10f, steps = 9, enabled = parked && !trialState.active)
            Button(onClick = {
                if (trial.begin(AdapterConfigPreference.getInstance(context), cabinManager, requireNewRenderer = false)) presentation.setBezelPercent(bezel.toInt())
            }, enabled = parked && !trialState.active && bezel.toInt() != settings.bezelPercent,
                modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_try_bezel)) }
            if (trialState.error) Text(stringResource(R.string.gx_trial_error), color = MaterialTheme.colorScheme.error)
            if (trialState.active) {
                Text(stringResource(R.string.gx_trial_countdown, trialState.remainingSeconds))
                Text(stringResource(R.string.gx_trial_return))
                OutlinedButton(trial::revert, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_revert)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.gx_audio_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (cabinManager?.supportsProjectionGain == false) R.string.gx_audio_adapter_owned else R.string.gx_audio_cabin_owned))
            var buffering by remember { mutableStateOf(audio.buffering) }
            Text(stringResource(R.string.gx_buffering_note), style = MaterialTheme.typography.bodySmall)
            AudioBufferProfile.entries.forEach { option ->
                SettingsChoice(stringResource(when (option) {
                    AudioBufferProfile.PLATFORM -> R.string.gx_buffer_platform
                    AudioBufferProfile.RESPONSIVE -> R.string.gx_buffer_responsive
                    AudioBufferProfile.BALANCED -> R.string.gx_buffer_balanced
                    AudioBufferProfile.RESILIENT -> R.string.gx_buffer_resilient
                }), buffering == option, { buffering = option; audio.buffering = option }, enabled = cabinManager?.supportsProjectionGain != false)
            }
            AudioPresets(audio, driver, drivers, cabinManager)
            Text(stringResource(R.string.gx_microphone_input), style = MaterialTheme.typography.titleMedium)
            val systemAudio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            var inputRevision by remember { mutableIntStateOf(0) }
            var preferredInput by remember { mutableIntStateOf(audio.microphoneId) }
            val inputs = remember(inputRevision) { systemAudio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource } }
            Text(stringResource(R.string.gx_microphone_note), style = MaterialTheme.typography.bodySmall)
            SettingsChoice(stringResource(R.string.gx_input_default), preferredInput == 0, { preferredInput = 0; audio.microphoneId = 0 })
            inputs.forEach { input ->
                SettingsChoice(input.productName.toString(), preferredInput == input.id, { preferredInput = input.id; audio.microphoneId = input.id })
            }
            TextButton(onClick = { inputRevision++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_refresh)) }
            cabinManager?.let { manager ->
                var showHealth by rememberSaveable { mutableStateOf(false) }
                ExperienceToggle(stringResource(R.string.gx_show_health), showHealth, onChange = { showHealth = it })
                if (showHealth) PlaybackHealth(manager)
                ConnectionHistoryControls(manager.connectionHistory)
            }
        }
    }
}

@Composable
private fun AudioPresets(audio: AudioExperience, profile: TeyesDriverProfile, drivers: TeyesFeaturePreferences, manager: CabinManager?) {
    val guest by drivers.guestActive.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    var deleting by remember(profile.slot, guest) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(profile.slot) { mutableStateOf("") }
    var renaming by rememberSaveable(profile.slot) { mutableStateOf<String?>(null) }
    val presets = remember(profile.slot, revision) { audio.presets(profile.slot) }
    Text(stringResource(R.string.gx_presets), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.gx_presets_note))
    if (guest) Text(stringResource(R.string.gv_guest_detail))
    var media by rememberSaveable(profile.slot) { mutableFloatStateOf(profile.mediaGain) }
    var navigation by rememberSaveable(profile.slot) { mutableFloatStateOf(profile.navigationGain) }
    val mediaLabel = stringResource(R.string.gx_media_gain, (media * 100).toInt())
    val navigationLabel = stringResource(R.string.gx_navigation_gain, (navigation * 100).toInt())
    Text(mediaLabel)
    ExperienceSlider(mediaLabel, media, { media = it }, enabled = manager?.supportsProjectionGain != false)
    Text(navigationLabel)
    ExperienceSlider(navigationLabel, navigation, { navigation = it }, enabled = manager?.supportsProjectionGain != false)
    OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.gx_preset_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (audio.save(profile.slot, AudioGainPreset(name, media, navigation), renaming)) { revision++; name = ""; renaming = null }
        }, enabled = !guest && name.isNotBlank() && (presets.size < 8 || renaming != null), modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_save_preset)) }
        if (renaming != null) TextButton(onClick = { renaming = null; name = "" }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) }
    }
    presets.forEach { preset ->
        Text(preset.name, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                drivers.update { it.copy(mediaGain = preset.media, navigationGain = preset.navigation) }
                manager?.applyUserAudioGains(preset.media, preset.navigation)
                media = preset.media; navigation = preset.navigation
            }, enabled = manager?.supportsProjectionGain != false, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_apply)) }
            TextButton(onClick = { name = preset.name; renaming = preset.name; media = preset.media; navigation = preset.navigation }, enabled = !guest, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_rename)) }
            TextButton(onClick = { deleting = preset.name }, enabled = !guest, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_delete)) }
        }
    }
    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.uxc_delete_preset, name)) },
            text = { Text(stringResource(R.string.uxc_delete_preset_detail)) },
            confirmButton = { TextButton(onClick = {
                audio.delete(profile.slot, name)
                if (renaming == name) { renaming = null }
                revision++; deleting = null
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
private fun PlaybackHealth(manager: CabinManager) {
    var health by remember { mutableStateOf(AudioHealth()) }
    var microphone by remember { mutableStateOf(emptyMap<String, Any>()) }
    var picture by remember { mutableStateOf(longArrayOf(0, 0, -1)) }
    var previous by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    var deltas by remember { mutableStateOf(emptyMap<String, Pair<Int, Int>>()) }
    LaunchedEffect(manager) {
        while (true) {
            health = manager.audioHealth()
            deltas = health.streams.associate { it.purpose to ((it.underruns - (previous[it.purpose]?.first ?: it.underruns)).coerceAtLeast(0) to
                (it.overflows - (previous[it.purpose]?.second ?: it.overflows)).coerceAtLeast(0)) }
            previous = health.streams.associate { it.purpose to (it.underruns to it.overflows) }
            microphone = manager.microphoneHealth()
            picture = manager.pictureDelivery()
            delay(1000)
        }
    }
    Text(stringResource(R.string.gx_output_route, health.output ?: stringResource(R.string.gx_unobserved)))
    health.streams.forEach { stream ->
        val delta = deltas[stream.purpose] ?: (0 to 0)
        val purpose = stringResource(when (stream.purpose) {
            "MEDIA" -> R.string.gx_stream_media; "NAVIGATION" -> R.string.gx_stream_navigation
            "SIRI" -> R.string.gx_stream_assistant; "PHONE_CALL" -> R.string.gx_stream_call; else -> R.string.gx_stream_alert
        })
        val focus = stringResource(when (stream.focus) {
            "GRANTED" -> R.string.gx_focus_granted; "DENIED" -> R.string.gx_focus_denied
            "DELAYED" -> R.string.gx_focus_delayed; "LOST" -> R.string.gx_focus_lost; "DUCKED" -> R.string.gx_focus_ducked; else -> R.string.gx_focus_idle
        })
        Text(stringResource(R.string.gx_stream_health, purpose, stream.bufferMs, delta.first, delta.second, focus))
    }
    if (health.streams.any { it.focus in listOf("DENIED", "DELAYED") }) {
        Text(stringResource(R.string.gx_focus_retry_note))
        Button(manager::retryAudioFocus, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_focus_retry)) }
    }
    Text(stringResource(R.string.gx_mic_health, microphone["totalBytesCaptured"] ?: 0L,
        (microphone["route"] as? String)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gx_unobserved)))
    if (microphone["preferredInputApplied"] == false) Text(stringResource(R.string.gx_input_fallback))
    Text(stringResource(R.string.gx_picture_health, picture[0], picture[1], picture[2].takeIf { it >= 0 }?.toString() ?: stringResource(R.string.gx_unobserved)))
    Text(stringResource(R.string.gx_health_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ConnectionHistoryControls(history: ConnectionHistory) {
    var enabled by remember { mutableStateOf(history.enabled) }
    var days by remember { mutableIntStateOf(history.retentionDays) }
    var revision by remember { mutableIntStateOf(0) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    ExperienceToggle(stringResource(R.string.gx_history), enabled, stringResource(R.string.gx_history_note)) {
        if (!it && history.read().isNotEmpty()) pendingAction = "disable"
        else { enabled = it; history.enabled = it; revision++ }
    }
    if (enabled) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 7, 30, 90).forEach { count ->
                FilterChip(days == count, { history.retentionDays = count; days = count; revision++ }, label = { Text(stringResource(R.string.gx_days, count)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        val rows = remember(revision) { history.read().takeLast(10).reversed() }
        rows.forEach { row -> Text(stringResource(R.string.gx_history_row, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.endedAt)),
            row.durationMs / 1000, stringResource(row.reason.message), (row.recoveryMs ?: 0) / 1000)) }
        TextButton(onClick = { pendingAction = "clear" }, enabled = rows.isNotEmpty(), modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.gx_clear_history)) }
        TextButton(onClick = { revision++ }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_refresh)) }
    }
    pendingAction?.let { action ->
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(if (action == "disable") stringResource(R.string.uxv_stop_connection_history)
                else stringResource(R.string.uxv_delete_category, stringResource(R.string.hub_connection_history))) },
            text = { Text(stringResource(if (action == "disable") R.string.uxv_stop_connection_history_detail else R.string.gv_delete_global_confirm)) },
            confirmButton = { TextButton(onClick = {
                if (action == "disable") { enabled = false; history.enabled = false } else history.clear()
                revision++; pendingAction = null
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } })
    }
}

@Composable
private fun ExperienceSlider(label: String, value: Float, onChange: (Float) -> Unit,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0) {
    // Material's track is a separate merging semantics node. Put its accessible
    // range/action on the full-height target, rather than leaving two competing nodes.
    Box(Modifier.fillMaxWidth().height(56.dp).semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
        if (!enabled) disabled() else setProgress { requested ->
            if (!requested.isFinite()) false else {
                val bounded = requested.coerceIn(valueRange)
                val interval = if (steps > 0) (valueRange.endInclusive - valueRange.start) / (steps + 1) else 0f
                val next = if (interval > 0f) (valueRange.start + kotlin.math.round((bounded - valueRange.start) / interval) * interval).coerceIn(valueRange) else bounded
                if (next == value) false else { onChange(next); true }
            }
        }
    }) {
        Slider(value, onChange, modifier = Modifier.fillMaxSize().clearAndSetSemantics {}, enabled = enabled, valueRange = valueRange, steps = steps)
    }
}

@Composable
private fun ExperienceToggle(label: String, checked: Boolean, description: String = "", enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    if (enabled) SettingsToggle(label, checked, description, onChange)
    else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f)); Switch(checked, null, enabled = false)
    }
}
