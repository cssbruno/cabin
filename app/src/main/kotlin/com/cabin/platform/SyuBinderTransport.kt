package com.cabin.platform

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** FYT protocol with both legacy exception headers and Joying void replies.
 * Call from an owned background worker, never the UI thread.
 * Bounds apply to parcel sizes; Android Binder does not offer a cancellable transaction timeout.
 */
internal object SyuBinderTransport {
    const val MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
    const val MAX_PARCEL_BYTES = 64 * 1024

    fun <T> transact(remote: IBinder, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T =
        exchange(remote, MODULE_DESCRIPTOR, code, write, read)

    private fun <T> exchange(remote: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            write(data)
            require(data.dataSize() <= MAX_PARCEL_BYTES) { "SYU request exceeds parcel limit" }
            if (!remote.transact(code, data, reply, 0)) throw RemoteException("Unsupported SYU transaction $code")
            // Joying's x/c$a stub returns without writing a reply for cmd/register/unregister.
            // Keep synchronous dispatch for legacy stubs that write exception headers, but
            // accept an empty body ONLY for these void module methods. Never resend commands.
            val emptyVoidReply = descriptor == MODULE_DESCRIPTOR && code in intArrayOf(1, 3, 4) && reply.dataSize() == 0
            if (emptyVoidReply) return read(reply)
            if (reply.dataSize() > MAX_PARCEL_BYTES || reply.dataAvail() < 4) throw RemoteException("Invalid SYU reply size")
            reply.readException()
            read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getModule(toolkit: IBinder, module: Int): IBinder? {
        try {
            // ModuleService already returns IRemoteModule; don't send it a toolkit request.
            if (toolkit.interfaceDescriptor == MODULE_DESCRIPTOR) return toolkit
            return exchange(toolkit, "com.syu.ipc.IRemoteToolkit", 1, { it.writeInt(module) }, { it.readStrongBinder() }).also {
                if (it == null) com.cabin.telemetry.CabinTelemetry.record(com.cabin.telemetry.DiagnosticEvent.FYT_MODULE_UNAVAILABLE)
            }
        } catch (error: Exception) {
            com.cabin.telemetry.CabinTelemetry.record(com.cabin.telemetry.DiagnosticEvent.FYT_MODULE_FAILED)
            com.cabin.telemetry.CabinTelemetry.log(com.cabin.logging.Logger.Level.ERROR, error)
            throw error
        }
    }

    fun register(remote: IBinder, callback: IBinder, field: Int) {
        transact(remote, 3, { it.writeStrongBinder(callback); it.writeInt(field); it.writeInt(1) }, {})
    }

    fun unregister(remote: IBinder, callback: IBinder, field: Int) {
        transact(remote, 4, { it.writeStrongBinder(callback); it.writeInt(field) }, {})
    }
}
