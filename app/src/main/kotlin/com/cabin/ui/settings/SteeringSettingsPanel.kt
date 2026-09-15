package com.cabin.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R
import com.cabin.platform.LocalTeyesKeyRouter
import com.cabin.platform.TeyesAppShortcuts
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.platform.labelRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun SteeringSettingsPanel(moving: Boolean = false, onParkedAction: (() -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val preferences = remember(context) { TeyesFeaturePreferences.get(context) }
    val router = LocalTeyesKeyRouter.current
    val status = router?.status?.collectAsStateWithLifecycle()?.value.orEmpty()
    val revision by preferences.revision.collectAsStateWithLifecycle()
    var longPress by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var editingCode by remember { mutableStateOf<Int?>(null) }
    var learningToken by remember { mutableStateOf(0) }
    var learning by remember { mutableStateOf(false) }
    var hardware by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<TeyesLaunchableApp>>(emptyList()) }
    var appsLoaded by remember { mutableStateOf(false) }
    val actions = remember(revision, longPress) { preferences.mappedKeys(longPress) }
    val appMappings = remember(revision, longPress) { preferences.mappedKeyApps(longPress) }
    val codes = (actions.keys + appMappings.keys).sorted()
    fun stopLearning() { router?.cancelPressedKeys(); learning = false }
    LaunchedEffect(chooser) {
        if (chooser) {
            appsLoaded = false
            apps = withContext(Dispatchers.IO) { TeyesAppShortcuts.available(context) }
            appsLoaded = true
        }
    }
    LaunchedEffect(status) { if (router?.isLearning != true) learning = false }
    LaunchedEffect(learningToken) {
        if (learning) { delay(15_000); stopLearning() }
    }
    DisposableEffect(router) { onDispose { router?.cancelPressedKeys() } }
    SettingsDisclosure(stringResource(R.string.teyes_steering_shortcuts),
        androidx.compose.ui.res.pluralStringResource(R.plurals.teyes_learned_buttons, codes.size, codes.size),
        onCollapse = { stopLearning(); chooser = false; reset = false }) {
        Text(stringResource(R.string.steering_scope))
        if (status.isNotEmpty()) SettingsNotice(status)
        if (router == null) SettingsNotice(stringResource(R.string.teyes_steering_unavailable))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !longPress, onClick = { stopLearning(); longPress = false },
                label = { Text(stringResource(R.string.layout_short_press)) })
            FilterChip(selected = longPress, onClick = { stopLearning(); longPress = true },
                label = { Text(stringResource(R.string.layout_long_press)) })
        }
        TextButton(onClick = { stopLearning(); editingCode = null; chooser = true }, enabled = router != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.steering_add))
        }
        if (learning) TextButton(onClick = { stopLearning() }) { Text(stringResource(R.string.teyes_cancel_learning)) }
        codes.forEach { code ->
            val label = actions[code]?.let { stringResource(it.labelRes) }
                ?: appMappings[code].orEmpty()
            Column {
                Text(KeyEvent.keyCodeToString(code))
                Text(label)
                Row {
                    TextButton(onClick = { stopLearning(); editingCode = code; chooser = true }) {
                        Text(stringResource(R.string.steering_change))
                    }
                    TextButton(onClick = { stopLearning(); preferences.mapKey(code, null, longPress) }) {
                        Text(stringResource(R.string.steering_remove))
                    }
                }
            }
        }
        TextButton(onClick = { stopLearning(); hardware = true }, enabled = !moving) {
            Text(stringResource(R.string.steer_hw_title))
        }
        Text(stringResource(R.string.steering_backup_hint))
        TextButton(onClick = { stopLearning(); reset = true },
            enabled = preferences.mappedKeys().isNotEmpty() || preferences.mappedKeys(true).isNotEmpty() ||
                preferences.mappedKeyApps().isNotEmpty() || preferences.mappedKeyApps(true).isNotEmpty()) {
            Text(stringResource(R.string.steering_reset))
        }
    }
    if (hardware) SteeringHardwareScreen(moving, onParkedAction) { hardware = false }
    if (chooser) AlertDialog(
        onDismissRequest = { chooser = false },
        title = { Text(stringResource(R.string.steering_choose_action)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(TeyesKeyAction.entries.toList(), key = { it.name }) { action ->
                    TextButton(onClick = {
                        val code = editingCode
                        if (code != null) preferences.mapKey(code, action, longPress)
                        else { router?.learn(action, longPress); learning = true; learningToken++ }
                        chooser = false
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(action.labelRes)) }
                }
                item { Text(stringResource(R.string.steering_open_app)) }
                if (appsLoaded && apps.isEmpty()) item { Text(stringResource(R.string.steering_no_apps)) }
                items(apps, key = { it.component }) { app ->
                    TextButton(onClick = {
                        val code = editingCode
                        if (code != null) preferences.mapKeyApp(code, app.component, longPress)
                        else { router?.learnApp(app.component, longPress); learning = true; learningToken++ }
                        chooser = false
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(app.label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { chooser = false }) { Text(stringResource(R.string.teyes_cancel_learning)) } },
    )
    if (reset) AlertDialog(
        onDismissRequest = { reset = false }, title = { Text(stringResource(R.string.steering_reset)) },
        text = { Text(stringResource(R.string.steering_reset_detail)) },
        confirmButton = { TextButton(onClick = { preferences.resetKeyMappings(); reset = false }) { Text(stringResource(R.string.steering_reset)) } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text(stringResource(R.string.teyes_cancel_learning)) } },
    )
}
