package com.cabin.test

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Attach a detected dongle without granting USB access or starting projection. */
fun attachCarPlayDongle(context: Context) {
    val device = Shadow.newInstanceOf(UsbDevice::class.java)
    ReflectionHelpers.setField(device, "mName", "/dev/bus/usb/001/001")
    ReflectionHelpers.setField(device, "mVendorId", 0x1314)
    ReflectionHelpers.setField(device, "mProductId", 0x1520)
    shadowOf(context.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, false)
}
