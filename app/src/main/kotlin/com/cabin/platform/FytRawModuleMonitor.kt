package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel

/** Worker-owned, live-only subscription. Never issues a vendor command or readback request. */
internal class FytRawModuleMonitor(
    private val context: Context,
    private val handler: Handler,
    private val fields: Set<Int>,
    private val onSample: (Int, FytRawSample) -> Unit,
    private val onClear: () -> Unit,
) : AutoCloseable {
    private var active = true
    private var owner: ServiceConnection? = null
    private var module: IBinder? = null
    private var listener: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private val registered = mutableSetOf<Int>()
    private val retry = Runnable { bind() }
    private val timeout = Runnable { reconnect() }

    init { require(fields.all { it in 0..255 }); bind() }

    private fun bind() {
        if (!active || fields.isEmpty() || owner != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val expected = this
                handler.post {
                    if (!active || owner !== expected) return@post
                    handler.removeCallbacks(timeout)
                    try {
                        val remote = service?.let { SyuBinderTransport.getModule(it, 0) } ?: error("No MAIN module")
                        module = remote
                        val callback = object : Binder() {
                            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                                if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                                if (code != 1) return false
                                data.enforceInterface(CALLBACK)
                                if (data.dataAvail() !in 8..SyuBinderTransport.MAX_PARCEL_BYTES) return false
                                val field: Int
                                val sample: FytRawSample
                                try {
                                    field = data.readInt()
                                    if (field !in fields) { reply?.writeNoException(); return true }
                                    sample = FytRawSample.read(data)
                                } catch (_: RuntimeException) { return false }
                                handler.post {
                                    if (active && owner === expected && module === remote) onSample(field, sample)
                                }
                                reply?.writeNoException()
                                return true
                            }
                        }
                        listener = callback
                        death = IBinder.DeathRecipient { handler.post { if (owner === expected) reconnect() } }
                        remote.linkToDeath(death!!, 0)
                        fields.forEach { field ->
                            // notify=0 subscribes to future events without executing cached register branches.
                            SyuBinderTransport.transact(remote, 3, {
                                it.writeStrongBinder(callback); it.writeInt(field); it.writeInt(0)
                            }, {})
                            registered.add(field)
                        }
                    } catch (_: Exception) { reconnect() }
                }
            }
            private fun lost() { val expected = this; handler.post { if (owner === expected) reconnect() } }
            override fun onServiceDisconnected(name: ComponentName?) = lost()
            override fun onBindingDied(name: ComponentName?) = lost()
            override fun onNullBinding(name: ComponentName?) = lost()
        }
        owner = connection
        val accepted = try { bindFytService(context, fytModuleIntent(context, 0), connection) } catch (_: RuntimeException) { false }
        if (accepted) handler.postDelayed(timeout, 10_000) else reconnect()
    }

    private fun reconnect() {
        clear()
        handler.removeCallbacks(retry)
        if (active) handler.postDelayed(retry, 5_000)
    }

    private fun clear() {
        handler.removeCallbacks(timeout)
        val oldOwner = owner; owner = null
        val oldModule = module; module = null
        val oldListener = listener; listener = null
        if (oldModule != null) {
            registered.forEach { field ->
                try { if (oldListener != null) SyuBinderTransport.unregister(oldModule, oldListener, field) } catch (_: Exception) { }
            }
            try { death?.let { oldModule.unlinkToDeath(it, 0) } } catch (_: Exception) { }
        }
        registered.clear(); death = null
        if (oldOwner != null) try { context.unbindService(oldOwner) } catch (_: RuntimeException) { }
        onClear()
    }

    override fun close() {
        if (!active) return
        active = false
        handler.removeCallbacks(retry)
        clear()
    }

    private companion object { const val CALLBACK = "com.syu.ipc.IModuleCallback" }
}
