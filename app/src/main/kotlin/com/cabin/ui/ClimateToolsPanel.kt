package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun ClimateToolsPanel(state: TeyesClimateState, onAc: ((Boolean) -> Unit)?, onFan: ((Int) -> Unit)?, onSwitch: ((TeyesClimateSwitch) -> Unit)?) {
    val context = LocalContext.current
    val prefs = remember { vehicleToolsPreferences(context) }
    val values = rememberAutomationValues("cabin_vehicle_tools")
    val favorites = (values["climateFavorites"] as? String)?.split(',')?.filter { it.isNotBlank() }?.distinct().orEmpty()
    fun saveFavorites(next: List<String>) { prefs.edit().putString("climateFavorites", next.take(6).joinToString(",")).apply() }
    var edit by remember(state.profileId) { mutableStateOf(false) }
    val controls = buildList<Pair<String, Int>> {
        add("ac" to R.string.climate_ac_on)
        add("fan" to R.string.gv_fan)
        TeyesClimateSwitch.entries.forEach { add(it.name to when(it) {
            TeyesClimateSwitch.POWER -> R.string.gv_power; TeyesClimateSwitch.AUTO -> R.string.gv_auto; TeyesClimateSwitch.DUAL -> R.string.gv_dual
            TeyesClimateSwitch.RECIRCULATION -> R.string.gv_recirculation; TeyesClimateSwitch.FRONT_DEFROST -> R.string.gv_front_defrost; TeyesClimateSwitch.REAR_DEFROST -> R.string.gv_rear_defrost
        }) }
    }
    fun supported(key: String): Boolean = if(key in listOf("ac", "fan")) state.controlsSupported else TeyesClimateControlPolicy.supportsTemperature(state.profileId)
    fun enabled(key: String): Boolean = when(key) {
        "ac" -> state.controlsAvailable && onAc != null
        "fan" -> state.fanControlsAvailable && onFan != null
        else -> TeyesClimateSwitch.entries.firstOrNull { it.name == key }?.let { TeyesClimateControlPolicy.canToggle(state, it) && onSwitch != null } == true
    }
    if(state.commandStatus != ClimateCommandStatus.NONE) Text(stringResource(when(state.commandStatus) {
        ClimateCommandStatus.WAITING -> R.string.gv_command_waiting; ClimateCommandStatus.CONFIRMED -> R.string.gv_command_confirmed
        ClimateCommandStatus.TIMED_OUT -> R.string.gv_command_timeout; else -> R.string.gv_command_cancelled
    }), style = MaterialTheme.typography.bodySmall)
    if(state.voluntaryReadOnly) Text(stringResource(R.string.gv_read_only))
    if(controls.isNotEmpty()) {
        TextButton(onClick = { edit = !edit }) { Text(stringResource(if(edit) R.string.launcher_done else R.string.gv_favorites)) }
        if(edit) Text(stringResource(R.string.uxv_favorite_limit, favorites.size), style = MaterialTheme.typography.bodySmall)
        FlowRow {
            controls.filter { edit && supported(it.first) || it.first in favorites }.sortedBy { favorites.indexOf(it.first).takeIf { index -> index >= 0 } ?: 99 }.forEach { (key, title) ->
                if(edit) FilterChip(selected = key in favorites, enabled = key in favorites || favorites.size < 6, onClick = {
                    saveFavorites(if(key in favorites) favorites - key else favorites + key)
                }, label = { Text(stringResource(title)) })
                if(edit && key in favorites) TextButton(onClick = {
                    val index = favorites.indexOf(key)
                    if(index > 0) { val list = favorites.toMutableList(); list[index] = list[index - 1]; list[index - 1] = key; saveFavorites(list) }
                }, enabled = favorites.indexOf(key) > 0) { Text(stringResource(R.string.uxv_move_favorite, stringResource(title))) }
                if(!edit) FilledTonalButton(enabled = enabled(key), onClick = {
                    when(key) { "ac" -> onAc?.invoke(!state.ac); "fan" -> onFan?.invoke(if(state.fanLevel >= 7) 1 else state.fanLevel + 1)
                        else -> TeyesClimateSwitch.entries.firstOrNull { it.name == key }?.let { onSwitch?.invoke(it) }
                    }
                }) { Text(stringResource(title)) }
                if(!edit && !enabled(key)) Text(stringResource(title) + ": " + stringResource(when {
                    state.voluntaryReadOnly -> R.string.gv_read_only
                    !state.connected -> R.string.vehicle_status_disconnected
                    !state.controlsSupported || key !in listOf("ac", "fan") && !TeyesClimateControlPolicy.supportsTemperature(state.profileId) -> R.string.gv_unsupported
                    else -> R.string.gv_missing_feedback
                }), style = MaterialTheme.typography.bodySmall)
            }
        }
        if(controls.any { it.first in favorites && !enabled(it.first) }) Text(stringResource(when {
            state.voluntaryReadOnly -> R.string.gv_read_only
            !state.connected -> R.string.vehicle_status_disconnected
            else -> R.string.gv_stale_controls
        }), style = MaterialTheme.typography.bodySmall)
    }
}
