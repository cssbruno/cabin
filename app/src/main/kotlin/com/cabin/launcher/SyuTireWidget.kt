package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.SyuTireReading
import java.text.NumberFormat

@Composable
private fun CompactTireWidget(vehicle: TeyesClimateState) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        fun tire(wheel: Int) = vehicle.syuVehicle.tires.getOrNull(wheel)?.takeIf { vehicle.connected }
        if (maxHeight < 180.dp || maxWidth < 240.dp) {
            HorizontalPager(rememberPagerState { 4 }, Modifier.fillMaxSize()) { wheel ->
                TireReading(wheel, tire(wheel), Modifier.fillMaxSize())
            }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (column in 0..1) {
                    val wheel = row * 2 + column
                    TireReading(wheel, tire(wheel), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun TireReading(wheel: Int, tire: SyuTireReading?, modifier: Modifier) {
    val warning = tire?.warning
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr,
            R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)[wheel]), maxLines = 1,
            style = MaterialTheme.typography.labelSmall)
        Text(tire?.pressureKpa?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it) + " kPa" } ?: "—",
            color = if (warning != null && warning > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge, maxLines = 1)
        if (warning != null && warning > 0) Text(stringResource(when (warning) {
            1 -> R.string.vehicle_tire_high
            2 -> R.string.vehicle_tire_low
            3, 7 -> R.string.vehicle_tire_leak
            4 -> R.string.vehicle_tire_fault
            5 -> R.string.vehicle_tire_battery
            else -> R.string.vehicle_tire_missing
        }), maxLines = 1, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
}


@Composable
internal fun SyuTireWidget(vehicle: TeyesClimateState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 300.dp && maxHeight >= 320.dp) ExpandedTireWidget(vehicle)
        else CompactTireWidget(vehicle)
    }
}
