package com.cabin.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cabin.R
import kotlin.math.roundToInt

@Composable
internal fun WidgetResizeHandle(tile: DashboardTile, cellWidth: Dp, cellHeight: Dp,
    onResize: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    val currentTile by rememberUpdatedState(tile)
    val commit by rememberUpdatedState(onResize)
    val density = LocalDensity.current
    val cell = with(density) { Offset(cellWidth.toPx(), cellHeight.toPx()) }
    var preview by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val description = stringResource(R.string.widget_drag_resize)
    Box(modifier.size(56.dp).testTag("resize-${tile.id}")
        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(topStart = 16.dp))
        .semantics { contentDescription = description }
        .pointerInput(tile.id, cell) {
            var start = currentTile
            var delta = Offset.Zero
            detectDragGestures(
                onDragStart = { start = currentTile; delta = Offset.Zero; preview = start.width to start.height },
                onDragCancel = { preview = null },
                onDragEnd = { preview?.let { (w, h) -> commit(w, h) }; preview = null },
                onDrag = { change, amount ->
                    change.consume()
                    delta += amount
                    preview = (start.width + (delta.x / cell.x).roundToInt()).coerceIn(1, 4 - start.x) to
                        (start.height + (delta.y / cell.y).roundToInt()).coerceIn(1, 2 - start.y)
                })
        }, contentAlignment = Alignment.Center) {
        val size = preview
        if (size == null) Icon(Icons.Default.OpenInFull, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        else Text("${size.first} × ${size.second}", color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge)
    }
}
