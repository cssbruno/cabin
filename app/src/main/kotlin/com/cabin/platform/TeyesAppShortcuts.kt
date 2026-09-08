package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import java.util.Locale

enum class TeyesShortcut(val label: String) { EQUALIZER("Equalizer / DSP"), TPMS("Tyre pressure"), DASHCAM("Dashcam"), OBD("OBD dashboard") }

data class TeyesLaunchableApp(val component: String, val label: String)

/** User-selected exported launcher activities only. No package guesses or privileged vendor commands. */
object TeyesAppShortcuts {
    fun available(context: Context): List<TeyesLaunchableApp> = launcherActivities(context)
        .mapNotNull { resolved ->
            val info = resolved.activityInfo ?: return@mapNotNull null
            val component = componentOf(info) ?: return@mapNotNull null
            if (!allowed(context, info) || !installedAndAllowed(context, component)) return@mapNotNull null
            val label = try {
                resolved.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: info.packageName
            } catch (_: RuntimeException) {
                info.packageName
            }
            TeyesLaunchableApp(component.flattenToString(), label)
        }
        .distinctBy { it.component }.sortedBy { it.label.lowercase(Locale.ROOT) }

    /** False also means hidden by Android package visibility; never guess an exported target. */
    fun isAvailable(context: Context, savedComponent: String?): Boolean = validatedComponent(context, savedComponent) != null

    fun launch(
        context: Context,
        savedComponent: String?,
    ): Boolean {
        val component = validatedComponent(context, savedComponent) ?: return false
        return try {
            context.startActivity(Intent.makeMainActivity(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun validatedComponent(context: Context, savedComponent: String?): ComponentName? {
        if (savedComponent.isNullOrBlank() || savedComponent.length > 512) return null
        val component = ComponentName.unflattenFromString(savedComponent) ?: return null
        if (component.packageName == context.packageName) return null
        // Query the implicit MAIN/LAUNCHER intent: querying an explicit component ignores
        // its intent filters and would wrongly authorize arbitrary exported activities.
        if (launcherActivities(context).none { resolved ->
                val info = resolved.activityInfo
                info != null && componentOf(info) == component && allowed(context, info)
            }
        ) return null
        return component.takeIf { installedAndAllowed(context, it) }
    }

    private fun installedAndAllowed(context: Context, component: ComponentName): Boolean = try {
        // Re-read current installed metadata; query results can race package changes.
        val info = context.packageManager.getActivityInfo(component, 0)
        componentOf(info) == component && allowed(context, info)
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun launcherActivities(context: Context): List<ResolveInfo> = try {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    } catch (_: RuntimeException) {
        emptyList()
    }

    private fun componentOf(info: ActivityInfo): ComponentName? {
        val packageName = info.packageName?.takeIf { it.isNotBlank() } ?: return null
        val name = info.name?.takeIf { it.isNotBlank() } ?: return null
        return ComponentName(packageName, name)
    }

    private fun allowed(context: Context, info: ActivityInfo): Boolean = try {
        info.exported && info.enabled && info.applicationInfo?.enabled == true &&
            info.packageName != context.packageName &&
            (info.permission.isNullOrEmpty() || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
    } catch (_: RuntimeException) {
        false
    }
}

/** Localized presentation; enum names remain stable for saved configuration. */
@get:androidx.annotation.StringRes
val TeyesShortcut.labelRes: Int
    get() = when (this) {
        TeyesShortcut.EQUALIZER -> com.cabin.R.string.teyes_shortcut_equalizer
        TeyesShortcut.TPMS -> com.cabin.R.string.teyes_shortcut_tpms
        TeyesShortcut.DASHCAM -> com.cabin.R.string.teyes_shortcut_dashcam
        TeyesShortcut.OBD -> com.cabin.R.string.teyes_shortcut_obd
    }
