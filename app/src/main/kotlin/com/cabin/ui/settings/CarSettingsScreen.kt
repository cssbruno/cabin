package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.launcher.ClimateWidgetActions
import com.cabin.launcher.factoryValueLabel
import com.cabin.launcher.title
import com.cabin.platform.*

/** One place for verified factory controls and saved car preferences. */
@Composable
internal fun CarSettingsScreen(
    vehicle: TeyesClimateState,
    moving: Boolean,
    actions: ClimateWidgetActions,
    onParkedAction: (() -> Unit) -> Unit,
    onOpenClimate: (() -> Unit)?,
) {
    val latestVehicle by rememberUpdatedState(vehicle)
    val latestMoving by rememberUpdatedState(moving)
    val profile = vehicle.profileId
    val data = vehicle.syuVehicle
    val supported = SyuFactoryProtocol.controls(profile)
    val guarded: (() -> Unit) -> Unit = { action ->
        onParkedAction {
            if (latestVehicle.profileId == profile && latestVehicle.connected && !latestMoving) action()
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.car_settings_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.car_settings_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsNotice(stringResource(if (vehicle.connected) R.string.car_settings_connected else R.string.car_settings_disconnected))
        key(profile) {
            NativeCarSettings(vehicle, moving, actions, guarded)
            @Composable fun factoryGroup(group: SyuFactoryGroup) {
                SyuFactoryControl.entries.filter { it.group == group && it in supported }.forEach { control ->
                    val current = data.factoryControls[control]?.takeIf { vehicle.connected && it in 0..control.maximum }
                    CarSettingPicker(stringResource(control.title()), current, (0..control.maximum).toList(),
                        enabled = !moving && current != null && actions.onFactoryControl != null,
                        onParkedAction = guarded,
                        valueLabel = { value ->
                            if (control.maximum == 1 && control !in setOf(SyuFactoryControl.AMBIENT_PALETTE, SyuFactoryControl.HONDA_DISTANCE_UNITS))
                                stringResource(if (value == 1) R.string.climate_state_on else R.string.interface_state_off)
                            else factoryValueLabel(control, value)
                        },
                        onSelect = { value -> actions.onFactoryControl?.invoke(control, value) })
                }
            }
            if (supported.any { it.group == SyuFactoryGroup.HONDA_PANEL }) {
                SettingsDisclosure(stringResource(R.string.honda_panel_title), stringResource(R.string.honda_panel_detail)) {
                    factoryGroup(SyuFactoryGroup.HONDA_PANEL)
                }
            }
            SettingsDisclosure(stringResource(R.string.car_settings_lights), stringResource(R.string.car_settings_lights_detail)) {
                val lighting = profile in SyuVehicleProtocol.lightingProfiles
                if (!lighting && supported.none { it.group == SyuFactoryGroup.AMBIENT }) UnsupportedCarSettings()
                if (lighting) SyuLightingSetting.entries.forEach { setting ->
                    val current = data.lighting[setting]?.takeIf { vehicle.connected && it in setting.values.indices }
                    val label = when (setting) {
                        SyuLightingSetting.SENSITIVITY -> R.string.vehicle_light_sensitivity
                        SyuLightingSetting.HEADLIGHT_DELAY -> R.string.vehicle_headlight_delay
                        SyuLightingSetting.INTERIOR_DELAY -> R.string.vehicle_interior_delay
                    }
                    CarSettingPicker(stringResource(label), current, setting.values.indices.toList(),
                        enabled = !moving && current != null && actions.onVehicleLighting != null,
                        onParkedAction = guarded,
                        valueLabel = { "${setting.values[it]}" + if (setting == SyuLightingSetting.SENSITIVITY) "/5" else " s" },
                        onSelect = { actions.onVehicleLighting?.invoke(setting, it) })
                }
                factoryGroup(SyuFactoryGroup.AMBIENT)
            }
            SettingsDisclosure(stringResource(R.string.vehicle_mirror_settings), stringResource(R.string.car_settings_mirrors_detail)) {
                if (supported.none { it.group == SyuFactoryGroup.MIRRORS }) UnsupportedCarSettings()
                factoryGroup(SyuFactoryGroup.MIRRORS)
            }
            SettingsDisclosure(stringResource(R.string.car_settings_parking), stringResource(R.string.car_settings_parking_detail)) {
                if (supported.none { it.group in setOf(SyuFactoryGroup.CAMERA, SyuFactoryGroup.PARKING) }) UnsupportedCarSettings()
                factoryGroup(SyuFactoryGroup.CAMERA)
                factoryGroup(SyuFactoryGroup.PARKING)
            }
            SettingsDisclosure(stringResource(R.string.car_settings_comfort), stringResource(R.string.car_settings_comfort_detail)) {
                FilledTonalButton(onClick = { onOpenClimate?.invoke() }, enabled = onOpenClimate != null && vehicle.connected && !moving,
                    modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_climate)) }
                factoryGroup(SyuFactoryGroup.SEAT_MEMORY)
            }
            if (supported.any { it.group == SyuFactoryGroup.CHARGING }) {
                SettingsDisclosure(stringResource(R.string.energy_settings), stringResource(R.string.car_settings_charging_detail)) {
                    factoryGroup(SyuFactoryGroup.CHARGING)
                }
            }
            SettingsDisclosure(stringResource(R.string.car_settings_audio), stringResource(R.string.car_settings_audio_detail)) {
                if (profile == SyuVehicleProtocol.AMPLIFIER_PROFILE) SyuAmplifierSetting.entries.forEach { setting ->
                    val current = data.amplifier[setting]?.takeIf { vehicle.connected && it in 0..18 }
                    CarSettingPicker(stringResource(if (setting == SyuAmplifierSetting.BALANCE) R.string.vehicle_audio_balance else R.string.vehicle_audio_fader),
                        current, (0..18).toList(), enabled = !moving && current != null && actions.onFactoryAmplifier != null,
                        onParkedAction = guarded, valueLabel = { (it - 9).toString() },
                        onSelect = { actions.onFactoryAmplifier?.invoke(setting, it) })
                } else UnsupportedCarSettings()
                SteeringSettingsPanel(moving, onParkedAction)
            }
            SettingsDisclosure(stringResource(R.string.car_settings_my_car), stringResource(R.string.car_settings_my_car_detail)) {
                VehicleAppearancePanel(profile)
                TripToolsPanel(vehicle)
                VehicleCompatibilityPanel(vehicle)
            }
        }
    }
}

@Composable
private fun UnsupportedCarSettings() {
    Text(stringResource(R.string.compat_unverified), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Selection stays on confirmed feedback. Every write goes through the existing parked guard. */
@Composable
private fun CarSettingPicker(
    label: String,
    current: Int?,
    values: List<Int>,
    enabled: Boolean,
    onParkedAction: (() -> Unit) -> Unit,
    valueLabel: @Composable (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(enabled) { if (!enabled) choosing = false }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { choosing = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(current?.let { valueLabel(it) } ?: stringResource(R.string.compat_waiting))
            }
            DropdownMenu(expanded = choosing && enabled, onDismissRequest = { choosing = false }) {
                values.forEach { value ->
                    DropdownMenuItem(text = { Text(valueLabel(value)) }, onClick = {
                        choosing = false
                        onParkedAction { if (latestEnabled) latestSelect(value) }
                    }, modifier = Modifier.heightIn(min = 56.dp))
                }
            }
        }
    }
}

/** The native decoder already supplies the profile-specific labels, choices and fresh feedback. */
@Composable
private fun NativeCarSettings(
    vehicle: TeyesClimateState,
    moving: Boolean,
    actions: ClimateWidgetActions,
    guarded: (() -> Unit) -> Unit,
) {
    val latestVehicle by rememberUpdatedState(vehicle)
    val latestActions by rememberUpdatedState(actions)
    val profile = vehicle.profileId
    var selected by remember { mutableStateOf<FytSyuReading?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val rows = vehicle.fytSyuReadings.filter { it.options.isNotEmpty() }
    rows.forEach { row ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label ?: "CAN ${row.viewId}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { selected = row },
                enabled = vehicle.connected && !moving && actions.onSyuVehicleOption != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(row.text ?: stringResource(if (row.checked == true) R.string.vehicle_syu_checked else R.string.vehicle_syu_unchecked))
            }
        }
    }
    selected?.let { setting ->
        val current = rows.firstOrNull { it.screen == setting.screen && it.viewId == setting.viewId }
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(setting.label ?: "CAN ${setting.viewId}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    current?.options?.forEach { (value, label) ->
                        TextButton(enabled = vehicle.connected && !moving, onClick = {
                            selected = null
                            guarded {
                                val fresh = latestVehicle.fytSyuReadings.firstOrNull {
                                    it.screen == setting.screen && it.viewId == setting.viewId
                                }
                                if (latestVehicle.profileId == profile && fresh?.options?.containsKey(value) == true)
                                    latestActions.onSyuVehicleOption?.invoke(profile, setting.viewId, value)
                            }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) } })
    }
    if (vehicle.fytChoices.isNotEmpty() || vehicle.fytActions.isNotEmpty()) {
        OutlinedButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.vehicle_syu_readings))
        }
    }
    if (showAll) SyuReadingsDialog(vehicle,
        onSetOption = { id, field, value -> guarded { latestActions.onSyuVehicleOption?.invoke(id, field, value) } },
        onAction = { id, action -> guarded { latestActions.onSyuAction?.invoke(id, action) } },
        onChoice = { id, choice, value -> guarded { latestActions.onSyuChoice?.invoke(id, choice, value) } },
        onClose = { showAll = false })
}
