package com.cabin.platform

import com.cabin.R

internal data class VehicleAlert(val label: Int, val detail: Int? = null)

/** Alerts describe fresh reported states, never the absence of a callback. */
internal fun vehicleAlerts(state: TeyesClimateState): List<VehicleAlert> = buildList {
    if (!state.connected) return@buildList
    val wheels = listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr, R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)
    state.syuVehicle.tires.take(4).forEachIndexed { index, tire ->
        val detail = when (tire.warning) {
            1 -> R.string.vehicle_tire_high; 2 -> R.string.vehicle_tire_low
            3, 7 -> R.string.vehicle_tire_leak; 4 -> R.string.vehicle_tire_fault
            5 -> R.string.vehicle_tire_battery; 6 -> R.string.vehicle_tire_missing
            else -> null
        }
        if (detail != null) add(VehicleAlert(wheels[index], detail))
    }
    val doors = listOf(
        Triple(36, state.hoodOpen, R.string.alert_hood), Triple(37, state.frontLeftDoorOpen, R.string.alert_front_left),
        Triple(38, state.frontRightDoorOpen, R.string.alert_front_right), Triple(39, state.rearLeftDoorOpen, R.string.alert_rear_left),
        Triple(40, state.rearRightDoorOpen, R.string.alert_rear_right), Triple(41, state.bootOpen, R.string.alert_trunk),
    )
    doors.forEach { (code, open, label) -> if (code in state.availableCodes && open) add(VehicleAlert(label)) }
    if (137 in state.availableCodes && state.oilLifePercent == 0) add(VehicleAlert(R.string.alert_oil_due))
    if (181 in state.availableCodes && state.oilServiceDistance?.let { it <= 0 } == true) add(VehicleAlert(R.string.alert_service_due))
}
