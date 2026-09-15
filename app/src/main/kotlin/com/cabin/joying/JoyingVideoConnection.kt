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
            throw ConnectionException(Failure.HANDOFF_BLOCKED, "Car Link is using the video connection. Joying did not allow Cabin to release it. Open Stock Car Link settings, tap Force stop, return to Cabin and Retry. If Force stop is unavailable, use the Joying ADB handoff tool.", error)
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

    private fun failure(error: IOException) = ConnectionException(classify(error), when (classify(error)) {
        Failure.BUSY -> "Joying’s video connection is still in use after releasing Car Link. Open Stock Car Link settings, tap Force stop, return to Cabin and Retry. If Force stop is unavailable, use the Joying ADB handoff tool."
        Failure.DENIED -> "Joying firmware denied Cabin access to CarPlay video. This requires firmware-provided access; Retry cannot grant it."
        Failure.HANDOFF_BLOCKED -> "Joying did not allow the stock video connection to be released."
        Failure.OTHER -> "Joying video connection failed: ${error.message ?: error.javaClass.simpleName}"
    }, error)
}
