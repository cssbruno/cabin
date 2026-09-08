package com.cabin.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R
import com.cabin.diagnostics.AndroidDiagnosticRunner
import com.cabin.diagnostics.DiagnosticKind
import com.cabin.diagnostics.DiagnosticPhase
import com.cabin.diagnostics.DiagnosticRunner
import com.cabin.diagnostics.ProjectionDiagnostics
import kotlin.math.roundToInt

@Composable
fun ProjectionDiagnosticPanel(
    diagnosticsAllowed: Boolean,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    runner: DiagnosticRunner? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    // Preview/Robolectric owners can be focused before the View is attached. Real actions
    // always recheck the attached window's current focus as well as Lifecycle.RESUMED.
    var focused by remember(view) { mutableStateOf(view.hasWindowFocus()) }
    val allowed by rememberUpdatedState(diagnosticsAllowed && resumed && focused)
    val backend = remember(context, runner) { runner ?: AndroidDiagnosticRunner(context) }
    val session = remember(scope, backend, view) { ProjectionDiagnostics(scope, backend, { allowed && view.hasWindowFocus() }) }
    val state by session.state.collectAsStateWithLifecycle()
    val running = state.phase == DiagnosticPhase.RUNNING
    var permissionRevision by remember { mutableStateOf(0) }
    val microphonePermission = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val gpsPermission = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    DisposableEffect(lifecycle, view, session) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision++
            if (!resumed) session.stop()
        }
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            focused = hasFocus
            if (!hasFocus) session.stop()
        }
        lifecycle.addObserver(observer)
        val viewTreeObserver = view.viewTreeObserver
        viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            lifecycle.removeObserver(observer)
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            session.stop()
        }
    }
    LaunchedEffect(allowed) { if (!allowed) session.stop() }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.diagnostics_description), style = MaterialTheme.typography.bodyMedium)
            if (!diagnosticsAllowed) Text(stringResource(R.string.diagnostics_parked_idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.diagnostics_audio_note), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { session.start(DiagnosticKind.MICROPHONE) },
                    enabled = allowed && !running && microphonePermission, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_microphone))
                }
                OutlinedButton(onClick = { session.start(DiagnosticKind.SPEAKERS) },
                    enabled = allowed && !running, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_speakers))
                }
                OutlinedButton(onClick = { session.start(DiagnosticKind.GPS) },
                    enabled = allowed && !running && gpsPermission, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_gps))
                }
                if (running) Button(onClick = session::stop, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_stop))
                }
            }
            if (!microphonePermission || !gpsPermission || state.phase == DiagnosticPhase.PERMISSION_NEEDED) {
                Text(stringResource(R.string.diagnostics_permissions_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { session.stop(); onOpenPermissions() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_permissions))
                }
            }
            if (state.kind == DiagnosticKind.MICROPHONE && state.phase in listOf(DiagnosticPhase.RUNNING, DiagnosticPhase.COMPLETE)) {
                LinearProgressIndicator(progress = { state.microphonePercent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(if (running) R.string.diagnostics_mic_level else R.string.diagnostics_mic_peak, state.microphonePercent))
                if (state.clipped) Text(stringResource(R.string.diagnostics_clipping), color = MaterialTheme.colorScheme.error)
            }
            val status = when (state.phase) {
                DiagnosticPhase.IDLE -> null
                DiagnosticPhase.RUNNING -> when (state.kind) {
                    DiagnosticKind.MICROPHONE -> stringResource(R.string.diagnostics_listening)
                    DiagnosticKind.SPEAKERS -> stringResource(R.string.diagnostics_playing)
                    else -> stringResource(R.string.diagnostics_waiting_gps)
                }
                DiagnosticPhase.COMPLETE -> when (state.kind) {
                    DiagnosticKind.MICROPHONE -> stringResource(R.string.diagnostics_mic_finished)
                    DiagnosticKind.SPEAKERS -> stringResource(R.string.diagnostics_speakers_finished)
                    else -> stringResource(R.string.diagnostics_gps_finished, state.accuracyMeters?.roundToInt() ?: 0)
                }
                DiagnosticPhase.STOPPED -> stringResource(R.string.diagnostics_stopped)
                DiagnosticPhase.PERMISSION_NEEDED -> stringResource(R.string.diagnostics_permission_needed)
                DiagnosticPhase.UNAVAILABLE -> stringResource(R.string.diagnostics_unavailable)
                DiagnosticPhase.TIMED_OUT -> stringResource(R.string.diagnostics_timeout)
                DiagnosticPhase.NO_PROGRESS -> stringResource(
                    if (state.kind == DiagnosticKind.MICROPHONE) R.string.diagnostics_mic_no_samples else R.string.diagnostics_speakers_stalled,
                )
            }
            if (status != null) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
