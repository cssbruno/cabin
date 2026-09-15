package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Stock entry point verified in the Joying 2023-08-31 image. No adapter protocol is used. */
object JoyingCarPlay {
    const val PACKAGE = "com.syu.carlink"
    const val ACTIVITY = "com.syu.carlink.MainActivity"

    fun launchIntent() = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(PACKAGE, ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isAvailable(context: Context): Boolean = try {
        val info = context.packageManager.getActivityInfo(ComponentName(PACKAGE, ACTIVITY), 0)
        info.enabled && info.applicationInfo.enabled && info.exported &&
            context.packageManager.resolveActivity(launchIntent(), 0) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Open Cabin's own embedded projection page; never launch the vendor activity. */
    fun open(context: Context): Boolean = try {
        context.startActivity(Intent(context, com.cabin.MainActivity::class.java)
            .setAction(com.cabin.MainActivity.ACTION_SHOW_FULLSCREEN_PROJECTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
