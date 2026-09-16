package com.cabin.platform

import android.content.Context
import android.hardware.usb.UsbManager
import com.cabin.protocol.KnownDevices

enum class CarPlayBackend { DONGLE, JOYING }
data class CarPlayBackends(val available: List<CarPlayBackend>, val selected: CarPlayBackend?)

internal fun resolveCarPlayBackends(native: Boolean, dongle: Boolean, preferred: CarPlayBackend?): CarPlayBackends {
    val available = buildList {
        if (dongle) add(CarPlayBackend.DONGLE)
        if (native) add(CarPlayBackend.JOYING)
    }
    return CarPlayBackends(available, preferred ?: available.singleOrNull())
}

object CarPlayBackendSelection {
    @Volatile internal var switching = false
    fun preferences(context: Context) = context.applicationContext.getSharedPreferences("carplay_backend", Context.MODE_PRIVATE)
    fun snapshot(context: Context): CarPlayBackends {
        val dongle = context.getSystemService(UsbManager::class.java)?.deviceList?.values?.any {
            KnownDevices.isKnownDevice(it.vendorId, it.productId)
        } == true
        val saved = preferences(context).getString("selected", null)
        return resolveCarPlayBackends(JoyingCarPlay.isAvailable(context), dongle,
            CarPlayBackend.entries.firstOrNull { it.name == saved })
    }
    fun select(context: Context, backend: CarPlayBackend) {
        preferences(context).edit().putString("selected", backend.name).apply()
    }
    fun usesNative(context: Context) = !switching && snapshot(context).selected == CarPlayBackend.JOYING
    fun allowsDongle(context: Context): Boolean = snapshot(context).let {
        !switching && (it.selected == CarPlayBackend.DONGLE || (it.selected == null && it.available.isEmpty()))
    }
}
