package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.platform.TeyesClimateState

/** A temporary status overlay, never a reserved strip or a vehicle command. */
@Composable
internal fun ProjectionClimateSummary(
    state: TeyesClimateState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.testTag("projection_climate_summary"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = projectionClimateSummaryText(androidx.compose.ui.platform.LocalResources.current, state),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun projectionClimateSummaryText(resources: android.content.res.Resources, state: TeyesClimateState): String {
    if (!state.connected) return resources.getString(R.string.climate_data_unavailable)
    val codes = state.availableCodes
    val alternate = state.profileId == 262465
    return buildList {
        add(if ((if (alternate) 30 else 24) in codes) resources.getString(if (state.ac) R.string.climate_ac_on else R.string.climate_ac_off) else resources.getString(R.string.climate_updated))
        if (33 in codes) {
            if (25 in codes && state.leftTemperature != null) add(resources.getString(R.string.climate_left_temperature, formatClimateTemperature(state.leftTemperature, state.fahrenheit)))
            if (31 in codes && state.rightTemperature != null) add(resources.getString(R.string.climate_right_temperature, formatClimateTemperature(state.rightTemperature, state.fahrenheit)))
        }
        if ((if (alternate) 35 else 29) in codes) add(resources.getString(R.string.climate_fan_level, state.fanLevel))
        if (20 in codes && state.auto) add(resources.getString(R.string.state_auto))
        if (22 in codes && state.frontDefrost) add(resources.getString(R.string.climate_front_defrost))
        if (23 in codes && state.rearDefrost) add(resources.getString(R.string.climate_rear_defrost))
    }.joinToString(" · ")
}
