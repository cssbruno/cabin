package com.cabin.launcher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.background.CabinProjectionService
import com.cabin.navigation.NavigationStateManager
import com.cabin.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

/** Native HOME surface over the same service-owned projection manager. */
@Composable
fun CabinLauncher(
    manager: CabinManager,
    vehicle: TeyesClimateState,
    moving: Boolean,
    onProjection: () -> Unit,
    onVehicle: () -> Unit,
    onClimate: (() -> Unit)?,
    onSettings: () -> Unit,
    onParkedAction: (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    page: Int? = null,
    onPageChange: ((Int) -> Unit)? = null,
    onProjectionPlacement: (ProjectionModulePlacement?) -> Unit = {},
    climateActions: ClimateWidgetActions = ClimateWidgetActions(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val health by manager.dashboardState.collectAsStateWithLifecycle()
    val navigation by NavigationStateManager.state.collectAsStateWithLifecycle()
    val profile by TeyesFeaturePreferences.get(context).profile.collectAsStateWithLifecycle()
    val units by MeasurementPreferences.get(context).unit.collectAsStateWithLifecycle()
    val preferences = remember(profile.slot, vehicle.profileId, vehicle.vehicleDataLayout) { LauncherPreferences(context, profile.slot, vehicle.profileId, vehicle.vehicleDataLayout) }
    val layout by preferences.state.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf<List<TeyesLaunchableApp>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var localPage by rememberSaveable { mutableIntStateOf(1) }
    val currentPage = page ?: localPage
    val drawer = currentPage == 2
    val editing = currentPage == 3
    fun goPage(target: Int) {
        if (target in 2..3 && moving) return
        val action = {
            if (onPageChange != null) onPageChange(target)
            else if (target == 0) onProjection()
            else localPage = target
        }
        if (target in 2..3) onParkedAction(action) else action()
    }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<TeyesLaunchableApp?>(null) }
    var failure by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var clockText by remember { mutableStateOf("") }
    val streaming = health.connection == CabinManager.State.STREAMING
    val active = streaming || health.connection == CabinManager.State.DEVICE_CONNECTED
    LaunchedEffect(lifecycle, refresh, profile.slot) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            apps = withContext(Dispatchers.IO) { TeyesAppShortcuts.available(context) }
            preferences.refresh()
            while (true) {
                now = SystemClock.elapsedRealtime()
                clockText = android.text.format.DateFormat.getTimeFormat(context).format(Date())
                delay(1000)
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { selected = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(moving) { if (moving) { if (currentPage in 2..3) goPage(1); selected = null; search = "" } }
    BackHandler { if (selected != null) selected = null else { goPage(1); search = "" } }
    fun launch(app: TeyesLaunchableApp) {
        if (moving) return
        if (!TeyesAppShortcuts.launch(context, app.component)) { failure = true; refresh++ }
    }
    fun defaultHome() {
        try { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: RuntimeException) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: RuntimeException) { failure = true }
        }
    }
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout).background(if (currentPage == 1) androidx.compose.ui.graphics.Color.Transparent else colors.background)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth().then(if (currentPage == 1) Modifier else Modifier.launcherPageSwipe(currentPage, ::goPage)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentPage != 1) Row(Modifier.fillMaxWidth().background(colors.background).heightIn(min = 56.dp).padding(end = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    FilledTonalIconButton(onClimate ?: onVehicle, modifier = Modifier.size(56.dp)) {
                        Icon(if (onClimate != null) Icons.Default.AcUnit else Icons.Default.DirectionsCar,
                            stringResource(if (onClimate != null) R.string.launcher_climate else R.string.launcher_vehicle), Modifier.size(28.dp))
                    }
                }
                if (failure) TextButton({ failure = false }) { Text(stringResource(R.string.launcher_action_failed)) }
                if (drawer && !moving) {
                    val visibleApps = remember(apps, search, layout.favorites) {
                        filterLauncherApps(apps, search).sortedBy { app -> layout.favorites.indexOf(app.component).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                    }
                    PagedLauncherItems(visibleApps, Modifier.weight(1f), header = {
                        OutlinedTextField(search, { search = it.take(100) }, label = { Text(stringResource(R.string.launcher_search)) },
                            modifier = Modifier.weight(1f), singleLine = true)
                    }) { app -> LauncherAppTile(app, onOpen = { launch(app) }, onManage = { selected = app }) }
                    if (visibleApps.isEmpty()) Text(stringResource(R.string.launcher_no_apps))
                } else if (currentPage == 4) {
                    Box(Modifier.weight(1f)) { VehicleWidgetsPage(preferences, vehicle, moving, onParkedAction) }
                } else if (editing && !moving) {
                    val actions = listOf(
                        R.string.launcher_vehicle to onVehicle,
                        R.string.launcher_settings to onSettings,
                        R.string.launcher_choose_home to { defaultHome() },
                        (if (active) R.string.launcher_disconnect else R.string.launcher_connect) to {
                            if (active) manager.disconnectPhone() else try { CabinProjectionService.startPhoneConnection(context) }
                            catch (_: RuntimeException) { failure = true }
                        },
                        R.string.launcher_favorites to { goPage(2) },
                        R.string.launcher_widgets to { goPage(4) },
                    )
                    PagedLauncherItems(actions, Modifier.weight(1f), header = {
                        Text(stringResource(R.string.launcher_customize), Modifier.weight(1f))
                    }) { (label, action) ->
                        FilledTonalButton({ action() }, modifier = Modifier.fillMaxSize().heightIn(min = 56.dp)) { Text(stringResource(label)) }
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        ModularDashboard(manager, vehicle, moving, preferences, onParkedAction, onProjectionPlacement, clockText, onClimate ?: onVehicle, onSettings, climateActions)
                    }

                }
            }
        }
        if (page == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            LauncherPageSwitcher(currentPage, moving, ::goPage, Modifier.padding(4.dp))
        }
        selected?.takeUnless { moving }?.let { app ->
            AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.label) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.component in layout.favorites) {
                        TextButton({ preferences.unpin(app.component); selected = null }) { Text(stringResource(R.string.launcher_unpin)) }
                        TextButton({ preferences.move(app.component, -1) }, enabled = layout.favorites.indexOf(app.component) > 0) { Text(stringResource(R.string.launcher_move_left)) }
                        TextButton({ preferences.move(app.component, 1) }, enabled = layout.favorites.indexOf(app.component) < layout.favorites.lastIndex) { Text(stringResource(R.string.launcher_move_right)) }
                    } else TextButton({ preferences.pin(app.component); selected = null }, enabled = layout.favorites.size < LauncherPreferences.MAX_FAVORITES) {
                        Text(stringResource(R.string.launcher_pin))
                    }
                    TextButton({
                        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(android.net.Uri.fromParts("package", ComponentName.unflattenFromString(app.component)?.packageName, null))) }
                        catch (_: RuntimeException) { failure = true }
                        selected = null
                    }) { Text(stringResource(R.string.launcher_app_info)) }
                }
            }, confirmButton = { TextButton({ selected = null }) { Text(stringResource(R.string.launcher_close)) } })
        }
    }
    }
}

@Composable
private fun LauncherCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
private fun LauncherTransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
        Icon(icon, stringResource(label), Modifier.size(36.dp))
    }
}

@Composable
private fun LauncherAppTile(app: TeyesLaunchableApp, enabled: Boolean = true, onOpen: () -> Unit, onManage: (() -> Unit)?) {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.component) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getActivityIcon(ComponentName.unflattenFromString(app.component)!!).toBitmap(64, 64) }
            catch (_: RuntimeException) { null } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) } ?: Icon(Icons.Default.Apps, null, Modifier.size(36.dp))
            Text(app.label, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            onManage?.let { action -> IconButton(action, enabled = enabled) { Icon(Icons.Default.MoreVert, stringResource(R.string.launcher_manage)) } }
        }
    }

}

internal fun filterLauncherApps(apps: List<TeyesLaunchableApp>, query: String): List<TeyesLaunchableApp> = apps.filter {
    it.label.contains(query.trim(), ignoreCase = true) || it.component.substringBefore('/').contains(query.trim(), ignoreCase = true)
}

internal fun launcherGuidanceFresh(streaming: Boolean, active: Boolean, updated: Long?, now: Long): Boolean =
    streaming && active && updated != null && updated >= 0 && now >= updated && now - updated <= 30_000
