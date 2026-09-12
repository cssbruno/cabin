package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal data class SyuAudioSourceState(val connected: Boolean = false, val sourceId: Int? = null)

/** Read-only MAIN/APP_ID interface. Source selection is not a playback state or Android audio focus. */
internal class SyuAudioSourceMonitor(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = HandlerThread("CabinSyuAudio").apply { start() }
    private val handler = Handler(worker.looper)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutable = MutableStateFlow(SyuAudioSourceState())
    val state = mutable.asStateFlow()
    private var owner: ServiceConnection? = null
    private var module: IBinder? = null
    private var callback: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val retry = Runnable { bind() }
    private val timeout = Runnable { reconnect() }

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
                                if (field != 0 || count != 1) {
                                    reply?.writeNoException()
                                    return true
                                }
                                val id = data.readInt()
                                handler.post {
                                    if (!closed.get() && owner === expected && module === remote)
                                        mutable.value = SyuAudioSourceState(true, id.takeIf { it in 0..14 })
                                }
                                reply?.writeNoException()
                                return true
                            }
                        }
                        mutable.value = SyuAudioSourceState(connected = true)
                        transactSubscription(remote, 3, callback!!)
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
    private fun getMain(toolkit: IBinder): IBinder? = SyuBinderTransport.getModule(toolkit, 0)
    private fun transactSubscription(remote: IBinder, transaction: Int, listener: IBinder) {
        if (transaction == 3) SyuBinderTransport.register(remote, listener, 0)
        else SyuBinderTransport.unregister(remote, listener, 0)
    }
    private fun reconnect() {
        clear()
        handler.removeCallbacks(retry)
        if (!closed.get()) handler.postDelayed(retry, 5_000)
    }
    private fun clear() {
        handler.removeCallbacks(timeout)
        val oldOwner = owner; owner = null
        val oldModule = module; module = null
        val oldCallback = callback; callback = null
        val oldDeath = death; death = null
        if (oldModule != null) {
            try { if (oldCallback != null) transactSubscription(oldModule, 4, oldCallback) } catch (_: Exception) { }
            try { if (oldDeath != null) oldModule.unlinkToDeath(oldDeath, 0) } catch (_: Exception) { }
        }
        if (oldOwner != null) try { context.unbindService(oldOwner) } catch (_: RuntimeException) { }
        mutable.value = SyuAudioSourceState()
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
