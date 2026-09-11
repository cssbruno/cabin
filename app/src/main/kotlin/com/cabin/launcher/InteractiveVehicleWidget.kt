package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

/** Buttons open confirmed readings or climate UI; no door actuation is implied. */
@Composable
internal fun InteractiveVehicleWidget(vehicle: TeyesClimateState, onClimate: () -> Unit) {
    val (appearance, _) = rememberVehicleAppearance(vehicle.profileId)
    var detail by remember { mutableStateOf<Int?>(null) }
    val wheelLabels = listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr, R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        TireCarDiagram(if (vehicle.connected) vehicle.syuVehicle.tires else emptyList(), appearance,
            Modifier.fillMaxHeight().fillMaxWidth(.45f).align(Alignment.Center))
        for (side in 0..1) Column(Modifier.align(if (side == 0) Alignment.CenterStart else Alignment.CenterEnd), verticalArrangement = Arrangement.SpaceEvenly) {
            for (row in 0..1) {
                val wheel = row * 2 + side
                IconButton(onClick = { detail = wheel }) { Icon(Icons.Default.Speed, stringResource(wheelLabels[wheel])) }
                IconButton(onClick = { detail = 4 + wheel }) { Icon(Icons.Default.DirectionsCar, stringResource(R.string.module_doors) + " · " + stringResource(wheelLabels[wheel])) }
            }
        }
        IconButton(onClimate, modifier = Modifier.align(Alignment.Center)) { Icon(Icons.Default.AcUnit, stringResource(R.string.launcher_climate)) }
    }
    detail?.let { selected ->
        val tire = vehicle.syuVehicle.tires.getOrNull(selected)?.takeIf { vehicle.connected }
        val door = selected - 4
        val open = listOf(vehicle.frontLeftDoorOpen, vehicle.frontRightDoorOpen, vehicle.rearLeftDoorOpen, vehicle.rearRightDoorOpen).getOrNull(door)
        val known = vehicle.connected && (37 + door) in vehicle.availableCodes
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text(stringResource(if (selected < 4) wheelLabels[selected] else R.string.module_doors)) },
            text = { Text(if (selected < 4) tire?.pressureKpa?.let { java.text.NumberFormat.getNumberInstance().format(it) + " kPa" } ?: "—"
                else if (!known) "—" else stringResource(if (open == true) R.string.tools_open else R.string.tools_closed)) },
            confirmButton = { TextButton({ detail = null }) { Text(stringResource(R.string.launcher_done)) } })
    }
}
