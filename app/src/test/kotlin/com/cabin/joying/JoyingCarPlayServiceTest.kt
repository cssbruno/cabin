package com.cabin.joying

import android.content.Intent
import android.view.MotionEvent
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingCarPlayServiceTest {
    class Runtime(val status: (String) -> Unit, val notifyFailed: (Boolean) -> Unit) : JoyingSessionRuntime {
        fun failed(retryable: Boolean = true) = notifyFailed(retryable)
        var starts = 0
        var closes = 0
        var attached: Surface? = null
        var detaches = 0
        override fun start() { starts++ }
        override fun close() { closes++ }
        override fun attach(surface: Surface) { attached = surface }
        override fun detach(surface: Surface) { detaches++; attached = null }
        override fun touch(event: MotionEvent, width: Int, height: Int) = true
        override fun connectPhone(address: String) = Unit
        override fun enableWireless() = Unit
        override fun siri() = Unit
    }
    internal class TestService : JoyingCarPlayService() {
        val runtimes = mutableListOf<Runtime>()
        var exhaustionReports = 0
        override fun reportRecoveryExhausted() { exhaustionReports++ }
        override fun createSession(status: (String) -> Unit, size: (Int, Int) -> Unit, failed: (Boolean) -> Unit): JoyingSessionRuntime =
            Runtime(status, failed).also(runtimes::add)
    }
    private fun advance(seconds: Long) = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofSeconds(seconds))

    @Test fun `failure retries are bounded and manual retry resets the budget`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        for (delay in listOf(2L, 4L, 8L)) {
            val old = service.runtimes.last()
            old.failed()
            old.failed()
            advance(delay)
            assertEquals(1, old.closes)
        }
        assertEquals(4, service.runtimes.size)
        service.runtimes.last().failed()
        advance(60)
        assertEquals(4, service.runtimes.size)
        repeat(5) { service.runtimes.last().failed() }
        advance(0)
        assertEquals(1, service.exhaustionReports)
        service.restart()
        service.runtimes.last().failed()
        advance(2)
        assertEquals(6, service.runtimes.size)
        controller.destroy()
    }

    @Test fun `permanent socket failure preserves guidance until manual retry`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val runtime = service.runtimes.single()
        runtime.status("Video socket is busy; release stock Car Link")
        runtime.failed(false)
        advance(60)
        assertEquals(1, service.runtimes.size)
        assertEquals("Video socket is busy; release stock Car Link", service.state.value.status)
        assertEquals(0, service.exhaustionReports)
        service.restart()
        assertEquals(2, service.runtimes.size)
        controller.destroy()
    }

    @Test fun `disconnect cancels delayed and late recovery callbacks`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val old = service.runtimes.single()
        old.failed()
        advance(0)
        service.stopProjection()
        old.failed()
        advance(60)
        assertEquals(1, service.runtimes.size)
        assertEquals("CarPlay disconnected", service.state.value.status)
        controller.destroy()
    }

    @Test fun `system restart restores a session and explicit stop is not sticky`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(1, service.runtimes.size)
        assertEquals(android.app.Service.START_NOT_STICKY,
            service.onStartCommand(Intent().setAction(JoyingCarPlayService.STOP), 0, 2))
        controller.destroy()
    }

    @Test fun `unbinding the screen keeps a single started session alive`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        service.onBind(Intent())
        service.onUnbind(Intent())
        service.onStartCommand(Intent(), 0, 2)
        assertEquals(1, service.runtimes.size)
        assertEquals(1, service.runtimes.single().starts)
        assertEquals(0, service.runtimes.single().closes)
        controller.destroy()
        assertEquals(1, service.runtimes.single().closes)
    }
    @Test fun `retry retires old session and ignores its late callbacks`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val old = service.runtimes.single()
        service.onStartCommand(Intent().setAction(JoyingCarPlayService.RETRY), 0, 2)
        assertEquals(1, old.closes)
        service.runtimes.last().status("New connection")
        old.status("Stale failure")
        assertEquals("New connection", service.state.value.status)
        controller.destroy()
    }
    @Test fun `retiring screen cannot detach replacement screen`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val oldTexture = android.graphics.SurfaceTexture(0)
        val nextTexture = android.graphics.SurfaceTexture(0)
        val old = Surface(oldTexture)
        val next = Surface(nextTexture)
        try {
            service.attach(old)
            service.attach(next)
            service.detach(old)
            assertSame(next, service.runtimes.single().attached)
            assertEquals(0, service.runtimes.single().detaches)
            service.detach(next)
            assertNull(service.runtimes.single().attached)
            assertEquals(0, service.runtimes.single().closes)
        } finally { controller.destroy(); old.release(); next.release(); oldTexture.release(); nextTexture.release() }
    }
    @Test fun `notification disconnect closes session without recreating it`() {
        val controller = Robolectric.buildService(TestService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        service.onStartCommand(Intent().setAction(JoyingCarPlayService.STOP), 0, 2)
        assertEquals(1, service.runtimes.single().closes)
        assertEquals("CarPlay disconnected", service.state.value.status)
        controller.destroy()
        assertEquals(1, service.runtimes.single().closes)
    }
}
