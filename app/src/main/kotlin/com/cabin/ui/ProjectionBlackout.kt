package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Keeps one stable content slot, hiding covered actions from accessibility while blacked out. */
@Composable
internal fun ProjectionVisibilityLayers(
    blanked: Boolean,
    onWake: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (blanked) Modifier.clearAndSetSemantics {} else Modifier), content = content)
        if (blanked) ProjectionBlackout(onWake)
    }
}

/** Covers, but never unmounts, the video surface. No USB, audio, brightness or decoder operations. */
@Composable
internal fun ProjectionBlackout(
    onWake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxSize().background(Color.Black)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.projection_wake), onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.projection_wake_hint), color = Color.Gray)
    }
}
