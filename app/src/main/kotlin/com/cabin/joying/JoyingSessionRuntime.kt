package com.cabin.joying

import android.view.MotionEvent
import android.view.Surface
import java.io.Closeable

internal interface JoyingSessionRuntime : Closeable {
    fun start()
    fun attach(surface: Surface)
    fun detach(surface: Surface)
    fun touch(event: MotionEvent, width: Int, height: Int): Boolean
    fun connectPhone(address: String)
    fun enableWireless()
    fun siri()
}
