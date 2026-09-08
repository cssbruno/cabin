package com.cabin.platform.obd

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ObdPhase { IDLE, CONNECTING, NEGOTIATING, STREAMING, ERROR }

data class ObdAdapter(val address: String, val name: String)

/** Legacy value type retained for source compatibility; never used as a vehicle-data source. */
data class ObdSnapshot(
    val phase: ObdPhase = ObdPhase.IDLE,
    val selectedAddress: String = "",
    val message: String = "Vehicle data comes from the TEYES/SYU subsystem only. External OBD adapters are disabled.",
    val supportedPids: Set<ObdPid> = emptySet(),
    val speedKph: Double? = null,
    val engineRpm: Double? = null,
    val coolantCelsius: Double? = null,
    val ecuVoltage: Double? = null,
    val updatedAtElapsedMs: Long? = null,
) {
    val active: Boolean get() = phase in setOf(ObdPhase.CONNECTING, ObdPhase.NEGOTIATING, ObdPhase.STREAMING)

    fun isFresh(
        nowElapsedMs: Long,
        maxAgeMs: Long = 8_000,
    ): Boolean {
        val sampledAt = updatedAtElapsedMs ?: return false
        return phase == ObdPhase.STREAMING && sampledAt >= 0 && maxAgeMs >= 0 && nowElapsedMs >= sampledAt && nowElapsedMs - sampledAt <= maxAgeMs
    }
}

/**
 * Inert compatibility facade. TEYES vehicle data is supplied only by TeyesClimateController.
 * No Bluetooth objects, permissions, preferences, sockets, timers, or background jobs exist here.
 * Old callers cannot restore an external adapter selection or start an external connection.
 */
class ObdController internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {
    private val mutableState = MutableStateFlow(ObdSnapshot())
    val state: StateFlow<ObdSnapshot> = mutableState.asStateFlow()

    fun hasPermission(): Boolean = false

    fun pairedAdapters(): List<ObdAdapter> = emptyList()

    fun selectAdapter(
        @Suppress("UNUSED_PARAMETER") address: String,
    ) = Unit

    fun connectSelected() = Unit

    fun disconnect() = Unit

    companion object {
        @Volatile private var instance: ObdController? = null

        fun getIfCreated(): ObdController? = instance

        fun disconnectIfCreated() = Unit

        fun get(context: Context): ObdController =
            instance ?: synchronized(this) {
                instance ?: ObdController(context).also { instance = it }
            }
    }
}
