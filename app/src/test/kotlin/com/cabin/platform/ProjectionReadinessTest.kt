package com.cabin.platform

import com.cabin.CabinManager
import com.cabin.protocol.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class ProjectionReadinessTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    private val connecting = ProjectionReadinessSnapshot(state = CabinManager.State.CONNECTING, sessionRequested = true)

    @Test
    fun `unavailable USB observation is not the same as no attached adapter`() {
        assertEquals(ProjectionUsbAccess.UNKNOWN, projectionUsbReadiness(null).access)
        assertEquals(null, projectionUsbReadiness(null).attachedAdapters)
        assertEquals(ProjectionUsbAccess.NOT_FOUND, projectionUsbReadiness(emptyList()).access)
        assertEquals(0, projectionUsbReadiness(emptyList()).attachedAdapters)
        assertEquals("USB status is unavailable", projectionReadinessPresentation(testResources, connecting).title)
        assertEquals(
            "No supported USB adapter detected",
            projectionReadinessPresentation(testResources, connecting.copy(usb = projectionUsbReadiness(emptyList()))).title,
        )
    }

    @Test
    fun `any permitted attached adapter wins over denied or unknown permission on others`() {
        val usb = projectionUsbReadiness(listOf(false, null, true))
        assertEquals(ProjectionUsbAccess.PERMITTED, usb.access)
        assertEquals(3, usb.attachedAdapters)
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(usb = usb))
        assertEquals("USB access is available", presentation.title)
        assertTrue(presentation.usbDetail.contains("3 recognized adapters"))
        assertFalse(presentation.canConnectPhone)
    }

    @Test
    fun `failed permission check cannot be presented as denied`() {
        assertEquals(ProjectionUsbAccess.UNKNOWN, projectionUsbReadiness(listOf(false, null)).access)
        assertEquals(ProjectionUsbAccess.PERMISSION_NEEDED, projectionUsbReadiness(listOf(false, false)).access)
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(usb = projectionUsbReadiness(listOf(false))))
        assertEquals("USB access needs approval", presentation.title)
        assertTrue(presentation.nextStep.contains("does not open a new prompt"))
        assertFalse(presentation.canConnectPhone)
    }

    @Test
    fun `paused phone has an explicit Connect even while adapter state remains connecting`() {
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(phoneConnectionAllowed = false, adapterOpened = true))
        assertEquals("Phone connection paused", presentation.title)
        assertTrue(presentation.canConnectPhone)
        assertTrue(presentation.nextStep.contains("restarting the adapter alone keeps this pause"))
    }

    @Test
    fun `idle adapter is distinct from a paused phone`() {
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(sessionRequested = false))
        assertEquals("Connection is idle", presentation.title)
        assertTrue(presentation.canConnectPhone)
        assertFalse(presentation.phoneDetail.contains("paused"))
    }

    @Test
    fun `opened USB interface is not a completed phone session`() {
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(adapterOpened = true))
        assertEquals("USB adapter opened", presentation.title)
        assertTrue(presentation.phoneDetail.contains("No connected phone"))
        assertFalse(presentation.canConnectPhone)
    }

    @Test
    fun `connected phone is not yet receiving projection video`() {
        val presentation = projectionReadinessPresentation(testResources, connecting.copy(state = CabinManager.State.DEVICE_CONNECTED))
        assertEquals("Phone connected", presentation.title)
        assertTrue(presentation.phoneDetail.contains("video has not started"))
        assertFalse(presentation.canConnectPhone)
    }

    @Test
    fun `streaming phone name follows observed protocol without claiming display quality`() {
        val streaming = connecting.copy(state = CabinManager.State.STREAMING)
        assertEquals("Projection is connected", projectionReadinessPresentation(testResources, streaming).title)
        assertEquals("CarPlay is connected", projectionReadinessPresentation(testResources, streaming.copy(phoneType = PhoneType.CARPLAY_WIRELESS)).title)
        assertEquals("Android Auto is connected", projectionReadinessPresentation(testResources, streaming.copy(phoneType = PhoneType.ANDROID_AUTO)).title)
        assertTrue(projectionReadinessPresentation(testResources, streaming).phoneDetail.contains("not a signal-strength or display-quality test"))
        assertFalse(projectionReadinessPresentation(testResources, streaming).canConnectPhone)
    }

    @Test
    fun `new stop and phone pause intent take priority over a previous streaming state`() {
        val streaming = connecting.copy(state = CabinManager.State.STREAMING)
        assertEquals("Connection is idle", projectionReadinessPresentation(testResources, streaming.copy(sessionRequested = false)).title)
        assertEquals("Phone connection paused", projectionReadinessPresentation(testResources, streaming.copy(phoneConnectionAllowed = false)).title)
    }

    @Test
    fun `denied optional permissions do not block the main projection stage`() {
        val presentation =
            projectionReadinessPresentation(testResources,
                connecting.copy(
                    state = CabinManager.State.STREAMING,
                    microphone = ProjectionOptionalCapability.PERMISSION_NEEDED,
                    location = ProjectionOptionalCapability.PERMISSION_NEEDED,
                ),
            )
        assertEquals("Projection is connected", presentation.title)
        assertTrue(presentation.microphoneDetail.contains("Projection can still connect"))
        assertTrue(presentation.locationDetail.contains("Projection can still connect"))
    }

    @Test
    fun `unrequested optional features do not need permissions or background access`() {
        assertEquals(ProjectionOptionalCapability.NOT_REQUESTED, projectionOptionalCapability(false, null, false))
        assertEquals(ProjectionOptionalCapability.NOT_REQUESTED, projectionOptionalCapability(false, false, false))
        assertEquals(ProjectionOptionalCapability.UNKNOWN, projectionOptionalCapability(null, true, true))
        assertEquals(ProjectionOptionalCapability.UNKNOWN, projectionOptionalCapability(true, null, true))
    }

    @Test
    fun `optional permission denial and background restriction are distinct`() {
        assertEquals(ProjectionOptionalCapability.PERMISSION_NEEDED, projectionOptionalCapability(true, false, false))
        assertEquals(ProjectionOptionalCapability.BACKGROUND_RESTRICTED, projectionOptionalCapability(true, true, false))
        assertEquals(ProjectionOptionalCapability.AVAILABLE, projectionOptionalCapability(true, true, true))
    }

    @Test
    fun `disconnected session can be explicitly retried without automatic reset in the evaluator`() {
        val snapshot = connecting.copy(state = CabinManager.State.DISCONNECTED)
        repeat(3) { assertTrue(projectionReadinessPresentation(testResources, snapshot).canConnectPhone) }
        assertEquals(CabinManager.State.DISCONNECTED, snapshot.state)
        assertTrue(snapshot.sessionRequested)
    }
}
