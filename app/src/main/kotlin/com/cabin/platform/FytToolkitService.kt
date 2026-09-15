package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Resolve firmware-specific service names within the shared vendor package, without a brand gate. */
internal fun fytToolkitIntent(context: Context): Intent {
    return fytServiceIntent(context, "com.syu.ms.toolkit", "app.ToolkitService")
}

internal fun fytCanbusIntent(context: Context): Intent =
    fytServiceIntent(context, "com.syu.ms.canbus", "app.ModuleService")

private fun fytServiceIntent(context: Context, action: String, fallback: String): Intent {
    val intent = Intent(action).setPackage("com.syu.ms")
    val service = try { context.packageManager.resolveService(intent, 0)?.serviceInfo } catch (_: RuntimeException) { null }
    val component = if (service != null && service.packageName == "com.syu.ms" && service.exported && service.enabled) {
        ComponentName(service.packageName, service.name)
    } else ComponentName("com.syu.ms", fallback)
    return intent.setComponent(component)
}

/** Prefer the existing toolkit; some firmware exposes only individual module services. */
internal fun fytModuleIntent(context: Context, module: Int): Intent {
    val toolkit = accessibleFytService(context, "com.syu.ms.toolkit")
    if (toolkit != null) return toolkit
    val action = when (module) {
        0 -> "main"
        1 -> "radio"
        2 -> "bt"
        4 -> "sound"
        7 -> "canbus"
        10 -> "steer"
        else -> return fytToolkitIntent(context)
    }
    return accessibleFytService(context, "com.syu.ms.$action") ?: fytToolkitIntent(context)
}

private fun accessibleFytService(context: Context, action: String): Intent? {
    val intent = Intent(action).setPackage("com.syu.ms")
    val service = try { context.packageManager.resolveService(intent, 0)?.serviceInfo } catch (_: RuntimeException) { null }
        ?: return null
    if (service.packageName != "com.syu.ms" || !service.exported || !service.enabled) return null
    val permission = service.permission
    if (!permission.isNullOrEmpty() && context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    return intent.setComponent(ComponentName(service.packageName, service.name))
}

internal fun bindFytService(context: Context, intent: Intent, connection: android.content.ServiceConnection): Boolean {
    return try {
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE).also { accepted ->
            if (!accepted) com.cabin.telemetry.CabinTelemetry.record(com.cabin.telemetry.DiagnosticEvent.FYT_BIND_REJECTED)
        }
    } catch (error: RuntimeException) {
        com.cabin.telemetry.CabinTelemetry.record(if (error is SecurityException)
            com.cabin.telemetry.DiagnosticEvent.FYT_BIND_DENIED else com.cabin.telemetry.DiagnosticEvent.FYT_BIND_FAILED)
        com.cabin.telemetry.CabinTelemetry.log(com.cabin.logging.Logger.Level.ERROR, error)
        false
    }
}
