package com.cabin.joying

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingServiceHandoffTest {
    @Test fun `manual handoff opens only the stock Car Link app settings`() {
        val intent = JoyingServiceHandoff.stockSettingsIntent()
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:com.syu.carlink", intent.data.toString())
    }

    @Test fun `ordinary installation requests exported service stop without force stop permission`() {
        var stopped: ComponentName? = null
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
            override fun stopService(service: Intent): Boolean { stopped = service.component; return true }
        }
        JoyingServiceHandoff.releaseStockClient(context)
        assertEquals(ComponentName("com.syu.carlink", "com.syu.carlink.CarLinkService"), stopped)
    }
}
