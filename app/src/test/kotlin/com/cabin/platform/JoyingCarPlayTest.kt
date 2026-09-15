package com.cabin.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
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

    private fun install(enabled: Boolean = true, exported: Boolean = true) {
        val app = ApplicationInfo().apply { packageName = JoyingCarPlay.PACKAGE; this.enabled = true }
        val activity = ActivityInfo().apply {
            name = JoyingCarPlay.ACTIVITY
            packageName = JoyingCarPlay.PACKAGE
            applicationInfo = app
            this.enabled = enabled
            this.exported = exported
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = JoyingCarPlay.PACKAGE
            applicationInfo = app
            activities = arrayOf(activity)
        })
    }

    @Test fun `missing stock app preserves adapter mode`() {
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

    @Test fun `disabled or private activity is not selected`() {
        install(enabled = false)
        assertFalse(JoyingCarPlay.isAvailable(context))
        install(exported = false)
        assertFalse(JoyingCarPlay.isAvailable(context))
    }

    @Test fun `package disappearing during launch is handled`() {
        install()
        val failing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(JoyingCarPlay.open(failing))
    }
}
