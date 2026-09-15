package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cabin.joying.JoyingCarPlayService
import android.content.*
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.cabin.joying.JoyingServiceHandoff
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.cabin.platform.JoyingCarPlay

@Composable
internal fun rememberJoyingCarPlayAvailable(): Boolean {
    val context = LocalContext.current
    var available by remember(context) { mutableStateOf(JoyingCarPlay.isAvailable(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { available = JoyingCarPlay.isAvailable(context) }
    return available
}

@Composable
internal fun JoyingCarPlayScreen(onOpenLauncher: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sessionHandle by remember { mutableStateOf<JoyingCarPlayService?>(null) }
    var showPhones by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Connecting to Joying’s native CarPlay service…") }
    var ratio by remember { mutableFloatStateOf(1280f / 720f) }
    fun retry() {
        runCatching { ContextCompat.startForegroundService(context,
            Intent(context, JoyingCarPlayService::class.java).setAction(JoyingCarPlayService.RETRY))
        }.onFailure { status = it.message ?: "Cannot restart CarPlay service" }
    }
    DisposableEffect(context) {
        val intent = Intent(context, JoyingCarPlayService::class.java)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                sessionHandle = (binder as? JoyingCarPlayService.Connection)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) { sessionHandle = null }
        }
        var bound = false
        try {
            ContextCompat.startForegroundService(context, intent)
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) { status = e.message ?: "Cannot start CarPlay service" }
        onDispose { if (bound) context.unbindService(connection) }
    }
    LaunchedEffect(sessionHandle) {
        sessionHandle?.state?.collect { status = it.status; ratio = it.ratio }
    }
    LifecycleResumeEffect(Unit) {
        active = true
        onPauseOrDispose { active = false }
    }
    if (showPhones) JoyingPhonePicker(
        onConnect = { address -> sessionHandle?.connectPhone(address); showPhones = false },
        onDismiss = { showPhones = false },
    )
    Column(Modifier.fillMaxSize().testTag("joying-embedded-carplay")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.joying_title), Modifier.weight(1f))
            TextButton(onClick = { retry() }) { Text(stringResource(R.string.joying_retry)) }
            TextButton(onClick = { com.cabin.reports.LiveDebugMenu.show(context) }) { Text("Live debug") }
            if (onOpenLauncher != null) TextButton(onClick = onOpenLauncher) { Text(stringResource(R.string.joying_home)) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(enabled = !preparing, onClick = {
                sessionHandle?.disconnect()
                try { context.startActivity(JoyingServiceHandoff.stockSettingsIntent()) }
                catch (_: android.content.ActivityNotFoundException) { status = context.getString(R.string.joying_stock_settings_missing) }
            }) { Text(stringResource(R.string.joying_stock_settings)) }
            TextButton(enabled = !preparing, onClick = {
                sessionHandle?.disconnect()
                preparing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { JoyingServiceHandoff.prepare(context) } }
                    preparing = false
                    result.onSuccess { retry() }.onFailure { status = it.cause?.message ?: it.message ?: "Handoff failed" }
                }
            }) { Text(stringResource(R.string.joying_takeover)) }
            TextButton(enabled = sessionHandle != null && !preparing, onClick = { sessionHandle?.enableWireless() }) { Text(stringResource(R.string.joying_wireless)) }
            TextButton(enabled = sessionHandle != null && !preparing, onClick = {
                showPhones = true
            }) { Text(stringResource(R.string.joying_phone)) }
            TextButton(enabled = sessionHandle != null && !preparing, onClick = { sessionHandle?.siri() }) { Text(stringResource(R.string.joying_siri)) }
            TextButton(enabled = !preparing, onClick = {
                sessionHandle?.disconnect()
                preparing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { JoyingServiceHandoff.restore(context) } }
                    preparing = false
                    result.onSuccess { sessionHandle?.stopProjection() }
                    status = result.fold({ "Stock Car Link service restored" }, { it.cause?.message ?: it.message ?: "Restore failed" })
                }
            }) { Text(stringResource(R.string.joying_restore)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val viewport = if (maxWidth / maxHeight > ratio) Modifier.fillMaxHeight().aspectRatio(ratio)
                else Modifier.fillMaxWidth().aspectRatio(ratio)
            if (active && sessionHandle != null) key(sessionHandle) {
                val service = sessionHandle!!
                AndroidView(modifier = viewport, factory = { viewContext ->
                    object : SurfaceView(viewContext), SurfaceHolder.Callback {
                        private var attached: android.view.Surface? = null
                        init {
                            holder.addCallback(this)
                            setOnTouchListener { _, event -> service.touch(event, width, height) }
                        }
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            attached = holder.surface
                            service.attach(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            attached?.let(service::detach)
                            attached = null
                        }
                    }
                })
            }
            if (status.isNotEmpty()) Surface(Modifier.padding(24.dp), tonalElevation = 6.dp) {
                Text(status, Modifier.padding(16.dp))
            }
        }
    }
}
