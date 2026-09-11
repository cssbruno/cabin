package com.cabin.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
    DashboardModule.CLIMATE -> R.string.widget_ac
    DashboardModule.FAN -> R.string.widget_fan
    DashboardModule.REAR_CLIMATE -> R.string.widget_rear_climate
    DashboardModule.SEATS -> R.string.widget_seats
    DashboardModule.DEFROST -> R.string.widget_defrost
    DashboardModule.CLOCK -> R.string.module_clock
    DashboardModule.WIDGET -> R.string.launcher_widgets
    DashboardModule.DRIVER_TEMPERATURE -> R.string.widget_driver_temperature
    DashboardModule.PASSENGER_TEMPERATURE -> R.string.widget_passenger_temperature
    DashboardModule.AIRFLOW -> R.string.widget_airflow
    DashboardModule.RECIRCULATION -> R.string.widget_recirculation
    DashboardModule.HOOD -> R.string.widget_hood
    DashboardModule.TRUNK -> R.string.widget_trunk
    DashboardModule.CAN_CONNECTION -> R.string.widget_can_connection
    DashboardModule.DATE -> R.string.widget_date
    DashboardModule.PHONE_CONNECTION -> R.string.widget_phone_connection
    DashboardModule.ASSISTANT -> R.string.widget_assistant
    DashboardModule.ROUTE_OVERVIEW -> R.string.widget_route_overview
    DashboardModule.AUDIO_CONTROL -> R.string.teyes_media_volume
    DashboardModule.PINNED_APPS -> R.string.launcher_favorites
    DashboardModule.TRIP_CONSUMPTION -> R.string.vehicle_trip_consumption
    DashboardModule.HYBRID_BATTERY -> R.string.vehicle_hybrid_battery
    DashboardModule.VEHICLE_LIGHTING -> R.string.vehicle_lighting
    DashboardModule.VEHICLE_ALERTS -> R.string.alert_title
    DashboardModule.TIRE_HISTORY -> R.string.history_title
    DashboardModule.TRIP_HISTORY -> R.string.tools_trips
    DashboardModule.SEAT_PRESET -> R.string.energy_seat_preset
    DashboardModule.ENERGY_FLOW -> R.string.energy_flow
    DashboardModule.CHARGING_SETTINGS -> R.string.energy_settings
    DashboardModule.AMBIENT_LIGHTING -> R.string.energy_palette
    DashboardModule.VEHICLE_OVERVIEW -> R.string.launcher_vehicle
    DashboardModule.TIRE_PRESSURE -> R.string.vehicle_tire_pressure
    DashboardModule.FACTORY_AMPLIFIER -> R.string.vehicle_factory_amplifier
    DashboardModule.CAMERA_MODE -> R.string.vehicle_camera_mode
    DashboardModule.MIRROR_SETTINGS -> R.string.vehicle_mirror_settings
    DashboardModule.PARKING_SETTINGS -> R.string.vehicle_parking_settings
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
    val storedLayout by prefs.state.collectAsStateWithLifecycle()
    var glance by rememberSaveable(driver.slot, vehicle.profileId) { mutableStateOf(false) }
    var priorPage by rememberSaveable { mutableIntStateOf(0) }
    var priorCompact by rememberSaveable { mutableIntStateOf(0) }
    var quick by remember { mutableStateOf(false) }
    val layout = if (glance) DashboardLayout(1, listOf(
        DashboardTile(10001, DashboardModule.SPEED, 0, 0, 0, 4, 4),
        DashboardTile(10002, DashboardModule.NAVIGATION, 0, 4, 0, 4, 4),
    )) else storedLayout
    val history by prefs.history.collectAsStateWithLifecycle()
    val launcher by launcherPreferences.state.collectAsStateWithLifecycle()
    var page by rememberSaveable(driver.slot, vehicle.profileId, vehicle.vehicleDataLayout) { mutableIntStateOf(0) }
    var compactIndex by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var adding by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var draggedId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
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
    LaunchedEffect(moving) { if (moving) { draggedId = null; dragOffset = Offset.Zero; editing = false; selected = null; adding = false; presets = false } }
    LaunchedEffect(layout.pages) { page = page.coerceIn(0, layout.pages - 1) }
    val availableModules = dashboardPickerModules.filter { module ->
        when (module) {
            DashboardModule.SEAT_PRESET -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.SEAT_MEMORY }
            DashboardModule.ENERGY_FLOW -> vehicle.syuVehicle.energy != null
            DashboardModule.CHARGING_SETTINGS -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.CHARGING }
            DashboardModule.AMBIENT_LIGHTING -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.AMBIENT }
            DashboardModule.CAMERA_MODE -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.CAMERA }
            DashboardModule.MIRROR_SETTINGS -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.MIRRORS }
            DashboardModule.PARKING_SETTINGS -> vehicle.syuVehicle.factoryCapabilities.any { it.group == SyuFactoryGroup.PARKING }
            DashboardModule.TIRE_HISTORY -> vehicle.profileId in SyuVehicleProtocol.tireProfiles
            DashboardModule.TIRE_PRESSURE -> vehicle.syuVehicle.tires.isNotEmpty()
            DashboardModule.FACTORY_AMPLIFIER -> vehicle.syuVehicle.amplifier.isNotEmpty()
            DashboardModule.VEHICLE_LIGHTING -> vehicle.syuVehicle.lighting.isNotEmpty()
            DashboardModule.TRIP_CONSUMPTION -> vehicle.syuVehicle.tripSupported
            DashboardModule.HYBRID_BATTERY -> vehicle.syuVehicle.hybridSupported
            else -> true
        }
    }.toSet()
    fun result(success: Boolean) { noRoom = !success }
    if (quick) QuickControls(glance, onGlance = {
        if (!glance) { priorPage = page; priorCompact = compactIndex; page = 0; compactIndex = 0; editing = false; selected = null }
        else { page = priorPage.coerceIn(0, storedLayout.pages - 1); compactIndex = priorCompact }
        glance = !glance
    }, onClimate = { onVehicle() }, onCamera = storedLayout.tiles.firstOrNull { it.module == DashboardModule.CAMERA_MODE }?.let { camera ->
        { glance = false; page = camera.page; compactIndex = storedLayout.tiles.filter { it.page == camera.page }.indexOf(camera) }
    }, onDismiss = { quick = false })
    if (presets && !moving) AlertDialog(onDismissRequest = { presets = false },
        title = { Text(stringResource(R.string.layout_presets)) },
        text = { Column {
            DashboardPreset.entries.forEach { preset ->
                TextButton(onClick = {
                    presets = false
                    result(prefs.addPreset(preset, availableModules))
                    page = prefs.state.value.pages - 1
                    compactIndex = 0
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(when (preset) {
                        DashboardPreset.COMMUTE -> R.string.layout_commute
                        DashboardPreset.NAVIGATION -> R.string.layout_navigation
                        DashboardPreset.PARKING -> R.string.layout_parking
                        DashboardPreset.GLANCE -> R.string.layout_glance
                    }))
                }
            }
        } }, confirmButton = {})
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val grid = DashboardGrid(portrait = maxHeight > maxWidth)
    val showVehicle = maxWidth >= 680.dp
    val compact = !editing && (maxHeight < 240.dp || maxWidth < (if (grid.portrait) 360.dp else 600.dp))
    val pageTiles = layout.tiles.filter { it.page == page }.map(grid::transform)
    val subpage = compactIndex.coerceIn(0, (pageTiles.size - 1).coerceAtLeast(0))
    val displayed = if (compact) pageTiles.drop(subpage).take(1) else pageTiles
    val projection = displayed.firstOrNull { it.module == DashboardModule.PROJECTION }
    LaunchedEffect(page, prefs, projection) { if (projection == null) onPlacement(null) }
    val keyRouter = LocalTeyesKeyRouter.current
    val currentPageTiles by rememberUpdatedState(pageTiles)
    val currentLayout by rememberUpdatedState(layout)
    val currentCompact by rememberUpdatedState(compact)
    LaunchedEffect(keyRouter) {
        keyRouter?.pageChanges?.collect { direction ->
            if (currentCompact && direction > 0 && compactIndex < currentPageTiles.size - 1) {
                compactIndex++
            } else if (currentCompact && direction < 0 && compactIndex > 0) {
                compactIndex--
            } else {
                page = (page + direction).mod(currentLayout.pages)
                compactIndex = if (currentCompact && direction < 0)
                    (currentLayout.tiles.count { it.page == page } - 1).coerceAtLeast(0) else 0
            }
        }
    }
    val displayPage = if (compact) (0 until page).sumOf { p -> maxOf(1, layout.tiles.count { it.page == p }) } + subpage + 1 else page + 1
    val displayPages = if (compact) (0 until layout.pages).sumOf { p -> maxOf(1, layout.tiles.count { it.page == p }) } else layout.pages
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 60.dp).background(MaterialTheme.colorScheme.background)
            .launcherPageSwipe(page, { target -> page = target; compactIndex = 0 }), verticalAlignment = Alignment.CenterVertically) {
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
            if (!editing) IconButton({ quick = true }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Bolt, stringResource(R.string.tools_quick))
            }
            if (showVehicle && !editing) IconButton(onVehicle, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.DirectionsCar, stringResource(R.string.launcher_vehicle))
            }
            IconButton({ onParkedAction { editing = !editing; selected = null } }, enabled = !moving && !glance, modifier = Modifier.size(56.dp)) {
                Icon(if (editing) Icons.Default.Check else Icons.Default.Edit,
                    stringResource(if (editing) R.string.launcher_done else R.string.module_edit),
                    tint = if (editing) MaterialTheme.colorScheme.primary else LocalContentColor.current)
            }
            if (!editing) IconButton({ onParkedAction { onSettings() } }, enabled = !moving,
                modifier = Modifier.size(56.dp).testTag("dashboard-settings")) {
                Icon(Icons.Default.Settings, stringResource(R.string.launcher_settings))
            }
            if (editing && !moving) Box {
                IconButton({
                    if (prefs.addPage()) {
                        page = prefs.state.value.pages - 1
                        compactIndex = 0
                    }
                }, enabled = layout.pages < 6, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.NoteAdd, stringResource(R.string.module_add_page))
                }
            }
            if (editing && !moving) Box {
                IconButton({ adding = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Add, stringResource(R.string.module_add)) }
                DropdownMenu(adding, { adding = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_undo)) }, enabled = history.canUndo,
                        leadingIcon = { Icon(Icons.Default.Undo, null) }, onClick = { adding = false; prefs.undo() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_redo)) }, enabled = history.canRedo,
                        leadingIcon = { Icon(Icons.Default.Redo, null) }, onClick = { adding = false; prefs.redo() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.layout_presets)) }, enabled = layout.pages < 6,
                        leadingIcon = { Icon(Icons.Default.Dashboard, null) }, onClick = { adding = false; presets = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.module_add_page)) }, enabled = layout.pages < 6,
                        leadingIcon = { Icon(Icons.Default.NoteAdd, null) }, onClick = {
                            adding = false
                            if (prefs.addPage()) {
                                page = prefs.state.value.pages - 1
                                compactIndex = 0
                            }
                        })
                    HorizontalDivider()
                    availableModules.forEach { module ->
                        DropdownMenuItem(text = { Text(stringResource(module.title())) },
                            enabled = module != DashboardModule.PROJECTION || layout.tiles.none { it.module == module },
                            onClick = { adding = false; result(prefs.add(module, page)) })
                    }
                    launcher.widgets.forEach { id ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.module_android_widget, id)) },
                            enabled = layout.tiles.none { it.module == DashboardModule.WIDGET && it.widgetId == id },
                            onClick = { adding = false; result(prefs.add(DashboardModule.WIDGET, page, id)) })
                    }
                }
            }
        }
        if (noRoom) TextButton({ noRoom = false }) { Text(stringResource(R.string.module_no_room)) }
        Box(Modifier.fillMaxWidth().height(24.dp)) {
            if (editing) Text(stringResource(if (noRoom) R.string.module_no_room else R.string.layout_drag_hint),
                modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("dashboard-grid")) {
            // Portrait uses the entire available area; square cells must not letterbox it.
            val cellSize = minOf(maxWidth / grid.columns, maxHeight / grid.rows)
            val cellWidth = if (grid.portrait) maxWidth / grid.columns else cellSize
            val cellHeight = if (grid.portrait) maxHeight / grid.rows else cellSize
            val gridOffsetX = (maxWidth - cellWidth * grid.columns) / 2
            val gridOffsetY = (maxHeight - cellHeight * grid.rows) / 2
            val gridDensity = androidx.compose.ui.platform.LocalDensity.current
            val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            if (editing && !compact) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                for (x in 0..grid.columns) {
                    val px = gridOffsetX.toPx() + cellWidth.toPx() * x
                    drawLine(gridColor, Offset(px, gridOffsetY.toPx()), Offset(px, gridOffsetY.toPx() + cellHeight.toPx() * grid.rows))
                }
                for (y in 0..grid.rows) {
                    val py = gridOffsetY.toPx() + cellHeight.toPx() * y
                    drawLine(gridColor, Offset(gridOffsetX.toPx(), py), Offset(gridOffsetX.toPx() + cellWidth.toPx() * grid.columns, py))
                }
            }
            if (editing && !compact) pageTiles.firstOrNull { it.id == draggedId }?.let { dragged ->
                val x = (dragged.x + (dragOffset.x / with(gridDensity) { cellWidth.toPx() }).roundToInt()).coerceIn(0, grid.columns - dragged.width)
                val y = (dragged.y + (dragOffset.y / with(gridDensity) { cellHeight.toPx() }).roundToInt()).coerceIn(0, grid.rows - dragged.height)
                val neighbors = pageTiles.filter { it.id != dragged.id }
                val vertical = listOf(x, x + dragged.width).filter { edge -> neighbors.any { edge == it.x || edge == it.x + it.width } }
                val horizontal = listOf(y, y + dragged.height).filter { edge -> neighbors.any { edge == it.y || edge == it.y + it.height } }
                val guideColor = MaterialTheme.colorScheme.primary
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().zIndex(2f)) {
                    vertical.forEach { edge -> val px = gridOffsetX.toPx() + cellWidth.toPx() * edge
                        drawLine(guideColor, Offset(px, gridOffsetY.toPx()), Offset(px, gridOffsetY.toPx() + cellHeight.toPx() * grid.rows), 2.dp.toPx()) }
                    horizontal.forEach { edge -> val py = gridOffsetY.toPx() + cellHeight.toPx() * edge
                        drawLine(guideColor, Offset(gridOffsetX.toPx(), py), Offset(gridOffsetX.toPx() + cellWidth.toPx() * grid.columns, py), 2.dp.toPx()) }
                }
            }
            displayed.forEach { tile ->
                key(tile.id, grid.portrait, compact) {
                    val currentTile by rememberUpdatedState(tile)
                    val editingNow by rememberUpdatedState(editing)
                    var dragStart by remember { mutableStateOf(tile) }
                    var releasedDragOffset by remember { mutableStateOf<Offset?>(null) }
                    var pinchPreview by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                    fun resizeTile(width: Int, height: Int) {
                        onParkedAction { result(prefs.resizeInPlace(tile.id, grid.storedWidth(width, height), grid.storedHeight(width, height), allowProjection = true)) }
                    }
                    val pinchModifier = if (editing && !compact && !moving) Modifier.widgetPinchResize(tile, columns = grid.columns, rows = grid.rows,
                        onPreview = { pinchPreview = it }, onResize = { w, h -> resizeTile(w, h) }) else Modifier
                    fun startDrag() { if (glance) return; onParkedAction { editing = true; releasedDragOffset = null; dragStart = currentTile; draggedId = tile.id; dragOffset = Offset.Zero } }
                    fun finishDrag() {
                        if (draggedId == tile.id) {
                            releasedDragOffset = dragOffset
                            val x = (dragStart.x + (dragOffset.x / with(gridDensity) { cellWidth.toPx() }).roundToInt()).coerceIn(0, grid.columns - dragStart.width)
                            val y = (dragStart.y + (dragOffset.y / with(gridDensity) { cellHeight.toPx() }).roundToInt()).coerceIn(0, grid.rows - dragStart.height)
                            onParkedAction { result(prefs.drop(tile.id, grid.storedX(x, y), grid.storedY(x, y))) }
                        }
                        draggedId = null; dragOffset = Offset.Zero
                    }
                    fun cancelDrag() { releasedDragOffset = dragOffset; draggedId = null; dragOffset = Offset.Zero }
                    // A dashboard grid cell is deliberately large enough for the car
                    // UI. A hosted Android 1 × 1 widget should instead match the
                    // compact home-screen footprint, not consume that whole cell.
                    val compactWidgetEdge = minOf(cellWidth, cellHeight, 112.dp)
                    val isCompactWidget = !compact && tile.module == DashboardModule.WIDGET &&
                        tile.width == 1 && tile.height == 1
                    val widgetInsetX = if (isCompactWidget) (cellWidth - compactWidgetEdge) / 2 else 0.dp
                    val widgetInsetY = if (isCompactWidget) (cellHeight - compactWidgetEdge) / 2 else 0.dp
                    val tileX = if (compact) 0.dp else gridOffsetX + cellWidth * tile.x + widgetInsetX
                    val tileY = if (compact) 0.dp else gridOffsetY + cellHeight * tile.y + widgetInsetY
                    val tileWidth = when {
                        compact -> maxWidth
                        isCompactWidget -> compactWidgetEdge
                        else -> cellWidth * tile.width
                    }
                    val tileHeight = when {
                        compact -> maxHeight
                        isCompactWidget -> compactWidgetEdge
                        else -> cellHeight * tile.height
                    }
                    val targetPosition = with(gridDensity) { Offset(tileX.toPx(), tileY.toPx()) }
                    val releasePosition = releasedDragOffset?.let { delta ->
                        with(gridDensity) {
                            Offset((gridOffsetX + cellWidth * dragStart.x + widgetInsetX).toPx(),
                                (gridOffsetY + cellHeight * dragStart.y + widgetInsetY).toPx()) + delta
                        }
                    }
                    val position = rememberDashboardTilePosition(
                        target = targetPosition,
                        dragDelta = dragOffset.takeIf { draggedId == tile.id },
                        releasePosition = releasePosition,
                        onSettled = { releasedDragOffset = null },
                    )
                    val tileModifier = Modifier.offset {
                        IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt())
                    }
                        .zIndex(if (draggedId == tile.id) 1f else 0f)
                        .size(tileWidth.minus(4.dp).coerceAtLeast(1.dp), tileHeight.minus(4.dp).coerceAtLeast(1.dp))
                        .clipToBounds().testTag("module-${tile.module.name}-${tile.id}")
                    if (tile.module == DashboardModule.PROJECTION) {
                        Box(tileModifier.onGloballyPositioned { onPlacement(ProjectionModulePlacement(it.boundsInRoot(), editing)) }) {
                            // Normal use has no gesture interceptor: touches reach the live phone surface.
                            if (editing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
                                .border(2.dp, MaterialTheme.colorScheme.primary).then(pinchModifier)
                                .pointerInput(tile.id, prefs, cellWidth, cellHeight) {
                                    detectDragGestures(onDragStart = { startDrag() }, onDragEnd = { finishDrag() },
                                        onDragCancel = { cancelDrag() }) { change, amount ->
                                        if (draggedId == tile.id) { change.consume(); dragOffset += amount }
                                    }
                                }, contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxSize().clickable { selected = tile.id }, contentAlignment = Alignment.TopEnd) {
                                    FilledTonalButton(onClick = { selected = tile.id }, modifier = Modifier.heightIn(min = 56.dp)) {
                                        Icon(Icons.Default.Edit, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.module_resize_projection))
                                    }
                                }
                                pinchPreview?.let { Text("${it.first} × ${it.second}", Modifier.align(Alignment.TopCenter).padding(12.dp), color = Color.White) }
                                if (!compact && !moving) WidgetResizeHandle(tile, cellWidth, cellHeight, columns = grid.columns, rows = grid.rows,
                                    onResize = { w, h -> resizeTile(w, h) }, modifier = Modifier.align(Alignment.BottomEnd))
                            }
                        }
                    } else {
                        Card(tileModifier.then(if (!moving) Modifier.pointerInput(tile.id, prefs, cellWidth, cellHeight, compact) {
                            if (compact) detectTapGestures(onLongPress = { if (!glance) onParkedAction { editing = true } })
                            else {
                                var ownsDrag = false
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { ownsDrag = !editingNow; if (ownsDrag) startDrag() },
                                    onDragEnd = { if (ownsDrag) finishDrag(); ownsDrag = false },
                                    onDragCancel = { if (ownsDrag) cancelDrag(); ownsDrag = false },
                                ) { change, amount ->
                                    if (ownsDrag && draggedId == tile.id) { change.consume(); dragOffset += amount }
                                }
                            }
                        } else Modifier), shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
                            Box(Modifier.fillMaxSize()) {
                                if (tile.module == DashboardModule.WIDGET) {
                                    if (moving) Text(stringResource(R.string.module_widget_parked), Modifier.padding(16.dp))
                                    else DashboardAndroidWidget(host, tile.widgetId)
                                } else DashboardModuleContent(tile.module, manager, vehicle, onVehicle, climateActions, launcherPreferences, moving, onParkedAction)
                                if (editing) {
                                    Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary).then(pinchModifier)
                                        .clickable { selected = tile.id }
                                    .then(if (!compact && !moving) Modifier.pointerInput(tile.id, prefs, cellWidth, cellHeight) {
                                            detectDragGestures(onDragStart = { startDrag() }, onDragEnd = { finishDrag() }, onDragCancel = { cancelDrag() }) { change, amount ->
                                                if (draggedId == tile.id) { change.consume(); dragOffset += amount }
                                            }
                                        } else Modifier))
                                    FilledTonalIconButton(onClick = { selected = tile.id },
                                        modifier = Modifier.align(Alignment.TopEnd).size(56.dp)) {
                                        Icon(Icons.Default.Edit, stringResource(R.string.module_size))
                                    }
                                    pinchPreview?.let { Text("${it.first} × ${it.second}", Modifier.align(Alignment.TopCenter).padding(12.dp), color = MaterialTheme.colorScheme.primary) }
                                    Icon(Icons.Default.OpenWith, null, Modifier.align(Alignment.TopStart).padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                    if (!compact && !moving) WidgetResizeHandle(tile, cellWidth, cellHeight, columns = grid.columns, rows = grid.rows,
                                        onResize = { width, height -> resizeTile(width, height) },
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
    selected?.takeUnless { moving }?.let { id -> layout.tiles.firstOrNull { it.id == id }?.let(grid::transform)?.let { tile ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(stringResource(tile.module.title())) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.module_size))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sizes = (1..grid.rows).flatMap { h -> (1..grid.columns).map { w -> w to h } }
                        sizes.forEach { (w, h) -> FilterChip(selected = tile.width == w && tile.height == h, onClick = { result(prefs.resize(id, grid.storedWidth(w, h), grid.storedHeight(w, h))) }, modifier = Modifier.heightIn(min = 56.dp), label = { Text("$w × $h") }) }
                    }
                    OutlinedButton({ result(prefs.movePage(id, page + 1)); if (!noRoom) { page++; selected = null } }, enabled = page < 5) { Text(stringResource(R.string.module_move_next_page)) }
                    OutlinedButton({ result(prefs.movePage(id, page - 1)); if (!noRoom) { page--; selected = null } }, enabled = page > 0) { Text(stringResource(R.string.module_move_previous_page)) }
                    TextButton({ prefs.remove(id); selected = null }) { Text(stringResource(R.string.module_remove)) }
                    if (noRoom) Text(stringResource(R.string.module_no_room))
                }
            }, confirmButton = { TextButton({ selected = null }) { Text(stringResource(R.string.launcher_done)) } })
    } }
    }
}

@Composable
private fun DashboardModuleContent(module: DashboardModule, manager: CabinManager, vehicle: TeyesClimateState, onClimate: () -> Unit = {}, climateActions: ClimateWidgetActions = ClimateWidgetActions(), preferences: LauncherPreferences, moving: Boolean, onParkedAction: (() -> Unit) -> Unit) {
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
        if (module == DashboardModule.ENERGY_FLOW) {
            EnergyFlowWidget(vehicle)
        } else if (module == DashboardModule.VEHICLE_OVERVIEW) {
            InteractiveVehicleWidget(vehicle, onClimate)
        } else if (module == DashboardModule.MEDIA) {
            UniversalMediaWidget(manager, health, moving, onParkedAction)
        } else if (module == DashboardModule.VEHICLE_ALERTS) {
            VehicleAlertsWidget(vehicle)
        } else if (module == DashboardModule.TRIP_HISTORY) {
            TripHistoryWidget(vehicle)
        } else if (module == DashboardModule.TIRE_HISTORY) {
            TireHistoryWidget(vehicle)
        } else if (module == DashboardModule.CLOCK) {
            DashboardClockWidget()
        } else if (module in setOf(DashboardModule.CAMERA_MODE, DashboardModule.MIRROR_SETTINGS, DashboardModule.PARKING_SETTINGS, DashboardModule.CHARGING_SETTINGS, DashboardModule.AMBIENT_LIGHTING, DashboardModule.SEAT_PRESET)) {
            val group = when (module) {
                DashboardModule.SEAT_PRESET -> SyuFactoryGroup.SEAT_MEMORY
                DashboardModule.CHARGING_SETTINGS -> SyuFactoryGroup.CHARGING
                DashboardModule.AMBIENT_LIGHTING -> SyuFactoryGroup.AMBIENT
                DashboardModule.CAMERA_MODE -> SyuFactoryGroup.CAMERA
                DashboardModule.MIRROR_SETTINGS -> SyuFactoryGroup.MIRRORS
                else -> SyuFactoryGroup.PARKING
            }
            SyuFactoryWidget(group, vehicle, moving, climateActions.onFactoryControl, onParkedAction)
        } else if (module == DashboardModule.TIRE_PRESSURE) {
            SyuTireWidget(vehicle)
        } else if (module == DashboardModule.FACTORY_AMPLIFIER) {
            SyuAmplifierWidget(vehicle, moving, climateActions.onFactoryAmplifier, onParkedAction)
        } else if (module == DashboardModule.VEHICLE_LIGHTING) {
            SyuLightingWidget(vehicle, moving, climateActions.onVehicleLighting, onParkedAction)
        } else if (module == DashboardModule.TRIP_CONSUMPTION || module == DashboardModule.HYBRID_BATTERY) {
            SyuVehicleWidget(module, vehicle)
        } else if (gauge != null) {
            val reading = vehicleGaugeReading(gauge, vehicle, units)
            val title = stringResource(module.title())
            val availability = if (reading.available) reading.unit else stringResource(R.string.gauge_unavailable)
            Column(Modifier.fillMaxSize().padding(padding).semantics { contentDescription = title; stateDescription = availability },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(reading.value, fontSize = if (short) 32.sp else 64.sp, fontWeight = FontWeight.Light, maxLines = 1)
                if (reading.available) Text(reading.unit, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (!short && gauge != VehicleGauge.SPEED && gauge != VehicleGauge.RPM) Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
            }
        } else if (module == DashboardModule.ROUTE_OVERVIEW) {
            RouteOverviewWidget(nav, health.connection == CabinManager.State.STREAMING, now)
        } else if (module == DashboardModule.AUDIO_CONTROL) {
            AudioControlWidget()
        } else if (module == DashboardModule.PINNED_APPS) {
            PinnedAppsWidget(preferences, moving, onParkedAction)
        } else if (module in climateDashboardModules) {
            AcWidget(vehicle, climateActions, onClimate)
        } else if (module in additionalDashboardModules) {
            AdditionalDashboardWidget(module, vehicle, health.connection) {
                manager.performProjectionAction(CabinManager.ProjectionAction.VOICE)
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

/** Drag updates are immediate; released and displaced tiles settle in 220 ms without overshoot. */
@Composable
internal fun rememberDashboardTilePosition(
    target: Offset,
    dragDelta: Offset?,
    releasePosition: Offset?,
    onSettled: () -> Unit,
): State<Offset> {
    val animated = remember { Animatable(target, Offset.VectorConverter) }
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(target, dragDelta, releasePosition) {
        if (dragDelta != null) {
            animated.snapTo(target + dragDelta)
        } else {
            releasePosition?.let { animated.snapTo(it) }
            animated.animateTo(target, tween(durationMillis = 220, easing = FastOutSlowInEasing))
            settled()
        }
    }
    return rememberUpdatedState(if (dragDelta != null) target + dragDelta else animated.value)
}
