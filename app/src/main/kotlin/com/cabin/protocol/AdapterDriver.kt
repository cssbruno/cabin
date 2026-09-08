package com.cabin.protocol

import com.cabin.protocol.MultiTouchAction
import com.cabin.usb.UsbDeviceWrapper
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * CPC200-CCPA Adapter Protocol Driver.
 *
 * Manages the protocol-level communication with the Carlinkit adapter:
 *  - Initialization sequence (three modes; see [start]).
 *  - Heartbeat keepalive — 2 Hz, started BEFORE init because the firmware watchdog
 *    expects heartbeats within ~10 s of USB open or it resets the endpoint (see
 *    documents/reference/heartbeat_analysis.md).
 *  - Message sending (fan-out through a single [send] gate) and receiving (USB read
 *    thread → [messageHandler]).
 *  - Performance counters for diagnostic [logPerformanceStats] dumps on stop.
 *
 * Threading:
 *  - [send] is called from the heartbeat timer, mic capture timer, main thread, and
 *    anywhere an IPC needs to go out. [writeLock] serializes those calls so complete
 *    protocol frames reach the single USB OUT endpoint in order.
 *  - [messageHandler] runs on the USB read thread. Handlers MUST return quickly —
 *    blocking the read thread stalls all inbound messages AND (indirectly) heartbeats
 *    that contend for the same USB endpoint.
 *  - [isRunning] is the single gate: after [stop], every [send] silently returns
 *    false. Callers that need to distinguish "not running" from "send failed" must
 *    track state externally.
 */
class AdapterDriver(
    private val usbDevice: UsbDeviceWrapper,
    private val messageHandler: (Message) -> Unit,
    private val errorHandler: (String) -> Unit,
    private val logCallback: (String) -> Unit,
    private val readTimeout: Int = 30000,
    private val writeTimeout: Int = 1000,
    private val videoProcessor: UsbDeviceWrapper.VideoDataProcessor? = null,
    private val phoneConnectionAllowed: () -> Boolean = { true },
) {
    private var heartbeatTimer: Timer? = null
    private var wifiConnectTimer: Timer? = null
    private val autoConnectCancelled = AtomicBoolean(false)
    private val phoneConnectGeneration = AtomicInteger(0)
    // 2s gives ~5 heartbeats inside the firmware's ~10s watchdog window; the first
    // heartbeat must arrive within ~10s of USB open, which is why startHeartbeat()
    // runs BEFORE the init loop. Verified on live CarPlay sessions: the measured
    // mean interval on the wire stays within 2.00-2.20 s across the full session,
    // consistent with Timer.scheduleAtFixedRate jitter. See in-tree heartbeat
    // analysis notes under documents/reference/ for the watchdog derivation.
    private val heartbeatInterval = 2000L // 2 seconds

    private val isRunning = AtomicBoolean(false)
    private val isInitializing = AtomicBoolean(false)
    private val initializationTransportFailed = AtomicBoolean(false)
    private val transportFailureReported = AtomicBoolean(false)
    private val writeLock = Any()

    // Performance tracking — atomic because send() is called from multiple threads
    // and the reading loop callback runs on the USB read thread.
    private val messagesSent = AtomicInteger(0)
    private val messagesReceived = AtomicInteger(0)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    private val sendErrors = AtomicInteger(0)
    private val receiveErrors = AtomicInteger(0)
    private val heartbeatsSent = AtomicInteger(0)
    private val sessionStart = AtomicLong(0)
    private var initMessagesCount = 0

    // Diagnostic: per-type message arrival counter. Emits a [TYPE_COUNTS_5s] summary
    // every 5s on the USB read thread. Designed to prove which raw message types are
    // flowing on USB without polluting logs with per-message [RECV] floods.
    private val typeCounts = mutableMapOf<Int, Int>()
    private var lastTypeDumpMs = 0L

    /**
     * Start the adapter communication with smart initialization.
     *
     * @param config Adapter configuration
     * @param initMode Initialization mode: "FULL", "MINIMAL_PLUS_CHANGES", or "MINIMAL_ONLY"
     * @param pendingChanges Set of config keys that have changed since last init
     */
    fun start(
        config: AdapterConfig = AdapterConfig.DEFAULT,
        initMode: String = "FULL",
        pendingChanges: Set<String> = emptySet(),
        surfaceWidth: Int = 0,
        surfaceHeight: Int = 0,
    ): Boolean {
        if (isRunning.getAndSet(true)) {
            log("Adapter already running")
            return true
        }
        transportFailureReported.set(false)
        initializationTransportFailed.set(false)

        sessionStart.set(System.currentTimeMillis())
        log("Starting adapter connection sequence")

        if (!usbDevice.isOpened) {
            log("USB device not opened")
            errorHandler("USB device not opened")
            isRunning.set(false)
            return false
        }

        // Start heartbeat FIRST for firmware stabilization
        startHeartbeat()
        log("Heartbeat started before initialization (firmware stabilization)")

        // Send initialization sequence based on mode
        val initMessages = MessageSerializer.generateInitSequence(config, initMode, pendingChanges, surfaceWidth, surfaceHeight)
        initMessagesCount = initMessages.size
        var initFailures = 0
        log("Sending $initMessagesCount initialization messages (mode=$initMode, changes=$pendingChanges)")

        isInitializing.set(true)
        try {
            for ((index, message) in initMessages.withIndex()) {
                if (!isRunning.get()) {
                    log("Initialization cancelled before message ${index + 1}")
                    return false
                }
                log("Init message ${index + 1}/$initMessagesCount")
                if (!send(message)) {
                    initFailures++
                    log("Failed to send init message ${index + 1}")
                }
                if (initializationTransportFailed.get()) {
                    log("Initialization aborted after transport failure")
                    break
                }
                // Delay between messages to allow adapter firmware to process each one
                Thread.sleep(120)
            }
        } finally {
            isInitializing.set(false)
        }

        if (!isRunning.get()) {
            log("Initialization cancelled before read loop start")
            return false
        }

        val allSent = initFailures == 0 && !initializationTransportFailed.get()
        log("Initialization sequence completed (${initMessagesCount - initFailures}/$initMessagesCount sent)")
        if (!allSent) {
            log("Initialization transport was incomplete; closing protocol session")
            stopHeartbeat()
            isRunning.set(false)
            return false
        }

        // Schedule wifiConnect with timeout (600 ms delay derived from capture traces
        // under documents/reference/).
        wifiConnectTimer = if (config.autoConnectPhone) {
            Timer().apply {
                schedule(
                    object : TimerTask() {
                        override fun run() {
                            if (isRunning.get() && !autoConnectCancelled.get() && phoneConnectionAllowed()) {
                                log("Sending wifiConnect command (timeout-based)")
                                sendCommand(CommandMapping.WIFI_CONNECT)
                            }
                        }
                    },
                    600,
                )
            }
        } else null

        // Start reading loop
        log("Starting message reading loop")
        startReadingLoop()

        return allSent
    }

    /**
     * Stop the adapter communication.
     */
    fun stop() {
        synchronized(writeLock) {
            if (!isRunning.getAndSet(false)) {
                return
            }
        }

        log("Stopping adapter connection")

        wifiConnectTimer?.cancel()
        wifiConnectTimer = null
        stopHeartbeat()
        usbDevice.stopReadingLoop()

        logPerformanceStats()
        resetStats()

        log("Adapter stopped")
    }

    /**
     * Send raw data to the adapter.
     *
     * Silently returns false when [isRunning] is false (post-[stop]). Callers that
     * must distinguish "adapter not running" from "USB write failed" need to check
     * state separately. Concurrent callers are serialized on [writeLock].
     *
     * @param data Serialized message data
     * @return true if send was successful
     */
    fun send(data: ByteArray): Boolean = sendFrame(data)

    private fun sendFrame(
        data: ByteArray,
        requiresPhoneConnection: Boolean = false,
        automaticScan: Boolean = false,
        phoneGeneration: Int = phoneConnectGeneration.get(),
    ): Boolean {
        var failure: String? = null
        val success =
            synchronized(writeLock) {
                if (!isRunning.get()) return@synchronized false
                // Recheck after waiting for USB's writer, not only when a timer or
                // targeted-connect task was admitted before the user's Disconnect.
                if (requiresPhoneConnection &&
                    (!phoneConnectionAllowed() || phoneGeneration != phoneConnectGeneration.get())
                ) return@synchronized false
                if (automaticScan && autoConnectCancelled.get()) return@synchronized false
                try {
                    // Initialization can contain large file uploads; runtime traffic is
                    // latency-sensitive (mic/touch/heartbeat) and must not hold the shared
                    // writer for a full second when the OUT endpoint stalls.
                    val timeout = if (isInitializing.get()) writeTimeout else minOf(writeTimeout, 250)
                    val result = usbDevice.write(data, timeout)
                    if (result == data.size) {
                        messagesSent.incrementAndGet()
                        bytesSent.addAndGet(data.size.toLong())
                        true
                    } else {
                        sendErrors.incrementAndGet()
                        val message = "USB write incomplete: $result/${data.size} bytes"
                        failure = message
                        log(message)
                        false
                    }
                } catch (error: Exception) {
                    sendErrors.incrementAndGet()
                    val message = "USB write failed: ${error.message ?: error.javaClass.simpleName}"
                    failure = message
                    log(message)
                    false
                }
            }
        // Never invoke recovery while holding writeLock: recovery stops the read thread,
        // which may itself be waiting to send a protocol response.
        val failureMessage = failure
        if (!success && failureMessage != null && isInitializing.get()) {
            // Includes heartbeat writes racing with the init sequence. A partial frame can
            // desynchronize firmware, so the connection must not be reported as initialized.
            initializationTransportFailed.set(true)
        }
        if (!success && failureMessage != null && !isInitializing.get() &&
            transportFailureReported.compareAndSet(false, true)
        ) {
            errorHandler(failureMessage)
        }
        return success
    }

    /**
     * Send a command to the adapter.
     */
    fun sendCommand(command: CommandMapping): Boolean {
        if (command == CommandMapping.WIFI_CONNECT && !phoneConnectionAllowed()) return false
        log("[SEND] Command ${command.name}")
        return sendFrame(
            MessageSerializer.serializeCommand(command),
            requiresPhoneConnection = command == CommandMapping.WIFI_CONNECT,
            automaticScan = command == CommandMapping.WIFI_CONNECT,
        )
    }

    /**
     * Send a multi-touch event (CarPlay — type 0x17, 0..1 floats).
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>): Boolean = send(MessageSerializer.serializeMultiTouch(touches))

    /**
     * Send a single-touch event (Android Auto — type 0x05, 0..10000 ints).
     * @param encoderType Current video encoder type from video header flags
     * @param offScreen Current off-screen state from video header flags
     */
    fun sendSingleTouch(
        x: Int,
        y: Int,
        action: MultiTouchAction,
        encoderType: Int = 2,
        offScreen: Int = 0,
    ): Boolean = send(MessageSerializer.serializeSingleTouch(x, y, action, encoderType, offScreen))

    /**
     * Send microphone audio data.
     */
    fun sendAudio(
        data: ByteArray,
        decodeType: Int = 5,
        audioType: Int = 3,
    ): Boolean = send(MessageSerializer.serializeAudio(data, decodeType, audioType))

    /**
     * Send GNSS/NMEA data to the adapter for forwarding to the phone.
     */
    fun sendGnssData(nmeaSentences: String): Boolean = send(MessageSerializer.serializeGnssData(nmeaSentences))

    fun rebootAdapter(): Boolean {
        log("[SEND] Reboot adapter (0xCD)")
        return send(MessageSerializer.serializeRebootAdapter())
    }

    fun disconnectPhone(): Boolean {
        log("[SEND] Disconnect phone (0x0F)")
        return send(MessageSerializer.serializeDisconnectPhone())
    }

    fun closeDongle(): Boolean {
        log("[SEND] Close dongle (0x15)")
        return send(MessageSerializer.serializeCloseDongle())
    }

    /**
     * Request the adapter to connect to a specific paired device by BT MAC address.
     * Uses type 0x11 (AutoConnect_By_BluetoothAddress) H→A direction.
     */
    fun sendAutoConnectByBtAddress(btMac: String): Boolean {
        if (!phoneConnectionAllowed()) return false
        log("[SEND] AutoConnect_By_BluetoothAddress: $btMac")
        return sendFrame(MessageSerializer.serializeAutoConnectByBtAddress(btMac), requiresPhoneConnection = true)
    }

    /**
     * Request the adapter to forget/remove a device from its paired list.
     * Moves the device from DevList to DeletedDevList, preventing auto-reconnect.
     */
    fun sendForgetBluetoothAddr(btMac: String): Boolean {
        log("[SEND] ForgetBluetoothAddr: $btMac")
        return send(MessageSerializer.serializeForgetBluetoothAddr(btMac))
    }

    /**
     * Write an arbitrary file to the connected adapter via USB SendFile (0x99).
     *
     * The adapter's ARMadb-driver handler stages the upload through
     * /tmp/uploadFileTmp before atomically moving it to [adapterPath]. Path is
     * not validated server-side — any absolute path the adapter root can write
     * is reachable (see adapter RE_Documention §3 vulnerabilities.md). Used here
     * only for /tmp/ paths (e.g. /tmp/aa_gps_fix.sh) for in-session host-pushed
     * helpers. Naming a file `*Update.img` auto-triggers OTA on the adapter side
     * — caller must avoid that suffix unless OTA is intended.
     *
     * @param adapterPath Absolute destination path on the adapter (e.g. "/tmp/aa_gps_fix.sh")
     * @param content     Raw file bytes to write
     */
    fun sendFile(
        adapterPath: String,
        content: ByteArray,
    ): Boolean {
        log("[SEND] SendFile: $adapterPath (${content.size} bytes)")
        return send(MessageSerializer.serializeFile(adapterPath, content))
    }

    /**
     * Cancel the pending wifiConnect auto-connect timer and send a targeted
     * AutoConnect_By_BluetoothAddress instead. Called when the user explicitly
     * selects a device to connect to during a restart cycle.
     */
    fun overrideAutoConnectWithTarget(btMac: String): Boolean {
        cancelAutoConnect()
        log("[SEND] Overriding auto-connect with targeted connect: $btMac")
        return sendAutoConnectByBtAddress(btMac)
    }

    /** Invalidate queued scan/target writes as well as the timer, before changing phone intent. */
    fun cancelAutoConnect() {
        autoConnectCancelled.set(true)
        phoneConnectGeneration.incrementAndGet()
        wifiConnectTimer?.cancel()
        wifiConnectTimer = null
    }

    /**
     * Request the adapter to send its current list of paired BT devices.
     * Adapter responds with updated BoxSettings (0x19) containing DevList.
     */
    fun sendGetBtOnlineList(): Boolean {
        log("[SEND] GetBluetoothOnlineList")
        return sendCommand(CommandMapping.GET_BT_ONLINE_LIST)
    }

    /**
     * Send graceful teardown sequence. Must be called BEFORE stop()
     * since send() requires isRunning==true.
     *
     * Sequence (from USB captures — see documents/reference/):
     * 1. DisconnectPhone (0x0F) — end phone's CarPlay/AA session
     * 2. CloseDongle (0x15) — stop adapter internal processes
     * 3. RebootAdapter (0xCD) — optional full adapter reboot
     */
    fun sendGracefulTeardown(reboot: Boolean = false) {
        disconnectPhone()
        closeDongle()
        if (reboot) {
            rebootAdapter()
        }
    }

    // ==================== Private Methods ====================

    private fun startHeartbeat() {
        stopHeartbeat()

        heartbeatTimer =
            Timer("HeartbeatTimer", true).apply {
                scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            // Guard against unchecked exceptions killing the Timer thread.
                            // java.util.Timer silently terminates on any exception from run(),
                            // which would stop all heartbeats and cause adapter disconnect.
                            try {
                                if (isRunning.get()) {
                                    heartbeatsSent.incrementAndGet()
                                    if (!send(MessageSerializer.serializeHeartbeat())) {
                                        log("Heartbeat send failed (count: $heartbeatsSent)")
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("HeartbeatTimer", "Heartbeat exception: ${e.message}", e)
                            }
                        }
                    },
                    heartbeatInterval,
                    heartbeatInterval,
                )
            }

        log("Heartbeat started (every ${heartbeatInterval}ms)")
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun startReadingLoop() {
        usbDevice.startReadingLoop(
            object : UsbDeviceWrapper.ReadingLoopCallback {
                override fun onMessage(
                    type: Int,
                    data: ByteArray?,
                    dataLength: Int,
                ) {
                    messagesReceived.incrementAndGet()
                    bytesReceived.addAndGet((dataLength + HEADER_SIZE).toLong())

                    // Diagnostic per-type tally — proves which raw message types are
                    // arriving on USB without per-message log noise. Single-threaded
                    // (USB read thread) so plain map mutation is safe.
                    typeCounts[type] = (typeCounts[type] ?: 0) + 1
                    val now = System.currentTimeMillis()
                    if (now - lastTypeDumpMs > 5000L) {
                        val summary = typeCounts.entries
                            .sortedByDescending { it.value }
                            .joinToString(", ") { "0x${it.key.toString(16).padStart(2, '0')}=${it.value}" }
                        log("[TYPE_COUNTS_5s] $summary")
                        typeCounts.clear()
                        lastTypeDumpMs = now
                    }

                    // For VIDEO_DATA with direct processing, data is null (processed directly by videoProcessor)
                    // Just signal the message handler that video is streaming
                    if (type == MessageType.VIDEO_DATA.id &&
                        videoProcessor != null && (data == null || dataLength == 0)
                    ) {
                        // Video data was processed directly by videoProcessor - just signal streaming
                        try {
                            messageHandler(VideoStreamingSignal)
                        } catch (e: Exception) {
                            receiveErrors.incrementAndGet()
                            val trace = e.stackTraceToString().take(500)
                            log("VideoStreamingSignal handler error: ${e.message}\n$trace")
                        }
                        return
                    }

                    val header = MessageHeader(dataLength, MessageType.fromId(type))
                    val message = MessageParser.parseMessage(header, data)

                    // Log received message (except high-frequency types). Suppression
                    // list is conservative — extend it if a new echo path becomes noisy
                    // (e.g., adapter starts mirroring GNSS_DATA or position updates).
                    if (type != MessageType.VIDEO_DATA.id && type != MessageType.AUDIO_DATA.id &&
                        type != MessageType.NAVI_VIDEO_DATA.id && type != MessageType.HEARTBEAT_ECHO.id
                    ) {
                        log("[RECV] $message")
                    }

                    try {
                        messageHandler(message)
                    } catch (e: Exception) {
                        receiveErrors.incrementAndGet()
                        val trace = e.stackTraceToString().take(500)
                        log("Message handler error for $message: ${e.message}\n$trace")
                    }
                }

                override fun onError(error: String) {
                    receiveErrors.incrementAndGet()
                    log("Reading loop error: $error")
                    errorHandler(error)
                }
            },
            readTimeout,
            videoProcessor,
        )
    }

    private fun logPerformanceStats() {
        val sessionDuration =
            if (sessionStart.get() > 0) {
                (System.currentTimeMillis() - sessionStart.get()) / 1000
            } else {
                0L
            }
        val sent = bytesSent.get()
        val received = bytesReceived.get()
        val sendThroughput =
            if (sessionDuration > 0) {
                String.format(Locale.US, "%.1f", sent / sessionDuration / 1024.0)
            } else {
                "0.0"
            }
        val receiveThroughput =
            if (sessionDuration > 0) {
                String.format(Locale.US, "%.1f", received / sessionDuration / 1024.0)
            } else {
                "0.0"
            }

        log("Adapter Performance Summary:")
        log("  Session: ${sessionDuration}s | Init: $initMessagesCount msgs | Heartbeats: ${heartbeatsSent.get()}")
        log("  TX: ${messagesSent.get()} msgs / ${sent / 1024}KB / ${sendThroughput}KB/s")
        log("  RX: ${messagesReceived.get()} msgs / ${received / 1024}KB / ${receiveThroughput}KB/s")
        log("  Errors: TX=${sendErrors.get()} RX=${receiveErrors.get()}")
    }

    private fun resetStats() {
        messagesSent.set(0)
        messagesReceived.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        sendErrors.set(0)
        receiveErrors.set(0)
        heartbeatsSent.set(0)
        sessionStart.set(0)
        initMessagesCount = 0
    }

    private fun log(message: String) {
        logCallback("[ADAPTR] $message")
    }
}
