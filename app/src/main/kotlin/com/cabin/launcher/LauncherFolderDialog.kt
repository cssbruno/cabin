package com.cabin.launcher

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R
import com.cabin.platform.TeyesLaunchableApp
import com.cabin.ui.settings.settingsFocusRing
import kotlin.math.abs

@Composable internal fun LauncherFolderDialog(library: LauncherAppLibrary, onDismiss: () -> Unit) {
    val state by library.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable(library) { mutableStateOf<String?>(null) }
    var name by rememberSaveable(library) { mutableStateOf("") }
    val full = state.folders.size >= LauncherAppLibrary.MAX_FOLDERS
    val canSave = name.filterNot(Char::isISOControl).isNotBlank() &&
        (editing?.let { it in state.folders } ?: !full)
    fun saveFolder() {
        if (!canSave) return
        val saved = if (editing == null) library.createFolder(name) != null
            else { library.renameFolder(editing!!, name); true }
        if (saved) { name = ""; editing = null }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.goal_folders)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(LauncherAppLibrary.MAX_NAME) },
                label = { Text(stringResource(R.string.goal_folder_name), maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) { saveFolder(); defaultKeyboardAction(ImeAction.Done) } }))
            Text(stringResource(R.string.goal_draft_note))
            if (full && editing == null) Text(stringResource(R.string.ux_launcher_folder_capacity, LauncherAppLibrary.MAX_FOLDERS))
            TextButton(::saveFolder, enabled = canSave, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.app_library_save)) }
            state.folders.forEach { (id, title) ->
                val renameLabel = stringResource(R.string.ux_launcher_rename_folder, title)
                Text(title, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ editing = id; name = title }, Modifier.heightIn(min = 56.dp)
                        .semantics { contentDescription = renameLabel }) { Text(stringResource(R.string.app_library_rename)) }
                    TextButton({ library.removeFolder(id); if (editing == id) { editing = null; name = "" } }, Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.goal_remove_folder)) }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.launcher_done)) } })
}

/** Drag previews a new order. Apply commits once; cancel never changes stored order. */
@Composable internal fun PinnedReorderDialog(pins: List<String>, apps: List<TeyesLaunchableApp>, state: LauncherLibraryState, onApply: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(pins, stateSaver = listSaver(save = { it }, restore = { it })) { mutableStateOf(pins) }
    var active by remember { mutableStateOf<String?>(null) }
    var delta by remember { mutableFloatStateOf(0f) }
    val rowBounds = remember(pins) { mutableMapOf<String, Rect>() }
    var startCenter by remember { mutableFloatStateOf(0f) }
    val dragLabel = stringResource(R.string.goal_drag_reorder)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.goal_reorder_pins)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(dragLabel)
            draft.forEachIndexed { index, component ->
                key(component) {
                val label = state.names[component] ?: apps.firstOrNull { it.component == component }?.label ?: component.substringBefore('/')
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("pin-reorder-$component")
                    .onGloballyPositioned { rowBounds[component] = it.boundsInRoot() }
                    .background(if (active == component) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .pointerInput(component, draft) {
                    detectDragGesturesAfterLongPress(onDragStart = { active = component; delta = 0f; startCenter = rowBounds[component]?.center?.y ?: 0f }, onDragCancel = { active = null; delta = 0f }, onDragEnd = {
                        val destination = draft.minByOrNull { abs((rowBounds[it]?.center?.y ?: Float.MAX_VALUE) - (startCenter + delta)) }
                        val target = draft.indexOf(destination).takeIf { it >= 0 } ?: index
                        draft = draft.toMutableList().apply { removeAt(index); add(target, component) }
                        active = null; delta = 0f
                    }, onDrag = { change, amount -> change.consume(); delta += amount.y })
                }, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton({ draft = draft.toMutableList().apply { removeAt(index); add(index - 1, component) } }, enabled = index > 0,
                        modifier = Modifier.size(56.dp).settingsFocusRing()) { Icon(Icons.Default.ArrowUpward, stringResource(R.string.ux_launcher_move_up, label)) }
                    IconButton({ draft = draft.toMutableList().apply { removeAt(index); add(index + 1, component) } }, enabled = index < draft.lastIndex,
                        modifier = Modifier.size(56.dp).settingsFocusRing()) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.ux_launcher_move_down, label)) }
                }
                }
            }
        }
    }, confirmButton = { TextButton({ onApply(draft) }) { Text(stringResource(R.string.app_library_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.app_library_cancel)) } })
}
