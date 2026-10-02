package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.cabin.R

/** Fixed cells; overflow is another page, never a vertically scrolling launcher. */
@Composable
fun <T> PagedLauncherItems(items: List<T>, modifier: Modifier = Modifier,
    resetKey: Any? = null, compact: Boolean = false, header: @Composable RowScope.() -> Unit = {},
    emptyContent: @Composable () -> Unit = {}, content: @Composable (T) -> Unit) {
    var page by remember(resetKey) { mutableIntStateOf(0) }
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 640.dp
        val columns = (maxWidth.value / (if (compact) 180 else 240)).toInt().coerceIn(1, 4)
        val rows = ((maxHeight.value - 64) / (if (compact) 100 else 132) / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).toInt().coerceIn(1, 3)
        val capacity = columns * rows
        val count = maxOf(1, (items.size + capacity - 1) / capacity)
        val visiblePage = page.coerceIn(0, count - 1)
        LaunchedEffect(count) { page = page.coerceIn(0, count - 1) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                header()
                if (count > 1) {
                    // Three full-size page targets must not consume the search field on narrow displays.
                    if (narrow) LauncherPageMenu(visiblePage, count) { page = it }
                    else PageDots(visiblePage, count, { page = it }, maxVisible = 3)
                }
            }
            val visible = items.drop(visiblePage * capacity).take(capacity)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) emptyContent()
                visible.chunked(columns).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item -> Box(Modifier.weight(1f).fillMaxHeight()) { content(item) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LauncherPageMenu(page: Int, count: Int, onPage: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(R.string.module_page, page + 1, count)
    Box {
        FilledTonalIconButton({ expanded = true }, Modifier.size(56.dp).semantics { contentDescription = label }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((page + 1).toString(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            repeat(count) { target ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.module_page, target + 1, count)) },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { selected = target == page },
                    onClick = { expanded = false; onPage(target) },
                )
            }
        }
    }
}
