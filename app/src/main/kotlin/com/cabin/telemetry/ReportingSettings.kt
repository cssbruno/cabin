package com.cabin.telemetry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReportingSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(CabinTelemetry.configured && CabinTelemetry.enabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reporting_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(enabled, enabled = CabinTelemetry.configured && !busy, onCheckedChange = { next ->
                busy = true
                queued = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { CabinTelemetry.setEnabled(context, next) }
                        enabled = CabinTelemetry.enabled(context)
                    } finally { busy = false }
                }
            })
        }
        Text(stringResource(R.string.reporting_detail), style = MaterialTheme.typography.bodyMedium)
        if (!CabinTelemetry.configured) Text(stringResource(R.string.reporting_unconfigured))
        if (enabled) TextButton(enabled = !queued && !busy, onClick = {
            queued = CabinTelemetry.record(DiagnosticEvent.TEST_REPORT, report = true)
        }) { Text(stringResource(if (queued) R.string.reporting_queued else R.string.reporting_test)) }
    }
}
