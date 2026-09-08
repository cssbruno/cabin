package com.cabin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.background.CabinProjectionService
import com.cabin.platform.ProjectionReadinessSnapshot
import com.cabin.platform.ProjectionSetupPreferences
import com.cabin.platform.ProjectionSetupStep
import com.cabin.platform.ProjectionUsbAccess
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.AudioSourceConfig
import com.cabin.ui.settings.MicSourceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reopenable guide. Saving audio changes applies on the next normal adapter connection. */
@Composable
internal fun ProjectionSetupFlow(
    manager: CabinManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val progress = remember(context) { ProjectionSetupPreferences.get(context) }
    val audioPreferences = remember(context) { AdapterConfigPreference.getInstance(context) }
    val health by manager.dashboardState.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(progress.progress.value.step) }
    var parked by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var snapshot by remember(manager) {
        mutableStateOf(ProjectionReadinessSnapshot(state = manager.state, sessionRequested = manager.projectionSessionRequested))
    }
    var audio by remember { mutableStateOf(audioPreferences.getAudioSourceSync()) }
    var microphone by remember { mutableStateOf(audioPreferences.getMicSourceSync()) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(manager, health.connection, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshot = withContext(Dispatchers.IO) { manager.projectionReadinessSnapshot() }
        }
    }
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
    BackHandler { onClose() }
    ProjectionSetupScreen(
        step = step,
        snapshot = snapshot,
        parked = parked,
        audio = audio,
        microphone = microphone,
        saving = saving,
        saved = saved,
        saveFailed = saveFailed,
        onParked = { parked = it },
        onAudio = { audio = it; saved = false },
        onMicrophone = { microphone = it; saved = false },
        onSaveAudio = {
            if (parked && !saving) {
                val requestedAudio = audio
                val requestedMicrophone = microphone
                saving = true
                saveFailed = false
                scope.launch {
                    try {
                        withContext(NonCancellable) {
                            audioPreferences.setAudioSource(requestedAudio)
                            audioPreferences.setMicSource(requestedMicrophone)
                        }
                        saved = audio == requestedAudio && microphone == requestedMicrophone
                        refresh++
                    } catch (_: java.io.IOException) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }
        },
        onConnect = {
            try {
                CabinProjectionService.startPhoneConnection(context)
                refresh++
            } catch (_: RuntimeException) {
                android.widget.Toast.makeText(context, resources.getString(R.string.setup_connect_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        },
        onRefresh = { refresh++ },
        onOpenPermissions = { openProjectionAppPermissions(context) },
        onStep = { step = it; progress.visit(it) },
        onClose = onClose,
        onComplete = { progress.complete(); onClose() },
        modifier = modifier,
    )
}

@Composable
internal fun ProjectionSetupScreen(
    step: ProjectionSetupStep,
    snapshot: ProjectionReadinessSnapshot,
    parked: Boolean,
    audio: AudioSourceConfig,
    microphone: MicSourceConfig,
    saving: Boolean = false,
    saved: Boolean = false,
    saveFailed: Boolean = false,
    onParked: (Boolean) -> Unit,
    onAudio: (AudioSourceConfig) -> Unit,
    onMicrophone: (MicSourceConfig) -> Unit,
    onSaveAudio: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPermissions: () -> Unit,
    onStep: (ProjectionSetupStep) -> Unit,
    onClose: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.setup_guide)
    Surface(modifier.fillMaxSize().semantics { paneTitle = title }, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Exit remains outside the scroll area on compact head units and with large text.
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.setup_close)) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.setup_step, step.ordinal + 1, ProjectionSetupStep.entries.size))
                Text(stringResource(step.titleResource()), style = MaterialTheme.typography.headlineSmall)
                if (step != ProjectionSetupStep.COMPLETE) {
                    ProjectionParkedAcknowledgment(parked, onParked)
                }
                when (step) {
                    ProjectionSetupStep.USB -> {
                        Text(stringResource(R.string.setup_usb_body))
                        Text(stringResource(if (snapshot.adapterOpened || snapshot.usb.access == ProjectionUsbAccess.PERMITTED) R.string.setup_usb_ready else R.string.setup_usb_check))
                        OutlinedButton(onRefresh, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.setup_refresh)) }
                    }
                    ProjectionSetupStep.PHONE -> {
                        Text(stringResource(R.string.setup_phone_body))
                        Text(stringResource(if (snapshot.state == CabinManager.State.STREAMING) R.string.setup_phone_ready else R.string.setup_phone_check))
                        if (!snapshot.sessionRequested || !snapshot.phoneConnectionAllowed || snapshot.state == CabinManager.State.DISCONNECTED) {
                            Button(onConnect, Modifier.heightIn(min = 56.dp), enabled = parked) { Text(stringResource(R.string.setup_connect)) }
                        }
                    }
                    ProjectionSetupStep.AUDIO -> {
                        Text(stringResource(R.string.setup_audio_body))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AudioSourceConfig.entries.forEach { option ->
                                FilterChip(selected = audio == option, onClick = { onAudio(option) }, enabled = parked && !saving,
                                    label = { Text(stringResource(if (option == AudioSourceConfig.ADAPTER) R.string.setup_audio_adapter else R.string.setup_audio_bluetooth)) },
                                    modifier = Modifier.heightIn(min = 56.dp))
                            }
                        }
                        Text(stringResource(R.string.setup_microphone_body))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MicSourceConfig.entries.forEach { option ->
                                FilterChip(selected = microphone == option, onClick = { onMicrophone(option) }, enabled = parked && !saving,
                                    label = { Text(stringResource(if (option == MicSourceConfig.APP) R.string.setup_mic_app else R.string.setup_mic_phone)) },
                                    modifier = Modifier.heightIn(min = 56.dp))
                            }
                        }
                        Button(onSaveAudio, Modifier.heightIn(min = 56.dp), enabled = parked && !saving) { Text(stringResource(if (saving) R.string.setup_saving else R.string.setup_save_audio)) }
                        if (saved) Text(stringResource(R.string.setup_audio_saved))
                        if (saveFailed) Text(stringResource(R.string.setup_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    ProjectionSetupStep.PERMISSIONS -> {
                        Text(stringResource(R.string.setup_permissions_body))
                        Text(stringResource(R.string.setup_permissions_optional))
                        OutlinedButton(onOpenPermissions, Modifier.heightIn(min = 56.dp), enabled = parked) { Text(stringResource(R.string.setup_open_permissions)) }
                    }
                    ProjectionSetupStep.COMPLETE -> {
                        Text(stringResource(R.string.setup_done_body))
                        Text(stringResource(if (snapshot.state == CabinManager.State.STREAMING) R.string.setup_phone_ready else R.string.setup_done_unconnected))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step != ProjectionSetupStep.USB) {
                        OutlinedButton(onClick = { onStep(ProjectionSetupStep.entries[step.ordinal - 1]) }, modifier = Modifier.heightIn(min = 56.dp), enabled = !saving) { Text(stringResource(R.string.setup_back)) }
                    }
                    Button(onClick = {
                        if (step == ProjectionSetupStep.COMPLETE) onComplete() else onStep(ProjectionSetupStep.entries[step.ordinal + 1])
                    }, modifier = Modifier.heightIn(min = 56.dp), enabled = !saving) {
                        Text(stringResource(if (step == ProjectionSetupStep.COMPLETE) R.string.setup_finish else R.string.setup_next))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectionParkedAcknowledgment(parked: Boolean, onParked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(parked, role = Role.Checkbox, onValueChange = onParked), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = parked, onCheckedChange = null)
        Text(stringResource(R.string.setup_parked), modifier = Modifier.padding(start = 8.dp))
    }
}

private fun ProjectionSetupStep.titleResource(): Int = when (this) {
    ProjectionSetupStep.USB -> R.string.setup_usb_title
    ProjectionSetupStep.PHONE -> R.string.setup_phone_title
    ProjectionSetupStep.AUDIO -> R.string.setup_audio_title
    ProjectionSetupStep.PERMISSIONS -> R.string.setup_permissions_title
    ProjectionSetupStep.COMPLETE -> R.string.setup_done_title
}
