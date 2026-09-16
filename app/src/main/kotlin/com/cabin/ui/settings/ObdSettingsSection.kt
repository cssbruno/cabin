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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
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
        vehicle.fytSyuReadings.isEmpty() && teyesVehicleSettingsReadings(vehicle) == TeyesVehicleSettingsReadings() && teyesOilServiceReading(vehicle) == "—" -> resources.getString(R.string.vehicle_status_no_readings)
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

/** Vehicle presentation; supported option writes are delegated to the guarded controller. */
@Composable
fun ObdSettingsSection(vehicle: TeyesClimateState = TeyesClimateState(), onSyuAction: ((Int, com.cabin.platform.FytVehicleAction) -> Unit)? = null, onSyuChoice: ((Int, com.cabin.platform.FytVehicleChoice, Int) -> Unit)? = null, onSyuVehicleOption: ((Int, Int, Int) -> Unit)? = null) {
    val context = LocalContext.current
    val measurementPreferences = remember(context) { MeasurementPreferences.get(context) }
    val measurementUnit by measurementPreferences.unit.collectAsStateWithLifecycle()
    val readings = teyesVehicleSettingsReadings(vehicle, measurementUnit)
    var showRawFields by remember { mutableStateOf(false) }
    var showSyuFields by remember { mutableStateOf(false) }
    if (showSyuFields) SyuReadingsDialog(vehicle, onSyuVehicleOption, onSyuAction, onSyuChoice, onClose = { showSyuFields = false })
    var fieldFilter by remember { mutableStateOf("") }
    if (showRawFields) AlertDialog(
        onDismissRequest = { showRawFields = false },
        title = { Text(stringResource(R.string.vehicle_raw_fields)) },
        text = {
            Column {
            OutlinedTextField(value = fieldFilter, onValueChange = { fieldFilter = it },
                label = { Text(stringResource(R.string.vehicle_find_field)) }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(vehicle.fytPublishedFields.filter { id -> ("CAN $id " + vehicle.fytFieldNames[id].orEmpty().joinToString(" ").replace('_', ' ')).contains(fieldFilter.trim(), ignoreCase = true) }.sorted(), key = { "can:$it" }) { id ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        vehicle.fytFieldNames[id]?.joinToString(" / ") { it.removePrefix("U_").replace('_', ' ') }
                            ?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        Text("CAN · $id: ${vehicle.fytRawValues[id]?.toString() ?: "—"}")
                    }
                }
                items(vehicle.fytMainFields.filter { "MAIN $it".contains(fieldFilter.trim(), ignoreCase = true) }.sorted(), key = { "main:$it" }) { id ->
                    Text("MAIN · $id: ${vehicle.fytMainRawValues[id]?.toString() ?: "—"}", Modifier.padding(vertical = 6.dp))
                }
            }
            }
        },
        confirmButton = { TextButton(onClick = { showRawFields = false }) { Text(stringResource(R.string.action_close)) } },
    )
    SettingsSection(
        stringResource(R.string.vehicle_readings),
    ) {
        SettingsNotice(teyesVehicleSettingsStatus(androidx.compose.ui.platform.LocalResources.current, vehicle))
        if (vehicle.connected && vehicle.profileId != 0) {
            Text(stringResource(R.string.vehicle_profile_detail, vehicle.profileId))
        }
        if (vehicle.fytSyuReadings.isNotEmpty() || vehicle.fytActions.isNotEmpty() || vehicle.fytChoices.isNotEmpty()) {
            TextButton(onClick = { showSyuFields = true }) { Text(stringResource(R.string.vehicle_syu_readings)) }
        }
        if (vehicle.fytPublishedFields.isNotEmpty() || vehicle.fytMainFields.isNotEmpty()) {
            TextButton(onClick = { showRawFields = true }) { Text(stringResource(R.string.vehicle_raw_fields)) }
        }
        Text(stringResource(R.string.vehicle_firmware_detected, vehicle.fytFirmwareVersion.ifBlank { "—" }))
        if (vehicle.fytDetectedFields.isNotEmpty()) {
            Text(stringResource(R.string.vehicle_decoder_fields, vehicle.fytDetectedFields.size))
        }
        if (vehicle.fytCodeStatus == "no_packet_reader") {
            Text(stringResource(R.string.vehicle_no_packet_reader))
        } else if (vehicle.vehicleDataLayout == TeyesVehicleDataLayout.UNKNOWN) {
            val rawCount = vehicle.fytPublishedFields.size + vehicle.fytMainFields.size
            if (rawCount > 0) Text(stringResource(R.string.vehicle_raw_field_count, rawCount))
            else Text(stringResource(R.string.vehicle_decoder_unknown))
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

@Composable
internal fun SyuReadingsDialog(vehicle: TeyesClimateState, onSetOption: ((Int, Int, Int) -> Unit)?, onAction: ((Int, com.cabin.platform.FytVehicleAction) -> Unit)?, onChoice: ((Int, com.cabin.platform.FytVehicleChoice, Int) -> Unit)?, onClose: () -> Unit) {
    val portuguese = LocalContext.current.resources.configuration.locales[0].language == "pt"
    var pendingChoice by remember { mutableStateOf<Pair<Int, com.cabin.platform.FytVehicleChoice>?>(null) }
    pendingChoice?.let { (profile, choice) ->
        val options = vehicle.fytChoices[choice].orEmpty()
        AlertDialog(onDismissRequest = { pendingChoice = null },
            title = { Text(choice.title(portuguese)) },
            text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(options.entries.toList(), key = { it.key }) { (value, label) ->
                    TextButton(enabled = vehicle.connected && vehicle.profileId == profile, onClick = {
                        onChoice?.invoke(profile, choice, value)
                        pendingChoice = null
                    }) { Text(label) }
                }
            } },
            confirmButton = { TextButton(onClick = { pendingChoice = null }) { Text(stringResource(R.string.action_close)) } })
    }
    var pendingAction by remember { mutableStateOf<Pair<Int, com.cabin.platform.FytVehicleAction>?>(null) }
    pendingAction?.let { (profile, action) ->
        AlertDialog(onDismissRequest = { pendingAction = null },
            title = { Text(action.title(portuguese)) },
            text = { Text(action.confirmation(portuguese)) },
            confirmButton = { TextButton(enabled = vehicle.connected && vehicle.profileId == profile && action in vehicle.fytActions, onClick = {
                onAction?.invoke(profile, action)
                pendingAction = null
            }) { Text(action.confirmLabel(portuguese)) } },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text(stringResource(R.string.action_close)) } })
    }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Pair<Int, com.cabin.platform.FytSyuReading>?>(null) }
    selected?.let { (profile, setting) ->
        val current = vehicle.fytSyuReadings.firstOrNull { it.screen == setting.screen && it.viewId == setting.viewId }
        val valid = vehicle.connected && vehicle.profileId == profile && current != null
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(setting.label ?: "CAN ${setting.viewId}") },
            text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(setting.options.entries.toList(), key = { it.key }) { (value, text) ->
                    TextButton(enabled = valid && current?.options?.containsKey(value) == true, onClick = {
                        onSetOption?.invoke(profile, setting.viewId, value)
                        selected = null
                    }) { Text(text) }
                }
            } },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) } })
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.vehicle_syu_readings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                    label = { Text(stringResource(R.string.vehicle_find_field)) })
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    if (onChoice != null) items(vehicle.fytChoices.keys.toList(), key = { "choice:$it" }) { choice ->
                        TextButton(enabled = vehicle.connected, onClick = { pendingChoice = vehicle.profileId to choice }) {
                            Text(choice.title(portuguese))
                        }
                    }
                    if (onAction != null) items(vehicle.fytActions.toList(), key = { "action:$it" }) { action ->
                        TextButton(enabled = vehicle.connected, onClick = { pendingAction = vehicle.profileId to action }) {
                            Text(action.title(portuguese))
                        }
                    }
                    val rows = vehicle.fytSyuReadings.map { row ->
                        val names = row.label ?: row.fields.sorted().joinToString(" / ") { id ->
                            vehicle.fytFieldNames[id]?.joinToString(" / ") { it.removePrefix("U_").replace('_', ' ') } ?: "CAN $id"
                        }
                        row to names
                    }.filter { (row, names) ->
                        "$names ${row.fields.joinToString()} ${row.text.orEmpty()}".contains(search.trim(), ignoreCase = true)
                    }
                    items(rows, key = { (row, _) -> "${row.screen}:${row.viewId}" }) { (row, names) ->
                        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(names, style = MaterialTheme.typography.labelMedium)
                            val value = row.text ?: stringResource(if (row.checked == true) R.string.vehicle_syu_checked else R.string.vehicle_syu_unchecked)
                            if (row.options.isNotEmpty() && onSetOption != null) {
                                TextButton(onClick = { selected = vehicle.profileId to row }) { Text(value) }
                            } else Text(value, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
    )
}
