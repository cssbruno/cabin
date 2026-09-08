package com.cabin.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.cabin.CabinManager
import com.cabin.MainActivity
import com.cabin.R
import com.cabin.background.CabinProjectionService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "mdpi")
class CabinWidgetInteractionTest {
    private lateinit var context: Context
    private var manager: CabinManager? = null

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("carlink_widget_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() =
        runBlocking {
            manager?.let {
                CabinProjectionService.unregisterActivityManager(it)
                it.releaseAndWait()
            }
            Unit
        }

    @Test
    fun `registered connecting manager keeps progress and opens activity not another connect`() {
        manager = CabinManager(context).also(CabinProjectionService::registerActivityManager)
        persist(CabinManager.State.CONNECTING)
        val widgetManager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(widgetManager)
        val id = widgets.createWidget(CabinWidgetProvider::class.java, R.layout.cabin_widget)
        val view = widgets.getViewFor(id)
        val action = view.findViewById<TextView>(R.id.widget_action)
        assertEquals("VIEW", action.text.toString())
        action.performClick()
        assertEquals(MainActivity.ACTION_SHOW_COMPACT_PROJECTION, shadowOf(context as android.app.Application).nextStartedActivity.action)
        assertNull(shadowOf(context as android.app.Application).nextStartedService)
    }

    @Test
    fun `old connecting snapshot after process death offers a fresh connection`() {
        persist(CabinManager.State.CONNECTING)
        val widgetManager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(widgetManager)
        val id = widgets.createWidget(CabinWidgetProvider::class.java, R.layout.cabin_widget)
        val action = widgets.getViewFor(id).findViewById<TextView>(R.id.widget_action)
        assertEquals("CONNECT", action.text.toString())
        action.performClick()
        assertEquals(
            CabinProjectionService.ACTION_CONNECT_PHONE,
            shadowOf(context as android.app.Application).nextStartedService.action,
        )
    }

    @Test
    fun `narrow widget stacks its action without squeezing title width`() {
        val widgetManager = AppWidgetManager.getInstance(context)
        val widgets = shadowOf(widgetManager)
        val id = widgets.createWidget(CabinWidgetProvider::class.java, R.layout.cabin_widget)
        val options =
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 128)
            }
        widgetManager.updateAppWidgetOptions(id, options)
        CabinWidgetProvider().onAppWidgetOptionsChanged(context, widgetManager, id, options)
        val view = widgets.getViewFor(id)
        view.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 220, 128)
        val action = view.findViewById<TextView>(R.id.widget_action)
        assertEquals(56, action.height)
        assertTrue(action.width >= 180)
        assertTrue(action.bottom <= 128)
        assertEquals(View.GONE, view.findViewById<View>(R.id.widget_projection_icon).visibility)
    }

    private fun persist(state: CabinManager.State) {
        context.getSharedPreferences("carlink_widget_state", Context.MODE_PRIVATE).edit()
            .putString("connection_state", state.name).putString("status", "Connecting to adapter…").commit()
    }
}
