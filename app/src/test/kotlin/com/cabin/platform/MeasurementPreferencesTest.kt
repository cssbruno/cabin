package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MeasurementPreferencesTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(MeasurementPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `manual units persist independently of profile selection`() {
        val preferences = MeasurementPreferences(context)
        assertEquals(MeasurementUnit.SYSTEM, preferences.unit.value)
        preferences.select(MeasurementUnit.IMPERIAL)
        TeyesFeaturePreferences(context).select(2)
        assertEquals(MeasurementUnit.IMPERIAL, preferences.unit.value)
        assertEquals(MeasurementUnit.IMPERIAL, MeasurementPreferences(context).unit.value)
    }

    @Test fun `unknown and corrupt saved units fall back to system`() {
        val raw = context.getSharedPreferences(MeasurementPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
        raw.edit().putString("unit", "UNKNOWN").commit()
        assertEquals(MeasurementUnit.SYSTEM, MeasurementPreferences(context).unit.value)
        raw.edit().putBoolean("unit", true).commit()
        assertEquals(MeasurementUnit.SYSTEM, MeasurementPreferences(context).unit.value)
    }
}
