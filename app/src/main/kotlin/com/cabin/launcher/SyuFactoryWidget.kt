package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import kotlinx.coroutines.launch

internal fun SyuFactoryControl.title() = when (this) {
    SyuFactoryControl.SEAT_PRESET -> R.string.energy_seat_preset
    SyuFactoryControl.CHARGE_CURRENT -> R.string.energy_current_limit
    SyuFactoryControl.CHARGE_TEMPERATURE -> R.string.energy_temperature
    SyuFactoryControl.CHARGE_CLIMATE_BATTERY -> R.string.energy_climate_battery
    SyuFactoryControl.CHARGE_MINIMUM -> R.string.energy_minimum
    SyuFactoryControl.AMBIENT_PALETTE -> R.string.energy_palette
    SyuFactoryControl.CAMERA_MODE -> R.string.vehicle_camera_mode
    SyuFactoryControl.MIRROR_SYNC -> R.string.vehicle_mirror_sync
    SyuFactoryControl.MIRROR_REVERSE_DIP -> R.string.vehicle_mirror_dip
    SyuFactoryControl.MIRROR_PARK_FOLD -> R.string.vehicle_mirror_fold
    SyuFactoryControl.RAIN_WIPERS -> R.string.vehicle_rain_wipers
    SyuFactoryControl.REVERSE_REAR_WIPER -> R.string.vehicle_reverse_wiper
    SyuFactoryControl.PARKING_AUTO -> R.string.vehicle_parking_auto
    SyuFactoryControl.PARKING_FRONT_VOLUME -> R.string.vehicle_parking_front_volume
    SyuFactoryControl.PARKING_FRONT_TONE -> R.string.vehicle_parking_front_tone
    SyuFactoryControl.PARKING_REAR_VOLUME -> R.string.vehicle_parking_rear_volume
    SyuFactoryControl.PARKING_REAR_TONE -> R.string.vehicle_parking_rear_tone
}

@Composable
private fun CompactFactoryWidget(group: SyuFactoryGroup, vehicle: TeyesClimateState, moving: Boolean,
    onChange: ((SyuFactoryControl, Int) -> Unit)?, onParkedAction: (() -> Unit) -> Unit) {
    val controls = SyuFactoryControl.entries.filter { it.group == group && it in vehicle.syuVehicle.factoryCapabilities }
    if (controls.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("—") }
        return
    }
    val pager = rememberPagerState { controls.size }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val showDots = controls.size > 1 && maxHeight >= 120.dp
        val narrow = maxWidth < 224.dp || group == SyuFactoryGroup.SEAT_MEMORY
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val control = controls[page]
                val current = vehicle.syuVehicle.factoryControls[control]?.takeIf { vehicle.connected }
                val enabled = !moving && current != null && onChange != null
                var choosing by remember { mutableStateOf(false) }
                LaunchedEffect(moving, enabled) { if (moving || !enabled) choosing = false }
                @Composable fun valueLabel(value: Int): String = factoryValueLabel(control, value)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text(stringResource(control.title()), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium)
                    if (control.maximum == 1 && control != SyuFactoryControl.AMBIENT_PALETTE) {
                        TextButton(onClick = { onParkedAction { current?.let { onChange?.invoke(control, 1 - it) } } },
                            enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(if (current == null) "—" else stringResource(if (current == 1) R.string.climate_state_on else R.string.interface_state_off))
                        }
                    } else if (narrow) Box {
                        TextButton(onClick = { choosing = true }, enabled = enabled,
                            modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(current?.let { valueLabel(it) } ?: "—", maxLines = 1)
                        }
                        DropdownMenu(expanded = choosing && enabled, onDismissRequest = { choosing = false }) {
                            for (value in 0..control.maximum) DropdownMenuItem(
                                text = { Text(valueLabel(value)) },
                                onClick = { choosing = false; onParkedAction { onChange?.invoke(control, value) } })
                        }
                    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onParkedAction { current?.let { onChange?.invoke(control, it - 1) } } },
                            enabled = enabled && current > 0, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.ChevronLeft, stringResource(R.string.vehicle_setting_previous))
                        }
                        Text(current?.let { valueLabel(it) } ?: "—", Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { onParkedAction { current?.let { onChange?.invoke(control, it + 1) } } },
                            enabled = enabled && current < control.maximum, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.ChevronRight, stringResource(R.string.vehicle_setting_next))
                        }
                    }
                }
            }
            if (showDots) PageDots(pager.currentPage, pager.pageCount, { page -> scope.launch { pager.animateScrollToPage(page) } })
        }
    }
}


@Composable
internal fun SyuFactoryWidget(group: SyuFactoryGroup, vehicle: TeyesClimateState, moving: Boolean,
    onChange: ((SyuFactoryControl, Int) -> Unit)?, onParkedAction: (() -> Unit) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expandedHeight = when (group) { SyuFactoryGroup.CAMERA -> 380.dp; SyuFactoryGroup.MIRRORS -> 440.dp; SyuFactoryGroup.PARKING -> 500.dp; SyuFactoryGroup.CHARGING -> 420.dp; SyuFactoryGroup.AMBIENT -> 500.dp; SyuFactoryGroup.SEAT_MEMORY -> 500.dp }
        if (group !in setOf(SyuFactoryGroup.AMBIENT, SyuFactoryGroup.SEAT_MEMORY) && maxWidth >= 300.dp && maxHeight >= expandedHeight) {
            ExpandedFactoryWidget(group, vehicle, moving, onChange, onParkedAction)
        } else CompactFactoryWidget(group, vehicle, moving, onChange, onParkedAction)
    }
}

@Composable
internal fun factoryValueLabel(control: SyuFactoryControl, value: Int): String = when (control) {
    SyuFactoryControl.SEAT_PRESET -> stringResource(listOf(R.string.energy_default, R.string.energy_save, R.string.energy_activate)[value])
    SyuFactoryControl.CAMERA_MODE -> stringResource(listOf(R.string.vehicle_camera_wide, R.string.vehicle_camera_standard, R.string.vehicle_camera_down)[value])
    SyuFactoryControl.AMBIENT_PALETTE -> stringResource(if (value == 0) R.string.energy_recommended else R.string.energy_theme)
    SyuFactoryControl.CHARGE_CURRENT -> if (value == 3) stringResource(R.string.energy_maximum) else "${SyuFactoryProtocol.chargeCurrents[value]} A"
    SyuFactoryControl.CHARGE_TEMPERATURE -> when (value) {
        0 -> stringResource(R.string.energy_low)
        29 -> stringResource(R.string.energy_high)
        else -> java.text.NumberFormat.getNumberInstance().format(SyuFactoryProtocol.chargeTemperatures[value] / 2.0) + " °C"
    }
    SyuFactoryControl.CHARGE_MINIMUM -> "${value * 10}%"
    else -> value.toString()
}
