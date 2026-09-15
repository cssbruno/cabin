package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun SyuRadioScreen(moving: Boolean, onParkedAction: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(SyuRadioState()) }
    var controller by remember { mutableStateOf<SyuRadioController?>(null) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = SyuRadioController(context)
            controller = client
            try { client.start(); client.state.collect { state = it } }
            finally { controller = null; client.close(); state = SyuRadioState() }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("radio-screen")) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vehicle_radio), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.vehicle_close)) }
                }
                RadioControls(state, moving, { code, channel ->
                    val epoch = state.epoch
                    if (!moving) onParkedAction { controller?.command(epoch, code, channel) }
                }, Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
internal fun RadioControls(state: SyuRadioState, moving: Boolean, command: (Int, Int?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(false) }
    var saveSlot by remember(state.epoch) { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    val enabled = state.connected && state.frequency != null && !moving
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.frequency?.let { SyuRadioProtocol.label(state.band, it) } ?: "—", style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.vehicle_radio_note))
        if (!enabled) Text(stringResource(if (moving) R.string.syu_sound_parked else R.string.vehicle_radio_waiting))
        Row {
            TextButton({ command(6, null) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vehicle_seek_down)) }
            TextButton({ command(5, null) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text(stringResource(R.string.vehicle_seek_up)) }
        }
        Row {
            OutlinedButton({ command(4, null) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("−") }
            OutlinedButton({ command(3, null) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("+") }
        }
        TextButton({ presets = !presets }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.vehicle_presets)) }
        if (presets) state.presets.toSortedMap().forEach { (slot, frequency) ->
            Row {
                TextButton({ command(7, slot) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text("${slot % 65536 + 1} · ${SyuRadioProtocol.label(if (slot >= 65536) 65536 else 0, frequency)}")
                }
                TextButton({ saveSlot = slot }, enabled = enabled && (slot >= 65536) == ((state.band ?: 0) >= 65536),
                    modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.vehicle_save_preset)) }
            }
        }
        val factory = TeyesFeaturePreferences.get(context).shortcut(TeyesShortcut.RADIO)
        if (factory != null) OutlinedButton(onClick = {
            if (!moving) failed = !TeyesAppShortcuts.launch(context, factory)
        }, enabled = !moving) { Text(stringResource(R.string.vehicle_factory_radio)) }
        if (failed) Text(stringResource(R.string.launcher_action_failed))
    }
    saveSlot?.let { slot ->
        AlertDialog(onDismissRequest = { saveSlot = null }, title = { Text(stringResource(R.string.vehicle_save_preset)) },
            text = { Text(stringResource(R.string.vehicle_replace_preset, slot % 65536 + 1)) },
            confirmButton = { TextButton({ if (enabled) command(8, slot); saveSlot = null }, enabled = enabled) { Text(stringResource(R.string.vehicle_save_preset)) } },
            dismissButton = { TextButton({ saveSlot = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
