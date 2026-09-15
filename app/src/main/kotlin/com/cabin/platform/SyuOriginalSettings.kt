package com.cabin.platform

import android.content.Context
import android.content.Intent

/** Let the installed SYU launcher select its own screens and command dialect for the live profile. */
internal object SyuOriginalSettings {
    fun intent(context: Context): Intent? = try {
        context.packageManager.getLaunchIntentForPackage("com.syu.canbus")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (_: Exception) { null }

    fun open(context: Context): Boolean {
        val intent = intent(context) ?: return false
        return try { context.startActivity(intent); true } catch (_: Exception) { false }
    }
}
