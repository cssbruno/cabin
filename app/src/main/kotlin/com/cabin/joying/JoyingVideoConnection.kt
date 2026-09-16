package com.cabin.joying

import android.system.ErrnoException
import android.system.OsConstants
import java.io.IOException

/** Retry only ownership conflicts; a denied bind cannot be repaired by stopping another app. */
internal object JoyingVideoConnection {
    enum class Failure { BUSY, DENIED, HANDOFF_BLOCKED, OTHER }
    class ConnectionException(val reason: Failure, message: String, cause: Throwable) : IllegalStateException(message, cause) {
        val retryable get() = reason == Failure.OTHER
    }

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
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            throw ConnectionException(Failure.HANDOFF_BLOCKED, "Carlink cannot acquire the video connection. Firmware integration must release the existing client and grant Cabin access.", error)
        }
        // Allow up to two seconds for the stopped process to release its descriptors.
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

    private fun failure(error: IOException) = ConnectionException(classify(error), when (classify(error)) {
        Failure.BUSY -> "The Carlink video connection is still in use. Firmware integration must release the existing client."
        Failure.DENIED -> "Carlink firmware denied Cabin access to CarPlay video. This requires firmware-provided access; Retry cannot grant it."
        Failure.HANDOFF_BLOCKED -> "Carlink did not allow the stock video connection to be released."
        Failure.OTHER -> "Carlink video connection failed: ${error.message ?: error.javaClass.simpleName}"
    }, error)
}
