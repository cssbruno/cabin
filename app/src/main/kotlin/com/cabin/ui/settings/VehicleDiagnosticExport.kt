package com.cabin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.diagnostics.vehicleDiagnosticReport
import com.cabin.platform.TeyesClimateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VehicleDiagnosticExport(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Int?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pending
        pending = null
        if (uri != null && report != null) scope.launch {
            result = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
                    stream.bufferedWriter().use { it.write(report) }
                    R.string.tools_export_done
                } catch (_: java.io.IOException) { R.string.tools_export_failed }
                  catch (_: SecurityException) { R.string.tools_export_failed }
                  catch (_: IllegalStateException) { R.string.tools_export_failed }
            }
        }
    }
    TextButton(onClick = {
        pending = vehicleDiagnosticReport(vehicle)
        export.launch("cabin-diagnostics.json")
    }) { Text(stringResource(R.string.tools_export)) }
    result?.let { Text(stringResource(it)) }
}
