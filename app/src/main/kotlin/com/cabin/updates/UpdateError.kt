package com.cabin.updates

import com.cabin.R
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ConnectException
import javax.net.ssl.SSLException

internal class UpdateException(val errorRes: Int) : Exception("Update failure: $errorRes")

internal fun requireUpdate(condition: Boolean, errorRes: Int) {
    if (!condition) throw UpdateException(errorRes)
}

internal fun updateErrorResource(error: Exception): Int = when (error) {
    is UpdateException -> error.errorRes
    is SocketTimeoutException, is UnknownHostException, is ConnectException -> R.string.update_error_network
    is SSLException -> R.string.update_error_tls
    else -> R.string.update_failed
}
