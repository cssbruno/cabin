package com.cabin.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.cabin.BuildConfig
import com.cabin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Native Android RemoteViews geometry, independent of Compose and real launcher/hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CabinWidgetLayoutTest {
    @Test
    fun `compact 220 by 128 keeps title and full size action reachable at enlarged fonts`() {
        checkLayouts(220)
    }

    @Test
    fun `wide threshold 320 by 128 keeps title separate from the action at enlarged fonts`() {
        checkLayouts(320)
    }

    @Test
    fun `wide 480 by 128 preserves action geometry and readable title at enlarged fonts`() {
        checkLayouts(480)
    }

    private fun checkLayouts(widthDp: Int) {
        val examples = listOf("Cabin" to "CONNECT", "Wireless CarPlay" to "OPEN", "Android Auto" to "VIEW")
        for (fontScale in listOf(1f, 1.5f, 2f)) {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val configuration = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
            val context = base.createConfigurationContext(configuration)
            assertEquals(fontScale, context.resources.configuration.fontScale, 0f)
            val density = context.resources.displayMetrics.density
            val width = (widthDp * density).roundToInt()
            val height = (128 * density).roundToInt()
            val minimumAction = (56 * density).roundToInt()
            for ((title, actionText) in examples) {
                val label = "$widthDp×128 font=$fontScale title=$title action=$actionText"
                val compact = widthDp < 320
                val views =
                    RemoteViews(context.packageName, if (compact) R.layout.cabin_widget_compact else R.layout.cabin_widget).apply {
                        setTextViewText(R.id.widget_title, title)
                        setTextViewText(R.id.widget_action, actionText)
                        setTextViewText(R.id.widget_status, "Waiting for your phone to complete the connection. A long status may be ellipsized.")
                        setInt(R.id.widget_status, "setMaxLines", 1)
                        setViewVisibility(R.id.widget_projection_icon, if (compact) View.GONE else View.VISIBLE)
                        setViewVisibility(R.id.widget_live_indicator, if (actionText == "OPEN") View.VISIBLE else View.INVISIBLE)
                        setContentDescription(R.id.widget_action, actionText)
                    }
                val root = views.apply(context, FrameLayout(context)) as ViewGroup
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val titleView = root.findViewById<TextView>(R.id.widget_title)
                val action = root.findViewById<TextView>(R.id.widget_action)
                val status = root.findViewById<TextView>(R.id.widget_status)
                assertEquals("$label: inflation must use the requested font scale", fontScale, titleView.resources.configuration.fontScale, 0f)
                val titleBounds = root.boundsOf(titleView)
                val actionBounds = root.boundsOf(action)
                val contentBounds = Rect(root.paddingLeft, root.paddingTop, width - root.paddingRight, height - root.paddingBottom)

                assertTrue("$label: title must have visible width", titleView.width > 0)
                assertTrue("$label: title must have visible height", titleView.height > 0)
                assertTrue("$label: title is clipped by widget bounds: $titleBounds in $contentBounds", contentBounds.contains(titleBounds))
                assertFalse("$label: title overlaps action: $titleBounds and $actionBounds", Rect.intersects(titleBounds, actionBounds))
                assertTrue("$label: action is clipped by widget bounds: $actionBounds in $contentBounds", contentBounds.contains(actionBounds))
                assertTrue("$label: action height must be at least 56dp", action.height >= minimumAction)
                assertTrue("$label: action width must be at least 56dp", action.width >= minimumAction)
                assertNotNull("$label: action text must be laid out", action.layout)
                assertTrue("$label: action label clipped vertically", action.layout.height <= action.height - action.compoundPaddingTop - action.compoundPaddingBottom)
                for (line in 0 until action.lineCount) {
                    assertEquals("$label: action label must not be ellipsized", 0, action.layout.getEllipsisCount(line))
                    assertTrue(
                        "$label: action label clipped horizontally",
                        action.layout.getLineWidth(line) <= action.width - action.compoundPaddingLeft - action.compoundPaddingRight + 1f,
                    )
                }
                if (compact) {
                    assertEquals("$label: compact status is deliberately hidden", View.GONE, status.visibility)
                    assertTrue("$label: compact action must sit below the title", actionBounds.top >= titleBounds.bottom)
                } else {
                    assertEquals("$label: wide status remains available", View.VISIBLE, status.visibility)
                    assertTrue("$label: wide status must not overlap the action", !Rect.intersects(root.boundsOf(status), actionBounds))
                    // Ellipsizing the descriptive status is intentional at the minimum size.
                    assertEquals("$label: minimum-height status stays single-line", 1, status.maxLines)
                }
                var clicks = 0
                action.setOnClickListener { clicks++ }
                assertTrue("$label: primary action can receive a click", action.performClick())
                assertEquals("$label: one click invokes one action", 1, clicks)
                if (title == "Cabin") saveScreenshot(root, "widget-${widthDp}x128-font-${fontScale.toString().replace('.', '-')}")
            }
        }
    }

    private fun ViewGroup.boundsOf(child: View): Rect = Rect(0, 0, child.width, child.height).also { offsetDescendantRectToMyCoords(child, it) }

    private fun saveScreenshot(
        root: View,
        name: String,
    ) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/ui/${BuildConfig.BUILD_TYPE}/$name.png")
        checkNotNull(file.parentFile).mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
