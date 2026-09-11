package com.cabin.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cabin.R

internal class ClimateCloseTimer {
    val remaining = Animatable(1f)
    var interaction by mutableIntStateOf(0)
    var pressed by mutableStateOf(false)
    val touchModifier = Modifier.pointerInput(this) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                interaction++
                pressed = event.changes.any { it.pressed }
                // Observe before child buttons/sliders without consuming their events.
            }
        }
    }
}

@Composable
internal fun rememberClimateCloseTimer(onClose: (() -> Unit)?): ClimateCloseTimer {
    val timer = remember { ClimateCloseTimer() }
    val close by rememberUpdatedState(onClose)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(timer.interaction, timer.pressed, resumed, onClose != null) {
        timer.remaining.snapTo(1f)
        if (resumed && !timer.pressed && onClose != null) {
            timer.remaining.animateTo(0f, tween(10_000, easing = LinearEasing))
            close?.invoke()
        }
    }
    return timer
}

@Composable
internal fun ClimateCloseButton(onClose: () -> Unit, progress: Float? = null) {
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        FilledTonalIconButton(onClose, Modifier.size(56.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.climate_close))
        }
        if (progress != null) CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(64.dp).testTag("climate-close-countdown"),
            strokeWidth = 3.dp,
        )
    }
}
