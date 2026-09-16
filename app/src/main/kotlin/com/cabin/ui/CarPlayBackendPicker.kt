package com.cabin.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.background.CabinProjectionService
import com.cabin.joying.JoyingCarPlayService
import com.cabin.platform.*
import kotlinx.coroutines.launch

@Composable
internal fun rememberCarPlayBackends(): CarPlayBackends {
    val context = LocalContext.current
    var state by remember(context) { mutableStateOf(CarPlayBackendSelection.snapshot(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = CarPlayBackendSelection.snapshot(context) }
    DisposableEffect(context) {
        fun refresh() { state = CarPlayBackendSelection.snapshot(context) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
        val prefs = CarPlayBackendSelection.preferences(context)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener); context.unregisterReceiver(receiver) }
    }
    return state
}

@Composable
internal fun CarPlayBackendPicker(manager: CabinManager, state: CarPlayBackends) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.available.forEach { backend ->
                FilterChip(selected = state.selected == backend, enabled = !switching,
                    onClick = {
                        if (state.selected != backend) scope.launch {
                            switching = true
                            failed = false
                            CarPlayBackendSelection.switching = true
                            try {
                                CabinProjectionService.stopForAppExit(context, manager)
                                JoyingCarPlayService.stopAndAwait(context)
                                CarPlayBackendSelection.switching = false
                                CarPlayBackendSelection.select(context, backend)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { CarPlayBackendSelection.switching = false; switching = false }
                        }
                    }, label = { Text(stringResource(if (backend == CarPlayBackend.DONGLE)
                        R.string.carplay_backend_dongle else R.string.carplay_backend_native)) })
            }
        }
        if (failed) Text(stringResource(R.string.carplay_backend_switch_failed))
    }
}
