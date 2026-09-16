package com.cabin.carlink

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.graphics.BitmapFactory
import com.cabin.joying.JoyingDisplayConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarlinkSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `saved options reach native display and hotspot configuration`() {
        val chosen = CarlinkSettings(60, 1, false, 87)
        chosen.save(context)
        val loaded = CarlinkSettings.read(context)
        assertEquals(chosen, loaded)
        assertEquals(60, JoyingDisplayConfiguration(1024, 600).nativeValues(loaded.fps).last())
        assertEquals(36, loaded.channel)
        assertEquals(6, loaded.copy(band = 0).channel)
        val logo = loaded.logoFile(context)!!
        assertTrue(logo.canonicalPath.startsWith(context.filesDir.canonicalPath + "/"))
        assertNotNull(BitmapFactory.decodeFile(logo.path))
        assertNull(loaded.copy(logo = -1).logoFile(context))
    }

    @Test fun `all imported logo choices decode`() {
        for (index in 0..87) {
            val file = CarlinkSettings(logo = index).logoFile(context)!!
            assertNotNull("Logo $index", BitmapFactory.decodeFile(file.path))
        }
    }

    @Test fun `invalid saved options fall back to supported values`() {
        context.getSharedPreferences("carlink_settings", 0).edit()
            .putInt("fps", -1).putInt("band", 9).putInt("logo", 999).commit()
        val loaded = CarlinkSettings.read(context)
        assertEquals(25, loaded.fps)
        assertEquals(1, loaded.band)
        assertEquals(87, loaded.logo)
    }
}
