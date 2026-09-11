package com.cabin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cabin.CabinManager
import com.cabin.R
import kotlinx.coroutines.delay

/** Lives inside the existing projection overlay, so video and touch ownership stay unchanged. */
@Composable
internal fun ProjectionQuickMenu(
    manager: CabinManager,
    onRequestDeviceChange: (() -> Unit) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onScreenOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    var devices by remember(manager) { mutableStateOf(manager.pairedDevices) }
    var activeMac by remember(manager) { mutableStateOf<String?>(null) }
    var connected by remember(manager) { mutableStateOf(false) }
    var wireless by remember(manager) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(manager) {
        val listener = CabinManager.DeviceListener { devices = it }
        manager.addDeviceListener(listener)
        manager.refreshDeviceList()
        onDispose { manager.removeDeviceListener(listener) }
    }
    LaunchedEffect(manager, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                connected = manager.state == CabinManager.State.STREAMING || manager.state == CabinManager.State.DEVICE_CONNECTED
                wireless = manager.currentWifi == 1
                activeMac = manager.connectedBtMac.takeIf { connected && wireless }
                delay(1000)
            }
        }
    }
    val name = when {
        !connected -> stringResource(R.string.phones_no_device)
        !wireless -> stringResource(R.string.projection_usb_phone)
        else -> devices.firstOrNull { it.btMac == activeMac }?.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.phones_connected)
    }
    if (choosing) {
        ProjectionDevicePicker(
            devices = devices,
            activeMac = activeMac,
            onSelect = { device ->
                onRequestDeviceChange {
                    manager.connectToDevice(device.btMac)
                    onClose()
                }
            },
            onBack = { choosing = false },
            onClose = onClose,
            modifier = modifier,
        )
    } else {
        ProjectionToolsPanel(
            onSettings = onSettings,
            onClose = onClose,
            onScreenOff = onScreenOff,
            onChangeDevice = { choosing = true; manager.refreshDeviceList() },
            connectedDeviceName = name,
            modifier = modifier,
        )
    }
}

@Composable
internal fun ProjectionDevicePicker(
    devices: List<CabinManager.DeviceInfo>,
    activeMac: String?,
    onSelect: (CabinManager.DeviceInfo) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                }
                Text(stringResource(R.string.projection_change_device), modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.projection_tools_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.phones_no_paired))
                    Text(stringResource(R.string.projection_pair_from_phone), style = MaterialTheme.typography.bodyMedium)
                }
                devices.forEach { device ->
                    key(device.btMac) {
                        FilledTonalButton(
                            onClick = { onSelect(device) },
                            enabled = device.btMac != activeMac,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Column {
                                Text(device.name.ifBlank { device.type })
                                if (device.btMac == activeMac) Text(stringResource(R.string.phones_connected),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
