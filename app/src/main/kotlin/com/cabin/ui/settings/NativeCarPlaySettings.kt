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
    val nativeSelected = com.cabin.ui.rememberCarPlayBackends().selected == com.cabin.platform.CarPlayBackend.JOYING
    var service by remember { mutableStateOf<JoyingCarPlayService?>(null) }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var configuration by remember { mutableStateOf(com.cabin.carlink.CarlinkSettings.read(context)) }
    var microphone by remember { mutableStateOf(com.cabin.carlink.CarlinkMicrophone.read()) }
    var microphoneError by remember { mutableStateOf(false) }
    var showLogos by remember { mutableStateOf(false) }
    fun save(value: com.cabin.carlink.CarlinkSettings) { value.save(context); configuration = value }
    var showPhones by remember { mutableStateOf(false) }
    fun retry() {
        if (!nativeSelected) return
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
    if (showLogos) CarlinkLogoPicker(configuration.logo,
        onSelect = { save(configuration.copy(logo = it)); showLogos = false },
        onDismiss = { showLogos = false })
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("native-carplay-settings"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.carplay_backend_native), style = MaterialTheme.typography.headlineSmall)
        if (status.isNotEmpty()) Text(status)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = nativeSelected, onClick = { retry() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_retry))
            }
            OutlinedButton(enabled = nativeSelected && service != null, onClick = { service?.stopProjection() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_disconnect))
            }
            OutlinedButton(enabled = nativeSelected && service != null && running, onClick = { service?.enableWireless() }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_wireless))
            }
            OutlinedButton(enabled = nativeSelected && service != null && running, onClick = { showPhones = true }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(R.string.joying_phone))
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.carlink_reconnect_hint), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.carlink_frame_rate), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 25, 30, 60).forEach { fps ->
                FilterChip(selected = configuration.fps == fps, onClick = { save(configuration.copy(fps = fps)) },
                    label = { Text("$fps FPS") }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        Text(stringResource(R.string.carlink_wifi_band), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("2.4 GHz", "5 GHz").forEachIndexed { band, label ->
                FilterChip(selected = configuration.band == band, onClick = { save(configuration.copy(band = band)) },
                    label = { Text(label) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.carlink_auto_connect), Modifier.weight(1f))
            Switch(checked = configuration.autoConnect, onCheckedChange = { save(configuration.copy(autoConnect = it)) },
                modifier = Modifier.testTag("carlink-auto-connect"))
        }
        Text(stringResource(R.string.carlink_microphone), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.carlink_noise_none, R.string.carlink_noise_right, R.string.carlink_noise_left).forEachIndexed { value, label ->
                FilterChip(selected = microphone == value, onClick = {
                    runCatching { com.cabin.carlink.CarlinkMicrophone.set(value) }
                        .onSuccess { microphone = value; microphoneError = false }
                        .onFailure { microphoneError = true }
                }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        if (microphoneError) Text(stringResource(R.string.carlink_microphone_error), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { showLogos = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.carlink_logo))
        }
        Text("Cabin ${com.cabin.BuildConfig.VERSION_NAME} · Carlink 2.23.0712.1954", style = MaterialTheme.typography.bodySmall)

    }
}
