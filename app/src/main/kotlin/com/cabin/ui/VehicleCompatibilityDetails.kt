package com.cabin.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.vehicleCompatibility

@Composable
internal fun VehicleCompatibilityDetails(state: TeyesClimateState) {
    val data = vehicleCompatibility(state)
    val report = listOf(
        stringResource(R.string.vehicle_compat_profile, data.profile?.toString() ?: "—"),
        stringResource(R.string.vehicle_compat_counts, data.doorCount, data.climateActions, data.factoryActions, data.tireCount),
        stringResource(R.string.vehicle_compat_battery, stringResource(if (data.battery) R.string.syu_diag_received else R.string.syu_diag_missing)),
        stringResource(R.string.vehicle_compat_fields, data.fields.sorted().joinToString(", ").ifEmpty { "—" }),
        stringResource(R.string.vehicle_compat_note),
    ).joinToString("\n")
    val clipboard = LocalClipboardManager.current
    Text(report, style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text(stringResource(R.string.syu_diag_copy)) }
}
