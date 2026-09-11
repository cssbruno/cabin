package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import java.text.NumberFormat

/** Fits the dashboard tile in either orientation; no nested scrolling. */
@Composable
internal fun SyuVehicleWidget(module: DashboardModule, vehicle: TeyesClimateState) {
    val data = vehicle.syuVehicle
    val number = if (!vehicle.connected) null else when (module) {
        DashboardModule.HYBRID_BATTERY -> data.batterySegments?.toDouble()
        else -> data.averageConsumption
    }
    val formatter = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 150.dp
        Column(Modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) Text(stringResource(module.title()), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            Text(number?.let(formatter::format) ?: "—", maxLines = 1,
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall)
            if (number != null) {
                Text(if (module == DashboardModule.HYBRID_BATTERY) stringResource(R.string.vehicle_battery_segments)
                    else data.consumptionUnit.orEmpty(), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                if (!compact && module == DashboardModule.HYBRID_BATTERY) {
                    LinearProgressIndicator(progress = { number.toFloat() / 10f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
