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
