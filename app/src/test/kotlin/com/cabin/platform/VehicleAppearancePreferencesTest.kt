package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VehicleAppearancePreferencesTest {
    @Test fun `appearance persists per vehicle and malformed values fall back`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences("cabin_vehicle_appearance", 0)
        raw.edit().clear().commit()
        val chosen = VehicleAppearance(VehicleBodyStyle.PICKUP, VehiclePaint.BLUE)
        VehicleAppearancePreferences(context, 17).update(chosen)
        assertEquals(chosen, VehicleAppearancePreferences(context, 17).read())
        assertEquals(VehicleAppearance(), VehicleAppearancePreferences(context, 18).read())
        raw.edit().putInt("17.body", 42).putString("17.paint", "bad").commit()
        assertEquals(VehicleAppearance(), VehicleAppearancePreferences(context, 17).read())
    }
}
