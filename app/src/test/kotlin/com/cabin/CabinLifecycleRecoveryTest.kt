package com.cabin

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.cabin.gnss.GnssForwarder
import com.cabin.navigation.Iap2RouteParser
import com.cabin.navigation.compose.ComposedIconStore
import com.cabin.navigation.compose.ManeuverIconDebugDumper
import com.cabin.protocol.AdapterConfig
import com.cabin.protocol.MediaDataMessage
import com.cabin.protocol.MediaType
import com.cabin.protocol.MessageHeader
import com.cabin.protocol.MessageType
import com.cabin.ui.settings.AdapterConfigPreference
import com.cabin.ui.settings.MicSourceConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinLifecycleRecoveryTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `cancelled USB discovery cleans up and permits a new start`() = runBlocking {
        val manager = CabinManager(application)
        val connecting = Channel<Unit>(Channel.UNLIMITED)
        ReflectionHelpers.setField(manager, "callback", object : CabinManager.Callback {
            override fun onStateChanged(state: CabinManager.State) {
                if (state == CabinManager.State.CONNECTING) connecting.trySend(Unit)
            }
            override fun onStatusTextChanged(text: String) = Unit
            override fun onHostUIPressed() = Unit
        })
        try {
            repeat(2) {
                val start = launch(Dispatchers.IO) { manager.start() }
                try {
                    withTimeout(5_000) { connecting.receive() }
                } finally {
                    start.cancelAndJoin()
                }
                assertEquals(CabinManager.State.DISCONNECTED, manager.state)
                assertNotNull(ReflectionHelpers.getField<Any?>(manager, "reconnectJob"))
            }
            manager.stopAndWait()
            manager.resumeRequestedSession()
            assertEquals(CabinManager.State.DISCONNECTED, manager.state)
        } finally {
            manager.releaseAndWait()
            connecting.close()
        }
    }

    @Test
    fun `location permission promotion retries forwarding without a state change`() = runBlocking {
        val preferences = AdapterConfigPreference.getInstance(application)
        preferences.setGpsForwarding(true)
        preferences.setMicSource(MicSourceConfig.APP)
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = CabinManager(application, AdapterConfig.DEFAULT.copy(gpsForwarding = true, micType = "os"))
        val forwarder = GnssForwarder(application, { true }, {})
        val locations = shadowOf(application.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        ReflectionHelpers.setField(manager, "gnssForwarder", forwarder)
        markStreaming(manager)
        try {
            forwarder.start()
            assertTrue(locations.locationUpdateListeners.isEmpty())
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            repeat(2) { manager.setSensitiveBackgroundCapabilitiesAvailable(true).join() }
            assertEquals(1, locations.locationUpdateListeners.size)
            assertEquals(CabinManager.State.STREAMING, manager.state)
        } finally {
            manager.releaseAndWait()
        }
        assertTrue(locations.locationUpdateListeners.isEmpty())
    }

    @Test
    fun `visible promotion restores preferences after a restricted sticky start`() = runBlocking {
        val preferences = AdapterConfigPreference.getInstance(application)
        preferences.setGpsForwarding(true)
        preferences.setMicSource(MicSourceConfig.APP)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = CabinManager(application, AdapterConfig.DEFAULT.copy(gpsForwarding = true, micType = "os"))
        val locations = shadowOf(application.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        try {
            manager.setSensitiveBackgroundCapabilitiesAvailable(false).join()
            val restricted = ReflectionHelpers.getField<AdapterConfig>(manager, "config")
            assertFalse(restricted.gpsForwarding)
            assertEquals("box", restricted.micType)
            assertNull(ReflectionHelpers.getField<Any?>(manager, "gnssForwarder"))
            markStreaming(manager)

            manager.setSensitiveBackgroundCapabilitiesAvailable(true).join()
            val restored = ReflectionHelpers.getField<AdapterConfig>(manager, "config")
            assertTrue(restored.gpsForwarding)
            assertEquals("os", restored.micType)
            assertEquals(1, locations.locationUpdateListeners.size)
            assertTrue(preferences.getUserConfigSync().gpsForwarding)
            assertEquals(MicSourceConfig.APP, preferences.getUserConfigSync().micSource)

            manager.setSensitiveBackgroundCapabilitiesAvailable(false).join()
            assertTrue(locations.locationUpdateListeners.isEmpty())
        } finally {
            manager.releaseAndWait()
        }
    }

    @Test
    fun `disabled cluster navigation skips route processing and automatic PNG export`() = runBlocking {
        ManeuverIconDebugDumper.disable()
        ComposedIconStore.clear()
        AdapterConfigPreference.getInstance(application).setClusterNavigation(false)
        // One ordinary left-turn maneuver, with a complete iAP2 frame and trailing pad.
        val route = "40400017520200060000002a000600010000000500030100"
        assertEquals(1, Iap2RouteParser.parse(route)?.maneuvers?.size)
        val manager = CabinManager(application)
        try {
            assertNull(ComposedIconStore.debugSink)
            ReflectionHelpers.callInstanceMethod<Unit>(
                manager,
                "processMediaMetadata",
                ReflectionHelpers.ClassParameter.from(
                    MediaDataMessage::class.java,
                    MediaDataMessage(MessageHeader(0, MessageType.MEDIA_DATA), MediaType.NAVI_JSON, mapOf("_iap2m" to route)),
                ),
            )
            assertNull(ComposedIconStore.currentRoute())
            if (BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) assertFalse(ComposedIconStore.bitmapComposeEnabled)
        } finally {
            manager.releaseAndWait()
            ManeuverIconDebugDumper.disable()
            ComposedIconStore.clear()
        }
    }

    private fun markStreaming(manager: CabinManager) {
        ReflectionHelpers.getField<AtomicBoolean>(manager, "shouldBeRunning").set(true)
        ReflectionHelpers.getField<AtomicReference<CabinManager.State>>(manager, "currentState")
            .set(CabinManager.State.STREAMING)
    }
}
