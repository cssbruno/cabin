package com.cabin.platform.obd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ObdControllerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `external adapter selection and connect are inert without any context access`() {
        val forbidden =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = error("Must not read application context")

                override fun getSystemService(name: String): Any? = error("Must not query radio services")

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ): SharedPreferences = error("Must not read preferences")

                override fun checkPermission(
                    permission: String,
                    pid: Int,
                    uid: Int,
                ): Int = error("Must not query permissions")
            }
        val controller = ObdController(forbidden)
        repeat(3) {
            assertFalse(controller.hasPermission())
            assertTrue(controller.pairedAdapters().isEmpty())
            controller.selectAdapter("AA:BB:CC:DD:EE:FF")
            controller.connectSelected()
            controller.disconnect()
        }
        assertEquals(ObdSnapshot(), controller.state.value)
        assertFalse(controller.state.value.active)
        assertTrue(controller.state.value.message.contains("TEYES/SYU"))
    }

    @Test fun `previous external adapter preference is never restored or changed`() {
        val preferences = context.getSharedPreferences("obd_accessory_v1", Context.MODE_PRIVATE)
        preferences.edit().putString("adapter", "AA:BB:CC:DD:EE:FF").commit()
        try {
            val controller = ObdController(context)
            controller.connectSelected()
            controller.selectAdapter("11:22:33:44:55:66")
            assertEquals("", controller.state.value.selectedAddress)
            assertEquals("AA:BB:CC:DD:EE:FF", preferences.getString("adapter", null))
            assertNull(controller.state.value.speedKph)
            assertNull(controller.state.value.engineRpm)
            assertNull(controller.state.value.updatedAtElapsedMs)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
