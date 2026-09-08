package com.cabin.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.navigation.NavigationStateManager
import com.cabin.platform.*
import kotlinx.coroutines.delay
import java.util.Date

/** The actual persistent SurfaceView is positioned by CabinApp behind this transparent opening. */
data class ProjectionModulePlacement(val bounds: Rect, val editing: Boolean)

internal fun DashboardModule.title(): Int = when (this) {
    DashboardModule.PROJECTION -> R.string.launcher_page_carplay
    DashboardModule.MEDIA -> R.string.launcher_now_playing
    DashboardModule.NAVIGATION -> R.string.launcher_navigation
    DashboardModule.SPEED -> R.string.vehicle_speed
    DashboardModule.RPM -> R.string.vehicle_engine_speed
    DashboardModule.OIL -> R.string.vehicle_oil_life
    DashboardModule.SERVICE -> R.string.vehicle_oil_service
    DashboardModule.DOORS -> R.string.module_doors
    DashboardModule.CLIMATE -> R.string.launcher_climate
    DashboardModule.FAN -> R.string.widget_fan
    DashboardModule.REAR_CLIMATE -> R.string.widget_rear_climate
    DashboardModule.SEATS -> R.string.widget_seats
    DashboardModule.DEFROST -> R.string.widget_defrost
    DashboardModule.CLOCK -> R.string.module_clock
    DashboardModule.WIDGET -> R.string.launcher_widgets
}

@Composable
fun ModularDashboard(manager: CabinManager, vehicle: TeyesClimateState, moving: Boolean,
    launcherPreferences: LauncherPreferences, onParkedAction: (() -> Unit) -> Unit,
    onProjectionPlacement: (ProjectionModulePlacement?) -> Unit, clock: String = "", onVehicle: () -> Unit = {}, onSettings: () -> Unit = {}, climateActions: ClimateWidgetActions = ClimateWidgetActions()) {
    val context = LocalContext.current
    val driver by TeyesFeaturePreferences.get(context).profile.collectAsStateWithLifecycle()
    val prefs = remember(driver.slot, vehicle.profileId, vehicle.vehicleDataLayout) {
        DashboardPreferences(context, driver.slot, vehicle.profileId, vehicle.vehicleDataLayout)
    }
    val layout by prefs.state.collectAsStateWithLifecycle()
    val launcher by launcherPreferences.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(driver.slot, vehicle.profileId, vehicle.vehicleDataLayout) { mutableIntStateOf(0) }
    var compactIndex by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var noRoom by remember { mutableStateOf(false) }
    val onPlacement by rememberUpdatedState(onProjectionPlacement)
    val host = remember { AppWidgetHost(context.applicationContext, HOST_ID) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(host, lifecycle) {
        fun start() { try { host.startListening() } catch (_: RuntimeException) { } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) try { host.stopListening() } catch (_: RuntimeException) { }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); try { host.stopListening() } catch (_: RuntimeException) { } }
    }
    DisposableEffect(Unit) { onDispose { onPlacement(null) } }
    LaunchedEffect(moving) { if (moving) { editing = false; selected = null; adding = false } }
    fun result(success: Boolean) { noRoom = !success }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val showVehicle = maxWidth >= 680.dp
    val compact = maxHeight < 240.dp || maxWidth < 600.dp
    val pageTiles = layout.tiles.filter { it.page == page }
    val subpage = compactIndex.coerceIn(0, (pageTiles.size - 1).coerceAtLeast(0))
    val displayed = if (compact) pageTiles.drop(subpage).take(1) else pageTiles
    val projection = displayed.firstOrNull { it.module == DashboardModule.PROJECTION }
    LaunchedEffect(page, prefs, projection) { if (projection == null) onPlacement(null) }
    val displayPage = if (compact) (0 until page).sumOf { p -> maxOf(1, layout.tiles.count { it.page == p }) } + subpage + 1 else page + 1
    val displayPages = if (compact) (0 until layout.pages).sumOf { p -> maxOf(1, layout.tiles.count { it.page == p }) } else layout.pages
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 60.dp).background(MaterialTheme.colorScheme.background), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PageDots(displayPage - 1, displayPages, { target ->
                if (compact) {
                    var remaining = target
                    var destination = 0
                    while (destination < layout.pages - 1 && remaining >= maxOf(1, layout.tiles.count { it.page == destination })) {
                        remaining -= maxOf(1, layout.tiles.count { it.page == destination })
                        destination++
                    }
                    page = destination
                    compactIndex = remaining
                } else { page = target; compactIndex = 0 }
            }, maxVisible = if (compact) 3 else 5)
            if (showVehicle && !editing) IconButton(onVehicle, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.DirectionsCar, stringResource(R.string.launcher_vehicle))
            }
            IconButton({ onParkedAction { editing = !editing; selected = null } }, enabled = !moving, modifier = Modifier.size(56.dp)) {
                Icon(if (editing) Icons.Default.Check else Icons.Default.Tune,
                    stringResource(if (editing) R.string.launcher_done else R.string.module_edit),
                    tint = if (editing) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            if (!editing) IconButton({ onParkedAction { onSettings() } }, enabled = !moving,
                modifier = Modifier.size(56.dp).testTag("dashboard-settings")) {
                Icon(Icons.Default.Settings, stringResource(R.string.launcher_settings))
            }
            if (editing && !moving) Box {
                IconButton({ adding = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Add, stringResource(R.string.module_add)) }
                DropdownMenu(adding, { adding = false }) {
                    DashboardModule.entries.filter { it != DashboardModule.WIDGET }.forEach { module ->
                        DropdownMenuItem(text = { Text(stringResource(module.title())) },
                            enabled = module != DashboardModule.PROJECTION || layout.tiles.none { it.module == module },
                            onClick = { adding = false; result(prefs.add(module, page)) })
                    }
                    launcher.widgets.forEach { id ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.module_android_widget, id)) },
                            enabled = layout.tiles.none { it.module == DashboardModule.WIDGET && it.widgetId == id },
                            onClick = { adding = false; result(prefs.add(DashboardModule.WIDGET, page, id)) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.module_add_page)) }, enabled = layout.pages < 6,
                        onClick = { adding = false; if (prefs.addPage()) page = prefs.state.value.pages - 1 })
                }
            }
        }
        if (noRoom) TextButton({ noRoom = false }) { Text(stringResource(R.string.module_no_room)) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("dashboard-grid")) {
            val cellWidth = maxWidth / 4
            val cellHeight = maxHeight / 2
            displayed.forEach { tile ->
                key(tile.id) {
                    val tileModifier = Modifier.offset(cellWidth * if (compact) 0 else tile.x, cellHeight * if (compact) 0 else tile.y)
                        .size((cellWidth * if (compact) 4 else tile.width).minus(4.dp).coerceAtLeast(1.dp), (cellHeight * if (compact) 2 else tile.height).minus(4.dp).coerceAtLeast(1.dp))
                        .clipToBounds().testTag("module-${tile.module.name}-${tile.id}")
                    if (tile.module == DashboardModule.PROJECTION) {
                        Box(tileModifier.onGloballyPositioned { onPlacement(ProjectionModulePlacement(it.boundsInRoot(), editing)) }) {
                            // No button, image, background, or gesture detector here: touches reach the live phone surface.
                            if (editing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                                .border(2.dp, MaterialTheme.colorScheme.primary).clickable { selected = tile.id }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.OpenWith, stringResource(R.string.module_resize_projection), Modifier.size(40.dp), tint = Color.White)
                            }
                        }
                    } else {
                        Card(tileModifier.then(if (!editing && !moving) Modifier.pointerInput(tile.id, prefs) {
                            detectTapGestures(onLongPress = { onParkedAction { editing = true } })
                        } else Modifier), shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxSize()) {
                                if (tile.module == DashboardModule.WIDGET) {
                                    if (moving) Text(stringResource(R.string.module_widget_parked), Modifier.padding(16.dp))
                                    else DashboardAndroidWidget(host, tile.widgetId)
                                } else DashboardModuleContent(tile.module, manager, vehicle, onVehicle, climateActions)
                                if (editing) {
                                    Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary)
                                        .clickable { selected = tile.id })
                                    if (!compact && !moving) WidgetResizeHandle(tile, cellWidth, cellHeight,
                                        onResize = { width, height -> onParkedAction { result(prefs.resizeInPlace(tile.id, width, height)) } },
                                        modifier = Modifier.align(Alignment.BottomEnd))
                                }
                            }
                        }
                    }
                }
            }
            if (layout.tiles.none { it.page == page }) Text(stringResource(R.string.module_empty), Modifier.align(Alignment.Center))
        }
    }
    }
    selected?.takeUnless { moving }?.let { id -> layout.tiles.firstOrNull { it.id == id }?.let { tile ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(stringResource(tile.module.title())) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.module_size))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sizes = (1..2).flatMap { h -> (1..4).map { w -> w to h } }
                        sizes.forEach { (w, h) -> FilterChip(selected = tile.width == w && tile.height == h, onClick = { result(prefs.resize(id, w, h)) }, modifier = Modifier.heightIn(min = 56.dp), label = { Text("$w × $h") }) }
                    }
                    Text(stringResource(R.string.module_move))
                    Row {
                        listOf(Triple(-1, 0, Icons.Default.ArrowBack), Triple(1, 0, Icons.Default.ArrowForward), Triple(0, -1, Icons.Default.ArrowUpward), Triple(0, 1, Icons.Default.ArrowDownward)).forEachIndexed { index, (x, y, icon) ->
                            val labels = listOf(R.string.module_left, R.string.module_right, R.string.module_up, R.string.module_down)
                            IconButton({ result(prefs.move(id, x, y)) }, Modifier.size(56.dp)) { Icon(icon, stringResource(labels[index])) }
                        }
                    }
                    OutlinedButton({ result(prefs.movePage(id, page + 1)); if (!noRoom) { page++; selected = null } }, enabled = page < 5) { Text(stringResource(R.string.module_move_next_page)) }
                    OutlinedButton({ result(prefs.movePage(id, page - 1)); if (!noRoom) { page--; selected = null } }, enabled = page > 0) { Text(stringResource(R.string.module_move_previous_page)) }
                    TextButton({ prefs.remove(id); selected = null }) { Text(stringResource(R.string.module_remove)) }
                    if (noRoom) Text(stringResource(R.string.module_no_room))
                }
            }, confirmButton = { TextButton({ selected = null }) { Text(stringResource(R.string.launcher_done)) } })
    } }
}

@Composable
private fun DashboardModuleContent(module: DashboardModule, manager: CabinManager, vehicle: TeyesClimateState, onClimate: () -> Unit = {}, climateActions: ClimateWidgetActions = ClimateWidgetActions()) {
    val context = LocalContext.current
    val health by manager.dashboardState.collectAsStateWithLifecycle()
    val nav by NavigationStateManager.state.collectAsStateWithLifecycle()
    val units by MeasurementPreferences.get(context).unit.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { now = android.os.SystemClock.elapsedRealtime(); delay(1000) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val colors = MaterialTheme.colorScheme
        val short = maxHeight < 160.dp
        val wide = maxWidth >= 220.dp
        val padding = if (short) 8.dp else 20.dp
        val gauge = when (module) {
            DashboardModule.SPEED -> VehicleGauge.SPEED; DashboardModule.RPM -> VehicleGauge.RPM
            DashboardModule.OIL -> VehicleGauge.OIL; DashboardModule.SERVICE -> VehicleGauge.SERVICE; else -> null
        }
        if (gauge != null) {
            val reading = vehicleGaugeReading(gauge, vehicle, units)
            val title = stringResource(module.title())
            val availability = if (reading.available) reading.unit else stringResource(R.string.gauge_unavailable)
            Column(Modifier.fillMaxSize().padding(padding).semantics { contentDescription = title; stateDescription = availability },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(reading.value, fontSize = if (short) 32.sp else 64.sp, fontWeight = FontWeight.Light, maxLines = 1)
                if (reading.available) Text(reading.unit, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (!short) Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
            }
        } else when (module) {
            DashboardModule.CLIMATE, DashboardModule.FAN, DashboardModule.REAR_CLIMATE, DashboardModule.SEATS, DashboardModule.DEFROST -> VehicleComfortWidget(module, vehicle, climateActions, onClimate)
            DashboardModule.MEDIA -> {
                val active = health.connection == CabinManager.State.STREAMING
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!short && health.title.isBlank()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, Modifier.size(40.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    } else if (!short) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(health.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (health.artist.isNotBlank()) Text(health.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        if (wide) IconButton({ manager.performProjectionAction(CabinManager.ProjectionAction.VOICE) }, enabled = active, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Mic, stringResource(R.string.launcher_voice))
                        }
                        FilledIconButton({ manager.performProjectionAction(CabinManager.ProjectionAction.PLAY_PAUSE) }, enabled = active, modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(disabledContainerColor = colors.surfaceContainerHighest, disabledContentColor = colors.onSurfaceVariant)) {
                            Icon(if (health.playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (health.playing) R.string.launcher_pause else R.string.launcher_play), Modifier.size(28.dp))
                        }
                        if (wide) IconButton({ manager.performProjectionAction(CabinManager.ProjectionAction.NEXT) }, enabled = active, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, stringResource(R.string.launcher_next))
                        }
                    }
                }
            }
            DashboardModule.NAVIGATION -> {
                val fresh = launcherGuidanceFresh(health.connection == CabinManager.State.STREAMING, nav.isActive, nav.lastUpdateElapsedRealtimeMs, now)
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.NearMe, stringResource(module.title()), Modifier.size(if (short) 24.dp else 40.dp), tint = if (fresh) colors.primary else colors.onSurfaceVariant)
                    if (fresh) {
                        if (nav.hasManeuverDistance) Text(MeasurementFormatter.distance(nav.remainDistance.toDouble(), units), style = MaterialTheme.typography.headlineSmall)
                        if (!nav.roadName.isNullOrBlank()) Text(nav.roadName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (!short) Icon(when(module) { DashboardModule.DOORS -> Icons.Default.DirectionsCar; DashboardModule.CLIMATE -> Icons.Default.AcUnit; else -> Icons.Default.Schedule },
                    stringResource(module.title()), Modifier.padding(bottom = 16.dp).size(28.dp), tint = colors.onSurfaceVariant)
                when (module) {
                    DashboardModule.DOORS -> {
                        val valid = vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE && vehicle.doorsAvailable
                        val count = listOf(vehicle.hoodOpen, vehicle.frontLeftDoorOpen, vehicle.frontRightDoorOpen, vehicle.rearLeftDoorOpen, vehicle.rearRightDoorOpen, vehicle.bootOpen).count { it }
                        Text(if (valid) stringResource(R.string.module_doors_open, count) else "—", style = MaterialTheme.typography.headlineSmall)
                    }
                    DashboardModule.CLIMATE -> {
                        val valid = vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE
                        val left = vehicle.leftTemperature?.takeIf { valid && 25 in vehicle.availableCodes }
                        val right = vehicle.rightTemperature?.takeIf { valid && 31 in vehicle.availableCodes }
                        Text(com.cabin.ui.formatClimateTemperature(left, vehicle.fahrenheit), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                        Text(com.cabin.ui.formatClimateTemperature(right, vehicle.fahrenheit), style = MaterialTheme.typography.titleLarge, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    DashboardModule.CLOCK -> Text(android.text.format.DateFormat.getTimeFormat(context).format(Date()), style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun DashboardAndroidWidget(host: AppWidgetHost, widgetId: Int) {
    val context = LocalContext.current
    val info = remember(widgetId) { try { AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId) } catch (_: RuntimeException) { null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        if (info == null || info.minWidth / density > maxWidth.value || info.minHeight / density > maxHeight.value) {
            Text(stringResource(R.string.module_widget_size), Modifier.padding(12.dp))
        } else {
            val width = maxWidth.value.toInt(); val height = maxHeight.value.toInt()
            AndroidView(factory = { ctx -> try { host.createView(ctx, widgetId, info) } catch (_: RuntimeException) { android.appwidget.AppWidgetHostView(ctx) } },
                modifier = Modifier.fillMaxSize(), update = { view -> try { view.updateAppWidgetSize(null, width, height, width, height) } catch (_: RuntimeException) { } })
        }
    }
}
