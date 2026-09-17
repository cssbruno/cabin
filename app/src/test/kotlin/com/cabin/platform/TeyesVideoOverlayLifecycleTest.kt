package com.cabin.platform

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.protocol.PhoneType
import com.cabin.usb.UsbDeviceWrapper
import com.cabin.util.AppExecutors
import com.cabin.video.H264Renderer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TeyesVideoOverlayLifecycleTest {
    private lateinit var manager: CabinManager
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface
    private lateinit var renderer: CountingRenderer
    private val executors = AppExecutors()

    private class CountingRenderer(surface: Surface, executors: AppExecutors) :
        H264Renderer(640, 480, surface, {}, executors, null) {
        var stops = 0
        var resumes = 0
        var feeds = 0

        override fun stop() {
            stops++
        }

        override fun resume(newSurface: Surface) {
            resumes++
        }

        override fun feedDirect(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): Boolean {
            feeds++
            return true
        }
    }

    private val callback =
        object : CabinManager.Callback {
            override fun onStateChanged(state: CabinManager.State) {}

            override fun onStatusTextChanged(text: String) {}

            override fun onHostUIPressed() {}
        }

    @Before
    fun setUp() {
        manager = CabinManager(ApplicationProvider.getApplicationContext<Context>())
        (field("shouldBeRunning") as AtomicBoolean).set(true)
        texture = SurfaceTexture(0)
        surface = Surface(texture)
        renderer = CountingRenderer(surface, executors)
        setField("h264Renderer", renderer)
        setField("videoSurface", surface)
        setField("currentPhoneType", PhoneType.CARPLAY)
    }

    @After
    fun tearDown() =
        runBlocking {
            manager.releaseAndWait()
            surface.release()
            texture.release()
            executors.shutdown()
        }

    @Test
    fun `cover pauses once and focus resume cannot bypass blocker`() {
        manager.setVideoOverlayCovered(true)
        manager.setVideoOverlayCovered(true)
        manager.resumeVideo()
        manager.startCodecIfDeferred()
        assertTrue(manager.isVideoOverlayCovered)
        assertEquals(1, renderer.stops)
        assertEquals(0, renderer.resumes)
        assertTrue(field("codecDeferred") as Boolean)
    }

    @Test
    fun `uncover restarts deferred CarPlay once without changing user intent`() {
        val requested = field("shouldBeRunning") as AtomicBoolean
        requested.set(true)
        manager.setVideoOverlayCovered(true)
        manager.setVideoOverlayCovered(false)
        manager.setVideoOverlayCovered(false)
        assertFalse(manager.isVideoOverlayCovered)
        assertFalse(field("codecDeferred") as Boolean)
        assertEquals(1, renderer.resumes)
        assertTrue(requested.get())
        assertEquals(CabinManager.State.DISCONNECTED, manager.state)
    }

    @Test
    fun `disposal clears blocker without resuming background video`() {
        manager.setVideoOverlayCovered(true)
        manager.setVideoOverlayCovered(false, resumeWhenUncovered = false)
        assertFalse(manager.isVideoOverlayCovered)
        assertEquals(0, renderer.resumes)
        assertTrue(field("videoPaused") as Boolean)
    }

    @Test
    fun `manual recovery is queued until visible and coalesces multiple taps`() {
        @Suppress("UNCHECKED_CAST")
        (field("currentState") as AtomicReference<CabinManager.State>).set(CabinManager.State.STREAMING)
        manager.setVideoOverlayCovered(true)
        assertEquals(CabinManager.VideoResetResult.QUEUED, manager.resetVideoDecoder())
        assertEquals(CabinManager.VideoResetResult.QUEUED, manager.resetVideoDecoder())
        manager.resumeVideo()
        assertTrue(manager.hasPendingUserVideoReset)
        assertEquals(0, renderer.resumes)
        manager.setVideoOverlayCovered(false)
        assertFalse(manager.hasPendingUserVideoReset)
        assertEquals(1, renderer.resumes)
        assertEquals(2, renderer.stops) // Overlay pause plus one coalesced manual reset.
    }

    @Test
    fun `unavailable video reports no reset instead of false success`() {
        assertEquals(CabinManager.VideoResetResult.UNAVAILABLE, manager.resetVideoDecoder())
        assertFalse(manager.hasPendingUserVideoReset)
    }

    @Test
    fun `stop cancels a queued manual video reset`() =
        runBlocking {
            @Suppress("UNCHECKED_CAST")
            (field("currentState") as AtomicReference<CabinManager.State>).set(CabinManager.State.STREAMING)
            manager.setVideoOverlayCovered(true)
            manager.resetVideoDecoder()
            assertTrue(manager.hasPendingUserVideoReset)
            manager.stopAndWait()
            assertFalse(manager.hasPendingUserVideoReset)
        }

    @Test
    fun `stop intent immediately rejects recovery before asynchronous teardown`() {
        @Suppress("UNCHECKED_CAST")
        (field("currentState") as AtomicReference<CabinManager.State>).set(CabinManager.State.STREAMING)
        manager.setVideoOverlayCovered(true)
        manager.resetVideoDecoder()
        manager.stop()
        assertEquals(CabinManager.VideoResetResult.UNAVAILABLE, manager.resetVideoDecoder())
        manager.setVideoOverlayCovered(false)
        assertFalse(manager.hasPendingUserVideoReset)
        assertEquals(0, renderer.resumes)
    }

    @Test
    fun `release intent immediately rejects recovery before asynchronous teardown`() {
        @Suppress("UNCHECKED_CAST")
        (field("currentState") as AtomicReference<CabinManager.State>).set(CabinManager.State.STREAMING)
        manager.setVideoOverlayCovered(true)
        manager.resetVideoDecoder()
        manager.release()
        assertEquals(CabinManager.VideoResetResult.UNAVAILABLE, manager.resetVideoDecoder())
        manager.setVideoOverlayCovered(false)
        assertFalse(manager.hasPendingUserVideoReset)
        assertEquals(0, renderer.resumes)
    }

    @Test
    fun `unknown phone stays deferred on overlay close`() {
        setField("currentPhoneType", null)
        manager.setVideoOverlayCovered(true)
        manager.setVideoOverlayCovered(false)
        assertTrue(field("codecDeferred") as Boolean)
        assertEquals(0, renderer.resumes)
    }

    @Test
    fun `Android Auto waits for stabilized surface then starts when uncovered`() {
        setField("currentPhoneType", PhoneType.ANDROID_AUTO)
        manager.setVideoOverlayCovered(true)
        manager.setVideoOverlayCovered(false)
        assertEquals(0, renderer.resumes)
        assertTrue(field("codecDeferred") as Boolean)

        manager.setVideoOverlayCovered(true)
        manager.initialize(surface, 640, 480, callback)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(field("surfaceReadyForDeferredCodec") as Boolean)
        assertEquals(0, renderer.resumes)
        assertTrue(field("videoPaused") as Boolean)

        manager.setVideoOverlayCovered(false)
        assertEquals(1, renderer.resumes)
        assertFalse(field("codecDeferred") as Boolean)
    }

    @Test
    fun `covered surface resize cannot resume an already initialized decoder`() {
        setField("codecDeferred", false)
        manager.setVideoOverlayCovered(true)
        manager.initialize(surface, 640, 480, callback)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(0, renderer.resumes)
        manager.setVideoOverlayCovered(false)
        assertEquals(1, renderer.resumes)
    }

    @Test
    fun `reattached UI receives streaming state without another USB transition`() {
        (field("currentState") as AtomicReference<CabinManager.State>).set(CabinManager.State.STREAMING)
        setField("currentStatusText", "Phone connected")
        var observedState = CabinManager.State.DISCONNECTED
        var observedStatus = ""
        var observedPhone: PhoneType? = null
        manager.initialize(surface, 640, 480, object : CabinManager.Callback {
            override fun onStateChanged(state: CabinManager.State) { observedState = state }
            override fun onStatusTextChanged(text: String) { observedStatus = text }
            override fun onHostUIPressed() {}
            override fun onPhoneTypeChanged(phoneType: PhoneType) { observedPhone = phoneType }
        })
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(CabinManager.State.STREAMING, observedState)
        assertEquals("Phone connected", observedStatus)
        assertEquals(PhoneType.CARPLAY, observedPhone)
    }

    @Test
    fun `covered video drains USB and parses header without feeding decoder`() {
        setField("codecDeferred", false)
        val method = CabinManager::class.java.getDeclaredMethod("createVideoProcessor").apply { isAccessible = true }
        val processor = method.invoke(manager) as UsbDeviceWrapper.VideoDataProcessor
        val frame = ByteArray(25).apply { this[8] = 5 }
        manager.setVideoOverlayCovered(true)
        assertTrue(processor.processVideoDirect(frame, frame.size, 0))
        assertEquals(1, field("videoOffScreen"))
        assertEquals(0, renderer.feeds)
        manager.setVideoOverlayCovered(false)
        assertTrue(processor.processVideoDirect(frame, frame.size, 0))
        assertEquals(1, renderer.feeds)
    }

    private fun setField(
        name: String,
        value: Any?,
    ) {
        CabinManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    private fun field(name: String): Any? = CabinManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
}
