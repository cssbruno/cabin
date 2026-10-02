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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    val compact = maxHeight < 180.dp * LocalDensity.current.fontScale.coerceAtLeast(1f) || maxWidth < 248.dp
    val projectionReady = health.connection == CabinManager.State.STREAMING
    val canControl = if (selected.isEmpty()) projectionReady else player != null
    val title = if (selected.isEmpty()) health.title else player?.title.orEmpty()
    val artist = if (selected.isEmpty()) health.artist else player?.artist.orEmpty()
    val playing = if (selected.isEmpty()) health.playing else player?.playing == true
    val sourceLabel = stringResource(R.string.ux_launcher_choose_media)
    Column(Modifier.fillMaxSize().padding(if (compact) 4.dp else 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
        Box {
            TextButton(onClick = { menu = true }, modifier = Modifier.heightIn(min = 56.dp)
                .then(if (compact) Modifier.fillMaxWidth() else Modifier).semantics { contentDescription = sourceLabel },
                contentPadding = if (compact) PaddingValues(horizontal = 8.dp, vertical = 4.dp) else ButtonDefaults.TextButtonContentPadding) {
                Text(if (compact) title.ifBlank { stringResource(if (playing) R.string.ux_launcher_track_unavailable else R.string.ux_launcher_media_idle) }
                    else if (selected.isEmpty()) stringResource(R.string.launcher_now_playing) else appLabels.getValue(selected),
                    Modifier.weight(1f, fill = compact), maxLines = if (compact) 2 else 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(24.dp))
            }
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
        if (!compact) Text(title.ifBlank { stringResource(if (playing) R.string.ux_launcher_track_unavailable else R.string.ux_launcher_media_ready) },
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        if (!compact && (artist.isNotBlank() || title.isBlank())) Text(
            artist.ifBlank { stringResource(R.string.ux_launcher_media_hint) }, maxLines = 3,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (roomForVoice) IconButton({ manager.performTeyesKey(TeyesKeyAction.VOICE) }, enabled = projectionReady, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Mic, stringResource(R.string.launcher_voice))
            }
            if (!compact) IconButton({ control(TeyesKeyAction.PREVIOUS) }, modifier = Modifier.size(56.dp), enabled = canControl) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.teyes_action_previous)) }
            IconButton({ control(TeyesKeyAction.PLAY_PAUSE) }, modifier = Modifier.size(56.dp), enabled = canControl) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (playing) R.string.launcher_pause else R.string.launcher_play))
            }
            if (!compact) IconButton({ control(TeyesKeyAction.NEXT) }, modifier = Modifier.size(56.dp), enabled = canControl) { Icon(Icons.Default.SkipNext, stringResource(R.string.teyes_action_next)) }
        }
    }
}
}
