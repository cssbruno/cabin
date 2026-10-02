package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R

@Composable internal fun DashboardPageTools(prefs: DashboardPreferences, page: Int, onPage: (Int) -> Unit, onDismiss: () -> Unit) {
    val state by prefs.state.collectAsStateWithLifecycle()
    var name by rememberSaveable(page) { mutableStateOf(state.pageNames[page] ?: "") }
    var duplicate by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.goal_page_tools)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.goal_page_name)) }, singleLine = true)
            Text(stringResource(R.string.goal_draft_note))
            TextButton({ prefs.renamePage(page, name) }) { Text(stringResource(R.string.app_library_save)) }
            TextButton({ if (prefs.reorderPage(page, page - 1)) onPage(page - 1) }, enabled = page > 0) { Text(stringResource(R.string.launcher_move_left)) }
            TextButton({ if (prefs.reorderPage(page, page + 1)) onPage(page + 1) }, enabled = page < state.pages - 1) { Text(stringResource(R.string.launcher_move_right)) }
            TextButton({ duplicate = true }, enabled = state.pages < 6 && dashboardDuplicatePage(state, page) != null) { Text(stringResource(R.string.goal_duplicate_page)) }
        }
    }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.launcher_done)) } })
    if (duplicate) AlertDialog(onDismissRequest = { duplicate = false }, title = { Text(stringResource(R.string.goal_duplicate_page)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.goal_duplicate_preview))
            state.tiles.filter { it.page == page }.forEach { tile ->
                Text(stringResource(tile.module.title()) + if (tile.module in setOf(DashboardModule.WIDGET, DashboardModule.PROJECTION)) " — " + stringResource(R.string.goal_duplicate_excluded) else "")
            }
        }
    }, confirmButton = { TextButton({ if (prefs.duplicatePage(page)) { onPage(prefs.state.value.pages - 1); duplicate = false; onDismiss() } }) { Text(stringResource(R.string.app_library_confirm)) } },
        dismissButton = { TextButton({ duplicate = false }) { Text(stringResource(R.string.app_library_cancel)) } })
}
