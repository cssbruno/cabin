package com.cabin.platform

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TeyesVehicleDataLayout { LEGACY, CIVIC_0298 }

/** App-local decoding choice only; never changes the head unit's CAN configuration. */
class TeyesVehicleDataPreferences private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("teyes_vehicle_data_v1", Context.MODE_PRIVATE)
    private val mutableLayout =
        MutableStateFlow(
            TeyesVehicleDataLayout.entries.firstOrNull { it.name == prefs.all["layout"] } ?: TeyesVehicleDataLayout.LEGACY,
        )
    val layout = mutableLayout.asStateFlow()

    @Synchronized
    fun select(layout: TeyesVehicleDataLayout) {
        prefs.edit().putString("layout", layout.name).apply()
        mutableLayout.value = layout
    }

    companion object {
        @Volatile private var instance: TeyesVehicleDataPreferences? = null

        fun get(context: Context): TeyesVehicleDataPreferences =
            instance ?: synchronized(this) {
                instance ?: TeyesVehicleDataPreferences(context).also { instance = it }
            }
    }
}
