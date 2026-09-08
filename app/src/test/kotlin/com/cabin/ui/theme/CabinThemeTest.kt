package com.cabin.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinThemeTest {
    @Test
    fun `dark palette text pairs have readable contrast`() = assertTextContrast(true)

    @Test
    fun `light palette text pairs have readable contrast`() = assertTextContrast(false)

    @Test
    fun `system bars and screen background share the same surface color`() {
        listOf(false, true).forEach { dark ->
            val colors = cabinColorScheme(dark)
            assertEquals(colors.surface, colors.background)
            assertEquals(colors.onSurface, colors.onBackground)
        }
    }

    @Test
    fun `theme safely resolves wrapped activity hosts`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            assertSame(activity, ContextWrapper(ContextWrapper(activity)).findActivity())
            assertNull(ApplicationProvider.getApplicationContext<Context>().findActivity())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `a cyclic wrapper cannot hang the theme`() {
        val cyclic =
            object : ContextWrapper(null) {
                override fun getBaseContext(): Context = this
            }
        assertNull(cyclic.findActivity())
    }

    private fun assertTextContrast(dark: Boolean) {
        val colors = cabinColorScheme(dark)
        val pairs =
            listOf(
                "primary action" to (colors.onPrimary to colors.primary),
                "primary card" to (colors.onPrimaryContainer to colors.primaryContainer),
                "secondary action" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "warning" to (colors.onTertiaryContainer to colors.tertiaryContainer),
                "error" to (colors.error to colors.surface),
                "error action" to (colors.onError to colors.error),
                "error card" to (colors.onErrorContainer to colors.errorContainer),
                "body" to (colors.onSurface to colors.surface),
                "card body" to (colors.onSurface to colors.surfaceContainer),
                "secondary text" to (colors.onSurfaceVariant to colors.surfaceContainerHighest),
            )
        pairs.forEach { (role, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("$role contrast $ratio in dark=$dark", ratio >= 4.5)
        }
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val first = a.luminance().toDouble()
        val second = b.luminance().toDouble()
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
