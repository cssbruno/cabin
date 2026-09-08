package com.cabin.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabin.R

/** Small visual indicators with full-size touch targets. The window follows selection. */
@Composable
fun PageDots(page: Int, count: Int, onPage: (Int) -> Unit, modifier: Modifier = Modifier, maxVisible: Int = 5) {
    val total = count.coerceAtLeast(1)
    val current = page.coerceIn(0, total - 1)
    val visible = minOf(maxVisible.coerceAtLeast(3), total)
    val start = (current - visible / 2).coerceIn(0, total - visible)
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(visible) { offset ->
            val target = start + offset
            val label = stringResource(R.string.module_page, target + 1, total)
            Box(Modifier.size(48.dp, 56.dp).testTag("page-dot-$target")
                .semantics { contentDescription = label }
                .selectable(selected = target == current, role = Role.Tab, onClick = { onPage(target) }),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (target == current) 18.dp else 6.dp, 6.dp)
                    .background(if (target == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
    }
}
