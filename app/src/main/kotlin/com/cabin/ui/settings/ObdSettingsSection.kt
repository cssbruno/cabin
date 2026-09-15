package com.cabin.ui.settings

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.platform.MeasurementFormatter
import com.cabin.platform.MeasurementPreferences
import com.cabin.platform.MeasurementUnit
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.platform.TeyesVehicleDataLayout

internal data class TeyesVehicleSettingsReadings(
    val speed: String = "—",
    val rpm: String = "—",
    val oilLife: String = "—",
)

/** Controller expiry removes stale codes. Never substitute a value from another transport. */
internal fun teyesVehicleSettingsReadings(
    vehicle: TeyesClimateState,
    measurementUnit: MeasurementUnit = MeasurementUnit.METRIC,
): TeyesVehicleSettingsReadings {
    fun reading(
        code: Int,
        value: Int?,
        validRange: IntRange,
        unit: String,
    ): String =
        value?.takeIf {
            vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE &&
                code in vehicle.availableCodes && it in validRange
        }?.let { "$it $unit" } ?: "—"
    return TeyesVehicleSettingsReadings(
        speed = vehicle.speedKph?.takeIf {
            vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE &&
                89 in vehicle.availableCodes && it in 0..400
        }?.let { MeasurementFormatter.speed(it.toDouble(), measurementUnit) } ?: "—",
        rpm = reading(90, vehicle.engineRpm, 0..10_000, "RPM"),
        oilLife = reading(137, vehicle.oilLifePercent, 0..100, "%"),
    )
}

internal fun teyesVehicleSettingsStatus(resources: android.content.res.Resources, vehicle: TeyesClimateState): String =
    when {
        vehicle.health == TeyesTelemetryHealth.CONNECTING -> resources.getString(R.string.vehicle_status_connecting)
        !vehicle.connected -> resources.getString(R.string.vehicle_status_unavailable)
        vehicle.health == TeyesTelemetryHealth.STALE -> resources.getString(R.string.vehicle_status_waiting)
        teyesVehicleSettingsReadings(vehicle) == TeyesVehicleSettingsReadings() && teyesOilServiceReading(vehicle) == "—" -> resources.getString(R.string.vehicle_status_no_readings)
        else -> resources.getString(R.string.vehicle_status_available)
    }

/** Honda reports signed distance until oil service, not remaining oil-life percentage. */
internal fun teyesOilServiceReading(
    vehicle: TeyesClimateState,
    measurementUnit: MeasurementUnit = MeasurementUnit.METRIC,
): String =
    vehicle.oilServiceDistance?.takeIf {
        vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE &&
            setOf(179, 180, 181).all { code -> code in vehicle.availableCodes }
    }?.let { MeasurementFormatter.serviceDistance(it, vehicle.oilServiceDistanceMiles, measurementUnit) } ?: "—"

/** Read-only TEYES subsystem presentation. This composable never opens a transport or permission flow. */
@Composable
fun ObdSettingsSection(vehicle: TeyesClimateState = TeyesClimateState()) {
    val context = LocalContext.current
    val measurementPreferences = remember(context) { MeasurementPreferences.get(context) }
    val measurementUnit by measurementPreferences.unit.collectAsStateWithLifecycle()
    val readings = teyesVehicleSettingsReadings(vehicle, measurementUnit)
    SettingsSection(
        stringResource(R.string.vehicle_readings),
    ) {
        SettingsNotice(teyesVehicleSettingsStatus(androidx.compose.ui.platform.LocalResources.current, vehicle))
        if (vehicle.connected && vehicle.profileId != 0) {
            Text(stringResource(R.string.vehicle_profile_detail, vehicle.profileId))
        }
        Text(stringResource(R.string.vehicle_firmware_detected, vehicle.fytFirmwareVersion.ifBlank { "—" }))
        if (vehicle.vehicleDataLayout == TeyesVehicleDataLayout.UNKNOWN) {
            Text(stringResource(R.string.vehicle_decoder_unknown))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2,
        ) {
            listOf(
                stringResource(R.string.vehicle_speed) to readings.speed,
                stringResource(R.string.vehicle_engine_speed) to readings.rpm,
                stringResource(R.string.vehicle_oil_life) to readings.oilLife,
                stringResource(R.string.vehicle_oil_service) to teyesOilServiceReading(vehicle, measurementUnit),
            ).forEach { (label, value) ->
                Surface(
                    Modifier.widthIn(min = 130.dp).weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}
