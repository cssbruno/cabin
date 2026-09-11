package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClimateSwitchControls(state: TeyesClimateState, onSwitch: ((TeyesClimateSwitch) -> Unit)?, modifier: Modifier = Modifier) {
    if (!TeyesClimateControlPolicy.supportsTemperature(state.profileId)) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (control in TeyesClimateSwitch.entries) {
            val label = when (control) {
                TeyesClimateSwitch.POWER -> R.string.climate_power
                TeyesClimateSwitch.AUTO -> R.string.climate_state_auto
                TeyesClimateSwitch.DUAL -> R.string.climate_state_dual
                TeyesClimateSwitch.RECIRCULATION -> R.string.widget_recirculation
                TeyesClimateSwitch.FRONT_DEFROST -> R.string.climate_state_front_defrost
                TeyesClimateSwitch.REAR_DEFROST -> R.string.climate_state_rear_defrost
            }
            val icon = when (control) {
                TeyesClimateSwitch.POWER -> Icons.Default.PowerSettingsNew
                TeyesClimateSwitch.AUTO -> Icons.Default.AutoMode
                TeyesClimateSwitch.DUAL -> Icons.Default.SyncAlt
                TeyesClimateSwitch.RECIRCULATION -> Icons.Default.Autorenew
                TeyesClimateSwitch.FRONT_DEFROST -> Icons.Default.Air
                TeyesClimateSwitch.REAR_DEFROST -> Icons.Default.GridOn
            }
            val known = TeyesClimateControlPolicy.canToggle(state, control)
            FilterChip(selected = known && control.active(state), enabled = known && onSwitch != null,
                onClick = { onSwitch?.invoke(control) }, modifier = Modifier.heightIn(min = 88.dp),
                label = {
                    Column(Modifier.widthIn(min = 72.dp).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (control == TeyesClimateSwitch.FRONT_DEFROST || control == TeyesClimateSwitch.REAR_DEFROST) {
                            ClimateDefrostIcon(control == TeyesClimateSwitch.FRONT_DEFROST, Modifier.size(32.dp))
                        } else Icon(icon, null, Modifier.size(32.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                    }
                })
        }
    }
}
