package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean


/** Owns the installed radio service; no audio routing or native driver initialization. */
internal class SyuRadioController(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = HandlerThread("CabinSyuRadio").apply { start() }
    private val handler = Handler(worker.looper)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutable = MutableStateFlow(SyuRadioState())
    val state = mutable.asStateFlow()
    private var owner: ServiceConnection? = null
    private var module: IBinder? = null
    private var callback: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val retry = Runnable { bind() }
    private val timeout = Runnable { reconnect() }

    private var epoch = 0L
    private var lastCommand = -1000L
    private val received = mutableMapOf<Int, Long>()
    private var refreshedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (closed.get() || module == null) return
            val now = SystemClock.elapsedRealtime()
            val expired = received.filterValues { now - it >= 30000 }.keys.toSet()
            expired.forEach(received::remove)
            mutable.value = mutable.value.copy(samples = mutable.value.samples - expired)
            if (now - refreshedAt >= 15000) {
                refreshedAt = now
                try { transactSubscription(module!!, 3, callback!!) } catch (_: Exception) { reconnect(); return }
            }
            handler.postDelayed(this, 1000)
        }
    }
    fun command(expectedEpoch: Long, code: Int, channel: Int? = null) {
        handler.post {
            if (closed.get() || expectedEpoch != mutable.value.epoch) return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastCommand < 300) return@post
            val current = mutable.value.copy(samples = mutable.value.samples.filterKeys { key ->
                received[key]?.let { now - it < 30000 } == true
            })
            val frame = SyuRadioProtocol.command(current, code, channel) ?: return@post
            try {
                SyuBinderTransport.transact(module ?: return@post, 1, {
                    it.writeInt(frame.first); it.writeIntArray(frame.second)
                    it.writeFloatArray(null); it.writeStringArray(null)
                }, {})
                lastCommand = now
            } catch (_: Exception) { reconnect() }
        }
    }
    fun start() { if (!closed.get() && started.compareAndSet(false, true)) handler.post { bind() } }
    private fun bind() {
        if (closed.get() || owner != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val expected = this
                handler.post {
                    if (closed.get() || owner !== expected) return@post
                    handler.removeCallbacks(timeout)
                    try {
                        val remote = service?.let { getMain(it) } ?: run { reconnect(); return@post }
                        module = remote
                        death = IBinder.DeathRecipient { handler.post { if (owner === expected) reconnect() } }
                        remote.linkToDeath(death!!, 0)
                        callback = object : Binder() {
                            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                                if (code != 1) return false
                                data.enforceInterface(CALLBACK)
                                if (data.dataSize() > 4096 || data.dataAvail() < 12) return false
                                val field = data.readInt()
                                val count = data.readInt()
                                if (field !in setOf(0, 1, 4) || count !in 1..2 || count > data.dataAvail() / 4) return false
                                val values = List(count) { data.readInt() }
                                handler.post {
                                    if (!closed.get() && owner === expected && module === remote) {
                                        val sample = SyuRadioProtocol.sample(field, values)
                                        if (sample != null) {
                                            if (field == 0 && mutable.value.samples[0] != sample.second) {
                                                received.remove(1)
                                                mutable.value = mutable.value.copy(samples = mutable.value.samples - 1, epoch = ++epoch)
                                            }
                                            received[sample.first] = SystemClock.elapsedRealtime()
                                            mutable.value = mutable.value.copy(samples = mutable.value.samples + sample)
                                        } else if (field == 4) {
                                            val keys = mutable.value.samples.keys.filter { it >= 100000 }
                                            keys.forEach(received::remove)
                                            mutable.value = mutable.value.copy(samples = mutable.value.samples - keys.toSet())
                                        } else if (field in 0..1) {
                                            received.remove(field)
                                            mutable.value = mutable.value.copy(samples = mutable.value.samples - field)
                                        }
                                    }
                                }
                                reply?.writeNoException()
                                return true
                            }
                        }
                        mutable.value = SyuRadioState(connected = true, epoch = ++epoch)
                        transactSubscription(remote, 3, callback!!)
                        refreshedAt = SystemClock.elapsedRealtime()
                        handler.post(tick)
                    } catch (_: Exception) { reconnect() }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = lost(this)
            override fun onBindingDied(name: ComponentName?) = lost(this)
            override fun onNullBinding(name: ComponentName?) = lost(this)
        }
        owner = connection
        val accepted = try { context.bindService(fytToolkitIntent(context), connection, Context.BIND_AUTO_CREATE) } catch (_: RuntimeException) { false }
        if (accepted) handler.postDelayed(timeout, 10_000) else reconnect()
    }
    private fun lost(expected: ServiceConnection) { handler.post { if (owner === expected) reconnect() } }
    private fun getMain(toolkit: IBinder): IBinder? = SyuBinderTransport.getModule(toolkit, 1)
    private fun transactSubscription(remote: IBinder, transaction: Int, listener: IBinder) {
        for (field in listOf(0, 1, 4)) {
            if (transaction == 3) SyuBinderTransport.register(remote, listener, field)
            else SyuBinderTransport.unregister(remote, listener, field)
        }
    }
    private fun reconnect() {
        clear()
        handler.removeCallbacks(retry)
        if (!closed.get()) handler.postDelayed(retry, 5_000)
    }
    private fun clear() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(tick)
        received.clear()
        val oldOwner = owner; owner = null
        val oldModule = module; module = null
        val oldCallback = callback; callback = null
        val oldDeath = death; death = null
        if (oldModule != null) {
            try { if (oldCallback != null) transactSubscription(oldModule, 4, oldCallback) } catch (_: Exception) { }
            try { if (oldDeath != null) oldModule.unlinkToDeath(oldDeath, 0) } catch (_: Exception) { }
        }
        if (oldOwner != null) try { context.unbindService(oldOwner) } catch (_: RuntimeException) { }
        mutable.value = SyuRadioState(epoch = ++epoch)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post { handler.removeCallbacksAndMessages(null); clear(); worker.quitSafely() }
    }
    companion object {
        const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
        const val MODULE = "com.syu.ipc.IRemoteModule"
        const val CALLBACK = "com.syu.ipc.IModuleCallback"
    }
}
