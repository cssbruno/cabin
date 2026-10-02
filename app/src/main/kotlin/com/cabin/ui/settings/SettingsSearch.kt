package com.cabin.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import com.cabin.launcher.title
import com.cabin.launcher.highlightedLauncherText
import com.cabin.launcher.normalizedLauncherText
import kotlinx.coroutines.flow.distinctUntilChanged

internal val LocalSettingsSearchTarget = staticCompositionLocalOf { "" }
internal val LocalSettingsNavigationKey = staticCompositionLocalOf { "default" }

@Composable internal fun rememberSettingsScrollState(): ScrollState {
    val context = LocalContext.current
    val scope = LocalSettingsNavigationKey.current
    val callSite = currentCompositeKeyHash
    val key = "$scope.scroll.$callSite"
    val prefs = remember(context) { context.getSharedPreferences("cabin_settings_navigation_v1", 0) }
    val state = remember(key) { ScrollState((prefs.all[key] as? Int ?: 0).coerceAtLeast(0)) }
    LaunchedEffect(state, key) { snapshotFlow { state.value }.distinctUntilChanged().collect { prefs.edit().putInt(key, it).apply() } }
    return state
}

internal fun Modifier.settingsFocusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.hasFocus }.border(2.dp, if (focused) color else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
}

internal fun Modifier.settingsSearchAnchor(label: String): Modifier = composed {
    val target = LocalSettingsSearchTarget.current
    val requester = remember { BringIntoViewRequester() }
    val matching = target.isNotEmpty() && target == label
    LaunchedEffect(target, label) { if (matching) { kotlinx.coroutines.delay(180); requester.bringIntoView() } }
    bringIntoViewRequester(requester).settingsFocusRing().then(if (matching) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier)
}

internal data class SettingSearchEntry(val tab: SettingsTab, val label: Int, val literal: String? = null)
internal fun searchSettings(entries: List<Pair<SettingSearchEntry, String>>, query: String): List<Pair<SettingSearchEntry, String>> {
    val words = normalizedLauncherText(query).trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return entries.filter { (_, text) -> words.all(normalizedLauncherText(text)::contains) }.distinctBy { it.first }
}

@Composable internal fun SettingsSearchDialog(vehicle: TeyesClimateState = TeyesClimateState(), onDismiss: () -> Unit, onSelect: (SettingsTab, String) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val queryFocus = remember { FocusRequester() }
    val entries = (settingsSearchEntries + adapterSearchSections.keys.map { SettingSearchEntry(SettingsTab.CCPA, it) } + vehicleSettingsSearchEntries(vehicle)).filter { it.tab in SettingsTab.visible }.map { it to (it.literal ?: context.getString(it.label)) }
    val matches = searchSettings(entries, query)
    // A bounded viewport avoids editor/intrinsic measurement oscillation in short dialogs.
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LaunchedEffect(Unit) { queryFocus.requestFocus() }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().systemBarsPadding()) {
            val compact = maxHeight < 320.dp
            val padding = if (compact) 8.dp else 16.dp
            Surface(Modifier.fillMaxSize().padding(padding), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it.take(100) },
                            label = { Text(stringResource(R.string.goal_search_settings), maxLines = 1) },
                            singleLine = true, modifier = Modifier.weight(1f).focusRequester(queryFocus))
                        IconButton(onDismiss, Modifier.size(56.dp)) { Icon(Icons.Default.Close, stringResource(R.string.launcher_close)) }
                    }
                    if (matches.isEmpty()) Text(stringResource(R.string.goal_settings_empty), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(matches, key = { (entry, _) -> "${entry.tab}:${entry.label}:${entry.literal}" }) { (entry, label) ->
                            val category = stringResource(entry.tab.title)
                            val highlighted = highlightedLauncherText(label, query, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                            TextButton({ onSelect(entry.tab, label) }, Modifier.fillMaxWidth().heightIn(min = 56.dp).settingsFocusRing()
                                .then(if (compact) Modifier.semantics { contentDescription = "$label, $category" } else Modifier)) {
                                if (compact) {
                                    Text(highlighted, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(highlighted)
                                        Text(category, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val settingsSearchEntries = listOf(
    SettingSearchEntry(SettingsTab.CAR, R.string.vehicle_readings),
    SettingSearchEntry(SettingsTab.CCPA, R.string.projection_preferences),
    SettingSearchEntry(SettingsTab.CCPA, R.string.projection_focus),
    SettingSearchEntry(SettingsTab.CCPA, R.string.projection_return_ready),
    SettingSearchEntry(SettingsTab.CCPA, R.string.projection_vehicle_hud),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.bu_units_title),
    SettingSearchEntry(SettingsTab.TEYES, R.string.bu_backup_title),
    SettingSearchEntry(SettingsTab.TEYES, R.string.gv_comfort),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_driver_profile),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_compact_launch),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_preferred_phone),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_adapter_default),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_appearance),
    SettingSearchEntry(SettingsTab.TEYES, R.string.label_audio),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_camera_recovery),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_recover_overlays),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_retry_wake),
    SettingSearchEntry(SettingsTab.TEYES, R.string.teyes_accessory_shortcuts),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.app_language_title),
    SettingSearchEntry(SettingsTab.CAR, R.string.compat_title),
    SettingSearchEntry(SettingsTab.CAR, R.string.car_appearance),
    SettingSearchEntry(SettingsTab.CAR, R.string.tools_automation),
    SettingSearchEntry(SettingsTab.CAR, R.string.tools_trips),
    SettingSearchEntry(SettingsTab.CAR, R.string.tools_maintenance),
    SettingSearchEntry(SettingsTab.TEYES, R.string.headunit_home),
    SettingSearchEntry(SettingsTab.TEYES, R.string.headunit_settings),
    SettingSearchEntry(SettingsTab.CAR, R.string.teyes_steering_shortcuts),
    SettingSearchEntry(SettingsTab.CAR, R.string.car_settings_my_car),
    SettingSearchEntry(SettingsTab.CAR, R.string.car_settings_can),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.goal_accessibility),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.goal_reduce_motion),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.goal_high_contrast),
    SettingSearchEntry(SettingsTab.CCPA, R.string.gx_experience_title),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.update_title),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.update_automatic),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.ux_unmetered),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.ux_channel),
    SettingSearchEntry(SettingsTab.LOGS, R.string.lxg_workspace),
    SettingSearchEntry(SettingsTab.LOGS, R.string.lxg_pending_exports),
    SettingSearchEntry(SettingsTab.LOGS, R.string.lxg_compare_reports),
    SettingSearchEntry(SettingsTab.CONTROL, R.string.settings_display_recovery),
    SettingSearchEntry(SettingsTab.PHONES, R.string.carlink_frame_rate),
    SettingSearchEntry(SettingsTab.PHONES, R.string.carlink_wifi_band),
    SettingSearchEntry(SettingsTab.PHONES, R.string.carlink_auto_connect),
    SettingSearchEntry(SettingsTab.PHONES, R.string.carlink_microphone),
    SettingSearchEntry(SettingsTab.PHONES, R.string.carlink_logo),
)

internal fun vehicleSettingsSearchEntries(vehicle: TeyesClimateState): List<SettingSearchEntry> {
    val controls = SyuFactoryProtocol.controls(vehicle.profileId)
    val entries = controls.map { SettingSearchEntry(SettingsTab.CAR, it.title()) }.toMutableList()
    val rows = vehicle.fytSyuReadings.filter { it.options.isNotEmpty() }
    entries += rows.map { SettingSearchEntry(SettingsTab.CAR, 0, it.label ?: "CAN ${it.viewId}") }
    VehicleSettingCategory.entries.filter { category ->
        category in setOf(VehicleSettingCategory.AUDIO, VehicleSettingCategory.COMFORT, VehicleSettingCategory.SERVICE) ||
            controls.any { VehicleSettingCategory.control(it) == category } ||
            rows.any { VehicleSettingCategory.reading(vehicle.profileId, it) == category } ||
            vehicle.fytActions.any { VehicleSettingCategory.action(it) == category } ||
            (category == VehicleSettingCategory.INSTRUMENTS && vehicle.fytChoices.isNotEmpty()) ||
            (category == VehicleSettingCategory.LIGHTS && vehicle.profileId in SyuVehicleProtocol.lightingProfiles)
    }.forEach { entries += SettingSearchEntry(SettingsTab.CAR, it.title()) }
    return entries
}

internal val adapterSearchSections = mapOf(
    R.string.adapter_audio_source to 0, R.string.adapter_microphone to 0, R.string.adapter_media_delay to 0,
    R.string.adapter_resolution to 1, R.string.adapter_frame_rate to 1, R.string.adapter_drive_side to 1,
    R.string.adapter_gps to 2, R.string.adapter_cluster_nav to 2, R.string.adapter_wifi_band to 2,
)
internal fun adapterSearchTab(resources: android.content.res.Resources, label: String): Int? =
    adapterSearchSections.entries.firstOrNull { resources.getString(it.key) == label }?.value
