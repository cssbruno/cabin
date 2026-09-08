package com.cabin.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R

@Composable
internal fun ProjectionFullscreenButton(fullscreen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(56.dp).testTag("projection-fullscreen")) {
        Box(Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.32f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                stringResource(if (fullscreen) R.string.projection_restore else R.string.main_fullscreen),
                Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.8f))
        }
    }
}
