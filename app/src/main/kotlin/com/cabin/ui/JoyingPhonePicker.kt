package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Discovery and bond requests stay in Cabin; Android presents any required pairing consent. */
@SuppressLint("MissingPermission")
@Composable
internal fun JoyingPhonePicker(onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var devices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    fun refresh() {
        runCatching { devices = (devices + adapter?.bondedDevices.orEmpty()).distinctBy { it.address }; revision++ }
            .onFailure { notice = "Bluetooth permission is required" }
    }
    fun scan() {
        runCatching {
            check(adapter?.isEnabled == true) { "Enable Bluetooth on the head unit first" }
            check(adapter.startDiscovery()) { "Bluetooth discovery could not start" }
            notice = "Searching… Keep your iPhone’s Bluetooth settings open."
        }.onFailure { notice = it.message ?: "Bluetooth discovery failed" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) { refresh(); scan() } else notice = "Bluetooth discovery permission was denied"
    }
    DisposableEffect(adapter) {
        refresh()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (device != null) {
                    devices = (devices + device).distinctBy { it.address }
                    revision++
                    if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE) == BluetoothDevice.BOND_BONDED)
                        notice = "Phone paired. Select Connect."
                }
                if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) notice = "Search finished. Tap Search to try again."
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver); runCatching { adapter?.cancelDiscovery() } }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.joying_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(notice.ifEmpty { context.getString(R.string.joying_picker_hint) })
                key(revision) {
                    devices.forEach { device ->
                        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                        val name = runCatching { device.name ?: device.address }.getOrDefault("Phone")
                        TextButton(onClick = {
                            runCatching {
                                adapter?.cancelDiscovery()
                                if (bonded) onConnect(device.address)
                                else {
                                    check(device.createBond()) { "Pairing could not start" }
                                    notice = "Confirm the pairing request on both devices."
                                }
                            }.onFailure { notice = it.message ?: "Pairing failed" }
                        }) { Text("$name · ${stringResource(if (bonded) R.string.joying_connect else R.string.joying_pair)}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            val missing = required.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) scan() else permissions.launch(missing.toTypedArray())
        }) { Text(stringResource(R.string.joying_search)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.joying_close)) } },
    )
}
