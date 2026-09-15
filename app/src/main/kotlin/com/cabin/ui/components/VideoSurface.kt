package com.cabin.ui.components

import android.view.MotionEvent
import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** One delivery of teardown per attached surface, including Compose disposal. */
internal class VideoSurfaceLifecycle(private val destroyed: (Surface) -> Unit) {
    var surface: Surface? = null
        private set

    fun attach(surface: Surface) { this.surface = surface }
    fun destroy() {
        val previous = surface ?: return
        surface = null
        destroyed(previous)
    }
}

/** Keeps callbacks fresh and makes late teardown identify the surface it actually owned. */
@Composable
fun VideoSurface(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface, Int, Int) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null,
    onTouchEvent: ((MotionEvent) -> Boolean)? = null,
) {
    val available by rememberUpdatedState(onSurfaceAvailable)
    val destroyed by rememberUpdatedState(onSurfaceDestroyed)
    val resized by rememberUpdatedState(onSurfaceSizeChanged)
    val touched by rememberUpdatedState(onTouchEvent)
    val lifecycle = remember { VideoSurfaceLifecycle { destroyed(it) } }
    DisposableEffect(lifecycle) { onDispose { lifecycle.destroy() } }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        onRelease = { view ->
            view.callback = null
            lifecycle.destroy()
        },
        factory = { context ->
            VideoSurfaceView(context).apply {
                callback = object : VideoSurfaceView.Callback {
                    override fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
                        lifecycle.attach(surface)
                        available(surface, width, height)
                    }
                    override fun onSurfaceChanged(width: Int, height: Int) {
                        resized?.invoke(width, height)
                    }
                    override fun onSurfaceDestroyed() = lifecycle.destroy()
                    override fun onTouchEvent(event: MotionEvent): Boolean = touched?.invoke(event) ?: false
                }
            }
        },
    )
}

/**
 * Optional externally-driven state holder for a [VideoSurface].
 *
 * NOT automatically wired: the [VideoSurface] composable does NOT accept a state holder
 * parameter. Callers create an instance via [rememberVideoSurfaceState] and MUST manually
 * wire it by passing its methods as the composable's lambda params, e.g.:
 *   val state = rememberVideoSurfaceState()
 *   VideoSurface(
 *       onSurfaceAvailable = state::onSurfaceAvailable,
 *       onSurfaceDestroyed = state::onSurfaceDestroyed,
 *       onSurfaceSizeChanged = state::onSurfaceSizeChanged,
 *   )
 *
 * Live usage: MainScreen.kt:86. Reads like orphaned API but is intentionally decoupled —
 * callers that don't need observable surface state can ignore it entirely.
 *
 * WIRING REQUIREMENT: if you wire [onSurfaceAvailable] but leave [onSurfaceSizeChanged]
 * null on the composable, [width]/[height] here will stay at their creation values
 * (see VideoSurface's onSurfaceChanged silent-drop note).
 */
class VideoSurfaceState {
    var surface: Surface? by mutableStateOf(null)
        private set

    var width: Int by mutableIntStateOf(0)
        private set

    var height: Int by mutableIntStateOf(0)
        private set

    fun onSurfaceAvailable(
        surface: Surface,
        w: Int,
        h: Int,
    ) {
        this.surface = surface
        width = w
        height = h
    }

    fun onSurfaceDestroyed(expected: Surface): Boolean {
        if (surface !== expected) return false
        surface = null
        return true
    }

    fun onSurfaceSizeChanged(
        w: Int,
        h: Int,
    ) {
        width = w
        height = h
    }
}

/** Remember a [VideoSurfaceState] scoped to the current composition. See [VideoSurfaceState] for wiring. */
@Composable
fun rememberVideoSurfaceState(owner: Any? = null): VideoSurfaceState = remember(owner) { VideoSurfaceState() }
