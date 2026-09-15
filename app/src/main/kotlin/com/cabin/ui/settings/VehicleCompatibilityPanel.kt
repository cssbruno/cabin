package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

/** Profile support and current feedback are distinct: stale values never mean unsupported. */
@Composable
internal fun VehicleCompatibilityPanel(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = vehicle.profileId
    var cleared by remember(profile) { mutableStateOf(false) }
    val data = vehicle.syuVehicle
    SettingsDisclosure(stringResource(R.string.compat_title), profile.toString()) {
        val groups = listOf(
            Triple(R.string.honda_panel_title, profile in SyuHondaPanelProtocol.profiles, data.factoryControls.keys.any { it.group == SyuFactoryGroup.HONDA_PANEL }),
            Triple(R.string.energy_seat_preset, profile == SyuFactoryProtocol.SEAT_PRESET_PROFILE, data.factoryControls.containsKey(SyuFactoryControl.SEAT_PRESET)),
            Triple(R.string.energy_flow, profile == SyuFactoryProtocol.HYBRID_PROFILE, data.energy?.batteryPercent != null || data.energy?.direction != null),
            Triple(R.string.energy_settings, profile == SyuFactoryProtocol.HYBRID_PROFILE, data.factoryControls.keys.any { it.group == SyuFactoryGroup.CHARGING }),
            Triple(R.string.energy_palette, profile == SyuFactoryProtocol.AMBIENT_PROFILE, data.factoryControls.containsKey(SyuFactoryControl.AMBIENT_PALETTE)),
            Triple(R.string.vehicle_trip_consumption, profile in SyuVehicleProtocol.tripProfiles, data.averageConsumption != null),
            Triple(R.string.vehicle_hybrid_battery, profile in SyuVehicleProtocol.hybridProfiles, data.batterySegments != null),
            Triple(R.string.vehicle_tire_pressure, profile in SyuVehicleProtocol.tireProfiles, data.tires.any { it.pressureKpa != null }),
            Triple(R.string.vehicle_lighting, profile in SyuVehicleProtocol.lightingProfiles, data.lighting.isNotEmpty()),
            Triple(R.string.vehicle_factory_amplifier, profile == SyuVehicleProtocol.AMPLIFIER_PROFILE, data.amplifier.isNotEmpty()),
            Triple(R.string.vehicle_camera_mode, SyuFactoryProtocol.controls(profile).any { it.group == SyuFactoryGroup.CAMERA }, data.factoryControls.keys.any { it.group == SyuFactoryGroup.CAMERA }),
            Triple(R.string.vehicle_mirror_settings, SyuFactoryProtocol.controls(profile).any { it.group == SyuFactoryGroup.MIRRORS }, data.factoryControls.keys.any { it.group == SyuFactoryGroup.MIRRORS }),
            Triple(R.string.vehicle_parking_settings, SyuFactoryProtocol.controls(profile).any { it.group == SyuFactoryGroup.PARKING }, data.factoryControls.keys.any { it.group == SyuFactoryGroup.PARKING }),
        )
        groups.forEach { (label, supported, fresh) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Text(stringResource(when {
                    !supported -> R.string.compat_unverified
                    vehicle.connected && fresh -> R.string.compat_live
                    else -> R.string.compat_waiting
                }), modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (profile in SyuVehicleProtocol.tireProfiles) {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { TireHistory(context).clear(profile) }
                    cleared = true
                }
            }) { Text(stringResource(if (cleared) R.string.history_cleared else R.string.history_clear)) }
        }
        VehicleDiagnosticExport(vehicle)
        vehicle.syuAir?.let { air ->
            HorizontalDivider()
            Text(air.name, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.compat_air_actions,
                if (vehicle.connected) air.actions.count(air::canSend) else 0, air.actions.size))
        }
    }
}
