package com.cabin.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TeyesDrivingState(val speedKnown: Boolean = false, val moving: Boolean = false, val parkedConfirmed: Boolean = false) {
    /** Unknown speed permits asking the driver, never silently confirms parking. */
    val canRequestParkedAction: Boolean get() = !speedKnown || !moving
}

/** Supplementary UI gate, not a vehicle interlock. Missing speed never clears prior movement. */
class TeyesDrivingGuard {
    private val mutableState = MutableStateFlow(TeyesDrivingState())
    val state = mutableState.asStateFlow()

    fun observe(speed: Double?) {
        val previous = mutableState.value
        val valid = speed?.takeIf { it.isFinite() && it >= 0 }
        mutableState.value =
            if (valid == null) {
                previous.copy(speedKnown = false)
            } else {
                previous.copy(speedKnown = true, moving = valid > 0, parkedConfirmed = previous.parkedConfirmed && valid == 0.0)
            }
    }

    fun confirmParked(): Boolean {
        if (!mutableState.value.canRequestParkedAction) return false
        mutableState.value = mutableState.value.copy(moving = false, parkedConfirmed = true)
        return true
    }
}
