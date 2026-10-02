package com.cabin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun TeyesConfigurationTools(preferences: TeyesFeaturePreferences, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { PortableConfigurationBackup(context) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var password by remember { mutableStateOf("") }
    var encrypted by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf(setOf<String>()) }
    var differences by remember { mutableStateOf(emptyList<PortableConfigurationBackup.Difference>()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) { busy = true; scope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                val plain = store.snapshot()
                val content = if (encrypted) PasswordBackup.encrypt(plain, password.toCharArray()) else plain
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(content) }
            }.isSuccess }
            password = ""; busy = false; status = if(saved) R.string.bu_backup_saved else R.string.bu_backup_save_failed
        } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) { busy = true; scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0 && output.size() + n <= PortableConfigurationBackup.MAX_BYTES * 2); output.write(buffer, 0, n) }; output.toByteArray()
                }
                val text = String(bytes, Charsets.UTF_8)
                val plain = if(JSONObject(text).optString("format") == "cabin-encrypted-backup") PasswordBackup.decrypt(text, password.toCharArray()) else text
                Triple(plain, store.availableSections(plain), store.diff(plain))
            } }
            password = ""; busy = false
            result.onSuccess { (text, chosen, diff) -> preview = text; sections = chosen; differences = diff }
                .onFailure { status = R.string.uxv_backup_rejected }
        } }
    }
    SettingsDisclosure(stringResource(R.string.bu_backup_title), stringResource(R.string.gv_backup_scope), modifier = modifier, searchLabels = emptySet()) {
        Text(stringResource(R.string.gv_backup_scope))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(encrypted, { encrypted = it }, enabled = !busy, modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_encrypt) }); Text(stringResource(R.string.gv_encrypt))
        }
        OutlinedTextField(password, { password = it.take(1024) }, label = { Text(stringResource(R.string.gv_password)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.gv_password_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                status = null
                try { export.launch("cabin-settings.json") } catch (_: RuntimeException) { status = R.string.uxv_document_picker_missing }
            }, enabled = !busy && (!encrypted || password.length >= 8)) { Text(stringResource(R.string.bu_save_backup)) }
            TextButton(onClick = {
                status = null
                try { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } catch (_: RuntimeException) { status = R.string.uxv_document_picker_missing }
            }, enabled = !busy) { Text(stringResource(R.string.bu_restore_backup)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { SettingsNotice(stringResource(it), error = it !in setOf(R.string.bu_backup_saved, R.string.bu_restore_saved)) }
    }
    preview?.let { text ->
        val all = remember(text) { store.availableSections(text) }
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text(stringResource(R.string.gv_restore_sections)) }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gv_restore_restart))
                all.forEach { section ->
                    val sectionTitle = sectionLabel(section)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(section in sections, { sections = if(it) sections + section else sections - section },
                            enabled = !busy, modifier = Modifier.semantics { contentDescription = sectionTitle })
                        Text(sectionTitle)
                    }
                    differences.filter { it.section == section }.take(100).forEach { diff ->
                        Text("${diff.key}: ${diff.before.take(80).ifBlank { "—" }} → ${diff.after.take(80).ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (differences.isEmpty()) Text(stringResource(R.string.gv_no_changes))
            }
        }, confirmButton = { TextButton(onClick = {
            val restoreSections = sections.toSet()
            busy = true; scope.launch {
            val success = withContext(Dispatchers.IO) { runCatching { store.restore(text, restoreSections) }.getOrDefault(false) }
            status = if(success) R.string.bu_restore_saved else R.string.bu_restore_failed
            busy = false; preview = null
        } }, enabled = !busy && sections.isNotEmpty()) { Text(stringResource(R.string.action_apply)) } },
            dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
    }
    DriverComfortTools(preferences)
}

@Composable
private fun sectionLabel(section: String): String = stringResource(when(section) {
    "drivers" -> R.string.teyes_driver_profile; "projection" -> R.string.bu_projection_preview_title; "units" -> R.string.gv_units
    "launcher" -> R.string.settings_tab_launcher; "library" -> R.string.app_library_options; "dashboard" -> R.string.goal_page_tools
    "automation" -> R.string.tools_automation; "appearance" -> R.string.gv_appearance; "trips" -> R.string.tools_trips
    "accessibility" -> R.string.gv_accessibility; "audio" -> R.string.gv_audio; else -> R.string.vehicle_readings
})

@Composable
private fun DriverComfortTools(preferences: TeyesFeaturePreferences) {
    val current by preferences.profile.collectAsState()
    val guest by preferences.guestActive.collectAsState()
    var target by remember(current.slot) { mutableIntStateOf((current.slot + 1) % 3) }
    var selected by remember(current.slot) { mutableStateOf(setOf("appearance", "brightness", "audio", "wake")) }
    var action by remember(current.slot, guest) { mutableStateOf<String?>(null) }
    SettingsDisclosure(stringResource(R.string.gv_comfort), stringResource(R.string.gv_comfort_detail), searchLabels = emptySet()) {
        UtilityChoice(stringResource(R.string.gv_copy_to), preferences.profiles()[target].name) { close ->
            preferences.profiles().filter { it.slot != current.slot }.forEach { p -> DropdownMenuItem(text = { Text(p.name) }, onClick = { target = p.slot; close() }) }
        }
        listOf("appearance" to R.string.gv_appearance, "brightness" to R.string.gv_brightness, "audio" to R.string.gv_audio, "wake" to R.string.gv_wake).forEach { (key, label) ->
            val title = stringResource(label)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Checkbox(key in selected, { selected = if(it) selected + key else selected - key }, modifier = Modifier.semantics { contentDescription = title }); Text(title) }
        }
        FlowRow {
            TextButton(onClick = { action = "copy" }, enabled = target != current.slot && selected.isNotEmpty() && !guest) { Text(stringResource(R.string.gv_copy)) }
            TextButton(onClick = { action = "reset" }, enabled = !guest) { Text(stringResource(R.string.gv_reset_comfort)) }
        }
        TextButton(onClick = { if(guest) preferences.endGuest() else preferences.beginGuest() }) { Text(stringResource(if(guest) R.string.gv_end_guest else R.string.gv_guest)) }
        if(guest) Text(stringResource(R.string.gv_guest_detail))
    }
    action?.let { requested ->
        val before = preferences.profiles()[if(requested == "copy") target else current.slot]
        val after = if(requested == "copy") current else TeyesDriverProfile()
        AlertDialog(onDismissRequest = { action = null }, title = { Text(stringResource(if(requested == "copy") R.string.gv_copy else R.string.gv_reset_comfort)) }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(before.name)
                Text(stringResource(R.string.gv_comfort_detail))
                if(requested == "reset" || "appearance" in selected) Text(stringResource(R.string.gv_appearance) + ": " + stringResource(before.appearance.labelRes) + " → " + stringResource(after.appearance.labelRes))
                if(requested == "reset" || "brightness" in selected) Text(stringResource(R.string.gv_brightness) + ": ${(before.nightBrightness*100).toInt()}% → ${(after.nightBrightness*100).toInt()}%")
                if(requested == "reset" || "audio" in selected) {
                    Text(stringResource(R.string.teyes_projection_music, (before.mediaGain * 100).toInt()) + " → ${(after.mediaGain * 100).toInt()}%")
                    Text(stringResource(R.string.teyes_navigation_prompts, (before.navigationGain * 100).toInt()) + " → ${(after.navigationGain * 100).toInt()}%")
                }
                if(requested == "reset" || "wake" in selected) {
                    listOf(Triple(R.string.teyes_retry_wake, before.resumeOnWake, after.resumeOnWake), Triple(R.string.teyes_recover_overlays, before.recoverOverlays, after.recoverOverlays), Triple(R.string.teyes_compact_launch, before.compactOnLaunch, after.compactOnLaunch)).forEach { (label, old, new) ->
                        Text(stringResource(label) + ": " + stringResource(if(old) R.string.interface_state_on else R.string.interface_state_off) + " → " + stringResource(if(new) R.string.interface_state_on else R.string.interface_state_off))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { if(requested == "copy") preferences.copyComfort(current.slot, target, selected) else preferences.resetComfort(current.slot); action = null }) { Text(stringResource(R.string.action_apply)) } },
            dismissButton = { TextButton(onClick = { action = null }) { Text(stringResource(R.string.action_cancel)) } })
    }
}
