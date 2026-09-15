package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
internal fun SteeringHardwareScreen(moving: Boolean, onParkedAction: (() -> Unit) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember { mutableStateOf(SteeringHardwareState()) }
    var controller by remember { mutableStateOf<SyuSteeringController?>(null) }
    val latestMoving by rememberUpdatedState(moving)
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = SyuSteeringController(context)
            controller = client
            try { client.start(); client.state.collect { state = it } }
            finally { controller = null; client.close(); state = SteeringHardwareState() }
        }
    }
    LaunchedEffect(moving, state.epoch) { if (moving) controller?.stop(state.epoch) }
    val guarded: ((SyuSteeringController, Long) -> Unit) -> Unit = { action ->
        val epoch = state.epoch
        val client = controller
        if (!moving && client != null) onParkedAction {
            if (!latestMoving && controller === client && state.epoch == epoch) action(client, epoch)
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.steer_hw_title), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onClose) { Text(stringResource(R.string.vehicle_close)) }
                }
                SteeringHardwareControls(state, moving,
                    begin = { mode -> guarded { client, epoch -> client.begin(epoch, mode) } },
                    assign = { code, func -> guarded { client, epoch -> client.assign(epoch, code, func) } },
                    save = { guarded { client, epoch -> client.save(epoch) } },
                    clear = { mode -> guarded { client, epoch -> client.clear(epoch, mode) } },
                    stop = { controller?.stop(state.epoch) },
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
internal fun SteeringHardwareControls(
    state: SteeringHardwareState, moving: Boolean,
    begin: (SteeringLearningMode) -> Unit, assign: (Int, Int) -> Unit,
    save: () -> Unit, clear: (SteeringLearningMode) -> Unit, stop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember(state.epoch) { mutableStateOf(SteeringLearningMode.ADC) }
    var clearing by remember(state.epoch) { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var slot by remember { mutableStateOf(0) }
    val enabled = state.connected && state.feedbackSeen && !moving
    val active = state.mode != null
    val canAssign = enabled && state.mode == mode && state.pending == null &&
        (mode == SteeringLearningMode.MCU || (state.detecting == true && state.adc != null && state.adc != 50))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.steer_hw_scope))
        SettingsNotice(stringResource(when (state.status) {
            SteeringHardwareStatus.DISCONNECTED -> R.string.steer_hw_disconnected
            SteeringHardwareStatus.READY -> if (state.feedbackSeen) R.string.steer_hw_ready else R.string.steer_hw_waiting
            SteeringHardwareStatus.STARTED -> R.string.steer_hw_started
            SteeringHardwareStatus.ASSIGNING -> R.string.steer_hw_assigning
            SteeringHardwareStatus.LEARNED -> R.string.steer_hw_learned
            SteeringHardwareStatus.SAVE_SENT -> R.string.steer_hw_saved
            SteeringHardwareStatus.CLEAR_SENT -> R.string.steer_hw_cleared
            SteeringHardwareStatus.TIMED_OUT -> R.string.steer_hw_timeout
            SteeringHardwareStatus.FAILED -> R.string.steer_hw_failed
        }))
        if (moving) Text(stringResource(R.string.syu_sound_parked))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == SteeringLearningMode.ADC, enabled = !active,
                onClick = { mode = SteeringLearningMode.ADC }, label = { Text(stringResource(R.string.steer_hw_adc)) })
            FilterChip(selected = mode == SteeringLearningMode.MCU, enabled = !active && state.mcuEnabled == true,
                onClick = { mode = SteeringLearningMode.MCU }, label = { Text(stringResource(R.string.steer_hw_mcu)) })
        }
        if (state.mcuEnabled != true) Text(stringResource(R.string.steer_hw_mcu_unavailable))
        Text(stringResource(if (mode == SteeringLearningMode.ADC) R.string.steer_hw_adc_help else R.string.steer_hw_mcu_help))
        if (!active) {
            Button(onClick = { begin(mode) }, enabled = enabled) { Text(stringResource(R.string.steer_hw_start)) }
            OutlinedButton(onClick = { clearing = true }, enabled = enabled) { Text(stringResource(R.string.steer_hw_clear)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = save, enabled = enabled && state.pending == null) { Text(stringResource(R.string.steer_hw_finish_save)) }
                if (mode == SteeringLearningMode.ADC || state.pending != null) {
                    TextButton(onClick = stop) { Text(stringResource(if (mode == SteeringLearningMode.ADC) R.string.steer_hw_stop else R.string.steer_hw_finish_save)) }
                }
            }
            if (mode == SteeringLearningMode.ADC) {
                Text(stringResource(R.string.steer_hw_signal, state.adc?.toString() ?: "—"))
                SyuSteeringProtocol.adcFunctions.forEachIndexed { index, name ->
                    TextButton(onClick = { assign(index, 1) }, enabled = canAssign, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(steeringFunctionLabel(name)))
                    }
                }
            } else {
                FilterChip(selected = custom, onClick = { custom = !custom }, label = { Text(stringResource(R.string.steer_hw_custom)) })
                if (!custom) SyuSteeringProtocol.mcuFunctions.forEachIndexed { index, name ->
                    TextButton(onClick = { assign(index, 1) }, enabled = canAssign, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(steeringFunctionLabel(name)))
                    }
                } else {
                    Text(stringResource(R.string.steer_hw_slot_help))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(20) { index ->
                            FilterChip(selected = slot == index, onClick = { slot = index },
                                label = { Text(stringResource(R.string.steer_hw_slot, index + 1)) })
                        }
                    }
                    SyuSteeringProtocol.customFunctions.forEach { (function, name) ->
                        TextButton(onClick = { assign(25 + slot, function) }, enabled = canAssign,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(steeringFunctionLabel(name))) }
                    }
                }
            }
        }
        Text(stringResource(R.string.steer_hw_feedback), style = MaterialTheme.typography.titleMedium)
        if (mode == SteeringLearningMode.ADC) {
            state.learnedAdc.filter { it.key in SyuSteeringProtocol.adcFunctions.indices && it.value >= 0 && it.value != 50 }
                .toSortedMap().forEach { (key, value) ->
                    Text(stringResource(steeringFunctionLabel(SyuSteeringProtocol.adcFunctions[key])) + ": " + value)
                }
        } else {
            state.mcuKeys.filter { it.key in 0..44 && it.value != 2 }.toSortedMap().forEach { (key, value) ->
                val label = if (key < 25) SyuSteeringProtocol.mcuFunctions.getOrNull(key) else SyuSteeringProtocol.customFunctions[value]
                if (label != null) Text(stringResource(R.string.steer_hw_slot, key + 1) + ": " + stringResource(steeringFunctionLabel(label)))
            }
        }
        Text(stringResource(R.string.steer_hw_backup_note))
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false },
        title = { Text(stringResource(R.string.steer_hw_clear)) },
        text = { Text(stringResource(R.string.steer_hw_clear_detail)) },
        confirmButton = { TextButton(onClick = { if (enabled && !active) clear(mode); clearing = false }, enabled = enabled && !active) {
            Text(stringResource(R.string.steer_hw_clear))
        } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.action_cancel)) } })
}
