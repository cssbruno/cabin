package com.cabin.joying

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.os.IBinder

/** Explicit, reversible handoff for vendor-provisioned installations. Never requests root or flashes firmware. */
internal object JoyingServiceHandoff {
    /** Stock onDestroy does not close its socket thread. Only a process stop releases ownership. */
    fun releaseStockClient(context: Context) {
        if (context.checkSelfPermission("android.permission.FORCE_STOP_PACKAGES") == PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(ActivityManager::class.java)
            ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(manager, "com.syu.carlink")
        } else {
            throw SecurityException("This firmware has not granted Cabin permission to release the CarPlay connection. Cabin firmware integration is required.")
        }
    }

    fun prepare(context: Context) {
        check(JoyingEmbeddedSession.awaitReleased()) { "CarPlay is still shutting down; try again" }
        // A prior manual Force stop may already have freed the socket. Do not require
        // privileged handoff in that case, and never report success just from stopService.
        JoyingVideoConnection.open(
            bind = { LocalServerSocket(JoyingNativeProtocol.VIDEO_SOCKET) },
            releaseStock = { releaseStockClient(context) },
            pause = { Thread.sleep(200) },
        ).close()
        nativeService()
    }

    fun nativeService(): IBinder {
        val lookup = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        return awaitNativeService(
            lookup = { lookup.invoke(null, JoyingNativeProtocol.SERVICE) as? IBinder },
            start = {
                Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                    .invoke(null, "sys.fyt.carplay", "1")
            },
            pause = { Thread.sleep(200) },
        )
    }

    internal fun <T : Any> awaitNativeService(lookup: () -> T?, start: () -> Unit, pause: () -> Unit): T {
        lookup()?.let { return it }
        start()
        repeat(25) {
            pause()
            lookup()?.let { return it }
        }
        error("Joying CarPlay service did not start. Retry in Cabin. If this continues, the firmware integration needs attention.")
    }
}
