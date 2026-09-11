package com.cabin.launcher

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.R
import com.cabin.navigation.NavigationState
import com.cabin.platform.TeyesAppShortcuts
import com.cabin.platform.TeyesLaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

/** ETA is anchored to reception time so stale duration does not push arrival later every second. */
internal fun routeSecondsRemaining(nav: NavigationState, streaming: Boolean, now: Long): Int? {
    if (!launcherGuidanceFresh(streaming, nav.isActive, nav.lastUpdateElapsedRealtimeMs, now) || !nav.hasEta || nav.timeToDestination < 0) return null
    val received = nav.etaUpdatedElapsedRealtimeMs ?: return null
    if (received < 0 || now < received || now - received > 30_000) return null
    return (nav.timeToDestination - ((now - received) / 1000).toInt()).coerceAtLeast(0)
}

@Composable
internal fun RouteOverviewWidget(nav: NavigationState, streaming: Boolean, now: Long) {
    val context = LocalContext.current
    val remaining = routeSecondsRemaining(nav, streaming, now)
    val fresh = launcherGuidanceFresh(streaming, nav.isActive, nav.lastUpdateElapsedRealtimeMs, now)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("widget-ROUTE_OVERVIEW")) {
        val compact = maxHeight < 160.dp || maxWidth < 180.dp
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact) Icon(Icons.Default.Route, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (fresh) nav.destinationName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.widget_route_overview)
                else stringResource(R.string.widget_route_overview), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge)
            Text(remaining?.let { stringResource(R.string.widget_minutes, (it + 59L) / 60) } ?: "—",
                style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            if (!compact) {
                Text(stringResource(R.string.widget_arrival) + " · " + (remaining?.let {
                    android.text.format.DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + it * 1000L))
                } ?: "—"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun AudioControlWidget() {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var level by remember { mutableIntStateOf(0) }
    var max by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var fixed by remember { mutableStateOf(true) }
    fun refresh() {
        try {
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            fixed = audio.isVolumeFixed
        } catch (_: RuntimeException) { fixed = true }
    }
    fun adjust(direction: Int) {
        try { if (!audio.isVolumeFixed) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        catch (_: RuntimeException) { fixed = true }
        refresh()
    }
    LaunchedEffect(audio, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { refresh(); delay(500) } } }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("widget-AUDIO_CONTROL")) {
        val narrow = maxWidth < 200.dp
        val small = maxHeight < (if (narrow) 180.dp else 140.dp)
        @Composable fun Mute() {
            FilledTonalIconButton({ adjust(AudioManager.ADJUST_TOGGLE_MUTE) }, enabled = !fixed, modifier = Modifier.size(56.dp)) {
                Icon(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, stringResource(R.string.teyes_mute))
            }
        }
        @Composable fun Step(direction: Int, label: Int) {
            IconButton({ adjust(direction) }, enabled = !fixed && (if (direction < 0) level > 0 else level < max), modifier = Modifier.size(56.dp)) {
                Icon(if (direction < 0) Icons.Default.Remove else Icons.Default.Add, stringResource(label))
            }
        }
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (narrow && small) Mute() else if (narrow) {
                Mute()
                Row { Step(AudioManager.ADJUST_LOWER, R.string.teyes_volume_lower); Step(AudioManager.ADJUST_RAISE, R.string.teyes_volume_higher) }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Step(AudioManager.ADJUST_LOWER, R.string.teyes_volume_lower); Mute(); Step(AudioManager.ADJUST_RAISE, R.string.teyes_volume_higher)
            }
            if (!small) Text(if (fixed) stringResource(R.string.teyes_volume_firmware) else "$level / $max", maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun PinnedAppsWidget(preferences: LauncherPreferences, moving: Boolean, onParkedAction: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val layout by preferences.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var apps by remember { mutableStateOf<List<TeyesLaunchableApp>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(context, preferences, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            apps = withContext(Dispatchers.IO) { TeyesAppShortcuts.available(context) }
            preferences.refresh()
        }
    }
    LaunchedEffect(moving) { if (moving) more = false }
    val pinned = layout.favorites.mapNotNull { id -> apps.firstOrNull { it.component == id } }
    fun launch(app: TeyesLaunchableApp) {
        if (!moving) onParkedAction { failed = !TeyesAppShortcuts.launch(context, app.component); more = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp).testTag("widget-PINNED_APPS")) {
        val columns = (maxWidth.value / 160).toInt().coerceAtLeast(1)
        val rows = (maxHeight.value / 64).toInt().coerceAtLeast(1)
        val capacity = columns * rows
        val overflow = pinned.size > capacity
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            if (pinned.isEmpty()) Text(stringResource(R.string.launcher_pin_hint), maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            else if (failed) TextButton({ failed = false }) { Text(stringResource(R.string.launcher_action_failed), maxLines = 2) }
            else {
                val visible = pinned.take(if (overflow) capacity - 1 else capacity)
                val cells = visible.map { it as TeyesLaunchableApp? } + if (overflow) listOf(null) else emptyList()
                cells.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { app ->
                            Box(Modifier.weight(1f)) {
                                FilledTonalButton({ if (app == null) more = true else launch(app) }, enabled = !moving,
                                    modifier = Modifier.fillMaxWidth().height(56.dp), contentPadding = PaddingValues(4.dp)) {
                                    if (app == null) Icon(Icons.Default.MoreHoriz, stringResource(R.string.launcher_favorites))
                                    else Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (app == null) DropdownMenu(more && !moving, { more = false }) {
                                    pinned.forEach { item -> DropdownMenuItem(text = { Text(item.label) }, onClick = { launch(item) }) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
