package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun EnergyFlowWidget(vehicle: TeyesClimateState) {
    val data = vehicle.syuVehicle.energy?.takeIf { vehicle.connected }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        val compact = maxHeight < 180.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!compact) Icon(Icons.Default.BatteryStd, stringResource(R.string.energy_battery), Modifier.size(64.dp))
            Text(data?.batteryPercent?.let { "$it%" } ?: "—", style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium)
            data?.direction?.let { direction ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (direction == SyuEnergyDirection.CHARGING) Icons.Default.South else Icons.Default.North, null,
                        Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(if (direction == SyuEnergyDirection.CHARGING) R.string.energy_charging else R.string.energy_discharging), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (!compact) Text(stringResource(R.string.energy_flow), style = MaterialTheme.typography.labelMedium)
        }
    }
}
