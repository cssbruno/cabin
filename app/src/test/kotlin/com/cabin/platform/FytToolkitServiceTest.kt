package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class FytToolkitServiceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `uses the standard service when firmware has no action resolver`() {
        assertEquals(ComponentName("com.syu.ms", "app.ToolkitService"), fytToolkitIntent(context).component)
    }
    @Test fun `discovers a firmware-specific exported toolkit component`() {
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            packageName = "com.syu.ms"; name = "vendor.ToolkitService"; exported = true; enabled = true
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"), info)
        assertEquals(ComponentName("com.syu.ms", "vendor.ToolkitService"), fytToolkitIntent(context).component)
        info.serviceInfo.exported = false
        assertEquals(ComponentName("com.syu.ms", "app.ToolkitService"), fytToolkitIntent(context).component)
    }
    @Test fun `uses an exported direct module when toolkit is unavailable`() {
        val action = Intent("com.syu.ms.radio").setPackage("com.syu.ms")
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            packageName = "com.syu.ms"; name = "app.ModuleService"; exported = true; enabled = true
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(action, info)
        val selected = fytModuleIntent(context, 1)
        assertEquals("com.syu.ms.radio", selected.action)
        assertEquals(ComponentName("com.syu.ms", "app.ModuleService"), selected.component)
    }

    @Test fun `does not choose a private direct module`() {
        val info = ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
            packageName = "com.syu.ms"; name = "private.Sound"; exported = false; enabled = true
        } }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent("com.syu.ms.sound").setPackage("com.syu.ms"), info)
        assertEquals("com.syu.ms.toolkit", fytModuleIntent(context, 4).action)
    }

}
