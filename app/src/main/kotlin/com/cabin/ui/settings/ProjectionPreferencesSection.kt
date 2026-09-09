package com.cabin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.BuildConfig
import com.cabin.R
import com.cabin.platform.MeasurementPreferences
import com.cabin.platform.MeasurementUnit
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.ProjectionControlSide
import com.cabin.platform.ProjectionPreferences
import com.cabin.platform.ProjectionPreferencesState

@Composable
internal fun ProjectionPreferencesSection(showMeasurements: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { ProjectionPreferences.getInstance(context) }
    val state by preferences.state.collectAsStateWithLifecycle()
    val measurementPreferences = remember(context.applicationContext) { MeasurementPreferences.get(context) }
    val measurementUnit by measurementPreferences.unit.collectAsStateWithLifecycle()
    ProjectionPreferencesContent(
        state = state,
        showMeasurements = showMeasurements,
        isTeyes = BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE,
        onFocusControls = preferences::setFocusControls,
        onVehicleHud = preferences::setVehicleHud,
        onClimateNoticeMode = preferences::setClimateNoticeMode,
        onReturnWhenReady = preferences::setReturnWhenReady,
        onControlSide = preferences::setControlSide,
        measurementUnit = measurementUnit,
        onMeasurementUnit = measurementPreferences::select,
    )
}

/** State-only content can be tested without a manager, USB adapter, or vehicle service. */
@Composable
internal fun ProjectionPreferencesContent(
    state: ProjectionPreferencesState,
    isTeyes: Boolean,
    onFocusControls: (Boolean) -> Unit,
    onVehicleHud: (Boolean) -> Unit,
    onClimateNoticeMode: (ClimateNoticeMode) -> Unit,
    onReturnWhenReady: (Boolean) -> Unit,
    onControlSide: (ProjectionControlSide) -> Unit = {},
    measurementUnit: MeasurementUnit = MeasurementUnit.SYSTEM,
    onMeasurementUnit: (MeasurementUnit) -> Unit = {},
    showMeasurements: Boolean = true,
) {
    SettingsSection(
        title = stringResource(R.string.projection_preferences),
        description = null,
    ) {
        if (showMeasurements) {
            Text(stringResource(R.string.bu_units_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.bu_units_description), style = MaterialTheme.typography.bodyMedium)
            MeasurementUnit.entries.forEach { option ->
                SettingsChoice(measurementUnitLabel(option), measurementUnit == option, { onMeasurementUnit(option) })
            }
            HorizontalDivider()
        }
        SettingsToggle(
            label = stringResource(R.string.projection_focus),
            checked = state.focusControls,
            description = stringResource(R.string.projection_focus_detail),
            onChange = onFocusControls,
        )
        Text(stringResource(R.string.projection_controls_position), style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ProjectionControlSide.LEFT to R.string.projection_left_side, ProjectionControlSide.RIGHT to R.string.projection_right_side).forEach { (side, label) ->
                androidx.compose.material3.FilterChip(selected = state.controlSide == side, onClick = { onControlSide(side) },
                    label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        Text(stringResource(R.string.projection_controls_position_detail), style = MaterialTheme.typography.bodyMedium)
        if (isTeyes) {
            HorizontalDivider()
            SettingsToggle(
                label = stringResource(R.string.projection_return_ready),
                checked = state.returnWhenReady,
                description = stringResource(R.string.projection_return_ready_detail),
                onChange = onReturnWhenReady,
            )
            SettingsToggle(
                label = stringResource(R.string.projection_vehicle_hud),
                checked = state.vehicleHud,
                description = stringResource(R.string.projection_vehicle_hud_detail),
                onChange = onVehicleHud,
            )
            HorizontalDivider()
            Text(stringResource(R.string.projection_ac_notices), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.projection_ac_notices_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup().testTag("climate_notice_choices"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsChoice(
                    label = stringResource(R.string.projection_ac_summary),
                    selected = state.climateNoticeMode == ClimateNoticeMode.SUMMARY,
                    onClick = { onClimateNoticeMode(ClimateNoticeMode.SUMMARY) },
                    detail = stringResource(R.string.projection_ac_summary_detail),
                )
                SettingsChoice(
                    label = stringResource(R.string.projection_ac_panel),
                    selected = state.climateNoticeMode == ClimateNoticeMode.PANEL,
                    onClick = { onClimateNoticeMode(ClimateNoticeMode.PANEL) },
                    detail = stringResource(R.string.projection_ac_panel_detail),
                )
                SettingsChoice(
                    label = stringResource(R.string.interface_state_off),
                    selected = state.climateNoticeMode == ClimateNoticeMode.OFF,
                    onClick = { onClimateNoticeMode(ClimateNoticeMode.OFF) },
                    detail = stringResource(R.string.projection_ac_off_detail),
                )
            }
        }
    }
}

@Composable
internal fun measurementUnitLabel(unit: MeasurementUnit): String = stringResource(
    when (unit) {
        MeasurementUnit.SYSTEM -> R.string.bu_units_system
        MeasurementUnit.METRIC -> R.string.bu_units_metric
        MeasurementUnit.IMPERIAL -> R.string.bu_units_imperial
    },
)

@Composable
internal fun MeasurementSettingsSection() {
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { MeasurementPreferences.get(context) }
    val unit by preferences.unit.collectAsStateWithLifecycle()
    SettingsSection(stringResource(R.string.bu_units_title)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MeasurementUnit.entries.forEach { option ->
                androidx.compose.material3.FilterChip(selected = unit == option, onClick = { preferences.select(option) },
                    label = { Text(measurementUnitLabel(option)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
    }
}
