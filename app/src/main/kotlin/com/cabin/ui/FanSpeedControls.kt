package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesClimateControlPolicy
import com.cabin.platform.TeyesTelemetryHealth

internal fun fanSpeedKnown(state: TeyesClimateState): Boolean =
    state.connected && state.health == TeyesTelemetryHealth.LIVE &&
        TeyesClimateControlPolicy.fanCode(state.profileId) in state.availableCodes && state.fanLevel in 0..7

internal fun fanSpeedEnabled(state: TeyesClimateState): Boolean =
    fanSpeedKnown(state) && (state.fanControlsAvailable || state.controlsAvailable)

/** Values always come from CAN feedback. Selecting a speed sends one command, never a preview write. */
@Composable
internal fun FanSpeedControls(state: TeyesClimateState, onFan: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    var choosing by remember { mutableStateOf(false) }
    val known = fanSpeedKnown(state)
    val enabled = fanSpeedEnabled(state) && onFan != null
    LaunchedEffect(enabled, state.profileId) { if (!enabled) choosing = false }
    @Composable fun Decrease() {
        IconButton({ onFan?.invoke((state.fanLevel - 1).coerceAtLeast(1)) }, Modifier.size(56.dp),
            enabled = enabled && state.fanLevel > 1) {
            Icon(Icons.Default.Remove, stringResource(R.string.climate_fan_lower))
        }
    }
    @Composable fun Increase() {
        IconButton({ onFan?.invoke((state.fanLevel + 1).coerceAtMost(7)) }, Modifier.size(56.dp),
            enabled = enabled && state.fanLevel < 7) {
            Icon(Icons.Default.Add, stringResource(R.string.climate_fan_higher))
        }
    }
    @Composable fun Selector(target: Modifier) {
        val selectLabel = stringResource(R.string.fan_select_speed)
        TextButton({ choosing = true }, target.heightIn(min = 56.dp).semantics { contentDescription = selectLabel }, enabled = enabled) {
            Text(if (known) stringResource(R.string.climate_fan_level, state.fanLevel) else stringResource(R.string.climate_fan_unknown))
        }
    }
    BoxWithConstraints(modifier) {
        if (maxWidth < 200.dp) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Selector(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { Decrease(); Increase() }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Decrease()
            Selector(Modifier.weight(1f))
            Increase()
        }
    }
    if (choosing && enabled) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.widget_fan)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { speed ->
                    val label = stringResource(R.string.climate_fan_level, speed)
                    FilterChip(selected = known && state.fanLevel == speed,
                        onClick = { choosing = false; if (speed != state.fanLevel) onFan?.invoke(speed) },
                        modifier = Modifier.size(56.dp).semantics { contentDescription = label }, label = { Text(speed.toString()) })
                }
            }
        },
        confirmButton = { TextButton({ choosing = false }) { Text(stringResource(R.string.launcher_done)) } },
    )
}
