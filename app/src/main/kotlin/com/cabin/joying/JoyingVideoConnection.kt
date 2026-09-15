package com.cabin.joying

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException

/** Retry only ownership conflicts; a denied bind cannot be repaired by stopping another app. */
internal object JoyingVideoConnection {
    enum class Failure { BUSY, DENIED, OTHER }

    fun classify(error: Throwable): Failure {
        var cause: Throwable? = error
        repeat(16) {
            val current = cause ?: return Failure.OTHER
            if (current is ErrnoException) {
                when (current.errno) {
                    OsConstants.EADDRINUSE -> return Failure.BUSY
                    OsConstants.EACCES, OsConstants.EPERM -> return Failure.DENIED
                }
            }
            val message = current.message.orEmpty()
            if (message.contains("EADDRINUSE", true) || message.contains("Address already in use", true)) return Failure.BUSY
            if (message.contains("EACCES", true) || message.contains("EPERM", true) ||
                message.contains("Permission denied", true)) return Failure.DENIED
            cause = current.cause
        }
        return Failure.OTHER
    }

    fun <T> open(bind: () -> T, releaseStock: () -> Unit, pause: () -> Unit): T {
        try {
            return bind()
        } catch (error: IOException) {
            if (classify(error) != Failure.BUSY) throw failure(error)
        }
        try {
            releaseStock()
        } catch (error: Exception) {
            throw IllegalStateException("Car Link is using the video connection. Joying did not allow Cabin to release it. Use the Joying ADB handoff tool, then Retry.", error)
        }
        // stopService is asynchronous. Allow up to two seconds for vendor cleanup.
        repeat(10) {
            pause()
            try {
                return bind()
            } catch (error: IOException) {
                if (classify(error) != Failure.BUSY || it == 9) throw failure(error)
            }
        }
        error("Unreachable")
    }

    private fun failure(error: IOException) = IllegalStateException(when (classify(error)) {
        Failure.BUSY -> "Joying’s video connection is still in use after releasing Car Link. Use the Joying ADB handoff tool, then Retry."
        Failure.DENIED -> "Joying firmware denied Cabin access to CarPlay video. This requires firmware-provided access; Retry cannot grant it."
        Failure.OTHER -> "Joying video connection failed: ${error.message ?: error.javaClass.simpleName}"
    }, error)
}
