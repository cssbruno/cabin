package com.cabin.platform

import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Presence is not proof that BSP events or hardware commands are available. */
enum class SyuFrameworkAccess { NOT_CHECKED, ABSENT, RESTRICTED, TOOLKIT_PRESENT, WRONG_INTERFACE, FAILED, TIMED_OUT }

/** Read-only check of the framework endpoint, separate from the bound com.syu.ms service. */
internal object SyuFrameworkProbe {
    private const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "CabinSyuProbe").apply { isDaemon = true }
    }
    private var pending: Future<SyuFrameworkAccess>? = null

    // A stuck vendor Binder must not accumulate threads or queued requests. Reuse that
    // one outstanding check. Only diagnostics invokes this; it never sends module commands.
    fun inspect(): SyuFrameworkAccess {
        val request = synchronized(this) {
            pending?.takeIf { !it.isDone } ?: executor.submit<SyuFrameworkAccess> {
                inspectEndpoint {
                    Class.forName("android.os.ServiceManager")
                        .getDeclaredMethod("checkService", String::class.java)
                        .invoke(null, "syu") as? IBinder
                }
            }.also { pending = it }
        }
        return try {
            request.get(1500, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            SyuFrameworkAccess.TIMED_OUT
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SyuFrameworkAccess.FAILED
        } catch (_: Exception) {
            SyuFrameworkAccess.FAILED
        }
    }

    internal fun inspectEndpoint(lookup: () -> IBinder?): SyuFrameworkAccess = try {
        val binder = lookup()
        when {
            binder == null -> SyuFrameworkAccess.ABSENT
            binder.interfaceDescriptor == TOOLKIT -> SyuFrameworkAccess.TOOLKIT_PRESENT
            else -> SyuFrameworkAccess.WRONG_INTERFACE
        }
    } catch (error: Exception) {
        val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        if (cause is SecurityException || cause is ReflectiveOperationException) SyuFrameworkAccess.RESTRICTED
        else SyuFrameworkAccess.FAILED
    }
}
