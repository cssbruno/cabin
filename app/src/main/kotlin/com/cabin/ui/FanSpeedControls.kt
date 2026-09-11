package com.cabin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesClimateControlPolicy
import com.cabin.platform.TeyesTelemetryHealth
import kotlin.math.roundToInt

internal fun fanSpeedKnown(state: TeyesClimateState): Boolean =
    state.connected && state.health == TeyesTelemetryHealth.LIVE &&
        TeyesClimateControlPolicy.fanCode(state.profileId) in state.availableCodes && state.fanLevel in 0..7

internal fun fanSpeedEnabled(state: TeyesClimateState): Boolean =
    fanSpeedKnown(state) && (state.fanControlsAvailable || state.controlsAvailable)

/** Seven steps, one command when released. The label always shows actual CAN feedback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FanSpeedControls(state: TeyesClimateState, onFan: ((Int) -> Unit)?, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.fan_select_speed)
    val known = fanSpeedKnown(state)
    val enabled = fanSpeedEnabled(state) && onFan != null
    val latestState by rememberUpdatedState(state)
    val latestAction by rememberUpdatedState(onFan)
    key(state.profileId, enabled) {
        val profile = state.profileId
        var preview by remember { mutableStateOf<Int?>(null) }
        val displayed = preview ?: if (known) state.fanLevel else 0
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.outlineVariant
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Air, null, Modifier.size(20.dp), tint = if (enabled) active else inactive)
                Text(if (known) stringResource(R.string.climate_fan_level, state.fanLevel) else stringResource(R.string.climate_fan_unknown),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(displayed.coerceIn(1, 7).toFloat(), 1f..7f, 5)
                if (!enabled) disabled()
                setProgress { value ->
                    if (!enabled || !value.isFinite()) false else {
                        val level = value.roundToInt().coerceIn(1, 7)
                        if (latestState.profileId == profile && fanSpeedEnabled(latestState) && level != latestState.fanLevel) latestAction?.invoke(level)
                        true
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Slider(value = (preview ?: state.fanLevel).coerceIn(1, 7).toFloat(),
                onValueChange = { preview = it.roundToInt().coerceIn(1, 7) },
                onValueChangeFinished = {
                    val selected = preview ?: 1.takeIf { latestState.fanLevel == 0 }
                    if (selected != null && latestState.profileId == profile && fanSpeedEnabled(latestState) && selected != latestState.fanLevel) {
                        latestAction?.invoke(selected)
                    }
                    preview = null
                }, enabled = enabled, valueRange = 1f..7f, steps = 5,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                track = {
                    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                        val gap = 4.dp.toPx()
                        val segmentWidth = ((size.width - gap * 6) / 7).coerceAtLeast(1f)
                        for (index in 0..6) {
                            val height = (10 + index * 3).dp.toPx()
                            drawRoundRect(if (index < displayed && known) active.copy(alpha = if (enabled) 1f else 0.4f) else inactive,
                                topLeft = Offset(index * (segmentWidth + gap), size.height - height),
                                size = Size(segmentWidth, height), cornerRadius = CornerRadius(3.dp.toPx()))
                        }
                    }
                })
            }
        }
    }
}
