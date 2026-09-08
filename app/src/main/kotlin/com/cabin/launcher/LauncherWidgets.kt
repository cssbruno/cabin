package com.cabin.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cabin.R

/** Uses Android's bind/configure consent flows; never auto-grants widget binding. */
@Composable
fun LauncherWidgets(preferences: LauncherPreferences, canEdit: Boolean) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val manager = remember { AppWidgetManager.getInstance(context) }
    val density = LocalDensity.current.density
    val host = remember { AppWidgetHost(context.applicationContext, HOST_ID) }
    val layout by preferences.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var pending by remember { mutableIntStateOf(preferences.pendingWidget) }
    fun pendingInfo(): AppWidgetProviderInfo? = try { manager.getAppWidgetInfo(pending) } catch (_: RuntimeException) { null }
    fun abandon() {
        if (pending > 0) try { host.deleteAppWidgetId(pending) } catch (_: RuntimeException) { }
        pending = -1
        preferences.pendingWidget = -1
    }
    fun finish() {
        if (pending > 0 && pendingInfo() != null) preferences.addWidget(pending)
        else if (pending > 0) try { host.deleteAppWidgetId(pending) } catch (_: RuntimeException) { }
        pending = -1
        preferences.pendingWidget = -1
    }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish() else abandon()
    }
    fun configureOrFinish() {
        val info = pendingInfo()
        if (info == null) { abandon(); return }
        if (info.configure == null) { finish(); return }
        try {
            configure.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(info.configure)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending))
        } catch (_: RuntimeException) { unavailable = true; abandon() }
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) configureOrFinish() else abandon()
    }
    fun choose(info: AppWidgetProviderInfo) {
        if (!canEdit || pending > 0 || layout.widgets.size >= LauncherPreferences.MAX_WIDGETS) return
        picking = false
        try {
            pending = host.allocateAppWidgetId()
            preferences.pendingWidget = pending
            if (manager.bindAppWidgetIdIfAllowed(pending, info.provider)) configureOrFinish()
            else bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pending)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider))
        } catch (_: RuntimeException) { unavailable = true; abandon() }
    }
    DisposableEffect(lifecycle, host) {
        fun start() { try { host.startListening() } catch (_: RuntimeException) { unavailable = true } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { preferences.refresh(); start() }
            if (event == Lifecycle.Event.ON_STOP) try { host.stopListening() } catch (_: RuntimeException) { }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); try { host.stopListening() } catch (_: RuntimeException) { } }
    }
    var widgetPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(layout.widgets.size) { widgetPage = widgetPage.coerceAtMost((layout.widgets.size - 1).coerceAtLeast(0)) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val availableHeight = (maxHeight.value - if (canEdit) 232 else 140).toInt().coerceAtLeast(40)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.launcher_widgets), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton({ picking = true }, enabled = canEdit && pending < 1 && layout.widgets.size < LauncherPreferences.MAX_WIDGETS,
                modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_add_widget)) }
        }
        if (pending > 0) OutlinedButton({ abandon() }, enabled = canEdit) { Text(stringResource(R.string.launcher_cancel_widget)) }
        if (unavailable) Text(stringResource(R.string.launcher_widget_unavailable))
        if (layout.widgets.size > 1) PageDots(widgetPage, layout.widgets.size, { widgetPage = it })
        layout.widgets.drop(widgetPage).take(1).forEach { id ->
            key(id) {
                val info = try { manager.getAppWidgetInfo(id) } catch (_: RuntimeException) { null }
                Card {
                    val minimumHeight = info?.let { kotlin.math.ceil(it.minHeight / density).toInt().coerceAtLeast(160) } ?: 160
                    val height = maxOf(layout.widgetHeights[id] ?: 200, minimumHeight).coerceAtMost(minOf(600, availableHeight))
                    if (info != null && minimumHeight <= height) BoxWithConstraints(Modifier.fillMaxWidth().height(height.dp)) {
                        val width = maxWidth.value.toInt()
                        AndroidView(
                            factory = { viewContext ->
                                try { host.createView(viewContext, id, info) }
                                catch (_: RuntimeException) {
                                    unavailable = true
                                    android.appwidget.AppWidgetHostView(viewContext)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view ->
                                try { view.updateAppWidgetSize(null, width, height, width, height) }
                                catch (_: RuntimeException) { unavailable = true }
                            },
                        )
                    } else Text(stringResource(R.string.launcher_widget_removed), Modifier.padding(16.dp))
                    if (canEdit) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0) {
                        TextButton({ preferences.resizeWidget(id, (height - 40).coerceAtLeast(minimumHeight.coerceAtMost(600))) }, enabled = height > minimumHeight) {
                            Text(stringResource(R.string.launcher_widget_smaller))
                        }
                        TextButton({ preferences.resizeWidget(id, (height + 40).coerceAtMost(600)) }, enabled = height < 600) {
                            Text(stringResource(R.string.launcher_widget_larger))
                        }
                    }
                    TextButton({
                        try { host.deleteAppWidgetId(id) } catch (_: RuntimeException) { }
                        preferences.removeWidget(id)
                    }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_remove_widget)) }
                    }
                }
            }
        }
        if (picking) AlertDialog(onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.launcher_add_widget)) },
            text = {
                val providers = remember { try { manager.installedProviders.filter { it.provider.packageName != context.packageName }
                    .sortedBy { it.loadLabel(context.packageManager) } } catch (_: RuntimeException) { emptyList() } }
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    if (providers.isEmpty()) Text(stringResource(R.string.launcher_no_widgets))
                    providers.forEach { info ->
                        TextButton({ choose(info) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(info.loadLabel(context.packageManager))
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ picking = false }) { Text(stringResource(R.string.launcher_close)) } })
    }
}
}

internal const val HOST_ID = 0x434152
