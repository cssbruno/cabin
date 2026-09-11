package com.cabin.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Ask Android to change HOME; never clear or disable the manufacturer's launcher. */
object DefaultHome {
    fun isDefault(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true) {
                return@runCatching roles.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    fun requestIntent(context: Context): Intent = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return@runCatching roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
            }
        }
        Intent(Settings.ACTION_HOME_SETTINGS)
    }.getOrElse { Intent(Settings.ACTION_HOME_SETTINGS) }

    internal fun launchWithFallback(primary: Intent, launch: (Intent) -> Unit): Boolean {
        val candidates = listOf(primary, Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            .distinctBy { it.action }
        for (intent in candidates) {
            try { launch(intent); return true } catch (_: RuntimeException) { /* OEM settings may omit this activity. */ }
        }
        return false
    }
}

class DefaultHomeState internal constructor() {
    var isDefault by mutableStateOf(false)
        internal set
    var unavailable by mutableStateOf(false)
        internal set
    internal var request: () -> Unit = {}
    fun choose() = request()
}

@Composable
fun rememberDefaultHomeState(): DefaultHomeState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context) { DefaultHomeState().apply { isDefault = DefaultHome.isDefault(context) } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Cancellation is not success. Always read the actual system selection.
        state.isDefault = DefaultHome.isDefault(context)
    }
    SideEffect {
        state.request = {
            state.unavailable = !DefaultHome.launchWithFallback(DefaultHome.requestIntent(context)) { launcher.launch(it) }
        }
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.isDefault = DefaultHome.isDefault(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}
