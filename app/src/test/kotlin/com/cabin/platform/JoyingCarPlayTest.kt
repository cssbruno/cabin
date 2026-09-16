package com.cabin.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingCarPlayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    private fun install() {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val directory = temporary.newFolder()
        context.applicationInfo.nativeLibraryDir = directory.absolutePath
        for (name in listOf("libcabin_carlink.so", "libcps_7862.so", "libcarplay_plugin_r14g.so")) {
            java.io.File(directory, name).writeBytes(byteArrayOf(1))
        }
    }

    @Test fun `missing native runtime preserves adapter mode`() {
        assertFalse(JoyingCarPlay.isAvailable(context))
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun `connect opens Cabin embedded projection instead of stock activity`() {
        install()
        assertTrue(JoyingCarPlay.isAvailable(context))
        assertTrue(JoyingCarPlay.open(context))
        val intent = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(com.cabin.MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(com.cabin.MainActivity.ACTION_SHOW_FULLSCREEN_PROJECTION, intent.action)
    }

    @Test fun `automatic startup does not launch stock app or adapter service`() {
        install()
        com.cabin.background.CabinProjectionService.start(context)
        val app = shadowOf(context as android.app.Application)
        assertNull(app.nextStartedActivity)
        assertNull(app.nextStartedService)
    }

    @Test fun `explicit connect routes to Cabin without starting adapter service`() {
        install()
        com.cabin.background.CabinProjectionService.startPhoneConnection(context)
        val app = shadowOf(context as android.app.Application)
        assertEquals(com.cabin.MainActivity::class.java.name, app.nextStartedActivity.component?.className)
        assertNull(app.nextStartedService)
    }

    @Test fun `stock app alone cannot advertise embedded runtime`() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.syu.carlink"
            applicationInfo = ApplicationInfo().apply { packageName = "com.syu.carlink"; enabled = true }
        })
        assertFalse(JoyingCarPlay.isAvailable(context))
    }

    @Test fun `bundled engine requires the verified ABI and complete library set`() {
        install()
        val directory = context.applicationInfo.nativeLibraryDir
        assertTrue(JoyingCarPlay.bundledRuntimeAvailable(29, "arm64-v8a", directory))
        assertFalse(JoyingCarPlay.bundledRuntimeAvailable(30, "arm64-v8a", directory))
        assertFalse(JoyingCarPlay.bundledRuntimeAvailable(29, "x86_64", directory))
        java.io.File(directory, "libcps_7862.so").delete()
        assertFalse(JoyingCarPlay.bundledRuntimeAvailable(29, "arm64-v8a", directory))
    }

    @Test fun `package disappearing during launch is handled`() {
        install()
        val failing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(JoyingCarPlay.open(failing))
    }
}
