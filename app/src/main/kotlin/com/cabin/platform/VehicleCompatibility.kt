package com.cabin.platform

/** Snapshot uses only current controller state; connection loss invalidates all capabilities. */
internal data class VehicleCompatibility(
    val profile: Int?, val fields: Set<Int>, val doorCount: Int,
    val climateActions: Int, val factoryActions: Int, val tireCount: Int, val battery: Boolean,
)

internal fun vehicleCompatibility(state: TeyesClimateState): VehicleCompatibility {
    if (!state.connected) return VehicleCompatibility(null, emptySet(), 0, 0, 0, 0, false)
    val air = state.syuAir
    val climate = if (air != null) air.actions.count { air.canSend(it) } else {
        (if (state.controlsAvailable) 1 else 0) + (if (state.fanControlsAvailable) 1 else 0) +
            TeyesClimateSwitch.entries.count { state.controlsSupported && it.feedbackCode in state.availableCodes }
    }
    return VehicleCompatibility(state.profileId.takeIf { it > 0 }, state.availableCodes - 1000,
        (36..41).count { it in state.availableCodes }, climate,
        state.syuVehicle.factoryControls.size, state.syuVehicle.tires.count { it.pressureKpa != null },
        state.syuVehicle.batterySegments != null || state.syuVehicle.energy?.batteryPercent != null)
}
