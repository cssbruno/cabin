package com.cabin.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cabin.R
import com.cabin.joying.JoyingCarPlayService
import com.cabin.ui.JoyingPhonePicker

/** Configuration stays in Cabin; merely opening settings must not start projection. */
@Composable
internal fun NativeCarPlaySettings() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<JoyingCarPlayService?>(null) }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var showPhones by remember { mutableStateOf(false) }
    fun retry() {
        runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, JoyingCarPlayService::class.java).setAction(JoyingCarPlayService.RETRY))
        }.onFailure { status = it.message.orEmpty() }
    }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? JoyingCarPlayService.Connection)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null; running = false }
        }
        val bound = runCatching {
            context.bindService(Intent(context, JoyingCarPlayService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { status = it.message.orEmpty(); false }
        onDispose { if (bound) context.unbindService(connection) }
    }
    LaunchedEffect(service) { service?.state?.collect { status = it.status; running = it.running } }
    if (showPhones) JoyingPhonePicker(
        onConnect = { address -> service?.connectPhone(address); showPhones = false },
        onDismiss = { showPhones = false },
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("native-carplay-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.carplay_backend_native), style = MaterialTheme.typography.headlineSmall)
        if (status.isNotEmpty()) Text(status)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { retry() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_retry))
            }
            OutlinedButton(enabled = service != null, onClick = { service?.stopProjection() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_disconnect))
            }
            OutlinedButton(enabled = service != null && running, onClick = { service?.enableWireless() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_wireless))
            }
            OutlinedButton(enabled = service != null && running, onClick = { showPhones = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_phone))
            }
        }
    }
}
