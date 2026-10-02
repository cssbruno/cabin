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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberSettingsScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.car_settings_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.car_settings_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsNotice(stringResource(if (vehicle.connected) R.string.car_settings_connected else R.string.car_settings_disconnected))
        key(profile) {
            for (category in VehicleSettingCategory.entries) {
                val rows = vehicle.fytSyuReadings.filter { it.options.isNotEmpty() && VehicleSettingCategory.reading(profile, it) == category }
                val controls = supported.filter { control ->
                    VehicleSettingCategory.control(control) == category &&
                        !(control.group == SyuFactoryGroup.HONDA_PANEL && rows.any {
                            it.screen in setOf("honda_0298", "honda_wc_settings_2023") && it.viewId == control.field
                        })
                }
                val serviceActions = vehicle.fytActions.filter { VehicleSettingCategory.action(it) == category }.toSet()
                val choices = if (category == VehicleSettingCategory.INSTRUMENTS) vehicle.fytChoices else emptyMap()
                val lighting = category == VehicleSettingCategory.LIGHTS && profile in SyuVehicleProtocol.lightingProfiles
                val amplifier = category == VehicleSettingCategory.AUDIO && profile == SyuVehicleProtocol.AMPLIFIER_PROFILE
                val hasPanel = category == VehicleSettingCategory.AUDIO || category == VehicleSettingCategory.COMFORT || category == VehicleSettingCategory.SERVICE
                if (rows.isEmpty() && controls.isEmpty() && serviceActions.isEmpty() && choices.isEmpty() && !lighting && !amplifier && !hasPanel) continue
                val searchLabels = controls.map { stringResource(it.title()) }.toSet() + rows.map { it.label ?: "CAN ${it.viewId}" } +
                    when(category) {
                        VehicleSettingCategory.AUDIO -> setOf(stringResource(R.string.teyes_steering_shortcuts))
                        VehicleSettingCategory.SERVICE -> setOf(stringResource(R.string.tools_trips), stringResource(R.string.tools_maintenance))
                        else -> emptySet()
                    }
                SettingsDisclosure(stringResource(category.title()), stringResource(category.detail()), searchLabels = searchLabels) {
                    if (rows.isNotEmpty() || serviceActions.isNotEmpty() || choices.isNotEmpty()) {
                        NativeCarSettings(vehicle.copy(fytSyuReadings = rows, fytActions = serviceActions, fytChoices = choices), moving, actions, guarded)
                    }
                    controls.forEach { control ->
                        val current = data.factoryControls[control]?.takeIf { vehicle.connected && it in 0..control.maximum }
                        CarSettingPicker(stringResource(control.title()), current, (0..control.maximum).toList(),
                            enabled = !moving && current != null && actions.onFactoryControl != null,
                            onParkedAction = guarded,
                            valueLabel = { value ->
                                if (control.maximum == 1 && control !in setOf(SyuFactoryControl.AMBIENT_PALETTE, SyuFactoryControl.HONDA_DISTANCE_UNITS))
                                    stringResource(if (value == 1) R.string.climate_state_on else R.string.interface_state_off)
                                else factoryValueLabel(control, value)
                            }, onSelect = { actions.onFactoryControl?.invoke(control, it) })
                    }
                    if (lighting) SyuLightingSetting.entries.forEach { setting ->
                        val nativeField = when (setting) {
                            SyuLightingSetting.SENSITIVITY -> 61
                            SyuLightingSetting.HEADLIGHT_DELAY -> 62
                            SyuLightingSetting.INTERIOR_DELAY -> 63
                        }
                        if (rows.any { it.screen == "honda_0298" && it.viewId == nativeField }) return@forEach
                        val current = data.lighting[setting]?.takeIf { vehicle.connected && it in setting.values.indices }
                        val label = when (setting) {
                            SyuLightingSetting.SENSITIVITY -> R.string.vehicle_light_sensitivity
                            SyuLightingSetting.HEADLIGHT_DELAY -> R.string.vehicle_headlight_delay
                            SyuLightingSetting.INTERIOR_DELAY -> R.string.vehicle_interior_delay
                        }
                        CarSettingPicker(stringResource(label), current, setting.values.indices.toList(),
                            enabled = !moving && current != null && actions.onVehicleLighting != null, onParkedAction = guarded,
                            valueLabel = { "${setting.values[it]}" + if (setting == SyuLightingSetting.SENSITIVITY) "/5" else " s" },
                            onSelect = { actions.onVehicleLighting?.invoke(setting, it) })
                    }
                    if (amplifier) SyuAmplifierSetting.entries.forEach { setting ->
                        val current = data.amplifier[setting]?.takeIf { vehicle.connected && it in 0..18 }
                        CarSettingPicker(stringResource(if (setting == SyuAmplifierSetting.BALANCE) R.string.vehicle_audio_balance else R.string.vehicle_audio_fader),
                            current, (0..18).toList(), enabled = !moving && current != null && actions.onFactoryAmplifier != null,
                            onParkedAction = guarded, valueLabel = { (it - 9).toString() },
                            onSelect = { actions.onFactoryAmplifier?.invoke(setting, it) })
                    }
                    if (category == VehicleSettingCategory.AUDIO) SteeringSettingsPanel(moving, onParkedAction)
                    if (category == VehicleSettingCategory.SERVICE) TripToolsPanel(vehicle)
                    if (category == VehicleSettingCategory.COMFORT) {
                        FilledTonalButton(onClick = { onOpenClimate?.invoke() }, enabled = onOpenClimate != null && vehicle.connected && !moving,
                            modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_climate)) }
                    }
                }
            }
            SettingsDisclosure(stringResource(R.string.car_settings_my_car), stringResource(R.string.car_settings_my_car_detail), searchLabels = setOf(stringResource(R.string.car_appearance), stringResource(R.string.tools_automation))) {
                VehicleAppearancePanel(profile)
                CarAutomationPanel(vehicle)
            }
            SettingsDisclosure(stringResource(R.string.car_settings_can), stringResource(R.string.car_settings_can_detail), searchLabels = setOf(stringResource(R.string.compat_title), stringResource(R.string.vehicle_readings))) {
                VehicleCompatibilityPanel(vehicle)
                ObdSettingsSection(vehicle)
            }
        }
    }
}

internal fun VehicleSettingCategory.title(): Int = when (this) {
    VehicleSettingCategory.DOORS -> R.string.car_settings_doors
    VehicleSettingCategory.LIGHTS -> R.string.car_settings_lights
    VehicleSettingCategory.MIRRORS -> R.string.vehicle_mirror_settings
    VehicleSettingCategory.PARKING -> R.string.car_settings_parking
    VehicleSettingCategory.COMFORT -> R.string.car_settings_comfort
    VehicleSettingCategory.ASSISTANCE -> R.string.car_settings_assistance
    VehicleSettingCategory.INSTRUMENTS -> R.string.car_settings_instruments
    VehicleSettingCategory.AUDIO -> R.string.car_settings_audio
    VehicleSettingCategory.CHARGING -> R.string.energy_settings
    VehicleSettingCategory.SERVICE -> R.string.car_settings_maintenance
    VehicleSettingCategory.OTHER -> R.string.car_settings_other
}
private fun VehicleSettingCategory.detail(): Int = when (this) {
    VehicleSettingCategory.DOORS -> R.string.car_settings_doors_detail
    VehicleSettingCategory.LIGHTS -> R.string.car_settings_lights_detail
    VehicleSettingCategory.MIRRORS -> R.string.car_settings_mirrors_detail
    VehicleSettingCategory.PARKING -> R.string.car_settings_parking_detail
    VehicleSettingCategory.COMFORT -> R.string.car_settings_comfort_detail
    VehicleSettingCategory.ASSISTANCE -> R.string.car_settings_assistance_detail
    VehicleSettingCategory.INSTRUMENTS -> R.string.car_settings_instruments_detail
    VehicleSettingCategory.AUDIO -> R.string.car_settings_audio_detail
    VehicleSettingCategory.CHARGING -> R.string.car_settings_charging_detail
    VehicleSettingCategory.SERVICE -> R.string.car_settings_maintenance_detail
    VehicleSettingCategory.OTHER -> R.string.car_settings_other_detail
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
    Column(Modifier.fillMaxWidth().settingsSearchAnchor(label)) {
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
    var selected by remember(vehicle.profileId) { mutableStateOf<FytSyuReading?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    if (vehicle.fytSyuReadings.isNotEmpty()) OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
        label = { Text(stringResource(R.string.vehicle_find_field)) }, modifier = Modifier.fillMaxWidth())
    val rows = vehicle.fytSyuReadings.filter { it.options.isNotEmpty() }
    val visibleRows = rows.filter { "${it.label.orEmpty()} ${it.viewId}".contains(search.trim(), ignoreCase = true) }
    visibleRows.forEach { row ->
        Column(Modifier.fillMaxWidth().settingsSearchAnchor(row.label ?: "CAN ${row.viewId}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.label ?: "CAN ${row.viewId}", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { selected = row },
                enabled = vehicle.connected && !vehicle.voluntaryReadOnly && !moving && actions.onSyuVehicleOption != null,
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
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberSettingsScrollState())) {
                    current?.options?.forEach { (value, label) ->
                        TextButton(enabled = vehicle.connected && !vehicle.voluntaryReadOnly && !moving, onClick = {
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
            Text(stringResource(R.string.car_settings_actions))
        }
    }
    if (showAll) SyuReadingsDialog(vehicle, actionsOnly = true,
        onSetOption = { id, field, value -> guarded { latestActions.onSyuVehicleOption?.invoke(id, field, value) } },
        onAction = { id, action -> guarded { latestActions.onSyuAction?.invoke(id, action) } },
        onChoice = { id, choice, value -> guarded { latestActions.onSyuChoice?.invoke(id, choice, value) } },
        onClose = { showAll = false })
}
