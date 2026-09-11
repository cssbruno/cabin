package com.cabin.launcher

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DefaultHomeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `available home role uses Android consent request`() {
        shadowOf(context.getSystemService(RoleManager::class.java)).addAvailableRole(RoleManager.ROLE_HOME)
        assertEquals("android.app.role.action.REQUEST_ROLE", DefaultHome.requestIntent(context).action)
        assertFalse(DefaultHome.isDefault(context))
    }

    @Test fun `held role is reported and opens settings to switch away`() {
        val roles = shadowOf(context.getSystemService(RoleManager::class.java))
        roles.addAvailableRole(RoleManager.ROLE_HOME)
        roles.addHeldRole(RoleManager.ROLE_HOME)
        assertTrue(DefaultHome.isDefault(context))
        assertEquals(Settings.ACTION_HOME_SETTINGS, DefaultHome.requestIntent(context).action)
        roles.removeHeldRole(RoleManager.ROLE_HOME)
        assertFalse(DefaultHome.isDefault(context))
    }

    @Test @Config(sdk = [27]) fun `older head units use home settings`() {
        assertEquals(Settings.ACTION_HOME_SETTINGS, DefaultHome.requestIntent(context).action)
    }

    @Test @Config(sdk = [27]) fun `manifest exposes Cabin as an Android Home candidate`() {
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(matches.any { it.activityInfo.name == LauncherIntents.ALIAS && it.activityInfo.exported })
    }

    @Test @Config(sdk = [27]) fun `legacy default detection distinguishes Cabin from factory Home`() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pm = shadowOf(context.packageManager)
        fun select(packageName: String) {
            val info = android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    this.packageName = packageName
                    name = "Home"
                }
            }
            pm.setResolveInfosForIntent(intent, listOf(info))
        }
        select(context.packageName)
        assertTrue(DefaultHome.isDefault(context))
        select("factory.launcher")
        assertFalse(DefaultHome.isDefault(context))
    }

    @Test fun `missing OEM home settings falls back to default apps`() {
        val attempted = mutableListOf<String?>()
        assertTrue(DefaultHome.launchWithFallback(Intent(Settings.ACTION_HOME_SETTINGS)) {
            attempted += it.action
            if (it.action == Settings.ACTION_HOME_SETTINGS) throw ActivityNotFoundException()
        })
        assertEquals(listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), attempted)
    }

    @Test fun `no available settings reports failure instead of crashing`() {
        assertFalse(DefaultHome.launchWithFallback(Intent(Settings.ACTION_HOME_SETTINGS)) { throw SecurityException() })
    }
}
