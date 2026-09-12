package com.cabin.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.ui.components.VideoSurfaceLifecycle
import com.cabin.ui.components.VideoSurfaceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VideoSurfaceLifecycleTest {
    @Test fun `old view teardown cannot clear a replacement and each view destroys only once`() {
        val texture = SurfaceTexture(0)
        val old = Surface(texture)
        val next = Surface(texture)
        try {
            val state = VideoSurfaceState()
            var destroys = 0
            val lifecycle = VideoSurfaceLifecycle { destroys++; state.onSurfaceDestroyed(it) }
            lifecycle.attach(old)
            state.onSurfaceAvailable(old, 600, 1024)
            state.onSurfaceAvailable(next, 1024, 600)
            lifecycle.destroy()
            lifecycle.destroy()
            assertEquals(1, destroys)
            assertSame(next, state.surface)
            assertEquals(1024, state.width)
            assertTrue(state.onSurfaceDestroyed(next))
            assertNull(state.surface)
            lifecycle.attach(next)
            lifecycle.destroy()
            assertEquals(2, destroys)
        } finally { old.release(); next.release(); texture.release() }
    }

    @Test fun `late surface destruction preserves pending and active replacement`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = CabinManager(context)
        val texture = SurfaceTexture(0)
        val old = Surface(texture)
        val next = Surface(texture)
        try {
            ReflectionHelpers.setField(manager, "videoSurface", old)
            ReflectionHelpers.setField(manager, "pendingSurface", next)
            manager.onSurfaceDestroyed(old)
            assertSame(next, ReflectionHelpers.getField<Surface>(manager, "pendingSurface"))
            ReflectionHelpers.setField(manager, "videoSurface", next)
            ReflectionHelpers.setField(manager, "pendingSurface", null)
            manager.onSurfaceDestroyed(old)
            assertSame(next, ReflectionHelpers.getField<Surface>(manager, "videoSurface"))
            manager.onSurfaceDestroyed(next)
            assertNull(ReflectionHelpers.getField<Surface?>(manager, "videoSurface"))
            assertTrue(ReflectionHelpers.getField<Boolean>(manager, "videoPaused"))
        } finally { manager.releaseAndWait(); old.release(); next.release(); texture.release() }
    }
}
