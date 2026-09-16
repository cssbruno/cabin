package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.CabinManager
import com.cabin.R

@Composable
internal fun CarPlaySettingsContent(manager: CabinManager, initialConnection: Boolean = false, onReinitForDisplayMode: (DisplayMode) -> Unit = {}) {
    val backends = com.cabin.ui.rememberCarPlayBackends()
    Column(Modifier.fillMaxSize()) {
        if (backends.available.isEmpty()) {
            Text(stringResource(R.string.carplay_backend_none), Modifier.padding(24.dp))
            return@Column
        }
        if (backends.available.size > 1) com.cabin.ui.CarPlayBackendPicker(manager, backends)
        else if (backends.selected == com.cabin.platform.CarPlayBackend.DONGLE)
            Text(stringResource(R.string.carplay_backend_dongle), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        if (backends.available.size > 1 && backends.selected == null) return@Column
        if (backends.selected == com.cabin.platform.CarPlayBackend.JOYING) {
            NativeCarPlaySettings()
        } else DongleCarPlaySettings(manager, initialConnection, onReinitForDisplayMode)
    }
}

@Composable
private fun DongleCarPlaySettings(manager: CabinManager, initialConnection: Boolean, onReinitForDisplayMode: (DisplayMode) -> Unit) {
    var controls by rememberSaveable(initialConnection) { mutableStateOf(!initialConnection) }
    Column(Modifier.fillMaxSize().testTag("carplay-settings")) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !controls, onClick = { controls = false }, modifier = Modifier.heightIn(min = 56.dp),
                label = { Text(stringResource(R.string.label_connection)) }, leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, Modifier.size(20.dp)) })
            FilterChip(selected = controls, onClick = { controls = true }, modifier = Modifier.heightIn(min = 56.dp),
                label = { Text(stringResource(R.string.settings_carplay_controls)) }, leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(20.dp)) })
        }
        if (controls) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.cabin.ui.DongleAdapterSettings(manager, onReinitForDisplayMode)
            ProjectionPreferencesSection(showMeasurements = false)
        } else Box(Modifier.weight(1f)) { PhonesTabContent(manager) }
    }
}
