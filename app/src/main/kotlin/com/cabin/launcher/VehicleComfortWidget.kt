package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.ui.formatClimateTemperature

data class ClimateWidgetActions(
    val onAc: ((Boolean) -> Unit)? = null,
    val onFan: ((Int) -> Unit)? = null,
    val onRefresh: (() -> Unit)? = null,
)

/** Shared FYT toolkit telemetry; freshness is checked per field, including temperature units. */
internal fun comfortFieldAvailable(state: TeyesClimateState, vararg codes: Int): Boolean =
    state.connected && state.health == TeyesTelemetryHealth.LIVE && codes.all { it in state.availableCodes }

@Composable
internal fun VehicleComfortWidget(module: DashboardModule, state: TeyesClimateState, actions: ClimateWidgetActions = ClimateWidgetActions(), onClimate: () -> Unit) {
    fun known(vararg codes: Int) = comfortFieldAvailable(state, *codes)
    fun level(code: Int, value: Int) = if (known(code)) value.toString() else "—"
    fun temperature(code: Int, value: Int?) = formatClimateTemperature(value?.takeIf { known(code, 33) }, state.fahrenheit)
    val alternate = state.profileId == 262465
    val fanCode = if (alternate) 35 else 29
    val acCode = if (alternate) 30 else 24
    val title = stringResource(module.title())
    val rows: List<Pair<String, String>> = when (module) {
        DashboardModule.CLIMATE -> listOf(
            stringResource(R.string.widget_driver) to temperature(25, state.leftTemperature),
            stringResource(R.string.widget_passenger) to temperature(31, state.rightTemperature))
        DashboardModule.REAR_CLIMATE -> listOf(
            stringResource(R.string.widget_rear) to temperature(52, state.rearTemperature),
            stringResource(R.string.widget_fan) to level(56, state.rearFanLevel))
        DashboardModule.FAN -> listOf(stringResource(R.string.widget_fan) to level(fanCode, state.fanLevel))
        DashboardModule.SEATS -> listOf(
            "${stringResource(R.string.widget_driver)} · ${stringResource(R.string.widget_heat)}" to level(95, state.driverSeatHeating),
            "${stringResource(R.string.widget_driver)} · ${stringResource(R.string.widget_cooling)}" to level(94, state.driverSeatCooling),
            "${stringResource(R.string.widget_passenger)} · ${stringResource(R.string.widget_heat)}" to level(97, state.passengerSeatHeating),
            "${stringResource(R.string.widget_passenger)} · ${stringResource(R.string.widget_cooling)}" to level(96, state.passengerSeatCooling))
        else -> listOf(
            stringResource(R.string.widget_front) to if (!known(22)) "—" else stringResource(if (state.frontDefrost) R.string.climate_state_on else R.string.interface_state_off),
            stringResource(R.string.widget_rear) to if (!known(23)) "—" else stringResource(if (state.rearDefrost) R.string.climate_state_on else R.string.interface_state_off))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 160.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when (module) {
                    DashboardModule.FAN -> Icons.Default.Air
                    DashboardModule.SEATS -> Icons.Default.EventSeat
                    DashboardModule.DEFROST -> Icons.Default.Dehaze
                    else -> Icons.Default.AcUnit
                }, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                if (!compact) Text(title, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                else Spacer(Modifier.weight(1f))
                if (module in setOf(DashboardModule.CLIMATE, DashboardModule.FAN) && actions.onRefresh != null) IconButton(actions.onRefresh, Modifier.size(56.dp)) {
                    Icon(Icons.Default.Refresh, stringResource(R.string.widget_refresh_climate))
                }
                if (module == DashboardModule.CLIMATE) IconButton(onClimate, Modifier.size(56.dp)) {
                    Icon(Icons.Default.Tune, stringResource(R.string.climate_open_controls))
                }
            }
            if (module == DashboardModule.FAN) {
                com.cabin.ui.FanSpeedControls(state, actions.onFan, Modifier.fillMaxWidth())
            } else rows.chunked(if (module == DashboardModule.SEATS) 2 else rows.size).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, value) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(value, fontSize = if (compact || module == DashboardModule.SEATS) 24.sp else 36.sp, fontWeight = FontWeight.Light, maxLines = 1)
                            if (!compact) Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (module == DashboardModule.CLIMATE && !compact) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ actions.onFan?.invoke((state.fanLevel - 1).coerceAtLeast(1)) }, Modifier.size(56.dp),
                        enabled = (state.fanControlsAvailable || state.controlsAvailable) && known(fanCode) && actions.onFan != null && state.fanLevel > 1) {
                        Icon(Icons.Default.Remove, stringResource(R.string.climate_fan_lower))
                    }
                    FilledTonalButton({ actions.onAc?.invoke(!state.ac) }, Modifier.heightIn(min = 56.dp),
                        enabled = state.controlsAvailable && known(acCode) && actions.onAc != null,
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text(stringResource(if (!known(acCode)) R.string.climate_ac_unknown else if (state.ac) R.string.climate_ac_on else R.string.climate_ac_off), maxLines = 1)
                    }
                    IconButton({ actions.onFan?.invoke((state.fanLevel + 1).coerceAtMost(7)) }, Modifier.size(56.dp),
                        enabled = (state.fanControlsAvailable || state.controlsAvailable) && known(fanCode) && actions.onFan != null && state.fanLevel < 7) {
                        Icon(Icons.Default.Add, stringResource(R.string.climate_fan_higher))
                    }
                }
                Text(if (known(fanCode)) stringResource(R.string.climate_fan_level, state.fanLevel) else stringResource(R.string.climate_fan_unknown),
                    Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
