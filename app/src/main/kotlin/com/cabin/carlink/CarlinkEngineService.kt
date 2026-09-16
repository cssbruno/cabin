package com.cabin.carlink

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.File

/** Private engine process: no global ServiceManager registration or vendor service. */
internal open class CarlinkEngineService : Service() {
    private var engine: IBinder? = null
    private val host = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != CREATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            check(Binder.getCallingUid() == Process.myUid()) { "Carlink engine belongs to Cabin" }
            checkNotNull(reply)
            synchronized(this@CarlinkEngineService) {
                try {
                    val instance = engine ?: createEngine().also { engine = it }
                    reply.writeNoException()
                    reply.writeStrongBinder(instance)
                } catch (error: Throwable) {
                    // Preserve library/ABI failures as a normal IPC error, not a null binding.
                    reply.writeException(IllegalStateException(error.message ?: "Carlink engine could not start"))
                }
            }
            return true
        }
    }

    protected open fun createEngine(): IBinder {
        check(Build.VERSION.SDK_INT == 29 && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            "The imported Carlink engine requires ARM64 Android 10"
        }
        val directory = File(filesDir, "carlink")
        check(File(directory, "KeyChains").mkdirs() || File(directory, "KeyChains").isDirectory) {
            "Cannot create Carlink storage"
        }
        System.loadLibrary("cabin_carlink")
        return CarlinkNative.create(directory.absolutePath)
    }

    override fun onBind(intent: Intent?): IBinder = host

    override fun onDestroy() {
        super.onDestroy()
        terminateEngineProcess()
    }

    // The imported library retains native threads and process-global references.
    // Killing ONLY this private process is its complete, deterministic teardown.
    protected open fun terminateEngineProcess() {
        check(android.app.Application.getProcessName() == "$packageName:carlink")
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val DESCRIPTOR = "com.cabin.carlink.EngineHost"
        const val CREATE = IBinder.FIRST_CALL_TRANSACTION
    }
}

internal object CarlinkNative {
    external fun create(directory: String): IBinder
}
