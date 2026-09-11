package com.cabin.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickControls(glance: Boolean, onGlance: () -> Unit, onClimate: () -> Unit,
    onCamera: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = remember(context) {
        var current: Context = context
        while (current is ContextWrapper && current !is Activity) current = current.baseContext
        current as? Activity
    }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: .6f) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tools_quick), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.tools_volume))
            Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..maxVolume.toFloat(),
                onValueChangeFinished = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.toInt(), 0) })
            Text(stringResource(R.string.tools_brightness))
            Slider(value = brightness, onValueChange = {
                brightness = it
                activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = it } }
            }, valueRange = .1f..1f)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onDismiss(); onClimate() }) { Text(stringResource(R.string.launcher_climate)) }
                if (onCamera != null) FilledTonalButton(onClick = { onDismiss(); onCamera() }) { Text(stringResource(R.string.vehicle_camera_mode)) }
                FilledTonalButton(onClick = { onDismiss(); onGlance() }) {
                    Text(stringResource(if (glance) R.string.tools_exit_glance else R.string.layout_glance))
                }
            }
        }
    }
}
