package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

/** Fixed cells; overflow is another page, never a vertically scrolling launcher. */
@Composable
fun <T> PagedLauncherItems(items: List<T>, modifier: Modifier = Modifier,
    header: @Composable RowScope.() -> Unit = {}, content: @Composable (T) -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier) {
        val columns = (maxWidth.value / 210).toInt().coerceIn(1, 4)
        val rows = ((maxHeight.value - 64) / 100).toInt().coerceIn(1, 3)
        val capacity = columns * rows
        val count = maxOf(1, (items.size + capacity - 1) / capacity)
        val visiblePage = page.coerceIn(0, count - 1)
        LaunchedEffect(count) { page = page.coerceIn(0, count - 1) }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                header()
                PageDots(visiblePage, count, { page = it }, maxVisible = 3)
            }
            val visible = items.drop(visiblePage * capacity).take(capacity)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
