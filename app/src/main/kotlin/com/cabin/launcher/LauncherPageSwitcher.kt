package com.cabin.launcher

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cabin.R

/** Page gestures stay outside the phone surface so a map pan is never a launcher swipe. */
@Composable
fun Modifier.launcherPageSwipe(page: Int, onPage: (Int) -> Unit, thresholdDp: Int = 64): Modifier {
    val latestOnPage by rememberUpdatedState(onPage)
    return pointerInput(page, thresholdDp) {
    var distance = 0f
    detectHorizontalDragGestures(
        onDragStart = { distance = 0f },
        onHorizontalDrag = { change, delta -> change.consume(); distance += delta },
        onDragEnd = {
            if (kotlin.math.abs(distance) >= thresholdDp.dp.toPx()) latestOnPage((page + if (distance < 0) 1 else -1).coerceIn(0, 4))
        },
        onDragCancel = { distance = 0f },
    )
    }
}

@Composable
fun LauncherPageSwitcher(page: Int, moving: Boolean, onPage: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.launcher_page_carplay, R.string.launcher_page_main, R.string.launcher_apps, R.string.launcher_settings, R.string.launcher_widgets)
    val icons = listOf(Icons.Default.Phonelink, Icons.Default.Home, Icons.Default.Apps, Icons.Default.Settings, Icons.Default.Widgets)
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = stringResource(labels[page.coerceIn(0, 4)])
    LaunchedEffect(moving, page) { expanded = false }
    Box(modifier) {
        FilledTonalIconButton(onClick = { expanded = !expanded },
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.size(56.dp).testTag("launcher-pages")
                .semantics { stateDescription = currentLabel }
                .launcherPageSwipe(page, { expanded = false; onPage(it) }, thresholdDp = 24)) {
            Icon(Icons.Default.Apps, stringResource(R.string.launcher_switch_page), Modifier.size(30.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icons[index], null) },
                    trailingIcon = { if (index == page) Icon(Icons.Default.Check, null) },
                    enabled = !moving || index !in 2..3,
                    modifier = Modifier.heightIn(min = 64.dp),
                    onClick = { expanded = false; onPage(index) })
            }
        }
    }
}
