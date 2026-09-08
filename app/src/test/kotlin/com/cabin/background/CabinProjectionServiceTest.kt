package com.cabin.background

import android.Manifest
import android.app.Application
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CabinProjectionServiceTest {
    private lateinit var application: Application
    private var controller: ServiceController<CabinProjectionService>? = null

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).denyPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun `late grants upgrade foreground types without opening a projection session`() {
        controller = Robolectric.buildService(CabinProjectionService::class.java).create()
        val service = controller!!.get()

        CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, service.foregroundServiceType)

        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            service.foregroundServiceType,
        )

        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.foregroundServiceType,
        )
        assertFalse(CabinProjectionService.hasRunningSession())
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun `grant callback does not start a service when none is running`() {
        shadowOf(application).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        CabinProjectionService.refreshForegroundCapabilitiesFromVisibleActivity()

        assertFalse(CabinProjectionService.hasRunningSession())
        assertNull(shadowOf(application).nextStartedService)
    }
}
