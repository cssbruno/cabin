package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabin.R
import com.cabin.platform.TeyesClimateControlPolicy
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesAirflowMode
import com.cabin.ui.FanSpeedControls
import com.cabin.ui.formatClimateTemperature

/** One resizable A/C widget: side-by-side when wide, stacked when tall, compact at the smallest sizes. */
@Composable
internal fun AcWidget(state: TeyesClimateState, actions: ClimateWidgetActions, onClimate: () -> Unit) {
    val acKnown = comfortFieldAvailable(state, TeyesClimateControlPolicy.acCode(state.profileId))
    @Composable fun AcButton(modifier: Modifier) {
        FilledTonalButton({ actions.onAc?.invoke(!state.ac) }, modifier.heightIn(min = 56.dp),
            enabled = acKnown && state.controlsAvailable && actions.onAc != null,
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(Icons.Default.AcUnit, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(if (!acKnown) R.string.climate_ac_unknown else if (state.ac) R.string.climate_ac_on else R.string.climate_ac_off), maxLines = 1)
        }
    }
    @Composable fun OpenPanel() {
        IconButton(onClimate, Modifier.size(56.dp)) {
            Icon(Icons.Default.Tune, stringResource(R.string.climate_open_controls))
        }
    }
    @Composable fun Header(refresh: Boolean = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AcButton(Modifier.weight(1f))
            if (refresh && actions.onRefresh != null) IconButton(actions.onRefresh, Modifier.size(56.dp)) {
                Icon(Icons.Default.Refresh, stringResource(R.string.widget_refresh_climate))
            }
            OpenPanel()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        val narrow = maxWidth < 240.dp
        val short = maxHeight < (if (maxWidth < 216.dp) 340.dp else if (maxWidth < 440.dp) 280.dp else 160.dp)
        val tiny = maxWidth < 180.dp
        val horizontal = maxWidth >= 440.dp && maxWidth > maxHeight * 1.25f
        val temperatures = listOf(
            Triple(R.string.widget_driver, 25, state.leftTemperature),
            Triple(R.string.widget_passenger, 31, state.rightTemperature))
        @Composable fun Temperature(labelId: Int, code: Int, raw: Int?, modifier: Modifier) {
            val label = stringResource(labelId)
            val value = formatClimateTemperature(raw?.takeIf { comfortFieldAvailable(state, code, 33) }, state.fahrenheit)
            Column(modifier.semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = if (narrow) 24.sp else 34.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Thermostat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        @Composable fun Temperatures(stacked: Boolean) {
            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                temperatures.forEach { (label, code, raw) -> Temperature(label, code, raw, Modifier.fillMaxWidth()) }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                temperatures.forEach { (label, code, raw) -> Temperature(label, code, raw, Modifier.weight(1f)) }
            }
        }
        when {
            tiny && maxHeight >= 128.dp -> Column(Modifier.fillMaxSize().padding(8.dp).testTag("ac-compact"),
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                AcButton(Modifier.fillMaxWidth())
                OpenPanel()
            }
            short -> Row(Modifier.fillMaxSize().padding(8.dp).testTag("ac-compact"), verticalAlignment = Alignment.CenterVertically) {
                if (availableWidth >= 480.dp) {
                    AcButton(Modifier.width(96.dp))
                    FanSpeedControls(state, actions.onFan, Modifier.weight(1f))
                    OpenPanel()
                } else Header(refresh = !narrow)
            }
            horizontal -> Row(Modifier.fillMaxSize().padding(12.dp).testTag("ac-horizontal"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceEvenly) {
                    Header()
                    Temperatures(stacked = false)
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    FanSpeedControls(state, actions.onFan, Modifier.fillMaxWidth())
                    AcModes(state, actions)
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(if (narrow) 8.dp else 12.dp).testTag("ac-vertical"),
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                Header(refresh = !narrow)
                Temperatures(stacked = availableHeight >= 360.dp && availableHeight > availableWidth)
                FanSpeedControls(state, actions.onFan, Modifier.fillMaxWidth())
                AcModes(state, actions)
            }
        }
    }
}

/** Airflow writes use the same verified controller as the full panel; recirculation is telemetry only. */
@Composable
private fun AcModes(state: TeyesClimateState, actions: ClimateWidgetActions) {
    val airflowKnown = additionalVehicleFieldKnown(DashboardModule.AIRFLOW, state)
    val recircKnown = comfortFieldAvailable(state, 21)
    val enabled = airflowKnown && state.controlsAvailable && actions.onAirflow != null
    var selecting by remember { mutableStateOf(false) }
    LaunchedEffect(enabled, state.profileId) { if (!enabled) selecting = false }
    val airflowLabel = stringResource(R.string.widget_airflow)
    val recircLabel = stringResource(R.string.widget_recirculation)
    val currentMode = when {
        !airflowKnown -> null
        state.blowUp && state.blowFoot -> R.string.climate_screen_feet
        state.blowBody && state.blowFoot -> R.string.climate_face_feet
        state.blowBody -> R.string.climate_face
        state.blowFoot -> R.string.climate_feet
        state.blowUp -> R.string.widget_air_screen
        else -> R.string.interface_state_off
    }
    val currentText = currentMode?.let { stringResource(it) } ?: "—"
    val recircText = if (!recircKnown) "—" else stringResource(if (state.recirculating) R.string.climate_state_on else R.string.interface_state_off)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.weight(1f)) {
            TextButton({ selecting = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                    contentDescription = airflowLabel; stateDescription = currentText
                }, contentPadding = PaddingValues(4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Air, null, Modifier.size(22.dp))
                    Text(currentText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DropdownMenu(selecting && enabled, { selecting = false }) {
                listOf(TeyesAirflowMode.BODY to R.string.climate_face, TeyesAirflowMode.BODY_FOOT to R.string.climate_face_feet,
                    TeyesAirflowMode.FOOT to R.string.climate_feet, TeyesAirflowMode.UP_FOOT to R.string.climate_screen_feet).forEach { (mode, label) ->
                    DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 56.dp),
                        onClick = { selecting = false; actions.onAirflow?.invoke(mode) })
                }
            }
        }
        Column(Modifier.weight(1f).heightIn(min = 56.dp).semantics(mergeDescendants = true) {
            contentDescription = recircLabel; stateDescription = recircText
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Autorenew, null, Modifier.size(22.dp),
                tint = if (recircKnown && state.recirculating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(recircText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
