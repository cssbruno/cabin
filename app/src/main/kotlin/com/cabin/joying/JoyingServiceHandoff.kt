package com.cabin.joying

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Explicit, reversible handoff for vendor-provisioned installations. Never requests root or flashes firmware. */
internal object JoyingServiceHandoff {
    /** The exported service can be stopped without signature privileges on supporting firmware. */
    fun releaseStockClient(context: Context) {
        if (context.checkSelfPermission("android.permission.FORCE_STOP_PACKAGES") == PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(ActivityManager::class.java)
            ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(manager, "com.syu.carlink")
        } else {
            context.stopService(Intent().setComponent(ComponentName("com.syu.carlink", "com.syu.carlink.CarLinkService")))
        }
    }

    fun stockSettingsIntent() = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.parse("package:com.syu.carlink"))

    fun prepare(context: Context) {
        check(JoyingEmbeddedSession.awaitReleased()) { "CarPlay is still shutting down; try again" }
        releaseStockClient(context)
        val lookup = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        if (lookup.invoke(null, JoyingNativeProtocol.SERVICE) == null) {
            Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
                .invoke(null, "sys.fyt.carplay", "1")
        }
        check(lookup.invoke(null, JoyingNativeProtocol.SERVICE) != null) {
            "Stock Car Link stopped, but the native daemon is not ready. Retry after firmware startup or restore the stock service."
        }
    }

    fun restore(context: Context) {
        check(JoyingEmbeddedSession.awaitReleased()) { "CarPlay is still shutting down; try again" }
        check(context.startService(Intent().setComponent(ComponentName("com.syu.carlink", "com.syu.carlink.CarLinkService"))) != null) {
            "Stock Car Link service could not be restored"
        }
    }
}
