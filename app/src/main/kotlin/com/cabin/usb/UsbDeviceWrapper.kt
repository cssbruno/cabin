package com.cabin.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.cabin.logging.Logger
import com.cabin.logging.logDebug
import com.cabin.logging.logWarn
import com.cabin.protocol.KnownDevices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.carlink.USB_PERMISSION"
private const val MAX_PAYLOAD_SIZE = 8 * 1024 * 1024 // Covers high-resolution IDRs while bounding corrupt headers.
// Some Android USB host drivers expose a valid USB zero-length packet as bulkTransfer()==0.
// Tolerate a short bounded run, but never spin forever on a broken endpoint.
private const val MAX_CONSECUTIVE_ZERO_READS = 8
// 15s chosen with headroom over the adapter's ~10s heartbeat window (see
// documents/reference/heartbeat_analysis.md). If no data arrives at all inside this window
// after loop start, the USB IN endpoint is assumed dead. Live validation 2026-04-20:
// timeout fired correctly at 07:29:26 after ~70s of post-handshake silence
// (logcat_20260420_063729_cabin.txt:15735); zero spurious 15s timeouts across 6 sessions.
private const val INITIAL_RESPONSE_TIMEOUT_MS = 15_000L

/**
 * USB Device Wrapper for Carlinkit Adapter Communication.
 *
 * Provides a high-level interface for USB device operations including:
 * - Device discovery and permission handling
 * - Connection lifecycle management
 * - Bulk transfer operations for data exchange
 * - Interface claiming and endpoint configuration
 *
 * Thread model: the reading loop runs on one dedicated "USB-ReadLoop" daemon thread.
 * Writes are serialized by AdapterDriver before they reach [write].
 *
 * Framing invariants:
 * - Every bulk read requests exactly the bytes still needed for the current header or
 *   payload. USB short reads are accumulated instead of discarded.
 * - A corrupt header or a timed-out partial frame is fatal to the current stream and
 *   triggers the manager's reconnect path. Continuing from an unknown byte offset would
 *   silently corrupt every following message.
 * - close() ↔ in-flight bulkTransfer race: close() calls stopReadingLoop().join(1000);
 *   if the read thread is blocked in bulkTransfer, join times out and teardown
 *   proceeds while the call is still mid-flight. Android unsticks pending transfers
 *   by returning -1 on endpoint close, so it survives.
 */
class UsbDeviceWrapper(
    private val context: Context,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val logCallback: (String) -> Unit,
) {
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private val _isOpened = AtomicBoolean(false)
    private val _isReadingLoopActive = AtomicBoolean(false)

    // @Volatile guards visibility only. Read/write happens from startReadingLoop
    // (creator) and stopReadingLoop/close (joiner + nulling); mutual exclusion comes
    // from the CAS on _isReadingLoopActive in stopReadingLoop, not from volatility.
    @Volatile private var readLoopThread: Thread? = null

    val isOpened: Boolean get() = _isOpened.get()
    val isReadingLoopActive: Boolean get() = _isReadingLoopActive.get()

    val vendorId: Int get() = device.vendorId
    val productId: Int get() = device.productId
    val deviceName: String get() = device.deviceName

    // Performance tracking — writes are serialized by AdapterDriver, while reads occur
    // on USB-ReadLoop. Atomics keep diagnostics safe across those two directions.
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val sendCount = AtomicInteger(0)
    private val receiveCount = AtomicInteger(0)
    private val sendErrors = AtomicInteger(0)
    private val receiveErrors = AtomicInteger(0)

    /**
     * Check if we have permission to access the USB device.
     */
    fun hasPermission(): Boolean = usbManager.hasPermission(device)

    /**
     * Request USB permission from the user.
     * This will show a system dialog asking the user to grant permission.
     *
     * @param timeoutMs Timeout in milliseconds to wait for user response
     * @return true if permission was granted, false if denied or timeout
     */
    suspend fun requestPermission(timeoutMs: Long = 30_000L): Boolean {
        if (usbManager.hasPermission(device)) {
            log("Permission already granted for ${device.deviceName}")
            return true
        }

        log("Requesting USB permission for ${device.deviceName}...")
        val permissionAction = "$ACTION_USB_PERMISSION.${device.deviceId}"

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            ctx: Context,
                            intent: Intent,
                        ) {
                            if (permissionAction == intent.action) {
                                val resultDevice =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                                    }
                                if (resultDevice?.deviceName != device.deviceName) return
                                try {
                                    context.unregisterReceiver(this)
                                } catch (_: IllegalArgumentException) {
                                    // Already unregistered
                                }

                                val granted =
                                    intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false,
                                    )
                                log("USB permission ${if (granted) "granted" else "denied"}")

                                if (continuation.isActive) {
                                    continuation.resume(granted)
                                }
                            }
                        }
                    }

                // Register receiver using ContextCompat for API compatibility
                // RECEIVER_NOT_EXPORTED ensures only this app can send permission broadcasts
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(permissionAction),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )

                // Create pending intent with explicit Intent (package set) for Android 12+ security.
                // FLAG_MUTABLE (API 31+) is required because UsbManager injects EXTRA_DEVICE /
                // EXTRA_PERMISSION_GRANTED into the intent at send time. Pre-31 PendingIntents are
                // mutable by default, so the flag is omitted there (it does not exist below API 31).
                val mutableFlag =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        device.deviceId,
                        Intent(permissionAction).apply { setPackage(context.packageName) },
                        mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                usbManager.requestPermission(device, pendingIntent)

                // Cleanup on cancellation
                continuation.invokeOnCancellation {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (_: IllegalArgumentException) {
                        // Already unregistered
                    }
                }
            }
        } ?: run {
            log("USB permission request timed out")
            false
        }
    }

    /**
     * Open the USB device and claim the interface.
     *
     * @return true if device was opened successfully
     */
    fun open(): Boolean {
        if (_isOpened.get()) {
            log("Device already opened")
            return true
        }

        // Check permission
        if (!usbManager.hasPermission(device)) {
            log("No permission for device ${device.deviceName}")
            return false
        }

        // Open connection
        connection = usbManager.openDevice(device)
        if (connection == null) {
            log("Failed to open device connection")
            return false
        }

        // Find and claim the bulk transfer interface
        if (!claimBulkInterface()) {
            connection?.close()
            connection = null
            return false
        }

        _isOpened.set(true)
        log("Device opened: VID=0x${vendorId.toString(16)} PID=0x${productId.toString(16)}")
        return true
    }

    /**
     * Request permission if needed and open the USB device.
     * This is a convenience suspend function that combines requestPermission() and open().
     *
     * @return true if device was opened successfully
     */
    suspend fun openWithPermission(): Boolean {
        if (!hasPermission()) {
            if (!requestPermission()) {
                return false
            }
        }
        return open()
    }

    /**
     * Close the USB device and release resources.
     */
    fun close() {
        stopReadingLoop()

        claimedInterface?.let { iface ->
            try {
                connection?.releaseInterface(iface)
            } catch (e: Exception) {
                log("Error releasing interface: ${e.message}")
            }
        }
        claimedInterface = null
        inEndpoint = null
        outEndpoint = null

        connection?.close()
        connection = null
        _isOpened.set(false)

        log(
            "Device closed (sent: ${sendCount.get()}/${bytesSent.get()} bytes, " +
                "received: ${receiveCount.get()}/${bytesReceived.get()} bytes)",
        )
    }

    /**
     * Write data to the USB device.
     *
     * @param data Data to send
     * @param timeout Timeout in milliseconds
     * @return Number of bytes actually sent, or -1 on error
     */
    fun write(
        data: ByteArray,
        timeout: Int = 1000,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot write: device not open")
                return -1
            }

        val endpoint =
            outEndpoint ?: run {
                log("Cannot write: no OUT endpoint")
                return -1
            }

        return try {
            var totalWritten = 0
            while (totalWritten < data.size) {
                val result =
                    conn.bulkTransfer(
                        endpoint,
                        data,
                        totalWritten,
                        data.size - totalWritten,
                        timeout,
                    )
                if (result <= 0) {
                    sendErrors.incrementAndGet()
                    return if (totalWritten > 0) totalWritten else result
                }
                totalWritten += result
                bytesSent.addAndGet(result.toLong())
                sendCount.incrementAndGet()
            }
            totalWritten
        } catch (e: Exception) {
            sendErrors.incrementAndGet()
            log("Write error: ${e.message}")
            -1
        }
    }

    /**
     * Read data from the USB device.
     *
     * @param buffer Buffer to receive data
     * @param timeout Timeout in milliseconds
     * @return Number of bytes read, or -1 on error/timeout
     */
    fun read(
        buffer: ByteArray,
        timeout: Int = 1000,
    ): Int = readInto(buffer, 0, buffer.size, timeout)

    /** Read at most [length] bytes into a specific buffer region. */
    private fun readInto(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int,
    ): Int {
        val conn =
            connection ?: run {
                log("Cannot read: device not open")
                return -1
            }

        val endpoint =
            inEndpoint ?: run {
                log("Cannot read: no IN endpoint")
                return -1
            }

        return try {
            val result = conn.bulkTransfer(endpoint, buffer, offset, length, timeout)
            if (result >= 0) {
                bytesReceived.addAndGet(result.toLong())
                receiveCount.incrementAndGet()
            } else if (result != -1) {
                // -1 is timeout, not an error
                receiveErrors.incrementAndGet()
            }
            result
        } catch (e: Exception) {
            receiveErrors.incrementAndGet()
            log("Read error: ${e.message}")
            -1
        }
    }

    /**
     * Callback interface for direct video data processing.
     * [DIRECT_HANDOFF]: Data is already read into a buffer by the read loop.
     * Processor receives the buffer directly — no callback, no copy.
     */
    interface VideoDataProcessor {
        /**
         * Process video data directly. Data is valid only for duration of this call.
         *
         * @param data Buffer containing video payload (including 20-byte video header)
         * @param dataLength Actual bytes read into data
         * @param sourcePtsMs Source presentation timestamp in milliseconds from video header
         */
        fun processVideoDirect(
            data: ByteArray,
            dataLength: Int,
            sourcePtsMs: Int,
        ): Boolean
    }

    /**
     * Callback interface for reading loop events.
     */
    interface ReadingLoopCallback {
        fun onMessage(
            type: Int,
            data: ByteArray?,
            dataLength: Int,
        )

        fun onError(error: String)
    }

    /**
     * Start the continuous reading loop.
     *
     * @param callback Callback for received messages
     * @param timeout Read timeout in milliseconds
     * @param videoProcessor Optional processor for direct video data handling (bypasses message parsing)
     */
    fun startReadingLoop(
        callback: ReadingLoopCallback,
        timeout: Int = 30000,
        videoProcessor: VideoDataProcessor? = null,
    ) {
        if (_isReadingLoopActive.getAndSet(true)) {
            log("Reading loop already active")
            return
        }

        Thread {
            // Elevate thread priority: USB read feeds BOTH audio and video pipelines.
            // Must be >= video codec priority to prevent audio underruns from data starvation.
            // Using -10 (between URGENT_DISPLAY=-8 and URGENT_AUDIO=-19) to match
            // MediaCodec_loop priority (see documents/reference/gminfo/projection/cpc200_integration.md:110).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY - 2)

            log("Reading loop started")
            val headerBuffer = ByteArray(16)

            // Pre-allocate video buffer to avoid per-frame allocation (reduces GC pressure at 60fps)
            // Initial size 256KB covers most frames; grows if needed (rare for 1080p H.264).
            // Growth is MONOTONIC (never shrinks); bounded by MAX_PAYLOAD_SIZE.
            var videoBuffer = ByteArray(256 * 1024)

            // Pre-allocate audio buffer to avoid per-packet allocation (~17 packets/sec).
            // Initial size 16KB covers typical 11532-byte audio payloads; grows if needed
            // (monotonic, bounded by MAX_PAYLOAD_SIZE).
            // REUSE CONTRACT: when AUDIO_DATA is delivered via callback.onMessage, the
            // returned payload is this same audioBuffer. Downstream consumers MUST copy
            // or fully consume bytes before the next AUDIO_DATA arrives on this thread.
            var audioBuffer = ByteArray(16 * 1024)

            // Timeout detection: two separate checks —
            // 1. Initial response: adapter MUST respond to Open within INITIAL_RESPONSE_TIMEOUT_MS.
            //    If no data arrives at all, the USB IN endpoint is dead (adapter can't write).
            // 2. Mid-session silence: if data was flowing and stops, adapter disconnected/stalled.
            var hasReceivedData = false
            var consecutiveTimeouts = 0
            val maxConsecutiveTimeouts = 2 // 2 × 30s timeout = 60s of mid-session silence
            val initialResponseDeadline = System.currentTimeMillis() + INITIAL_RESPONSE_TIMEOUT_MS

            try {
                readLoop@ while (_isReadingLoopActive.get() && _isOpened.get()) {
                    // A bulk endpoint may legally return fewer bytes than requested. Accumulate
                    // exactly one protocol header; never throw away a short header fragment.
                    var headerBytes = 0
                    var headerZeroReads = 0
                    while (headerBytes < headerBuffer.size && _isReadingLoopActive.get()) {
                        val headerResult =
                            readInto(
                                headerBuffer,
                                headerBytes,
                                headerBuffer.size - headerBytes,
                                if (hasReceivedData) timeout else minOf(timeout, INITIAL_RESPONSE_TIMEOUT_MS.toInt()),
                            )
                        if (headerResult > 0) {
                            headerBytes += headerResult
                            headerZeroReads = 0
                            continue
                        }
                        if (headerResult == 0 && headerZeroReads++ < MAX_CONSECUTIVE_ZERO_READS) {
                            continue
                        }
                        if (_isReadingLoopActive.get()) {
                            if (headerResult == -1 && headerBytes == 0) {
                                if (!hasReceivedData) {
                                    // No data ever received — check initial response deadline
                                    if (System.currentTimeMillis() >= initialResponseDeadline) {
                                        log(
                                            "Adapter not responding: no data received within " +
                                                "${INITIAL_RESPONSE_TIMEOUT_MS / 1000}s of connection — " +
                                                "USB IN endpoint may be dead",
                                        )
                                        callback.onError("USB read timeout — no initial response from adapter")
                                        break@readLoop
                                    }
                                } else {
                                    // Was receiving data, now silent — adapter disconnected?
                                    consecutiveTimeouts++
                                    if (consecutiveTimeouts >= maxConsecutiveTimeouts) {
                                        log(
                                            "Adapter silent: $consecutiveTimeouts consecutive timeouts " +
                                                "(${consecutiveTimeouts * timeout / 1000}s) after data was flowing",
                                        )
                                        callback.onError("USB read timeout — adapter not responding")
                                        break@readLoop
                                    }
                                }
                                continue@readLoop
                            }
                            val reason =
                                if (headerBytes > 0) {
                                    "USB partial header timed out at $headerBytes/${headerBuffer.size} bytes"
                                } else {
                                    "USB header read failed: result=$headerResult"
                                }
                            log(reason)
                            callback.onError(reason)
                        }
                        break@readLoop
                    }

                    if (headerBytes != headerBuffer.size) break@readLoop

                    // Successful header read — reset timeout counter
                    consecutiveTimeouts = 0
                    hasReceivedData = true

                    // A corrupt header leaves the byte-stream boundary unknown. Reconnect
                    // immediately instead of repeatedly parsing arbitrary 16-byte windows.
                    val header =
                        try {
                            com.cabin.protocol.MessageParser
                                .parseHeader(headerBuffer)
                        } catch (e: com.cabin.protocol.HeaderParseException) {
                            val hex = headerBuffer.take(16).joinToString(" ") { "%02X".format(it) }
                            logWarn(
                                "Header parse error: ${e.message} raw=[$hex]",
                                tag = com.cabin.logging.Logger.Tags.PROTO_UNKNOWN,
                            )
                            callback.onError("USB protocol header corrupt — reconnecting")
                            break@readLoop
                        }

                    // Reject corrupted headers with implausible payload sizes.
                    if (header.length < 0 || header.length > MAX_PAYLOAD_SIZE) {
                        val hex = headerBuffer.take(16).joinToString(" ") { "%02X".format(it) }
                        logWarn(
                            "Corrupted header: invalid length=${header.length} raw=[$hex]",
                            tag = com.cabin.logging.Logger.Tags.PROTO_UNKNOWN,
                        )
                        callback.onError("USB payload length corrupt — reconnecting")
                        break@readLoop
                    }

                    // Demux split for 0x06 (VIDEO_DATA) and 0x2C (NAVI_VIDEO_DATA / AltVideo):
                    //   0x06 → main IHU video pipeline (videoProcessor → H264Renderer → Surface).
                    //   0x2C → ClusterHomeDisplay forwarder (com.carlink.ipc.NaviVideoForwarder),
                    //          which strips the 20B video header and broadcasts H.264 Annex-B
                    //          payloads to bound consumer sinks over AIDL.
                    //
                    // Both paths share the chunked USB read into videoBuffer + the source-PTS
                    // extraction below; dispatch happens after totalRead == header.length.
                    //
                    // Previously both types OR'd into the same videoProcessor call — a latent
                    // bug: a 0x2C frame would stomp the main-video MediaCodec with nav-resolution
                    // SPS/PPS and stall the decoder. The split fixes that.
                    //
                    // 0x2C is only accepted when NaviVideoSingleton.enabled is true (debug build
                    // on AAOS emulator); on prod/non-emulator the adapter doesn't emit 0x2C
                    // anyway because MessageSerializer skips naviScreenInfo BoxSettings, and any
                    // unexpected 0x2C is drained via the non-video branch below.
                    //
                    // Gated on header.length > 0: zero-length video headers fall through to
                    // the non-video branch below where payload ends up null and callback.onMessage
                    // fires with dataLength=0 — this is the VideoStreamingSignal sentinel path
                    // consumed by MessageParser.kt (see the VideoStreamingSignal object for
                    // the synthetic-message contract).
                    val isMainVideo =
                        header.type == com.cabin.protocol.MessageType.VIDEO_DATA &&
                            videoProcessor != null
                    val isNaviVideo =
                        header.type == com.cabin.protocol.MessageType.NAVI_VIDEO_DATA &&
                            com.carlink.ipc.NaviVideoSingleton.enabled
                    if ((isMainVideo || isNaviVideo) && header.length > 0) {
                        if (connection != null && inEndpoint != null) {
                            try {
                                // Reuse pre-allocated buffer; grow only if needed (rare)
                                if (videoBuffer.size < header.length) {
                                    videoBuffer = ByteArray(maxOf(header.length, videoBuffer.size * 2))
                                }
                                var totalRead = 0
                                var readAttempts = 0
                                var lastChunkResult = 0
                                var consecutiveZeroReads = 0

                                while (totalRead < header.length && _isReadingLoopActive.get()) {
                                    val remaining = header.length - totalRead
                                    val chunkSize = minOf(remaining, 16384)
                                    readAttempts++
                                    val chunkRead = readInto(videoBuffer, totalRead, chunkSize, timeout)
                                    lastChunkResult = chunkRead
                                    if (chunkRead > 0) {
                                        totalRead += chunkRead
                                        consecutiveZeroReads = 0
                                    } else if (chunkRead == 0 &&
                                        consecutiveZeroReads++ < MAX_CONSECUTIVE_ZERO_READS
                                    ) {
                                        continue
                                    } else {
                                        logDebug(
                                            "[VIDEO_READ] Read failed: attempts=$readAttempts, " +
                                                "got=$totalRead/${header.length}, lastResult=$chunkRead",
                                            tag = Logger.Tags.VIDEO_USB,
                                        )
                                        break
                                    }
                                }

                                // Extract source PTS from video header (offset 12) if we have enough data
                                val sourcePts =
                                    if (totalRead >= 16) {
                                        extractPtsFromHeader(videoBuffer)
                                    } else {
                                        logDebug(
                                            "[VIDEO_READ] Incomplete read for PTS: got=$totalRead bytes, need>=16",
                                            tag = Logger.Tags.VIDEO_USB,
                                        )
                                        0
                                    }

                                if (totalRead == header.length) {
                                    if (isMainVideo) {
                                        val accepted = videoProcessor.processVideoDirect(videoBuffer, totalRead, sourcePts)
                                        if (accepted) {
                                            callback.onMessage(header.type.id, null, totalRead)
                                        }
                                    } else {
                                        // isNaviVideo — gate already enforced upstream.
                                        // Forwarder copies the Annex-B payload out before AIDL
                                        // broadcast, so the shared videoBuffer is safe to reuse
                                        // for the next read immediately.
                                        com.carlink.ipc.NaviVideoSingleton.forwarder
                                            .onUsbFrame(videoBuffer, totalRead)
                                    }
                                } else {
                                    if (!_isReadingLoopActive.get() || !_isOpened.get()) {
                                        break@readLoop
                                    }
                                    val reason =
                                        "USB partial video frame: got=$totalRead/${header.length} " +
                                            "attempts=$readAttempts result=$lastChunkResult"
                                    logWarn(reason, tag = Logger.Tags.VIDEO_USB)
                                    callback.onError("$reason — reconnecting")
                                    break@readLoop
                                }

                            } catch (e: Exception) {
                                log("Video processing error (non-fatal): ${e.message}")
                                receiveErrors.incrementAndGet()
                            }
                        }
                        continue
                    }

                    // Read exactly this payload. Limiting every transfer to the remaining byte
                    // count prevents consuming the next message's header in the same transaction.
                    // For AUDIO_DATA, reuse the pre-allocated buffer to avoid packet churn.
                    val isAudio = header.type == com.cabin.protocol.MessageType.AUDIO_DATA
                    if (header.type == com.cabin.protocol.MessageType.VIDEO_DATA && header.length == 0) {
                        logDebug("[VIDEO_READ] Ignoring empty video message", tag = Logger.Tags.VIDEO_USB)
                        continue
                    }
                    var dataLength = 0
                    val payload: ByteArray? =
                        if (header.length > 0) {
                            val payloadBuffer =
                                if (isAudio) {
                                    // Grow audioBuffer if needed (doubling strategy)
                                    if (header.length > audioBuffer.size) {
                                        audioBuffer = ByteArray(maxOf(header.length, audioBuffer.size * 2))
                                    }
                                    audioBuffer
                                } else {
                                    ByteArray(header.length)
                                }
                            var totalRead = 0
                            var consecutiveZeroReads = 0
                            while (totalRead < header.length && _isReadingLoopActive.get()) {
                                val chunkRead =
                                    readInto(
                                        payloadBuffer,
                                        totalRead,
                                        minOf(header.length - totalRead, 16384),
                                        timeout,
                                    )
                                if (chunkRead > 0) {
                                    totalRead += chunkRead
                                    consecutiveZeroReads = 0
                                } else if (chunkRead == 0 &&
                                    consecutiveZeroReads++ < MAX_CONSECUTIVE_ZERO_READS
                                ) {
                                    continue
                                } else {
                                    break
                                }
                            }
                            if (totalRead == header.length) {
                                dataLength = totalRead
                                payloadBuffer
                            } else {
                                if (!_isReadingLoopActive.get() || !_isOpened.get()) {
                                    break@readLoop
                                }
                                logWarn(
                                    "[USB_PARTIAL] Incomplete payload for type=0x${header.type.id.toString(16)}: " +
                                        "got=$totalRead/${header.length}B — reconnecting",
                                    tag = com.cabin.logging.Logger.Tags.PROTO_UNKNOWN,
                                )
                                callback.onError("USB partial payload — reconnecting")
                                break@readLoop
                            }
                        } else {
                            null
                        }

                    // Deliver message to callback
                    try {
                        callback.onMessage(header.type.id, payload, dataLength)
                    } catch (e: Exception) {
                        log("Message callback error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (_isReadingLoopActive.get()) {
                    log("Reading loop error: ${e.message}")
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                _isReadingLoopActive.set(false)
                log("Reading loop stopped")
            }
        }.apply {
            name = "USB-ReadLoop"
            isDaemon = true
            start()
        }.also { readLoopThread = it }
    }

    /**
     * Stop the reading loop.
     *
     * Best-effort: if the read thread is blocked inside UsbDeviceConnection.bulkTransfer
     * at the moment this is called, the join(1000) will time out silently and
     * readLoopThread is nulled anyway. Subsequent close() then releases the interface
     * and closes the connection, which Android uses as the signal to unstick the
     * pending transfer (returns -1). The read-loop try/catch absorbs the fallout and
     * flips _isReadingLoopActive=false in its finally block. Net: the loop exits
     * cleanly, but there is a brief window where a closing connection is still in
     * use by the reader thread.
     */
    fun stopReadingLoop() {
        if (!_isReadingLoopActive.getAndSet(false)) return
        try {
            val thread = readLoopThread
            // Error callbacks run on USB-ReadLoop and may synchronously tear down the
            // manager. A thread must never wait for itself to terminate.
            if (thread != null && thread !== Thread.currentThread()) {
                thread.join(1000)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readLoopThread = null
    }

    // ==================== Private Methods ====================

    // First-match semantics: claims the first interface that exposes BOTH a bulk-IN
    // and bulk-OUT endpoint. If the adapter ever exposes multiple such interfaces,
    // the code claims the lowest-index one and ignores the rest. Known devices (per
    // KnownDevices in MessageTypes.kt) present exactly one matching interface, so
    // this is safe today.
    private fun claimBulkInterface(): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)

            // Look for bulk endpoints
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                        inEp = endpoint
                    } else {
                        outEp = endpoint
                    }
                }
            }

            // If we found both endpoints, claim this interface
            if (inEp != null && outEp != null) {
                val claimed = connection?.claimInterface(iface, true) ?: false
                if (claimed) {
                    claimedInterface = iface
                    inEndpoint = inEp
                    outEndpoint = outEp
                    log("Claimed interface $i: IN=${inEp.address.toString(16)} OUT=${outEp.address.toString(16)}")
                    return true
                }
            }
        }

        log("No suitable bulk interface found")
        return false
    }

    private fun log(message: String) {
        logCallback("[USB] $message")
    }

    companion object {
        /**
         * Find all connected Carlinkit devices.
         */
        fun findDevices(usbManager: UsbManager): List<UsbDevice> =
            usbManager.deviceList.values.filter { device ->
                KnownDevices.isKnownDevice(device.vendorId, device.productId)
            }

        /**
         * Create a wrapper for the first available Carlinkit device.
         */
        fun findFirst(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): UsbDeviceWrapper? {
            val device = findDevices(usbManager).firstOrNull() ?: return null
            return UsbDeviceWrapper(context, usbManager, device, logCallback)
        }

        /** Create wrappers for every attached adapter so callers can try each one. */
        fun findAll(
            context: Context,
            usbManager: UsbManager,
            logCallback: (String) -> Unit,
        ): List<UsbDeviceWrapper> =
            findDevices(usbManager)
                .sortedBy { it.deviceName }
                .map { UsbDeviceWrapper(context, usbManager, it, logCallback) }

        /**
         * Extract source PTS from video header buffer.
         *
         * PTS sits at absolute byte 0x1C (=28) in the full USB frame per
         * documents/reference/.../video_protocol.md. Because [buffer] in the
         * reading-loop call site holds PAYLOAD ONLY (the 16-byte USB header
         * already consumed), the effective offset is 28 - 16 = 12, little-endian Int.
         *
         * The [offset] parameter supports reading the PTS from elsewhere in a larger
         * buffer (not used today; kept for future reuse with buffers that include
         * the 16-byte header).
         */
        fun extractPtsFromHeader(
            buffer: ByteArray,
            offset: Int = 0,
        ): Int {
            if (buffer.size < offset + 16) return 0
            return (buffer[offset + 12].toInt() and 0xFF) or
                ((buffer[offset + 13].toInt() and 0xFF) shl 8) or
                ((buffer[offset + 14].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 15].toInt() and 0xFF) shl 24)
        }
    }
}
