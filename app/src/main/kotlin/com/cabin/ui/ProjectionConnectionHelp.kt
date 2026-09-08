package com.cabin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cabin.R
import com.cabin.platform.ProjectionOptionalCapability
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.projectionDiagnosticsAllowed
import com.cabin.platform.projectionReadinessPresentation

/** Same-window overlay. The caller owns projection occlusion, back handling and refresh timing. */
@Composable
fun ProjectionConnectionHelp(
    snapshot: ProjectionReadinessSnapshot,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPermissions: (() -> Unit)? = null,
    onOpenSetup: (() -> Unit)? = null,
    onStopSession: (() -> Unit)? = null,
    sessionIdle: Boolean = false,
) {
    val presentation = projectionReadinessPresentation(androidx.compose.ui.platform.LocalResources.current, snapshot)
    val helpTitle = stringResource(R.string.help_title)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var parked by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) parked = false }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) parked = false }
        lifecycle.addObserver(observer)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            lifecycle.removeObserver(observer)
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        }
    }
    Surface(
        modifier = modifier.fillMaxSize().semantics { paneTitle = helpTitle },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Keep actions outside the scroll area, including at large font sizes.
            FlowRow(
                modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_close)) }
                OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_refresh)) }
                if (presentation.canConnectPhone) {
                    Button(onClick = onConnect, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.action_connect_phone)) }
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 800.dp).fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.help_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(presentation.title, style = MaterialTheme.typography.headlineSmall)
                Text(presentation.nextStep, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.help_snapshot_detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ReadinessSection(stringResource(R.string.help_usb_adapter), presentation.usbDetail)
                ReadinessSection(stringResource(R.string.help_phone_session), presentation.phoneDetail)
                ReadinessSection(stringResource(R.string.help_voice_optional), presentation.microphoneDetail)
                ReadinessSection(stringResource(R.string.help_gps_optional), presentation.locationDetail)
                if (onOpenPermissions != null &&
                    (snapshot.microphone == ProjectionOptionalCapability.PERMISSION_NEEDED ||
                        snapshot.location == ProjectionOptionalCapability.PERMISSION_NEEDED)
                ) {
                    OutlinedButton(onOpenPermissions, modifier = Modifier.heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.setup_open_permissions))
                    }
                }
                onOpenSetup?.let { open ->
                    OutlinedButton(open, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.setup_guide)) }
                }
                if (onOpenPermissions != null) {
                    Text(stringResource(R.string.setup_tests_idle))
                    ProjectionParkedAcknowledgment(parked) { parked = it }
                    if (!sessionIdle) {
                        onStopSession?.let { stop ->
                            OutlinedButton(stop, modifier = Modifier.heightIn(min = 56.dp), enabled = parked) {
                                Text(stringResource(R.string.setup_stop_tests))
                            }
                        }
                    }
                    ProjectionDiagnosticPanel(
                        diagnosticsAllowed = projectionDiagnosticsAllowed(parked, snapshot, sessionIdle),
                        onOpenPermissions = onOpenPermissions,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadinessSection(
    title: String,
    detail: String,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
