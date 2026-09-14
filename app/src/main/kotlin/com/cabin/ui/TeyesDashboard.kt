package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.CabinManager
import com.cabin.background.CabinProjectionService
import com.cabin.navigation.NavigationState
import com.cabin.navigation.NavigationStateManager
import com.cabin.platform.labelRes
import com.cabin.platform.labelRes
import com.cabin.platform.MeasurementFormatter
import com.cabin.platform.MeasurementPreferences
import com.cabin.platform.MeasurementUnit
import com.cabin.platform.ProjectionHealthSnapshot
import com.cabin.platform.TeyesAppShortcuts
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesDiagnostics
import com.cabin.platform.TeyesDrivingState
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import com.cabin.platform.TeyesReading
import com.cabin.platform.TeyesShortcut
import com.cabin.platform.TeyesVehicleReadings
import com.cabin.platform.obd.ObdSnapshot
import com.cabin.platform.teyesVehicleReadings
import com.cabin.ui.settings.teyesOilServiceReading
import com.cabin.ui.settings.teyesVehicleSettingsReadings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Responsive native hub; projection Surface stays mounted underneath this opaque screen. */
@Composable
fun TeyesDashboard(
    manager: CabinManager,
    vehicle: TeyesClimateState,
    onProjection: () -> Unit,
    onSettings: () -> Unit,
    onClimate: (() -> Unit)?,
    onRetryVehicle: () -> Unit,
    onParkedAction: (() -> Unit) -> Unit,
    moving: Boolean,
    speedKnown: Boolean,
    onConnectPhone: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val preferences = remember { TeyesFeaturePreferences.get(context) }
    val measurementPreferences = remember(context) { MeasurementPreferences.get(context) }
    val measurementUnit by measurementPreferences.unit.collectAsStateWithLifecycle()
    val profile by preferences.profile.collectAsStateWithLifecycle()
    val revision by preferences.revision.collectAsStateWithLifecycle()
    val projection by manager.dashboardState.collectAsStateWithLifecycle()
    val navigation by NavigationStateManager.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val readings = teyesVehicleReadings(vehicle)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("") }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    var previewReport by remember { mutableStateOf<String?>(null) }
    val shortcuts = remember(revision) { teyesVisibleShortcuts(TeyesShortcut.entries.associateWith(preferences::shortcut)) }
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val report = pendingReport
            pendingReport = null
            if (uri != null && report != null && !saving) {
                saving = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(report.toByteArray(Charsets.UTF_8)) }
                        }
                        exportStatus = resources.getString(R.string.hub_report_saved)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        exportStatus = resources.getString(R.string.hub_save_failed)
                    } finally {
                        saving = false
                    }
                }
            }
        }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = SystemClock.elapsedRealtime()
                delay(1_000)
            }
        }
    }
    TeyesDashboardContent(
        model =
            TeyesDashboardUiState(
                profileName = profile.name,
                measurementUnit = measurementUnit,
                projection = projection,
                vehicle = vehicle,
                readings = readings,
                navigation = navigation,
                nowMs = now,
                shortcuts = shortcuts,
                moving = moving,
                speedKnown = speedKnown,
                climatePanelAvailable = onClimate != null,
                exportBusy = saving || pendingReport != null || previewReport != null,
                exportStatus = exportStatus,
            ),
        actions =
            TeyesDashboardActions(
                projection = onProjection,
                settings = onSettings,
                climate = { onClimate?.invoke() },
                connect =
                    onConnectPhone ?: {
                        try {
                            CabinProjectionService.startPhoneConnection(context)
                        } catch (_: RuntimeException) {
                            Toast.makeText(context, resources.getString(R.string.hub_connect_failed), Toast.LENGTH_LONG).show()
                        }
                    },
                media = { manager.performTeyesKey(it) },
                shortcut = { kind ->
                    onParkedAction {
                        if (!TeyesAppShortcuts.launch(context, shortcuts[kind])) {
                            Toast.makeText(context, resources.getString(R.string.hub_app_unavailable), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                retryVehicle = { onParkedAction(onRetryVehicle) },
                exportReport = {
                    onParkedAction {
                        if (!saving && previewReport == null && pendingReport == null) {
                            saving = true
                            scope.launch {
                                try {
                                    val framework = withContext(Dispatchers.IO) { com.cabin.platform.SyuFrameworkProbe.inspect() }
                                    previewReport = TeyesDiagnostics.encode(projection, vehicle, shortcuts.size, Build.VERSION.SDK_INT, framework)
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                },
            ),
    )
    previewReport?.let { report ->
        HealthReportPreview(
            report = report,
            onSave = {
                onParkedAction {
                    pendingReport = report
                    previewReport = null
                    try {
                        export.launch("cabin-health.json")
                    } catch (_: RuntimeException) {
                        pendingReport = null
                        exportStatus = resources.getString(R.string.hub_picker_unavailable)
                    }
                }
            },
            onCancel = { previewReport = null },
        )
    }
}

/** Pure presentation boundary for compact-screen, font-scale and accessibility checks. */
internal data class TeyesDashboardUiState(
    val profileName: String,
    val projection: ProjectionHealthSnapshot,
    val vehicle: TeyesClimateState,
    val readings: TeyesVehicleReadings,
    /** Legacy fixture field only. No runtime collection, fallback readings or accessory controls. */
    val obd: ObdSnapshot = ObdSnapshot(),
    val navigation: NavigationState,
    val nowMs: Long,
    val shortcuts: Map<TeyesShortcut, String?>,
    val moving: Boolean,
    val speedKnown: Boolean,
    val climatePanelAvailable: Boolean,
    val exportBusy: Boolean = false,
    val exportStatus: String = "",
    val measurementUnit: MeasurementUnit = MeasurementUnit.METRIC,
) {
    val canConfigure: Boolean get() = TeyesDrivingState(speedKnown, moving).canRequestParkedAction
    val streaming: Boolean get() = projection.connection == CabinManager.State.STREAMING
    val guidanceFresh: Boolean
        get() =
            streaming && navigation.isActive && navigation.lastUpdateElapsedRealtimeMs?.let {
                it >= 0 && nowMs >= it && nowMs - it <= 30_000L
            } == true
}

internal data class TeyesDashboardActions(
    val projection: () -> Unit,
    val settings: () -> Unit,
    val climate: () -> Unit,
    val connect: () -> Unit,
    val media: (TeyesKeyAction) -> Unit,
    val shortcut: (TeyesShortcut) -> Unit,
    /** Legacy fixture slot only; no dashboard action calls it. */
    val disconnectObd: () -> Unit = {},
    val retryVehicle: () -> Unit,
    val exportReport: () -> Unit,
)

@Composable
internal fun TeyesDashboardContent(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontScale = LocalDensity.current.fontScale
            // Cap at three columns and give enlarged text more room instead of squeezing controls.
            val columns = teyesHubColumnCount(maxWidth.value, fontScale)
            // Never let a fixed header consume a short or enlarged-text viewport. The full
            // header becomes a grid item so every action remains reachable by scrolling.
            val scrollingHeader = teyesHubHeaderScrolls(maxWidth.value, maxHeight.value, fontScale)
            Column {
                if (!scrollingHeader) HubHeader(model, actions, Modifier.padding(horizontal = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("teyes_hub_grid"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (scrollingHeader) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HubHeader(model, actions) }
                    }
                    item(key = "connection") { ConnectionCard(model, actions) }
                    item(key = "vehicle") { VehicleCard(model, actions) }
                    item(key = "media") { MediaCard(model, actions) }
                    item(key = "climate") { ClimateCard(model, actions) }
                    item(key = "guidance") { GuidanceCard(model, actions) }
                    item(key = "accessories") { AccessoriesCard(model, actions) }
                    item(key = "health") { HealthCard(model, actions) }
                }
            }
        }
    }
}

@Composable
private fun HubHeader(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hub_your_drive), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(
                    "${stringResource(R.string.app_name)} · ${model.profileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = actions.projection, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.hub_return_projection))
            }
            IconButton(onClick = actions.settings, enabled = model.canConfigure, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Settings, contentDescription = if (model.canConfigure) stringResource(R.string.hub_settings_parked) else stringResource(R.string.hub_settings_locked))
            }
        }
        if (model.moving && !model.speedKnown) {
            TextButton(onClick = actions.settings, modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.hub_speed_lost), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (model.moving) Icons.Default.Lock else Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (model.moving) stringResource(R.string.hub_settings_locked) else stringResource(R.string.hub_parked_detail),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    val disconnected = model.projection.connection == CabinManager.State.DISCONNECTED
    HubCard(stringResource(R.string.hub_phone_connection), Icons.Default.Usb, highlighted = true) {
        Text(teyesConnectionHeadline(androidx.compose.ui.platform.LocalResources.current, model.projection.connection), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (model.streaming) stringResource(R.string.hub_phone_ready) else model.projection.status.ifBlank { stringResource(R.string.hub_attach_adapter) },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Button(onClick = if (disconnected) actions.connect else actions.projection, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(if (disconnected) Icons.Default.Usb else Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                if (disconnected) {
                    stringResource(R.string.action_connect_phone)
                } else if (model.streaming) {
                    stringResource(R.string.action_open_projection)
                } else {
                    stringResource(R.string.hub_show_connection)
                },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            if (disconnected) stringResource(R.string.hub_adapter_required) else stringResource(R.string.hub_background_detail),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VehicleCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    HubCard(stringResource(R.string.hub_vehicle), Icons.Default.Speed) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LargeReading(stringResource(R.string.vehicle_speed), model.readings.speed?.let { it.copy(value = MeasurementFormatter.speedValue(it.value, model.measurementUnit)) }, MeasurementFormatter.speedLabel(model.measurementUnit), Modifier.weight(1f))
            LargeReading(stringResource(R.string.hub_engine), model.readings.rpm, "RPM", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.hub_vehicle_limits),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (model.readings.speed == null && model.readings.rpm == null) {
            TextButton(onClick = actions.settings, enabled = model.canConfigure, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.hub_setup_vehicle))
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        } else {
            Text(stringResource(R.string.hub_factory_instruments), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    HubCard(stringResource(R.string.hub_now_playing), Icons.Default.MusicNote) {
        Text(
            model.projection.title.ifBlank {
                if (model.streaming) stringResource(R.string.hub_choose_music) else stringResource(R.string.hub_your_music)
            },
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            model.projection.artist.ifBlank { if (model.streaming) stringResource(R.string.hub_waiting_media) else stringResource(R.string.hub_connect_music) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { actions.media(TeyesKeyAction.PREVIOUS) }, enabled = model.streaming, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.teyes_action_previous), modifier = Modifier.size(28.dp))
            }
            FilledIconButton(onClick = { actions.media(TeyesKeyAction.PLAY_PAUSE) }, enabled = model.streaming, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (model.projection.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (model.projection.playing) stringResource(R.string.hub_pause_music) else stringResource(R.string.hub_play_music),
                    modifier = Modifier.size(32.dp),
                )
            }
            OutlinedIconButton(onClick = { actions.media(TeyesKeyAction.NEXT) }, enabled = model.streaming, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.teyes_action_next), modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun ClimateCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    val vehicle = model.vehicle
    HubCard(stringResource(R.string.hub_climate_doors), Icons.Default.Air) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TemperatureReading(stringResource(R.string.label_left), teyesDashboardTemperature(vehicle, left = true), Modifier.weight(1f))
            TemperatureReading(stringResource(R.string.label_right), teyesDashboardTemperature(vehicle, left = false), Modifier.weight(1f))
        }
        val openDoors = vehicleDoorWarning(androidx.compose.ui.platform.LocalResources.current, vehicle)
        Text(
            teyesDoorStatus(androidx.compose.ui.platform.LocalResources.current, vehicle),
            style = MaterialTheme.typography.bodyMedium,
            color = if (openDoors != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!vehicle.controlsAvailable) {
            Text(
                when {
                    !vehicle.connected -> stringResource(R.string.hub_waiting_vehicle)
                    !vehicle.controlsSupported -> stringResource(R.string.hub_climate_unsupported)
                    else -> stringResource(R.string.hub_climate_waiting)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = actions.climate, enabled = model.climatePanelAvailable, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.hub_open_ac))
            Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun GuidanceCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    HubCard(stringResource(R.string.hub_phone_navigation), Icons.Default.NearMe) {
        if (model.guidanceFresh) {
            Text(
                model.navigation.roadName.orEmpty().take(160).ifBlank { stringResource(R.string.hub_guidance_active) },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    Text(
                        if (model.navigation.hasManeuverDistance) MeasurementFormatter.distance(model.navigation.remainDistance.toDouble(), model.measurementUnit) else "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.hub_next_maneuver), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    Text(
                        if (model.navigation.hasEta) "${(model.navigation.timeToDestination.coerceAtLeast(0).toLong() + 59) / 60} min" else "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.hub_destination), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text(if (model.streaming && model.navigation.isActive) stringResource(R.string.hub_waiting_guidance) else stringResource(R.string.hub_next_turn), style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    model.streaming && model.navigation.isActive -> stringResource(R.string.hub_guidance_stale)
                    model.streaming -> stringResource(R.string.hub_start_route)
                    else -> stringResource(R.string.hub_connect_route)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = actions.projection, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(stringResource(R.string.hub_view_projection))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun AccessoriesCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    val configured = teyesVisibleShortcuts(model.shortcuts)
    HubCard(stringResource(R.string.hub_your_apps), Icons.AutoMirrored.Filled.Launch) {
        if (configured.isEmpty()) {
            Text(stringResource(R.string.hub_favourites), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.hub_add_shortcuts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.settings, enabled = model.canConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.hub_choose_apps)) }
        } else {
            configured.keys.forEach { kind ->
                OutlinedButton(onClick = { actions.shortcut(kind) }, enabled = model.canConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(kind.labelRes), modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            TextButton(onClick = actions.settings, enabled = model.canConfigure, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.hub_edit_shortcuts)) }
        }
    }
}

@Composable
private fun HealthCard(
    model: TeyesDashboardUiState,
    actions: TeyesDashboardActions,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    HubCard(stringResource(R.string.hub_system_health), Icons.Default.Info) {
        Text(if (model.vehicle.connected) stringResource(R.string.hub_vehicle_connected) else stringResource(R.string.hub_vehicle_unavailable), style = MaterialTheme.typography.titleLarge)
        Text(
            teyesVehicleFieldSummary(androidx.compose.ui.platform.LocalResources.current, model.vehicle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpandDetailsButton(expanded, stringResource(R.string.hub_health_compatibility), { expanded = !expanded })
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.hub_profile_health, model.vehicle.profileId, stringResource(model.vehicle.health.labelRes)), style = MaterialTheme.typography.titleSmall)
            Text(
                model.vehicle.controlUnavailableReason ?: if (model.vehicle.controlsAvailable) {
                    stringResource(R.string.hub_climate_available)
                } else {
                    stringResource(R.string.hub_climate_unavailable)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val oilLife = teyesVehicleSettingsReadings(model.vehicle, model.measurementUnit).oilLife
            Text(stringResource(R.string.hub_oil_life_value, oilLife.takeUnless { it == "—" } ?: stringResource(R.string.state_unavailable)), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.hub_oil_service_value, teyesOilServiceReading(model.vehicle, model.measurementUnit).takeUnless { it == "—" } ?: stringResource(R.string.state_unavailable)), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = actions.retryVehicle,
                enabled = model.canConfigure,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text(if (model.vehicle.connected) stringResource(R.string.hub_refresh_readings) else stringResource(R.string.hub_retry_vehicle)) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.hub_connection_history), style = MaterialTheme.typography.titleSmall)
            Text(model.projection.status, style = MaterialTheme.typography.bodyMedium)
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.hub_disconnects, model.projection.disconnectTransitions, model.projection.disconnectTransitions), style = MaterialTheme.typography.bodyMedium)
            model.projection.lastRecoveryDurationMs?.let {
                Text(stringResource(R.string.hub_last_return, it / 1000), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.hub_incidents, model.projection.incidents.size, model.projection.incidents.size),
                style = MaterialTheme.typography.bodySmall,
            )
            val usbDetachCount = model.projection.events.count { it.kind == com.cabin.platform.ProjectionEventKind.USB_DETACHED }
            if (usbDetachCount >= 2) {
                Text(
                    stringResource(R.string.hub_repeated_detach),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            model.projection.events.takeLast(4).reversed().forEach {
                Text("+${it.elapsedMs / 1000}s · ${stringResource(it.kind.labelRes)}", style = MaterialTheme.typography.bodySmall)
            }
            if (model.projection.transitions.isEmpty()) {
                Text(stringResource(R.string.hub_no_changes), style = MaterialTheme.typography.bodySmall)
            } else {
                model.projection.transitions.takeLast(5).reversed().forEach {
                    Text("+${it.elapsedMs / 1000}s · ${teyesConnectionHeadline(androidx.compose.ui.platform.LocalResources.current, it.state)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                stringResource(R.string.hub_notification_stop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = actions.exportReport, enabled = !model.exportBusy && model.canConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(if (model.exportBusy) stringResource(R.string.hub_saving_report) else stringResource(R.string.hub_save_report), modifier = Modifier.padding(start = 8.dp))
            }
            if (model.exportStatus.isNotEmpty()) {
                Text(model.exportStatus, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(stringResource(R.string.hub_report_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.hub_compatibility), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.hub_cluster_limits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.hub_accessory_limits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.hub_readings_limits),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HubCard(
    title: String,
    icon: ImageVector,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                )
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

@Composable
private fun LargeReading(
    label: String,
    reading: TeyesReading?,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(teyesReadingValue(reading, 0), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$unit · ${reading?.source ?: stringResource(R.string.hub_no_data)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TemperatureReading(
    label: String,
    temperature: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(temperature, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandDetailsButton(
    expanded: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val expansionState = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = expansionState }) {
        Text(label, modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
    }
}

internal fun teyesReadingValue(
    reading: TeyesReading?,
    decimals: Int,
): String = reading?.takeIf { it.value.isFinite() }?.let { String.format(Locale.getDefault(), "%.${decimals.coerceIn(0, 2)}f", it.value) } ?: "—"

internal fun teyesConnectionHeadline(resources: android.content.res.Resources, state: CabinManager.State): String =
    when (state) {
        CabinManager.State.DISCONNECTED -> resources.getString(R.string.hub_connection_ready)
        CabinManager.State.CONNECTING -> resources.getString(R.string.hub_connection_connecting)
        CabinManager.State.DEVICE_CONNECTED -> resources.getString(R.string.hub_connection_connected)
        CabinManager.State.STREAMING -> resources.getString(R.string.hub_connection_projection_ready)
    }

internal fun teyesHubColumnCount(
    widthDp: Float,
    fontScale: Float,
): Int {
    val minimumCardWidth = 280 * fontScale.coerceIn(1f, 1.5f)
    return ((widthDp - 20) / (minimumCardWidth + 12)).toInt().coerceIn(1, 3)
}

internal fun teyesHubHeaderScrolls(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): Boolean = heightDp < 360f || widthDp < 360f || fontScale >= 1.5f

/** Old imported OBD mappings cannot expose an external adapter workflow from the Hub. */
internal fun teyesVisibleShortcuts(shortcuts: Map<TeyesShortcut, String?>): Map<TeyesShortcut, String?> =
    shortcuts.filter { (kind, component) -> kind != TeyesShortcut.OBD && !component.isNullOrBlank() }

internal fun teyesVehicleFieldSummary(resources: android.content.res.Resources, vehicle: TeyesClimateState): String =
    if (!vehicle.connected) {
        resources.getString(R.string.hub_waiting_teyes)
    } else {
        resources.getQuantityString(R.plurals.hub_vehicle_fields, vehicle.availableCodes.count { it != 1000 }, vehicle.availableCodes.count { it != 1000 })
    }

/** Partial reports must never imply that every door is closed. */
internal fun teyesDoorStatus(resources: android.content.res.Resources, vehicle: TeyesClimateState): String =
    when {
        !vehicle.connected -> resources.getString(R.string.hub_doors_disconnected)
        vehicleDoorWarning(resources, vehicle) != null -> vehicleDoorWarning(resources, vehicle)!!
        (36..41).all { it in vehicle.availableCodes } -> resources.getString(R.string.hub_doors_closed)
        (36..41).any { it in vehicle.availableCodes } -> resources.getString(R.string.hub_doors_partial)
        else -> resources.getString(R.string.hub_doors_unavailable)
    }

internal fun teyesDashboardTemperature(
    vehicle: TeyesClimateState,
    left: Boolean,
): String {
    val code = if (left) 25 else 31
    if (!vehicle.connected || code !in vehicle.availableCodes || 33 !in vehicle.availableCodes) return "—"
    return formatClimateTemperature(if (left) vehicle.leftTemperature else vehicle.rightTemperature, vehicle.fahrenheit)
}
