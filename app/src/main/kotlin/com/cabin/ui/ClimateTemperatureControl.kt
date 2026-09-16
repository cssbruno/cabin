package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabin.R
import com.cabin.platform.*

/** Shared widget/panel control; temperature is always confirmed vehicle feedback. */
@Composable
internal fun ClimateTemperatureControl(
    state: TeyesClimateState,
    zone: TeyesTemperatureZone,
    onAdjust: ((TeyesTemperatureZone, Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    showArrows: Boolean = true,
    fontSize: TextUnit = 34.sp,
    horizontal: Boolean = false,
) {
    val driver = zone == TeyesTemperatureZone.DRIVER
    val label = stringResource(if (driver) R.string.widget_driver else R.string.widget_passenger)
    val code = if (driver) 25 else 31
    val known = state.connected && state.health == TeyesTelemetryHealth.LIVE && code in state.availableCodes && 33 in state.availableCodes
    val raw = (if (driver) state.leftTemperature else state.rightTemperature).takeIf { known }
    val arrows = showArrows
    val unavailable = stringResource(R.string.climate_temperature_unavailable)
    @Composable fun Arrow(up: Boolean) {
        val enabled = onAdjust != null && TeyesClimateControlPolicy.canAdjustTemperature(state, zone, up)
        FilledTonalIconButton(onClick = { onAdjust?.invoke(zone, up) }, enabled = enabled,
            modifier = Modifier.size(56.dp).semantics { if (!enabled) stateDescription = unavailable }) {
            Icon(if (horizontal) { if (up) Icons.Default.Add else Icons.Default.Remove }
                else if (up) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                stringResource(if (up) R.string.climate_temperature_increase else R.string.climate_temperature_decrease, label),
                Modifier.size(28.dp))
        }
    }
    @Composable fun Reading(modifier: Modifier = Modifier) {
        Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatClimateTemperature(raw, state.fahrenheit), fontSize = fontSize, fontWeight = FontWeight.Light, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
    if (horizontal) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (arrows) Arrow(false)
            Reading(Modifier.weight(1f))
            if (arrows) Arrow(true)
        }
    } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (arrows) Arrow(true)
        Reading()
        if (arrows) Arrow(false)
    }
}
