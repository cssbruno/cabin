package com.cabin.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

/** Registration lives exactly as long as the visible launcher composition. */
@Composable
internal fun ObserveLauncherPackages(context: Context, onChanged: () -> Unit) {
    val callback by rememberUpdatedState(onChanged)
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { callback() } }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
}

internal enum class ShortcutAvailability { AVAILABLE, REMOVED, DISABLED, FAILED }
internal fun shortcutAvailability(context: Context, component: String): ShortcutAvailability {
    val name = ComponentName.unflattenFromString(component) ?: return ShortcutAvailability.REMOVED
    return try {
        val app = context.packageManager.getApplicationInfo(name.packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        val activity = context.packageManager.getActivityInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS)
        if (!app.enabled || !activity.enabled || context.packageManager.getComponentEnabledSetting(name) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) ShortcutAvailability.DISABLED
        else ShortcutAvailability.AVAILABLE
    } catch (_: PackageManager.NameNotFoundException) { ShortcutAvailability.REMOVED }
      catch (_: RuntimeException) { ShortcutAvailability.FAILED }
}

/** Keep source offsets while stripping combining marks; accents retain their visual spelling. */
internal fun launcherMatchRanges(text: String, query: String): List<IntRange> {
    val normalized = StringBuilder(); val offsets = mutableListOf<Int>()
    text.forEachIndexed { index, char ->
        normalizedLauncherText(char.toString()).forEach { normalized.append(it); offsets += index }
    }
    val ranges = mutableListOf<IntRange>()
    normalizedLauncherText(query).split(Regex("\\s+")).filter(String::isNotBlank).distinct().forEach { token ->
        var index = normalized.indexOf(token)
        while (index >= 0) {
            ranges += offsets[index]..offsets[index + token.length - 1]
            index = normalized.indexOf(token, index + token.length)
        }
    }
    return ranges
}
internal fun highlightedLauncherText(text: String, query: String, background: Color, foreground: Color): AnnotatedString =
    AnnotatedString.Builder(text).apply { launcherMatchRanges(text, query).forEach { addStyle(SpanStyle(background = background, color = foreground), it.first, it.last + 1) } }.toAnnotatedString()
