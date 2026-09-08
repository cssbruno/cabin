package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The exact frozen JSON that will be written after explicit confirmation. */
@Composable
internal fun HealthReportPreview(
    report: String,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.health_review)) },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.health_review_detail))
                Text(report, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.health_choose_save)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}
