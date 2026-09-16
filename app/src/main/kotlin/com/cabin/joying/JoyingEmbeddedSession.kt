package com.cabin.joying

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.view.MotionEvent
import android.view.Surface
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Direct native video/touch client. Does not launch or load code from the stock APK. */
internal class JoyingEmbeddedSession(
    private val context: android.content.Context,
    width: Int,
    height: Int,
    private val onStatus: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit,
    private val onFailure: (Boolean) -> Unit,
) : JoyingSessionRuntime {
    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)

    private fun fail(message: String, retryable: Boolean = true) {
        if (closed.get() || !failureReported.compareAndSet(false, true)) return
        onStatus(message)
        close()
        onFailure(retryable)
    }
    private val videoLock = Any()
    private var videoHasRendered = false // Guarded by videoLock, shared with native callbacks.
    private var outputSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private var parkingSurface: Surface? = null

    /** Detach only the surface being retired; an old Activity cannot detach its replacement. */
    override fun attach(surface: Surface) {
        synchronized(videoLock) {
            if (closed.get()) return
            decoder?.setOutputSurface(surface)
            outputSurface = surface
        }
        dispatch { if (listenerRegistered) command(JoyingNativeProtocol.SCREEN, intArrayOf(3)) }
    }
    override fun detach(surface: Surface) {
        synchronized(videoLock) {
            if (outputSurface !== surface) return
            parkingSurface?.let { decoder?.setOutputSurface(it) }
            outputSurface = null
        }
        dispatch { command(JoyingNativeProtocol.TOUCH, IntArray(6)) }
        pointerIds.fill(-1)
        touches.fill(0)
    }
    private val workers = Executors.newFixedThreadPool(3)
    private val frames = ArrayBlockingQueue<ByteArray>(8)
    private val controls = Executors.newSingleThreadExecutor()
    private var ownsSession = false
    private var listenerRegistered = false
    private var lastLinkState: Int? = null // Owned by controls executor.
    private val death = IBinder.DeathRecipient { fail("Carlink native service stopped.") }
    private val audio = JoyingAudioFocus(context) { play -> dispatch { command(216, intArrayOf(if (play) 1 else 0)) } }
    private val wireless = JoyingWireless(context, ::dispatch, ::command,
        { state -> binder?.let { JoyingNativeProtocol.bluetoothState(it, state) } },
        { bytes -> binder?.let { JoyingNativeProtocol.bluetoothBytes(it, bytes) } }, onStatus)
    private val factoryBluetooth = JoyingFactoryBluetooth(context, ::dispatch) { name, address ->
        command(226, intArrayOf(), listOf(name, address))
    }
    private val listener = JoyingNativeListener { event -> dispatch { handle(event) } }
    private var server: LocalServerSocket? = null
    private var socket: LocalSocket? = null
    private var binder: IBinder? = null
    private var engineConnection: com.cabin.carlink.CarlinkEngineConnection? = null
    private val pointerIds = intArrayOf(-1, -1)
    private val touches = IntArray(6)
    private val display = JoyingDisplayConfiguration.fromDisplay(width, height)
    @Volatile private var videoWidth = display.width
    @Volatile private var videoHeight = display.height

    companion object {
        private val sessionGate = java.util.concurrent.Semaphore(1)
        fun awaitReleased(): Boolean {
            if (!sessionGate.tryAcquire(5, TimeUnit.SECONDS)) return false
            sessionGate.release()
            return true
        }
    }

    private fun dispatch(work: () -> Unit) {
        if (closed.get()) return
        runCatching { controls.execute {
            if (!closed.get()) try { work() } catch (e: Exception) { com.cabin.telemetry.CabinTelemetry.log(com.cabin.logging.Logger.Level.ERROR, e); onStatus(e.cause?.message ?: e.message ?: "Carlink control failed") }
        } }
    }
    private fun command(code: Int, ints: IntArray = intArrayOf(), strings: List<String> = emptyList()) {
        JoyingNativeProtocol.command(checkNotNull(binder) { "Carlink session is not ready" }, code, ints, strings)
    }
    override fun connectPhone(address: String) = dispatch { wireless.connect(address) }
    override fun enableWireless() = dispatch { wireless.hotspot(true) }
    override fun siri() = dispatch { command(208, intArrayOf(1)); command(208, intArrayOf(0)) }

    private fun handle(event: JoyingNativeListener.Event) {
        when (event) {
            is JoyingNativeListener.Event.Bluetooth -> wireless.write(event.bytes)
            is JoyingNativeListener.Event.Bundle -> {
                val values = event.values
                when {
                    values.getString("_btcmd")?.startsWith("AT#SP") == true -> wireless.reconnect(factoryBluetooth.remoteAddress)
                    values.getString("_btcmd")?.startsWith("AT#SH") == true -> wireless.disconnectBluetooth()
                    values.getString("_btcmd")?.startsWith("AT#CD") == true -> factoryBluetooth.cutHandsFree()
                }
                if (values.containsKey("\$CarPlay.AudioState")) audio.updateAudio(values.getInt("\$CarPlay.AudioState"))
                if (values.containsKey("\$CarPlay.WifiApCtl")) when (values.getInt("\$CarPlay.WifiApCtl")) {
                    0 -> wireless.hotspot(false)
                    1 -> wireless.hotspot(true)
                }
            }
            is JoyingNativeListener.Event.State -> when (event.id) {
                107 -> audio.updateCall(event.value)
                100 -> updateLinkState(event.value)
            }
            JoyingNativeListener.Event.ShowUi -> command(JoyingNativeProtocol.SCREEN, intArrayOf(3))
            is JoyingNativeListener.Event.Message -> Unit
        }
    }

    private fun updateLinkState(value: Int) {
        com.cabin.reports.DebugJournal.record("CarPlay", "link_state", "state=$value")
        val changed = lastLinkState != value
        lastLinkState = value
        if (value == 0) {
            audio.reset()
            factoryBluetooth.restoreHandsFree()
            synchronized(videoLock) {
                videoHasRendered = false
                onStatus("Waiting for iPhone…")
            }
        } else if (changed && value in 1..4) {
            // Stock f.d publishes link changes and the CarplayView requests video
            // when it becomes visible. The startup request can precede phone readiness.
            command(JoyingNativeProtocol.SCREEN, intArrayOf(3))
            synchronized(videoLock) {
                if (!videoHasRendered) onStatus("Waiting for CarPlay video…")
            }
        }
    }

    override fun start() {
        com.cabin.reports.DebugJournal.record("CarPlay", "starting", "Starting Cabin’s Carlink engine")
        workers.execute {
            try {
                check(sessionGate.tryAcquire(5, TimeUnit.SECONDS)) { "Previous CarPlay session is still shutting down" }
                synchronized(this) {
                    ownsSession = true
                    if (closed.get()) { ownsSession = false; sessionGate.release(); return@execute }
                }
                val acquired = LocalServerSocket(JoyingNativeProtocol.VIDEO_SOCKET)
                synchronized(this) {
                    if (closed.get()) { acquired.close(); return@execute }
                    server = acquired
                    com.cabin.reports.DebugJournal.record("CarPlay", "socket_acquired", "Video connection ready")
                }
                controls.submit {
                    if (!closed.get()) {
                        val ownedEngine = com.cabin.carlink.CarlinkEngineConnection.open(context)
                        engineConnection = ownedEngine
                        val remote = ownedEngine.engine
                        binder = remote
                        if (closed.get()) return@submit
                        com.cabin.reports.DebugJournal.record("CarPlay", "native_engine_ready", "App-owned receiver")
                        remote.linkToDeath(death, 0)
                        JoyingNativeProtocol.registerListener(remote, listener)
                        listenerRegistered = true
                        com.cabin.reports.DebugJournal.record("CarPlay", "listener_registered", "")
                        factoryBluetooth.start()
                        // Screen geometry + physical reference width and stock FPS marker (c.m).
                        command(218, display.nativeValues())
                        command(223, intArrayOf(1)) // Wired auto-connect.
                        command(219) // Stock native startup request (c.m).
                        command(JoyingNativeProtocol.SCREEN, intArrayOf(3))
                        com.cabin.reports.DebugJournal.record("CarPlay", "video_requested", "")
                        updateLinkState(JoyingNativeProtocol.command(remote, JoyingNativeProtocol.LINK_STATE))
                    }
                }.get()
                if (closed.get()) return@execute
                onSize(videoWidth, videoHeight)
                workers.execute(::decode)
                while (!closed.get()) {
                    val accepted = server!!.accept()
                    com.cabin.reports.DebugJournal.record("CarPlay", "video_connected", "Native video stream accepted")
                    synchronized(videoLock) {
                        videoHasRendered = false
                        onStatus("Waiting for first video frame…")
                    }
                    synchronized(this) {
                        if (closed.get()) { accepted.close(); return@execute }
                        socket = accepted
                    }
                    // A new socket is a new H.264 stream; discard old reference pictures.
                    frames.put(ByteArray(0))
                    var firstFrame = true
                    try {
                        accepted.inputStream.buffered().use { input ->
                            while (!closed.get()) {
                                val frame = JoyingNativeProtocol.readFrame(input) ?: break
                                if (firstFrame) {
                                    firstFrame = false
                                    com.cabin.reports.DebugJournal.record("CarPlay", "first_frame_received", "bytes=${frame.size}")
                                    onStatus("Starting video…")
                                }
                                frames.put(frame)
                            }
                        }
                    } catch (e: java.io.IOException) {
                        if (!closed.get()) onStatus("Video link interrupted; waiting for Carlink to reconnect…")
                    } finally {
                        runCatching { accepted.close() }
                        synchronized(videoLock) { videoHasRendered = false }
                        synchronized(this) { if (socket === accepted) socket = null }
                    }
                    if (!closed.get()) onStatus("Waiting for Carlink video to reconnect…")
                }
            } catch (e: Exception) {
                com.cabin.reports.DebugJournal.record("CarPlay", "connection_failed", "$e; cause=${e.cause}")
                if (!closed.get()) com.cabin.telemetry.CabinTelemetry.log(com.cabin.logging.Logger.Level.ERROR, e)
                android.util.Log.e("CarlinkCarPlay", "Native CarPlay connection failed", e)
                fail(e.message ?: "Carlink connection failed", (e as? JoyingVideoConnection.ConnectionException)?.retryable ?: true)
            } finally {
                close()
            }
        }
    }

    private fun decode() {
        var codec: MediaCodec? = null
        var texture: android.graphics.SurfaceTexture? = null
        try {
            codec = MediaCodec.createDecoderByType("video/avc")
            val format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, JoyingNativeProtocol.MAX_FRAME)
            }
            texture = android.graphics.SurfaceTexture(false)
            synchronized(videoLock) {
                parkingSurface = Surface(texture)
                codec.configure(format, outputSurface ?: parkingSurface, null, 0)
                codec.start()
                decoder = codec
            }
            val info = MediaCodec.BufferInfo()
            var pending: ByteArray? = null
            while (!closed.get()) {
                if (pending == null) pending = frames.poll(5, TimeUnit.MILLISECONDS)
                synchronized(videoLock) {
                if (pending?.isEmpty() == true) {
                    codec.flush()
                    pending = null
                    videoHasRendered = false
                }
                if (pending != null) {
                    val index = codec.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        val frame = requireNotNull(pending)
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        check(buffer.capacity() >= frame.size) { "Carlink frame exceeds decoder capacity" }
                        buffer.clear()
                        buffer.put(frame)
                        codec.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000, 0)
                        pending = null
                    }
                }
                var output = codec.dequeueOutputBuffer(info, 1_000)
                while (output >= 0) {
                    codec.releaseOutputBuffer(output, outputSurface != null)
                    if (!videoHasRendered && outputSurface != null) {
                        videoHasRendered = true
                        onStatus("")
                        com.cabin.reports.DebugJournal.record("CarPlay", "video_rendering", "First frame displayed")
                    }
                    output = codec.dequeueOutputBuffer(info, 0)
                }
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    videoWidth = actual.getInteger(MediaFormat.KEY_WIDTH)
                    videoHeight = actual.getInteger(MediaFormat.KEY_HEIGHT)
                    onSize(videoWidth, videoHeight)
                }
                }
            }
        } catch (e: Exception) {
            com.cabin.reports.DebugJournal.record("CarPlay", "decoder_failed", "")
            if (!closed.get()) com.cabin.telemetry.CabinTelemetry.log(com.cabin.logging.Logger.Level.ERROR, e)
            fail(e.message ?: "Carlink video decoder failed")
        } finally {
            synchronized(videoLock) {
                decoder = null
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                parkingSurface?.release()
                parkingSurface = null
                texture?.release()
            }
        }
    }

    /** Two contacts, each encoded as x, y, pressed; pointer IDs are mapped to stable slots. */
    override fun touch(event: MotionEvent, width: Int, height: Int): Boolean {
        if (closed.get() || width <= 0 || height <= 0) return false
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_CANCEL) {
            touches[2] = 0; touches[5] = 0
            pointerIds.fill(-1)
        } else {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                val id = event.getPointerId(event.actionIndex)
                if (id !in pointerIds) {
                    val slot = pointerIds.indexOf(-1)
                    if (slot >= 0) pointerIds[slot] = id
                }
            }
            for (slot in 0..1) {
                val index = event.findPointerIndex(pointerIds[slot])
                if (index >= 0) {
                    touches[slot * 3] = (event.getX(index) * videoWidth / width).toInt().coerceIn(0, videoWidth - 1)
                    touches[slot * 3 + 1] = (event.getY(index) * videoHeight / height).toInt().coerceIn(0, videoHeight - 1)
                    val released = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && index == event.actionIndex
                    touches[slot * 3 + 2] = if (released) 0 else 1
                    if (released) pointerIds[slot] = -1
                }
            }
        }
        val snapshot = touches.copyOf()
        dispatch { command(JoyingNativeProtocol.TOUCH, snapshot) }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Unblock I/O immediately. All Binder cleanup remains on the serialized control worker.
        synchronized(this) {
            runCatching { socket?.close() }
        }
        workers.shutdownNow()
        controls.execute {
            try {
                factoryBluetooth.close()
                wireless.close()
                audio.close()
                val remote = binder
                if (remote != null && server != null) {
                    runCatching { JoyingNativeProtocol.command(remote, JoyingNativeProtocol.TOUCH, IntArray(6)) }
                    runCatching { JoyingNativeProtocol.command(remote, JoyingNativeProtocol.SCREEN, intArrayOf(2)) }
                    if (listenerRegistered) runCatching { JoyingNativeProtocol.registerListener(remote, null) }
                    runCatching { remote.unlinkToDeath(death, 0) }
                }
            } finally {
                runCatching { engineConnection?.close() }
                engineConnection = null
                synchronized(this) {
                    runCatching { server?.close() }
                    binder = null
                    if (ownsSession) { ownsSession = false; sessionGate.release() }
                }
            }
        }
        controls.shutdown()
    }
}
