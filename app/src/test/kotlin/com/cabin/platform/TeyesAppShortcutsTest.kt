package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TeyesAppShortcutsTest {
    private lateinit var context: Context

    @Before fun setup() { context = ApplicationProvider.getApplicationContext() }

    private fun install(
        packageName: String = "example.accessory",
        className: String = "$packageName.Main",
        label: String = "Accessory",
        launcher: Boolean = true,
        exported: Boolean = true,
        enabled: Boolean = true,
        applicationEnabled: Boolean = true,
        permission: String? = null,
    ): ComponentName {
        val component = ComponentName(packageName, className)
        val info = ActivityInfo().apply {
            this.packageName = packageName
            name = className
            nonLocalizedLabel = label
            this.exported = exported
            this.enabled = enabled
            this.permission = permission
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.enabled = applicationEnabled
            }
        }
        val manager = shadowOf(context.packageManager)
        manager.addOrUpdateActivity(info)
        if (launcher) manager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        return component
    }

    @Test fun `installed exported launcher is available and launched explicitly`() {
        val component = install()
        val saved = component.flattenToString()
        assertTrue(TeyesAppShortcuts.isAvailable(context, saved))
        assertTrue(TeyesAppShortcuts.available(context).any { it.component == saved && it.label == "Accessory" })
        var launched: Intent? = null
        val recording = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        assertTrue(TeyesAppShortcuts.launch(recording, saved))
        assertEquals(component, launched?.component)
        assertEquals(Intent.ACTION_MAIN, launched?.action)
        assertTrue(launched?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
        assertTrue((launched!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test fun `imported exported nonlauncher cannot be opened`() {
        val component = install(launcher = false)
        assertFalse(TeyesAppShortcuts.isAvailable(context, component.flattenToString()))
        assertFalse(TeyesAppShortcuts.launch(context, component.flattenToString()))
    }

    @Test fun `removed activity cannot be reopened from saved configuration`() {
        val component = install()
        assertTrue(TeyesAppShortcuts.isAvailable(context, component.flattenToString()))
        shadowOf(context.packageManager).removeActivity(component)
        assertFalse(TeyesAppShortcuts.isAvailable(context, component.flattenToString()))
        assertFalse(TeyesAppShortcuts.launch(context, component.flattenToString()))
    }

    @Test fun `own app and nonexported activities are never shortcuts`() {
        val own = install(packageName = context.packageName)
        val private = install(packageName = "example.private", exported = false)
        assertFalse(TeyesAppShortcuts.isAvailable(context, own.flattenToString()))
        assertFalse(TeyesAppShortcuts.isAvailable(context, private.flattenToString()))
        assertTrue(TeyesAppShortcuts.available(context).none { it.component in setOf(own.flattenToString(), private.flattenToString()) })
    }

    @Test fun `disabled component and disabled application are rejected`() {
        val disabled = install(packageName = "example.disabled", enabled = false)
        val disabledApp = install(packageName = "example.disabledapp", applicationEnabled = false)
        assertFalse(TeyesAppShortcuts.isAvailable(context, disabled.flattenToString()))
        assertFalse(TeyesAppShortcuts.isAvailable(context, disabledApp.flattenToString()))
    }

    @Test fun `permission protected launcher is not offered without that permission`() {
        val component = install(permission = "example.permission.PRIVATE")
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        }
        assertFalse(TeyesAppShortcuts.isAvailable(denied, component.flattenToString()))
        assertTrue(TeyesAppShortcuts.available(denied).none { it.component == component.flattenToString() })
    }

    @Test fun `query service failures return unavailable instead of crashing`() {
        val component = install()
        val unavailable = object : ContextWrapper(context) {
            override fun getPackageManager(): PackageManager = throw IllegalStateException("Package manager unavailable")
        }
        assertTrue(TeyesAppShortcuts.available(unavailable).isEmpty())
        assertFalse(TeyesAppShortcuts.isAvailable(unavailable, component.flattenToString()))
        assertFalse(TeyesAppShortcuts.launch(unavailable, component.flattenToString()))
    }

    @Test fun `permission lookup failures return unavailable instead of crashing`() {
        val component = install(permission = "example.permission.PRIVATE")
        val unavailable = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String): Int = throw SecurityException("Permission service unavailable")
        }
        assertTrue(TeyesAppShortcuts.available(unavailable).isEmpty())
        assertFalse(TeyesAppShortcuts.isAvailable(unavailable, component.flattenToString()))
    }

    @Test fun `launch rejection after validation returns false`() {
        val component = install()
        val rejected = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw SecurityException("Package changed") }
        }
        assertFalse(TeyesAppShortcuts.launch(rejected, component.flattenToString()))
    }

    @Test fun `invalid imported components are rejected`() {
        listOf(null, "", "garbage", "missing.app/.Main", "x".repeat(513)).forEach {
            assertFalse(TeyesAppShortcuts.isAvailable(context, it))
        }
    }
}
