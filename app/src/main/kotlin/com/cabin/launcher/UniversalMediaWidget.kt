package com.cabin.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.platform.*

internal fun mediaAppLabel(context: Context, packageName: String): String {
    val fallback = context.getString(R.string.widget_media_app)
    return try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString().trim()
            .takeIf { it.isNotEmpty() && it != packageName } ?: fallback
    } catch (_: PackageManager.NameNotFoundException) {
        fallback
    } catch (_: SecurityException) {
        fallback
    }
}

@Composable
internal fun UniversalMediaWidget(manager: CabinManager, health: ProjectionHealthSnapshot, moving: Boolean, onParkedAction: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val players by CabinMediaSessions.players.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val player = players.firstOrNull { it.id == selected }
    val appLabels = remember(context, configuration, players.map { it.id }, selected) {
        (players.map { it.id } + selected).filter { it.isNotEmpty() }.distinct()
            .associateWith { mediaAppLabel(context, it) }
    }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val featurePrefs = remember { TeyesFeaturePreferences.get(context) }
    val sourceRevision by featurePrefs.revision.collectAsStateWithLifecycle()
    val memory = remember { context.getSharedPreferences("cabin_source_volume", 0) }
    fun select(id: String) {
        memory.edit().putInt(selected.ifEmpty { "projection" }, audio.getStreamVolume(AudioManager.STREAM_MUSIC)).apply()
        selected = id
        val saved = memory.all[id.ifEmpty { "projection" }] as? Int
        if (saved != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC, saved.coerceIn(0, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
    }
    fun control(action: TeyesKeyAction) {
        if (selected.isEmpty()) manager.performTeyesKey(action) else CabinMediaSessions.control(selected, action)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val roomForVoice = maxWidth >= 120.dp
    val compact = maxHeight < 180.dp || maxWidth < 220.dp
    Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Box {
            TextButton(onClick = { menu = true }) { Text(if (selected.isEmpty()) stringResource(R.string.launcher_now_playing) else appLabels.getValue(selected)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("CarPlay") }, onClick = { select(""); menu = false })
                listOf(TeyesShortcut.RADIO, TeyesShortcut.BLUETOOTH_AUDIO).forEach { kind ->
                    val component = remember(sourceRevision, kind) { featurePrefs.shortcut(kind) }
                    if (component != null) DropdownMenuItem(text = { Text(stringResource(kind.labelRes)) }, enabled = !moving,
                        onClick = { menu = false; onParkedAction {
                            if (TeyesAppShortcuts.launch(context, component)) select(component.substringBefore('/'))
                        } })
                }
                players.forEach { source -> DropdownMenuItem(text = { Text(appLabels.getValue(source.id)) },
                    onClick = { select(source.id); menu = false }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.tools_media_access)) }, enabled = !moving,
                    onClick = { menu = false; onParkedAction {
                        try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: RuntimeException) { }
                    } })
            }
        }
        Text(if (selected.isEmpty()) health.title else player?.title.orEmpty(), maxLines = 2, style = MaterialTheme.typography.titleMedium)
        if (!compact) Text(if (selected.isEmpty()) health.artist else player?.artist.orEmpty(), maxLines = 1, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (roomForVoice) IconButton({ manager.performTeyesKey(TeyesKeyAction.VOICE) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Mic, stringResource(R.string.launcher_voice))
            }
            if (!compact) IconButton({ control(TeyesKeyAction.PREVIOUS) }, enabled = selected.isEmpty() || player != null) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.teyes_action_previous)) }
            IconButton({ control(TeyesKeyAction.PLAY_PAUSE) }, modifier = Modifier.size(56.dp), enabled = selected.isEmpty() || player != null) {
                Icon(if (if (selected.isEmpty()) health.playing else player?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (if (selected.isEmpty()) health.playing else player?.playing == true) R.string.launcher_pause else R.string.launcher_play))
            }
            if (!compact) IconButton({ control(TeyesKeyAction.NEXT) }, enabled = selected.isEmpty() || player != null) { Icon(Icons.Default.SkipNext, stringResource(R.string.teyes_action_next)) }
        }
    }
}
}
