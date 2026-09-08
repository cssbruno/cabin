package com.cabin.ui.settings

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.cabin.platform.TeyesVehicleDataPreferences

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
        stringResource(R.string.vehicle_readings_detail),
    ) {
        SettingsNotice(teyesVehicleSettingsStatus(androidx.compose.ui.platform.LocalResources.current, vehicle))
        Text(
            stringResource(R.string.vehicle_source),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (vehicle.connected && vehicle.profileId != 0) {
                stringResource(R.string.vehicle_profile_detail, vehicle.profileId)
            } else {
                stringResource(R.string.vehicle_service_required)
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (vehicle.profileId in setOf(1048874, 1114410, 196906, 262442)) {
            CivicVehicleDataLayoutSelector()
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
        Text(
            stringResource(R.string.vehicle_readings_stale_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.vehicle_oil_service_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text(stringResource(R.string.vehicle_not_exposed), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.vehicle_coolant_unavailable))
        Text(stringResource(R.string.vehicle_voltage_unavailable))
        Text(stringResource(R.string.vehicle_dtc_unavailable))
        Text(
            stringResource(R.string.vehicle_interface_limits),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CivicVehicleDataLayoutSelector() {
    val context = LocalContext.current
    val preferences = remember(context) { TeyesVehicleDataPreferences.get(context) }
    val layout by preferences.layout.collectAsStateWithLifecycle()
    Text(stringResource(R.string.vehicle_layout), style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TeyesVehicleDataLayout.entries.forEach { option ->
            FilterChip(
                modifier = Modifier.heightIn(min = 56.dp),
                selected = layout == option,
                onClick = { preferences.select(option) },
                label = {
                    Text(
                        when (option) {
                            TeyesVehicleDataLayout.LEGACY -> stringResource(R.string.vehicle_layout_existing)
                            TeyesVehicleDataLayout.CIVIC_0298 -> stringResource(R.string.vehicle_layout_civic)
                        },
                    )
                },
            )
        }
    }
    Text(
        stringResource(R.string.vehicle_layout_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.vehicle_layout_readonly),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
