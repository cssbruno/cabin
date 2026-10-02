package com.cabin.launcher

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.settings.settingsFocusRing

@Composable
internal fun LauncherAppDrawer(
    apps: List<TeyesLaunchableApp>, preferences: LauncherPreferences,
    onRefresh: () -> Unit, onLaunch: (TeyesLaunchableApp) -> Unit, onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val layout by preferences.state.collectAsStateWithLifecycle()
    val library = preferences.library
    val state by library.state.collectAsStateWithLifecycle()
    var search by rememberSaveable(preferences) { mutableStateOf("") }
    var options by remember(preferences) { mutableStateOf(false) }
    var selected by remember(preferences) { mutableStateOf<TeyesLaunchableApp?>(null) }
    var renaming by rememberSaveable(preferences) { mutableStateOf<String?>(null) }
    var selecting by rememberSaveable(preferences) { mutableStateOf(false) }
    var selection by rememberSaveable(preferences) { mutableStateOf(arrayListOf<String>()) }
    var bulkAction by remember(preferences) { mutableStateOf<LauncherBulkAction?>(null) }
    var folders by rememberSaveable(preferences) { mutableStateOf(false) }
    var reorder by rememberSaveable(preferences) { mutableStateOf(false) }
    val canUndo by preferences.canUndo.collectAsStateWithLifecycle()
    val installedAt by produceState<Map<String, Long>>(emptyMap(), apps) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { apps.associate { app ->
            app.component to try { context.packageManager.getPackageInfo(app.component.substringBefore('/'), 0).firstInstallTime } catch (_: Exception) { 0L }
        } }
    }
    var confirmation by remember(preferences) { mutableStateOf<LibraryConfirmation?>(null) }
    val missing = layout.favorites.filter { component -> apps.none { it.component == component } }
    val visible = remember(apps, search, layout.favorites, state, installedAt) { launcherLibraryApps(apps, search, layout.favorites, state, installedAt) }
    LaunchedEffect(preferences) { preferences.refresh() }

    BoxWithConstraints(modifier) {
    val shortScreen = maxHeight < 300.dp
    val narrowToolbar = maxWidth < 400.dp
    Column(Modifier.fillMaxSize()) {
        if (!shortScreen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherAppFilter.entries.forEach { filter ->
                FilterChip(selected = state.filter == filter, onClick = { library.setFilter(filter) },
                    label = { Text(stringResource(filter.label)) }, leadingIcon = { if (state.filter == filter) Icon(Icons.Default.Check, null) })
            }
        }
        if (!shortScreen && state.folders.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(state.selectedFolder == null, { library.selectFolder(null) }, label = { Text(stringResource(R.string.goal_all_folders)) })
            state.folders.forEach { (id, name) -> FilterChip(state.selectedFolder == id, { library.selectFolder(id) }, label = { Text(name) }) }
        }
        if (!shortScreen && selecting) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.goal_selected_apps, selection.size))
            LauncherBulkAction.entries.forEach { action -> TextButton({ bulkAction = action }, enabled = selection.isNotEmpty()) { Text(stringResource(action.label)) } }
            TextButton({ selecting = false; selection = arrayListOf() }) { Text(stringResource(R.string.launcher_done)) }
        }
        if (!shortScreen && canUndo) TextButton({ preferences.undoLibraryChange() }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.goal_undo_library)) }
        PagedLauncherItems(visible, Modifier.weight(1f), resetKey = listOf(search, state.filter, state.sort, state.selectedFolder), compact = state.compact, header = {
            val searchLabel = stringResource(R.string.launcher_search)
            OutlinedTextField(search, { search = it.take(100) },
                label = if (shortScreen) null else ({ Text(searchLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                placeholder = if (shortScreen) ({ Text(searchLabel, maxLines = 1) }) else null,
                modifier = Modifier.weight(1f).semantics { contentDescription = searchLabel }, singleLine = true, trailingIcon = {
                    if (search.isNotEmpty()) IconButton({ search = "" }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.app_library_clear_search))
                    }
                })
            if (!narrowToolbar) IconButton(onRefresh, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Refresh, stringResource(R.string.launcher_refresh)) }
            IconButton({ options = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.Tune, stringResource(R.string.app_library_options)) }
        }, emptyContent = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val filtered = state.filter != LauncherAppFilter.ALL || state.selectedFolder != null
                Text(stringResource(when {
                    search.isNotBlank() -> R.string.launcher_no_apps
                    state.filter == LauncherAppFilter.RECENT && state.usage.isEmpty() -> R.string.app_library_no_recent
                    filtered -> R.string.ux_launcher_empty_view
                    else -> R.string.launcher_no_apps
                }))
                if (search.isNotBlank()) TextButton({ search = "" }, Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.app_library_clear_search))
                }
                if (filtered) TextButton({ library.setFilter(LauncherAppFilter.ALL); library.selectFolder(null); search = "" }, Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.ux_launcher_show_all))
                }
            }
        }) { app ->
            if (selecting) Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).settingsFocusRing()
                .semantics { contentDescription = app.label }
                .toggleable(app.component in selection, role = Role.Checkbox) { checked ->
                    selection = ArrayList(if (checked) selection + app.component else selection - app.component)
                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(app.component in selection, null)
                Text(highlightedLauncherText(app.label, search, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer),
                    Modifier.weight(1f).padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else LauncherAppTile(app, showPackage = state.showPackages, onOpen = { onLaunch(app) }, onManage = { selected = app }, query = search)
        }
        if (!state.recordHistory && state.sort in setOf(LauncherAppSort.RECENT, LauncherAppSort.FREQUENT) && state.filter != LauncherAppFilter.RECENT) {
            Text(stringResource(R.string.app_library_no_recent), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text(stringResource(R.string.app_library_options)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (narrowToolbar) TextButton({ onRefresh(); options = false }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.launcher_refresh)) }
            if (shortScreen) {
                LauncherAppFilter.entries.forEach { filter -> TextButton({ library.setFilter(filter); options = false }) { Text(stringResource(filter.label)) } }
                TextButton({ library.selectFolder(null); options = false }) { Text(stringResource(R.string.goal_all_folders)) }
                state.folders.forEach { (id, name) -> TextButton({ library.selectFolder(id); options = false }) { Text(name) } }
                if (canUndo) TextButton({ preferences.undoLibraryChange(); options = false }) { Text(stringResource(R.string.goal_undo_library)) }
                if (selecting) {
                    Text(stringResource(R.string.goal_selected_apps, selection.size))
                    LauncherBulkAction.entries.forEach { action -> TextButton({ bulkAction = action; options = false }, enabled = selection.isNotEmpty()) { Text(stringResource(action.label)) } }
                    TextButton({ selecting = false; selection = arrayListOf(); options = false }) { Text(stringResource(R.string.launcher_done)) }
                }
            }
            TextButton({ selecting = true; options = false }) { Text(stringResource(R.string.goal_select_apps)) }
            TextButton({ folders = true; options = false }) { Text(stringResource(R.string.goal_folders)) }
            TextButton({ reorder = true; options = false }, enabled = layout.favorites.size > 1) { Text(stringResource(R.string.goal_reorder_pins)) }
            LibrarySwitch(stringResource(R.string.goal_compact_tiles), state.compact, library::setCompact)
            Text(stringResource(R.string.goal_recent_period))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(7, 30, 90).forEach { days -> FilterChip(state.recentDays == days, { library.setRecentDays(days) }, label = { Text(stringResource(R.string.goal_days, days)) }) } }
            Text(stringResource(R.string.app_library_sort), style = MaterialTheme.typography.titleSmall)
            LauncherAppSort.entries.forEach { sort ->
                TextButton({ library.setSort(sort) }, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = state.sort == sort, onClick = null)
                    Text(stringResource(sort.label), Modifier.weight(1f).padding(start = 8.dp))
                }
            }
            LibrarySwitch(stringResource(R.string.app_library_packages), state.showPackages, library::showPackages)
            LibrarySwitch(stringResource(R.string.app_library_history), state.recordHistory) { enabled ->
                if (enabled) library.recordHistory(true) else confirmation = LibraryConfirmation.DISABLE_HISTORY
            }
            Text(stringResource(R.string.app_library_history_note), style = MaterialTheme.typography.bodySmall)
            TextButton({ confirmation = LibraryConfirmation.HISTORY }, enabled = state.usage.isNotEmpty()) { Text(stringResource(R.string.app_library_clear_history)) }
            HorizontalDivider()
            Text(stringResource(R.string.app_library_pin_count, layout.favorites.size, LauncherPreferences.MAX_FAVORITES))
            missing.forEach { component -> TextButton({ options = false; onLaunch(TeyesLaunchableApp(component, state.names[component] ?: component.substringBefore('/'))) }) { Text(stringResource(R.string.goal_shortcut_unavailable) + ": " + (state.names[component] ?: component.substringBefore('/'))) } }
            TextButton({ confirmation = LibraryConfirmation.MISSING }, enabled = missing.isNotEmpty()) {
                Text(stringResource(R.string.app_library_missing, missing.size))
            }
            TextButton({ confirmation = LibraryConfirmation.PINS }, enabled = layout.favorites.isNotEmpty()) { Text(stringResource(R.string.app_library_clear_pins)) }
        }
    }, confirmButton = { TextButton({ options = false }) { Text(stringResource(R.string.launcher_done)) } })

    selected?.let { app ->
        val isPinned = app.component in layout.favorites
        val hidden = app.component in state.hidden
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(state.names[app.component] ?: app.label) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.component.substringBefore('/'), style = MaterialTheme.typography.bodySmall)
                if (isPinned) {
                    TextButton({ preferences.unpin(app.component); selected = null }) { Text(stringResource(R.string.launcher_unpin)) }
                    TextButton({ preferences.move(app.component, -1) }, enabled = layout.favorites.indexOf(app.component) > 0) { Text(stringResource(R.string.launcher_move_left)) }
                    TextButton({ preferences.move(app.component, 1) }, enabled = layout.favorites.indexOf(app.component) < layout.favorites.lastIndex) { Text(stringResource(R.string.launcher_move_right)) }
                    TextButton({ preferences.moveToEdge(app.component, true) }, enabled = layout.favorites.firstOrNull() != app.component) { Text(stringResource(R.string.app_library_move_first)) }
                    TextButton({ preferences.moveToEdge(app.component, false) }, enabled = layout.favorites.lastOrNull() != app.component) { Text(stringResource(R.string.app_library_move_last)) }
                } else {
                    TextButton({ preferences.pin(app.component); selected = null }, enabled = layout.favorites.size < LauncherPreferences.MAX_FAVORITES) { Text(stringResource(R.string.launcher_pin)) }
                    if (layout.favorites.size >= LauncherPreferences.MAX_FAVORITES) Text(stringResource(R.string.app_library_pins_full, LauncherPreferences.MAX_FAVORITES))
                }
                TextButton({ renaming = app.component; selected = null }) { Text(stringResource(R.string.app_library_rename)) }
                TextButton({ library.hide(app.component, !hidden); selected = null }) {
                    Text(stringResource(if (hidden) R.string.app_library_restore else R.string.app_library_hide))
                }
                Text(stringResource(R.string.goal_move_folder))
                TextButton({ library.moveToFolder(app.component, null); selected = null }) { Text(stringResource(R.string.goal_no_folder)) }
                state.folders.forEach { (id, name) -> TextButton({ library.moveToFolder(app.component, id); selected = null }) { Text(name) } }
                Text(stringResource(R.string.app_library_hide_note), style = MaterialTheme.typography.bodySmall)
                TextButton({
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", ComponentName.unflattenFromString(app.component)?.packageName, null)))
                    } catch (_: RuntimeException) { onFailure() }
                    selected = null
                }) { Text(stringResource(R.string.launcher_app_info)) }
            }
        }, confirmButton = { TextButton({ selected = null }) { Text(stringResource(R.string.launcher_close)) } })
    }

    renaming?.let { component ->
        val app = apps.firstOrNull { it.component == component } ?: TeyesLaunchableApp(component, component.substringBefore('/'))
        var name by rememberSaveable(app.component) { mutableStateOf(state.names[app.component] ?: "") }
        Dialog(onDismissRequest = { renaming = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BoxWithConstraints(Modifier.fillMaxSize().imePadding().systemBarsPadding(), contentAlignment = Alignment.Center) {
            val compactEditor = maxHeight < 320.dp
            val editorPadding = when {
                maxHeight < 200.dp -> 4.dp
                compactEditor -> 8.dp
                else -> 16.dp
            }
            Surface(Modifier.padding(editorPadding).widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(if (compactEditor) 1f else .9f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxSize().padding(editorPadding), verticalArrangement = Arrangement.spacedBy(if (compactEditor) 4.dp else 8.dp)) {
                    if (!compactEditor) Text(stringResource(R.string.app_library_rename), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (compactEditor) Text(stringResource(R.string.app_library_rename), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        OutlinedTextField(name, { name = it.take(LauncherAppLibrary.MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.app_library_custom_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { library.rename(app.component, name); focus.clearFocus(); renaming = null }))
                        Text(stringResource(R.string.goal_draft_note))
                        Text(stringResource(R.string.app_library_rename_note), style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ renaming = null }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.app_library_cancel)) }
                        TextButton({ library.rename(app.component, name); renaming = null }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.app_library_save)) }
                    }
                }
            }
            }
        }
    }

    if (folders) LauncherFolderDialog(library, onDismiss = { folders = false })
    if (reorder) PinnedReorderDialog(layout.favorites, apps, state, onApply = { preferences.reorderFavorites(it); reorder = false }, onDismiss = { reorder = false })
    bulkAction?.let { action ->
        val additionalPins = selection.count { it !in layout.favorites }
        val remainingSlots = (LauncherPreferences.MAX_FAVORITES - layout.favorites.size).coerceAtLeast(0)
        val exceedsPinCapacity = action == LauncherBulkAction.PIN && additionalPins > remainingSlots
        AlertDialog(onDismissRequest = { bulkAction = null }, title = { Text(stringResource(action.label)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.goal_bulk_preview, selection.size))
                selection.forEach { component -> Text(state.names[component] ?: apps.firstOrNull { it.component == component }?.label ?: component) }
                if (exceedsPinCapacity) Text(stringResource(R.string.ux_launcher_pin_capacity, remainingSlots), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !exceedsPinCapacity, onClick = { preferences.bulk(selection.toSet(), action); bulkAction = null; selection = arrayListOf(); selecting = false }) { Text(stringResource(R.string.app_library_confirm)) } },
            dismissButton = { TextButton({ bulkAction = null }) { Text(stringResource(R.string.app_library_cancel)) } })
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(action.title)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(action.message))
                if (action == LibraryConfirmation.MISSING) missing.forEach { Text(it) }
            }
        }, confirmButton = { TextButton({
            when (action) {
                LibraryConfirmation.HISTORY -> library.clearHistory()
                LibraryConfirmation.DISABLE_HISTORY -> library.recordHistory(false)
                LibraryConfirmation.MISSING -> preferences.removeMissingFavorites(apps.map { it.component }.toSet())
                LibraryConfirmation.PINS -> preferences.clearFavorites()
            }
            confirmation = null
        }) { Text(stringResource(R.string.app_library_confirm)) } },
            dismissButton = { TextButton({ confirmation = null }) { Text(stringResource(R.string.app_library_cancel)) } })
    }
    }
}

@Composable
private fun LibrarySwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

private val LauncherAppFilter.label: Int get() = when (this) {
    LauncherAppFilter.ALL -> R.string.app_library_all
    LauncherAppFilter.PINNED -> R.string.app_library_pinned
    LauncherAppFilter.RECENT -> R.string.app_library_recent
    LauncherAppFilter.INSTALLED -> R.string.goal_recent_installed
    LauncherAppFilter.HIDDEN -> R.string.app_library_hidden
}
private val LauncherAppSort.label: Int get() = when (this) {
    LauncherAppSort.PINNED -> R.string.app_library_sort_pinned
    LauncherAppSort.NAME -> R.string.app_library_sort_name
    LauncherAppSort.NAME_DESCENDING -> R.string.app_library_sort_name_desc
    LauncherAppSort.RECENT -> R.string.app_library_sort_recent
    LauncherAppSort.FREQUENT -> R.string.app_library_sort_frequent
}
private enum class LibraryConfirmation(val title: Int, val message: Int) {
    HISTORY(R.string.app_library_clear_history, R.string.app_library_clear_history_note),
    DISABLE_HISTORY(R.string.app_library_disable_history, R.string.app_library_disable_history_note),
    MISSING(R.string.app_library_missing_title, R.string.app_library_missing_note),
    PINS(R.string.app_library_clear_pins, R.string.ux_launcher_clear_pins_note),
}

internal val LauncherBulkAction.label: Int get() = when (this) {
    LauncherBulkAction.PIN -> R.string.launcher_pin
    LauncherBulkAction.UNPIN -> R.string.launcher_unpin
    LauncherBulkAction.HIDE -> R.string.app_library_hide
    LauncherBulkAction.RESTORE -> R.string.app_library_restore
}
