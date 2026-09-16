package com.cabin.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

@Composable
internal fun QuickControls(glance: Boolean, onGlance: () -> Unit, onClimate: () -> Unit,
    onCamera: (() -> Unit)?, onDismiss: () -> Unit,
    moving: Boolean = false, onParkedAction: (() -> Unit) -> Unit = { it() }) {
    val context = LocalContext.current
    val activity = remember(context) {
        var current: Context = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        current as? Activity
    }
    var brightness by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
            ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 153) / 255f).coerceIn(.1f, 1f))
    }
    Dialog(onDismissRequest = onDismiss) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        // A dialog owns a separate window; changing only the Activity is hidden by it.
        val requestedBrightness = brightness
        SideEffect { dialogWindow?.let { it.attributes = it.attributes.apply { screenBrightness = requestedBrightness } } }
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tools_quick), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.tools_volume))
                Box(Modifier.fillMaxWidth().height(280.dp)) { AudioControlWidget(moving, onParkedAction) }
                Text(stringResource(R.string.tools_brightness))
                Slider(value = brightness.coerceIn(.1f, 1f), onValueChange = {
                    brightness = it
                    activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = it } }
                }, valueRange = .1f..1f, enabled = activity != null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { onDismiss(); onClimate() }) { Text(stringResource(R.string.launcher_climate)) }
                    if (onCamera != null) FilledTonalButton(onClick = { onDismiss(); onCamera() }) { Text(stringResource(R.string.vehicle_camera_mode)) }
                    FilledTonalButton(onClick = { onDismiss(); onGlance() }) {
                        Text(stringResource(if (glance) R.string.tools_exit_glance else R.string.layout_glance))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}
