package com.cabin.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.diagnostics.vehicleDiagnosticReport
import com.cabin.platform.TeyesClimateState

@Composable
internal fun VehicleDiagnosticExport(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    TextButton(onClick = {
        context.startActivity(android.content.Intent(context, com.cabin.hardware.LabActivity::class.java))
    }) { Text("Diagnostics") }
    TextButton(onClick = { com.cabin.reports.LiveDebugMenu.show(context) }) { Text("Live debug") }
    TextButton(onClick = {
        com.cabin.reports.ReportExport.show(context, "cabin-diagnostics.json", vehicleDiagnosticReport(vehicle))
    }) { Text(stringResource(R.string.tools_export)) }
}
