package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One foreground learning session. No raw serial access, automatic clearing, or write replay. */
internal class SyuSteeringController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = HandlerThread("CabinSteering").apply { start() }
    private val handler = Handler(worker.looper)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutable = MutableStateFlow(SteeringHardwareState())
    val state = mutable.asStateFlow()
    private var owner: ServiceConnection? = null
    private var remote: IBinder? = null
    private var listener: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var epoch = 0L
    private var adcAt = -10_000L
    private var expectedFeedback: Pair<Int, Int>? = null
    private var lastCommandAt = -1000L
    private val retry = Runnable { bind() }
    private val connectionTimeout = Runnable { disconnect() }
    private val learningTimeout = Runnable { finishSession(SteeringHardwareStatus.TIMED_OUT) }
    private val pendingTimeout = Runnable {
        expectedFeedback = null
        mutable.value = mutable.value.copy(pending = null, status = SteeringHardwareStatus.TIMED_OUT)
    }
    private val freshness = object : Runnable {
        override fun run() {
            if (closed.get() || remote == null) return
            if (SystemClock.elapsedRealtime() - adcAt > 3000) mutable.value = mutable.value.copy(adc = null)
            handler.postDelayed(this, 1000)
        }
    }

    fun start() { if (!closed.get() && started.compareAndSet(false, true)) handler.post { bind() } }

    fun begin(expectedEpoch: Long, mode: SteeringLearningMode) = submit(expectedEpoch) {
        val current = mutable.value
        if (!current.feedbackSeen || current.mode != null || (mode == SteeringLearningMode.MCU && current.mcuEnabled != true)) return@submit
        // Own cleanup even if a later command fails or focus is lost before callbacks arrive.
        if (mode == SteeringLearningMode.ADC) adcAt = -10_000L
        mutable.value = current.copy(mode = mode, pending = null,
            adc = if (mode == SteeringLearningMode.ADC) null else current.adc,
            status = SteeringHardwareStatus.STARTED)
        write(SyuSteeringProtocol.start(mode))
        if (mode == SteeringLearningMode.MCU) write(SteeringCommand(5, listOf(4)))
        handler.removeCallbacks(learningTimeout); handler.postDelayed(learningTimeout, 60_000)
    }

    fun assign(expectedEpoch: Long, key: Int, function: Int = 1) = submit(expectedEpoch) {
        val current = mutable.value
        val mode = current.mode ?: return@submit
        if (current.pending != null) return@submit
        val frame = SyuSteeringProtocol.assign(mode, key, function) ?: return@submit
        if (mode == SteeringLearningMode.ADC && (current.detecting != true || current.adc == null ||
                current.adc == 50 || SystemClock.elapsedRealtime() - adcAt > 3000)) return@submit
        expectedFeedback = key to if (mode == SteeringLearningMode.ADC) current.adc!! else function
        mutable.value = current.copy(pending = key, status = SteeringHardwareStatus.ASSIGNING)
        write(frame)
        handler.removeCallbacks(pendingTimeout); handler.postDelayed(pendingTimeout, 10_000)
    }

    fun save(expectedEpoch: Long) = submit(expectedEpoch) {
        val current = mutable.value
        val mode = current.mode ?: return@submit
        if (current.pending != null) return@submit
        write(SyuSteeringProtocol.save(mode))
        if (mode == SteeringLearningMode.ADC) write(SyuSteeringProtocol.finish(mode))
        endState(SteeringHardwareStatus.SAVE_SENT)
    }

    fun clear(expectedEpoch: Long, mode: SteeringLearningMode) = submit(expectedEpoch) {
        if (!mutable.value.feedbackSeen || mutable.value.mode != null ||
            (mode == SteeringLearningMode.MCU && mutable.value.mcuEnabled != true)) return@submit
        write(SyuSteeringProtocol.clear(mode))
        // Discard cached readings. Do not fabricate a cleared MCU table.
        mutable.value = mutable.value.copy(learnedAdc = emptyMap(), mcuKeys = emptyMap(), status = SteeringHardwareStatus.CLEAR_SENT)
        if (mode == SteeringLearningMode.MCU) write(SteeringCommand(5, listOf(4)))
        else refreshSubscriptions()
    }

    fun stop(expectedEpoch: Long) = submit(expectedEpoch, throttled = false) { finishSession(SteeringHardwareStatus.READY) }

    private fun submit(expectedEpoch: Long, throttled: Boolean = true, block: () -> Unit) {
        handler.post {
            if (closed.get() || remote == null || expectedEpoch != mutable.value.epoch) return@post
            val now = SystemClock.elapsedRealtime()
            if (throttled && now - lastCommandAt < 200) return@post
            try { block(); if (throttled) lastCommandAt = now } catch (_: Exception) { disconnect(SteeringHardwareStatus.FAILED) }
        }
    }
    private fun write(frame: SteeringCommand) = SyuSteeringWire.command(remote ?: error("Disconnected"), frame)
    private fun endState(status: SteeringHardwareStatus) {
        handler.removeCallbacks(learningTimeout); handler.removeCallbacks(pendingTimeout)
        expectedFeedback = null
        mutable.value = mutable.value.copy(mode = null, pending = null, status = status)
    }
    private fun finishSession(status: SteeringHardwareStatus) {
        val mode = mutable.value.mode ?: return
        try {
            write(SyuSteeringProtocol.finish(mode))
            // The vendor MCU finish operation saves. There is no verified cancel-without-save.
            endState(if (mode == SteeringLearningMode.MCU) SteeringHardwareStatus.SAVE_SENT else status)
        } catch (_: Exception) { endState(SteeringHardwareStatus.FAILED) }
    }
    private fun refreshSubscriptions() {
        val module = remote ?: return
        val callback = listener ?: return
        SyuSteeringProtocol.fields.forEach { SyuSteeringWire.subscribe(module, callback, it, true) }
    }
    private fun bind() {
        if (closed.get() || owner != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val expected = this
                handler.post {
                    if (closed.get() || owner !== expected) return@post
                    handler.removeCallbacks(connectionTimeout)
                    try {
                        val module = service?.let { SyuBinderTransport.getModule(it, SyuSteeringProtocol.MODULE) }
                            ?: run { disconnect(); return@post }
                        remote = module
                        mutable.value = SteeringHardwareState(connected = true, epoch = ++epoch, status = SteeringHardwareStatus.READY)
                        death = IBinder.DeathRecipient { handler.post { if (owner === expected) disconnect() } }
                        module.linkToDeath(death!!, 0)
                        listener = object : Binder() {
                            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                                if (code == INTERFACE_TRANSACTION) { reply?.writeString(SyuSteeringWire.CALLBACK); return true }
                                if (code != 1) return false
                                val feedback = SyuSteeringWire.feedback(data) ?: return false
                                handler.post {
                                    if (closed.get() || owner !== expected || remote !== module) return@post
                                    val (field, values) = feedback
                                    val next = SyuSteeringProtocol.feedback(mutable.value, field, values) ?: return@post
                                    if (field == 2) adcAt = SystemClock.elapsedRealtime()
                                    mutable.value = next
                                    val pending = expectedFeedback
                                    val expectedField = if (next.mode == SteeringLearningMode.ADC) 1 else 6
                                    if (pending != null && field == expectedField && values == listOf(pending.first, pending.second)) {
                                        expectedFeedback = null; handler.removeCallbacks(pendingTimeout)
                                        mutable.value = next.copy(pending = null, status = SteeringHardwareStatus.LEARNED)
                                    }
                                }
                                reply?.writeNoException()
                                return true
                            }
                        }
                        refreshSubscriptions()
                        handler.post(freshness)
                    } catch (_: Exception) { disconnect(SteeringHardwareStatus.FAILED) }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = lost(this)
            override fun onBindingDied(name: ComponentName?) = lost(this)
            override fun onNullBinding(name: ComponentName?) = lost(this)
        }
        owner = connection
        val accepted = try { context.bindService(fytToolkitIntent(context), connection, Context.BIND_AUTO_CREATE) } catch (_: RuntimeException) { false }
        if (accepted) handler.postDelayed(connectionTimeout, 10_000) else disconnect()
    }
    private fun lost(connection: ServiceConnection) { handler.post { if (owner === connection) disconnect() } }
    private fun disconnect(status: SteeringHardwareStatus = SteeringHardwareStatus.DISCONNECTED) {
        cleanup()
        mutable.value = SteeringHardwareState(epoch = ++epoch, status = status)
        handler.removeCallbacks(retry)
        if (!closed.get()) handler.postDelayed(retry, 5000)
    }
    private fun cleanup() {
        handler.removeCallbacks(connectionTimeout); handler.removeCallbacks(freshness)
        handler.removeCallbacks(learningTimeout); handler.removeCallbacks(pendingTimeout)
        finishSession(SteeringHardwareStatus.READY)
        val module = remote; val callback = listener; val oldOwner = owner
        owner = null; remote = null; listener = null
        if (module != null && callback != null) {
            SyuSteeringProtocol.fields.forEach { try { SyuSteeringWire.subscribe(module, callback, it, false) } catch (_: Exception) { } }
        }
        try { if (module != null && death != null) module.unlinkToDeath(death!!, 0) } catch (_: Exception) { }
        death = null; expectedFeedback = null; adcAt = -10_000L
        if (oldOwner != null) try { context.unbindService(oldOwner) } catch (_: RuntimeException) { }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post { handler.removeCallbacksAndMessages(null); cleanup(); worker.quitSafely() }
    }
}
