package com.cabin.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.cabin.ui.settings.settingsFocusRing
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
    val home = rememberDefaultHomeState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val health by manager.dashboardState.collectAsStateWithLifecycle()
    val profile by TeyesFeaturePreferences.get(context).profile.collectAsStateWithLifecycle()
    val profileRevision by TeyesFeaturePreferences.get(context).revision.collectAsStateWithLifecycle()
    val preferences = remember(profile.slot, vehicle.profileId, vehicle.vehicleDataLayout) { LauncherPreferences(context, profile.slot, vehicle.profileId, vehicle.vehicleDataLayout) }
    LaunchedEffect(profileRevision, preferences) { preferences.discardUndoHistory() }
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
    var failure by remember { mutableStateOf(false) }
    var failedApp by rememberSaveable { mutableStateOf<String?>(null) }
    ObserveLauncherPackages(context) { refresh++ }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var clockText by remember { mutableStateOf("") }
    val streaming = health.connection == CabinManager.State.STREAMING
    val active = streaming || health.connection == CabinManager.State.DEVICE_CONNECTED
    LaunchedEffect(lifecycle, refresh, preferences, profileRevision) {
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
    LaunchedEffect(moving) { if (moving && currentPage in 2..3) goPage(1) }
    BackHandler { goPage(1) }
    fun launch(app: TeyesLaunchableApp) {
        if (moving) return
        if (!TeyesAppShortcuts.launch(context, app.component)) { failedApp = app.component; refresh++ }
        else preferences.library.recordLaunch(app.component)
    }
    failedApp?.let { component ->
        val availability = shortcutAvailability(context, component)
        AlertDialog(onDismissRequest = { failedApp = null }, title = { Text(stringResource(R.string.goal_shortcut_unavailable)) },
            text = { Text(stringResource(when (availability) {
                ShortcutAvailability.REMOVED -> R.string.goal_app_removed
                ShortcutAvailability.DISABLED -> R.string.goal_app_disabled
                else -> R.string.goal_app_failed
            })) }, confirmButton = { TextButton({
                try {
                    val intent = if (availability == ShortcutAvailability.REMOVED) android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${component.substringBefore('/')}"))
                        else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", component.substringBefore('/'), null))
                    context.startActivity(intent)
                } catch (_: RuntimeException) { failure = true }
                failedApp = null
            }) { Text(stringResource(if (availability == ShortcutAvailability.REMOVED) R.string.goal_reinstall else R.string.launcher_app_info)) } },
            dismissButton = { TextButton({ failedApp = null }) { Text(stringResource(R.string.launcher_close)) } })
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
                if (home.unavailable) Text(stringResource(R.string.headunit_unavailable))
                if (failure) TextButton({ failure = false }) { Text(stringResource(R.string.launcher_action_failed)) }
                if (drawer && !moving) {
                    key(preferences) {
                        LauncherAppDrawer(apps, preferences, onRefresh = { refresh++ }, onLaunch = ::launch,
                            onFailure = { failure = true }, modifier = Modifier.weight(1f))
                    }
                } else if (currentPage == 4) {
                    Box(Modifier.weight(1f)) { VehicleWidgetsPage(preferences, vehicle, moving, onParkedAction) }
                } else if (editing && !moving) {
                    val actions = listOf(
                        R.string.launcher_vehicle to onVehicle,
                        R.string.launcher_settings to onSettings,
                        (if (home.isDefault) R.string.home_change else R.string.home_set_default) to { home.choose() },
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
internal fun LauncherAppTile(app: TeyesLaunchableApp, enabled: Boolean = true, showPackage: Boolean = false, onOpen: () -> Unit, onManage: (() -> Unit)?, query: String = "") {
    val context = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.component) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getActivityIcon(ComponentName.unflattenFromString(app.component)!!).toBitmap(64, 64) }
            catch (_: RuntimeException) { null } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).settingsFocusRing().clickable(enabled = enabled, onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.size(36.dp)) } ?: Icon(Icons.Default.Apps, null, Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(highlightedLauncherText(app.label, query, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showPackage) Text(app.component.substringBefore('/'), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            onManage?.let { action -> IconButton(action, enabled = enabled, modifier = Modifier.size(56.dp).semantics { stateDescription = app.label }) { Icon(Icons.Default.MoreVert, stringResource(R.string.launcher_manage)) } }
        }
    }

}

internal fun launcherGuidanceFresh(streaming: Boolean, active: Boolean, updated: Long?, now: Long): Boolean =
    streaming && active && updated != null && updated >= 0 && now >= updated && now - updated <= 30_000
