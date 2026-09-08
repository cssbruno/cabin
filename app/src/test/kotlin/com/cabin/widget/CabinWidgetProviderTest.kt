package com.cabin.widget

import com.cabin.CabinManager
import com.cabin.R
import com.cabin.protocol.PhoneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class CabinWidgetProviderTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test
    fun `wired CarPlay stream renders live open state`() {
        val model =
            snapshot(
                state = CabinManager.State.STREAMING,
                phoneType = PhoneType.CARPLAY,
                wifi = 0,
                status = "Streaming",
            ).toRenderModel(testResources)

        assertEquals("CarPlay", model.title)
        assertEquals("OPEN", model.action)
        assertTrue(model.isLive)
        assertFalse(model.connectInBackground)
    }

    @Test
    fun `wifi flag makes CarPlay label wireless`() {
        val model =
            snapshot(
                state = CabinManager.State.DEVICE_CONNECTED,
                phoneType = PhoneType.CARPLAY,
                wifi = 1,
                status = "Phone connected — starting session...",
            ).toRenderModel(testResources)

        assertEquals("Wireless CarPlay", model.title)
        assertEquals("VIEW", model.action)
        assertFalse(model.isLive)
        assertFalse(model.connectInBackground)
    }

    @Test
    fun `unknown disconnected session uses neutral branding`() {
        val model =
            snapshot(
                state = CabinManager.State.DISCONNECTED,
                phoneType = null,
                wifi = null,
                status = "Adapter not found",
            ).toRenderModel(testResources)

        assertEquals("Cabin", model.title)
        assertEquals("Adapter not found", model.status)
        assertEquals("CONNECT", model.action)
        assertTrue(model.connectInBackground)
        assertEquals(R.drawable.ic_phone_projection, model.icon)
    }

    @Test
    fun `connecting offers progress not a second connection request`() {
        val model = snapshot(CabinManager.State.CONNECTING, null, null, "").toRenderModel(testResources)
        assertEquals("VIEW", model.action)
        assertEquals("Connecting to adapter…", model.status)
        assertFalse(model.connectInBackground)
        assertEquals("View connection progress", model.actionDescription)
    }

    @Test
    fun `android auto uses its own icon and label`() {
        val model = snapshot(CabinManager.State.STREAMING, PhoneType.ANDROID_AUTO, null, "").toRenderModel(testResources)
        assertEquals("Android Auto", model.title)
        assertEquals(R.drawable.ic_android_auto, model.icon)
        assertEquals("Projection active", model.status)
    }

    @Test
    fun `widget status is bounded and control characters cannot break layout`() {
        val model = snapshot(CabinManager.State.DISCONNECTED, null, null, "Ready\n\u202E" + "x".repeat(200)).toRenderModel(testResources)
        assertEquals(160, model.status.length)
        assertFalse(model.status.contains('\n'))
        assertFalse(model.status.contains('\u202E'))
    }

    private fun snapshot(
        state: CabinManager.State,
        phoneType: PhoneType?,
        wifi: Int?,
        status: String,
    ) = CabinWidgetState.Snapshot(state, phoneType, wifi, status)
}
