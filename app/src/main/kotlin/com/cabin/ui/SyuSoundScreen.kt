package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cabin.R
import com.cabin.platform.SyuSoundConnection
import com.cabin.platform.SyuSoundControl
import com.cabin.platform.SyuSoundControlKind
import com.cabin.platform.SyuSoundProtocol
import kotlin.math.roundToInt

/** Displays only settings confirmed by the installed sound service. */
@Composable
internal fun SyuSoundScreen(
    state: SyuSoundConnection,
    moving: Boolean,
    onChange: (String, Int) -> Unit,
    onClose: () -> Unit,
    onFactory: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false,
    )) {
        Surface(Modifier.fillMaxSize().testTag("syu-sound-screen")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.syu_sound_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.syu_sound_close))
                    }
                }
                SyuSoundContent(state, moving, onChange, onFactory,
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp))
            }
        }
    }
}

@Composable
internal fun SyuSoundContent(
    state: SyuSoundConnection,
    moving: Boolean,
    onChange: (String, Int) -> Unit,
    onFactory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val controls = SyuSoundProtocol.controls(state.moduleId, state.samples)
    var advanced by remember(state.epoch) { mutableStateOf(false) }
    val enabled = state.connected && !moving
    val basic = controls.filter { it.kind != SyuSoundControlKind.EQ_GAIN }
    val detailed = controls.filter { it.kind == SyuSoundControlKind.EQ_GAIN }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val status = when {
            moving -> R.string.syu_sound_parked
            !state.connected -> R.string.syu_sound_connecting
            state.moduleId == null -> R.string.syu_sound_waiting
            !SyuSoundProtocol.supported(state.moduleId) -> R.string.syu_sound_unsupported
            controls.isEmpty() -> R.string.syu_sound_waiting
            state.failed -> R.string.syu_sound_failed
            state.pending.isNotEmpty() -> R.string.syu_sound_pending
            else -> null
        }
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
        basic.forEach { control ->
            key(state.epoch, control.key) {
                SoundControl(control, enabled && control.key !in state.pending) { value ->
                    if (enabled && control.key !in state.pending) onChange(control.key, value)
                }
            }
        }
        if (detailed.isNotEmpty()) {
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.syu_sound_advanced), Modifier.weight(1f))
                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (advanced) detailed.forEach { control ->
                key(state.epoch, control.key) {
                    SoundControl(control, enabled && control.key !in state.pending) { value ->
                        if (enabled && control.key !in state.pending) onChange(control.key, value)
                    }
                }
            }
        }
        SoundDiagnostics(state)
        onFactory?.let { launch ->
            OutlinedButton(onClick = { if (!moving) launch() }, enabled = !moving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.syu_sound_factory))
            }
        }
    }
}

@Composable
private fun SoundControl(control: SyuSoundControl, enabled: Boolean, onChange: (Int) -> Unit) {
    val label = when (control.kind) {
        SyuSoundControlKind.PRESET -> stringResource(R.string.syu_sound_presets)
        SyuSoundControlKind.EQ_GAIN -> stringResource(R.string.syu_sound_band, (control.band ?: 0) + 1)
        SyuSoundControlKind.BALANCE -> stringResource(R.string.syu_sound_balance)
        SyuSoundControlKind.FADER -> stringResource(R.string.syu_sound_fader)
        SyuSoundControlKind.LOUDNESS -> stringResource(R.string.syu_sound_loudness)
        SyuSoundControlKind.SUBWOOFER_GAIN -> stringResource(R.string.syu_sound_subwoofer)
    }
    if (control.kind == SyuSoundControlKind.LOUDNESS) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = control.current != 0, onCheckedChange = { onChange(if (it) 1 else 0) },
                enabled = enabled, modifier = Modifier.testTag("sound-${control.key}").semantics { contentDescription = label })
        }
    } else if (control.kind == SyuSoundControlKind.PRESET) {
        var choosing by remember(enabled) { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { choosing = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sound-preset")) {
                    Text(stringResource(R.string.syu_sound_preset_number, control.current + 1), Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(expanded = choosing && enabled, onDismissRequest = { choosing = false }) {
                    control.range.forEach { value ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.syu_sound_preset_number, value + 1)) },
                            onClick = { choosing = false; onChange(value) },
                            modifier = Modifier.heightIn(min = 56.dp).testTag("sound-preset-$value"))
                    }
                }
            }
        }
    } else {
        var draft by remember(control.key, control.current, enabled) { mutableFloatStateOf(control.current.toFloat()) }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(control.current.toString(), style = MaterialTheme.typography.bodyLarge)
            }
            Slider(value = draft, onValueChange = { draft = it },
                onValueChangeFinished = { if (enabled) onChange(draft.roundToInt()) },
                enabled = enabled, valueRange = control.range.first.toFloat()..control.range.last.toFloat(),
                steps = (control.range.last - control.range.first - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sound-${control.key}")
                    .semantics { contentDescription = label })
        }
    }
}

/** Read-only summary of the same fresh callbacks used by the controls. */
@Composable
private fun SoundDiagnostics(state: SyuSoundConnection) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val id = state.moduleId.takeIf { state.connected }
    val known = SyuSoundProtocol.supported(id)
    val controls = if (state.connected) SyuSoundProtocol.controls(id, state.samples) else emptyList()
    val unknown = stringResource(R.string.syu_diag_unknown)
    val profile = id?.let { "${SyuSoundProtocol.profileName(it) ?: unknown} (ID $it)" } ?: unknown
    val service = stringResource(if (state.connected) R.string.syu_diag_connected else R.string.syu_diag_disconnected)
    val lines = mutableListOf(
        stringResource(R.string.syu_diag_device, android.os.Build.MANUFACTURER, android.os.Build.MODEL),
        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        stringResource(R.string.syu_diag_service, service),
        stringResource(R.string.syu_diag_profile, profile),
    )
    val groups = listOf(
        SyuSoundControlKind.PRESET to R.string.syu_sound_presets,
        SyuSoundControlKind.LOUDNESS to R.string.syu_sound_loudness,
        SyuSoundControlKind.BALANCE to R.string.syu_sound_balance,
        SyuSoundControlKind.FADER to R.string.syu_sound_fader,
        SyuSoundControlKind.SUBWOOFER_GAIN to R.string.syu_sound_subwoofer,
    )
    for ((kind, label) in groups) {
        val mapped = known && (kind != SyuSoundControlKind.SUBWOOFER_GAIN || id == 11)
        val status = when {
            id == null -> R.string.syu_diag_missing
            !mapped -> R.string.syu_diag_unmapped
            controls.any { it.kind == kind } -> R.string.syu_diag_received
            else -> R.string.syu_diag_missing
        }
        lines += stringResource(label) + ": " + stringResource(status)
    }
    lines += stringResource(R.string.syu_diag_bands, controls.count { it.kind == SyuSoundControlKind.EQ_GAIN })
    lines += stringResource(R.string.syu_diag_note)
    val report = lines.joinToString("\n")
    var copied by remember(report) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .testTag("sound-diagnostics-toggle")) {
        Text(stringResource(R.string.syu_diag_title), Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (expanded) {
        Text(report, modifier = Modifier.testTag("sound-diagnostics-report"), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)); copied = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(if (copied) R.string.syu_diag_copied else R.string.syu_diag_copy))
        }
    }
}
