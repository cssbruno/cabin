package com.cabin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    BackHandler(enabled = choosing) { choosing = false }
    var devices by remember(manager) { mutableStateOf(manager.pairedDevices) }
    var activeMac by remember(manager) { mutableStateOf<String?>(null) }
    var connected by remember(manager) { mutableStateOf(false) }
    var wireless by remember(manager) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val presentation by com.cabin.platform.ProjectionPreferences.getInstance(context).state.collectAsState()
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
            onRefresh = { manager.refreshDeviceList() },
            phonePreference = manager::phonePreference,
            onPreference = manager::setPhonePreference,
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
            toolOrder = presentation.toolOrder,
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
    onRefresh: (() -> Unit)? = null,
    phonePreference: ((String) -> com.cabin.platform.PhoneConnectionPreference)? = null,
    onPreference: ((String, com.cabin.platform.PhoneConnectionPreference) -> Unit)? = null,
) {
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var preferenceRevision by remember { mutableIntStateOf(0) }
    val visibleDevices = remember(devices, query, platform, alphabetical) {
        filterProjectionDevices(devices, query, platform, alphabetical)
    }
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
                Text(stringResource(R.string.projection_change_device), modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.projection_tools_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                onRefresh?.let { refresh ->
                    OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.px_refresh_phones))
                    }
                }
                if (devices.isNotEmpty()) {
                    OutlinedButton(onClick = { showFilters = !showFilters }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(if (showFilters) R.string.px_hide_phone_filters else R.string.px_find_phones))
                    }
                    if (showFilters) {
                        val searchLabel = stringResource(R.string.px_search_phones)
                        OutlinedTextField(value = query, onValueChange = { query = it.take(100) }, singleLine = true,
                            label = { Text(searchLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = searchLabel })
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = platform == null, onClick = { platform = null }, label = { Text(stringResource(R.string.px_all_platforms)) }, modifier = Modifier.heightIn(min = 56.dp))
                            devices.map { it.type }.distinct().sorted().forEach { type ->
                                FilterChip(selected = platform == type, onClick = { platform = type }, label = { Text(type) }, modifier = Modifier.heightIn(min = 56.dp))
                            }
                        }
                        FilterChip(selected = alphabetical, onClick = { alphabetical = !alphabetical },
                            label = { Text(stringResource(R.string.px_sort_phones)) }, modifier = Modifier.heightIn(min = 56.dp))
                    }
                    if (visibleDevices.isEmpty()) {
                        Text(stringResource(R.string.px_no_phone_matches))
                        TextButton(onClick = { query = ""; platform = null }, modifier = Modifier.heightIn(min = 56.dp)) {
                            Text(stringResource(R.string.px_clear_filters))
                        }
                    }
                }
                if (devices.isEmpty()) {
                    Text(stringResource(R.string.phones_no_paired))
                    Text(stringResource(R.string.projection_pair_from_phone), style = MaterialTheme.typography.bodyMedium)
                }
                visibleDevices.forEach { device ->
                    key(device.btMac) {
                        // Preferred is exclusive across the list; re-read every row after a change.
                        val preference = remember(device.btMac, preferenceRevision) { phonePreference?.invoke(device.btMac) }
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
                        if (onPreference != null && preference != null) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                com.cabin.platform.PhoneConnectionPreference.entries.forEach { option ->
                                    FilterChip(selected = preference == option, onClick = { onPreference(device.btMac, option); preferenceRevision++ },
                                        label = { Text(stringResource(when (option) {
                                            com.cabin.platform.PhoneConnectionPreference.AUTOMATIC -> R.string.gx_phone_automatic
                                            com.cabin.platform.PhoneConnectionPreference.MANUAL -> R.string.gx_phone_manual
                                            com.cabin.platform.PhoneConnectionPreference.PREFERRED -> R.string.gx_phone_preferred
                                        })) }, modifier = Modifier.heightIn(min = 56.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search never uses or exposes phone addresses; matching is limited to the visible name and platform. */
internal fun filterProjectionDevices(
    devices: List<CabinManager.DeviceInfo>,
    query: String,
    platform: String?,
    alphabetical: Boolean,
): List<CabinManager.DeviceInfo> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val result = devices.filter { device ->
        (platform == null || device.type == platform) &&
            words.all { word -> device.name.contains(word, ignoreCase = true) || device.type.contains(word, ignoreCase = true) }
    }
    return if (alphabetical) result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.ifBlank { it.type } }) else result
}
