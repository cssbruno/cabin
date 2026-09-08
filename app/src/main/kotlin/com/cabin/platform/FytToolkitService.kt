package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Resolve firmware-specific service names within the shared vendor package, without a brand gate. */
internal fun fytToolkitIntent(context: Context): Intent {
    val intent = Intent("com.syu.ms.toolkit").setPackage("com.syu.ms")
    val service = try { context.packageManager.resolveService(intent, 0)?.serviceInfo } catch (_: RuntimeException) { null }
    val component = if (service != null && service.packageName == "com.syu.ms" && service.exported && service.enabled) {
        ComponentName(service.packageName, service.name)
    } else ComponentName("com.syu.ms", "app.ToolkitService")
    return intent.setComponent(component)
}
