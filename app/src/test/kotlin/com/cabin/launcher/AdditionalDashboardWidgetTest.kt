package com.cabin.launcher

import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import org.junit.Assert.*
import org.junit.Test

class AdditionalDashboardWidgetTest {
    private val fresh = TeyesClimateState(connected = true, health = TeyesTelemetryHealth.LIVE,
        leftTemperature = 44, rightTemperature = 46)

    @Test fun `each vehicle widget needs its own fresh fields`() {
        val required = mapOf(
            DashboardModule.DRIVER_TEMPERATURE to setOf(25, 33),
            DashboardModule.PASSENGER_TEMPERATURE to setOf(31, 33),
            DashboardModule.RECIRCULATION to setOf(21),
            DashboardModule.HOOD to setOf(36),
            DashboardModule.TRUNK to setOf(41),
            DashboardModule.AIRFLOW to setOf(26, 27, 28))
        required.forEach { (module, codes) ->
            val state = fresh.copy(availableCodes = codes)
            assertTrue(module.name, additionalVehicleFieldKnown(module, state))
            codes.forEach { missing ->
                assertFalse("$module without $missing", additionalVehicleFieldKnown(module, state.copy(availableCodes = codes - missing + 89)))
            }
            assertFalse(additionalVehicleFieldKnown(module, state.copy(connected = false)))
            assertFalse(additionalVehicleFieldKnown(module, state.copy(health = TeyesTelemetryHealth.STALE)))
        }
    }

    @Test fun `airflow accepts canonical and alternate fields without guessing missing directions`() {
        assertTrue(additionalVehicleFieldKnown(DashboardModule.AIRFLOW, fresh.copy(availableCodes = setOf(91, 92, 93))))
        assertFalse(additionalVehicleFieldKnown(DashboardModule.AIRFLOW, fresh.copy(availableCodes = setOf(91, 92))))
        assertTrue(additionalVehicleFieldKnown(DashboardModule.AIRFLOW, fresh.copy(profileId = 262465, availableCodes = setOf(73))))
        assertFalse(additionalVehicleFieldKnown(DashboardModule.AIRFLOW, fresh.copy(profileId = 262465, availableCodes = setOf(26, 27, 28))))
    }
}
