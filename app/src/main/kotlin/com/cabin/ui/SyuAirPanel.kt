package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabin.R
import com.cabin.platform.SyuAirState

/** Profile-specific controls; a missing mapping never falls back to another car's keys. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SyuAirPanel(state: SyuAirState, onAction: ((String) -> Unit)?, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val closeTimer = rememberClimateCloseTimer(onClose)
    Column(modifier.then(closeTimer.touchModifier).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.climate_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            onClose?.let { close -> ClimateCloseButton(close, closeTimer.remaining.value) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.actions.isEmpty()) Text(stringResource(R.string.syu_air_unavailable), style = MaterialTheme.typography.bodyMedium)
            if (state.actions.any { it.startsWith("C_AIR_TEMP_") }) SyuTemperatureRow(state, onAction)
            for ((title, keys) in syuActionGroups) {
                val available = keys.filter { it.first in state.actions && !it.first.startsWith("C_AIR_TEMP_") }
                if (available.isEmpty()) continue
                Text(stringResource(title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((key, label) in available) {
                        FilledTonalButton(onClick = { onAction?.invoke(key) }, enabled = onAction != null && state.canSend(key),
                            modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                            if (key.endsWith("ADD") || key.endsWith("UP")) Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(22.dp))
                            else if (key.endsWith("SUB") || key.endsWith("DOWN")) Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(22.dp))
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
    }
}

/** No vertical scrolling inside dashboard tiles: capacity follows the actual tile dimensions. */
@Composable
internal fun SyuAirWidget(state: SyuAirState, onAction: ((String) -> Unit)?, onOpen: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        if (maxHeight < 116.dp || maxWidth < 96.dp) {
            IconButton(onOpen, Modifier.size(56.dp).align(Alignment.Center)) {
                Icon(Icons.Default.Tune, stringResource(R.string.climate_open_controls))
            }
            return@BoxWithConstraints
        }
        val columns = (maxWidth.value / 144f).toInt().coerceIn(1, 4)
        val rows = ((maxHeight.value - 56f) / 64f).toInt().coerceIn(1, 6)
        val temperaturePage = maxHeight >= 256.dp && maxWidth >= 240.dp && state.actions.any { it.startsWith("C_AIR_TEMP_") }
        val actions = syuActionGroups.flatMap { it.second }.filter { it.first in state.actions && (!temperaturePage || !it.first.startsWith("C_AIR_TEMP_")) }.distinctBy { it.first }
        val pages = actions.chunked(columns * rows).ifEmpty { listOf(emptyList()) }
        val pageCount = pages.size + if (temperaturePage) 1 else 0
        val pager = rememberPagerState(pageCount = { pageCount })
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                if (temperaturePage && page == 0) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SyuTemperatureRow(state, onAction) }
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pages[page - if (temperaturePage) 1 else 0].chunked(columns).forEach { cells ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            cells.forEach { (key, label) ->
                                FilledTonalButton(onClick = { onAction?.invoke(key) },
                                    enabled = onAction != null && state.canSend(key), modifier = Modifier.weight(1f).height(60.dp),
                                    contentPadding = PaddingValues(4.dp)) {
                                    Text(stringResource(label), maxLines = 2, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                val start = (pager.currentPage - 2).coerceIn(0, (pageCount - 5).coerceAtLeast(0))
                Text((start until minOf(start + 5, pageCount)).joinToString(" ") { if (it == pager.currentPage) "●" else "·" },
                    Modifier.weight(1f), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onOpen, Modifier.size(56.dp)) { Icon(Icons.Default.Tune, stringResource(R.string.climate_open_controls)) }
            }
        }
    }
}

@Composable
private fun SyuTemperatureRow(state: SyuAirState, onAction: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("LEFT" to R.string.widget_driver, "RIGHT" to R.string.widget_passenger).forEach { (side, labelId) ->
            val label = stringResource(labelId)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val up = "C_AIR_TEMP_${side}_ADD"
                val down = "C_AIR_TEMP_${side}_SUB"
                IconButton({ onAction?.invoke(up) }, enabled = onAction != null && state.canSend(up), modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.climate_temperature_increase, label))
                }
                Text(state.temperatureText("U_AIR_TEMP_$side"), fontSize = 32.sp, maxLines = 1)
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                IconButton({ onAction?.invoke(down) }, enabled = onAction != null && state.canSend(down), modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.climate_temperature_decrease, label))
                }
            }
        }
    }
}

private val syuActionGroups = listOf(
    R.string.syu_air_front to listOf(
        "C_AIR_POWER" to R.string.climate_power, "C_AIR_AC" to R.string.syu_air_ac,
        "C_AIR_AUTO" to R.string.climate_state_auto, "C_AIR_DUAL" to R.string.climate_state_dual,
        "C_AIR_SYNC" to R.string.syu_air_sync, "C_AIR_CYCLE" to R.string.widget_recirculation,
        "C_AIR_FRONT_DEFROST" to R.string.climate_state_front_defrost, "C_AIR_REAR_DEFROST" to R.string.climate_state_rear_defrost,
        "C_AIR_AC_MAX" to R.string.syu_air_max_ac, "C_AIR_MAX_FRONT_DEFROST" to R.string.syu_air_max_defrost),
    R.string.climate_title to listOf(
        "C_AIR_TEMP_LEFT_ADD" to R.string.syu_air_driver_up, "C_AIR_TEMP_LEFT_SUB" to R.string.syu_air_driver_down,
        "C_AIR_TEMP_RIGHT_ADD" to R.string.syu_air_passenger_up, "C_AIR_TEMP_RIGHT_SUB" to R.string.syu_air_passenger_down,
        "C_AIR_WIND_ADD" to R.string.syu_air_fan_up, "C_AIR_WIND_SUB" to R.string.syu_air_fan_down),
    R.string.widget_airflow to listOf(
        "C_AIR_MODE_BODY" to R.string.climate_face, "C_AIR_MODE_FOOT" to R.string.climate_feet,
        "C_AIR_MODE_BODYFOOT" to R.string.climate_face_feet, "C_AIR_MODE_UPFOOT" to R.string.climate_screen_feet,
        "C_AIR_MODE_UP" to R.string.widget_air_screen, "C_AIR_MODE_CHANGER" to R.string.syu_air_next_mode,
        "C_AIR_MODE_ADD" to R.string.syu_air_next_mode, "C_AIR_MODE_SUB" to R.string.syu_air_previous_mode),
    R.string.syu_air_seats to listOf(
        "C_AIR_LEFT_HEAT" to R.string.syu_air_driver_heat, "C_AIR_RIGHT_HEAT" to R.string.syu_air_passenger_heat,
        "C_AIR_LEFT_COLD" to R.string.syu_air_driver_cool, "C_AIR_RIGHT_COLD" to R.string.syu_air_passenger_cool),
    R.string.syu_air_rear to listOf(
        "C_REAR_OFF" to R.string.syu_c_rear_off,
        "C_REAR_AUTO" to R.string.syu_c_rear_auto,
        "C_REAR_LOCK" to R.string.syu_c_rear_lock,
        "C_REAR_CTRL" to R.string.syu_c_rear_ctrl,
        "C_REAR_TEMP_UP" to R.string.syu_c_rear_temp_up,
        "C_REAR_TEMP_DOWN" to R.string.syu_c_rear_temp_down,
        "C_REAR_WIND_UP" to R.string.syu_c_rear_wind_up,
        "C_REAR_WIND_DOWN" to R.string.syu_c_rear_wind_down,
        "C_REAR_MODE" to R.string.syu_c_rear_mode,
        "C_REAR_MODE_BODY" to R.string.syu_c_rear_mode_body,
        "C_REAR_MODE_FOOT" to R.string.syu_c_rear_mode_foot,
        "C_REAR_MODE_BODY_FOOT" to R.string.syu_c_rear_mode_body_foot,
        "C_REAR_HEAT" to R.string.syu_c_rear_heat,
        "C_REAR_COOL" to R.string.syu_c_rear_cool),
    R.string.syu_air_extra to listOf(
        "C_AIR_STEER" to R.string.syu_c_air_steer,
        "C_AIR_POWER_FRONT" to R.string.syu_c_air_power_front,
        "C_AIR_AUTO_RIGHT" to R.string.syu_c_air_auto_right,
        "C_AIR_MODE_CHANGER_RIGHT" to R.string.syu_c_air_mode_changer_right,
        "C_AIR_MODE_BODY_RIGHT" to R.string.syu_c_air_mode_body_right,
        "C_AIR_MODE_FOOT_RIGHT" to R.string.syu_c_air_mode_foot_right,
        "C_AIR_MODE_UP_RIGHT" to R.string.syu_c_air_mode_up_right,
        "C_AIR_AQS" to R.string.syu_c_air_aqs,
        "C_AIR_SWING" to R.string.syu_c_air_swing,
        "C_AIR_ZONE" to R.string.syu_c_air_zone,
        "C_AIR_PTC" to R.string.syu_c_air_ptc,
        "C_AIR_NANOE" to R.string.syu_c_air_nanoe,
        "C_AIR_ION" to R.string.syu_c_air_ion,
        "C_AIR_BLOWTOP" to R.string.syu_c_air_blowtop,
        "C_AIR_FRONT_HOT" to R.string.syu_c_air_front_hot,
        "C_AIR_COOL" to R.string.syu_c_air_cool,
        "C_AIR_ECO" to R.string.syu_c_air_eco,
        "C_AIR_FOREST" to R.string.syu_c_air_forest,
        "C_AIR_HEAT" to R.string.syu_c_air_heat,
        "C_AIR_REARVIEW_HOT" to R.string.syu_c_air_rearview_hot,
        "C_AIR_REST" to R.string.syu_c_air_rest,
        "C_CLEAN" to R.string.syu_c_clean,
        "C_CLEAN_AIR" to R.string.syu_c_clean_air,
        "C_FAST" to R.string.syu_c_fast,
        "C_NORMAL" to R.string.syu_c_normal,
        "C_SOFT" to R.string.syu_c_soft
    )
)
