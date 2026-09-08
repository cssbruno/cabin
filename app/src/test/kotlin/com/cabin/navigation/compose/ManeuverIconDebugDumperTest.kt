package com.cabin.navigation.compose

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.cabin.navigation.Iap2ManeuverData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManeuverIconDebugDumperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base get() = File(context.filesDir, "composer_test")

    @Before fun setUp() { ManeuverIconDebugDumper.disable(); base.deleteRecursively() }
    @After fun tearDown() { ManeuverIconDebugDumper.disable(); base.deleteRecursively() }

    @Test fun `disabled exporter and previously captured sinks cannot write`() {
        assertNull(ComposedIconStore.debugSink)
        assertTrue(ManeuverIconDebugDumper.enable(context))
        val oldSink = checkNotNull(ComposedIconStore.debugSink)
        ManeuverIconDebugDumper.disable()
        assertTrue(ManeuverIconDebugDumper.enable(context))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            oldSink(maneuver(0), bitmap)
            assertEquals(0, base.walkTopDown().count { it.isFile })
        } finally { bitmap.recycle() }
    }

    @Test fun `exports bound files and sessions without road names`() {
        assertTrue(ManeuverIconDebugDumper.enable(context))
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            repeat(ManeuverIconDebugDumper.MAX_FILES + 4) { checkNotNull(ComposedIconStore.debugSink)(maneuver(it), bitmap) }
            assertEquals(ManeuverIconDebugDumper.MAX_FILES, base.walkTopDown().count { it.isFile })
            repeat(5) {
                ManeuverIconDebugDumper.resetSession()
                checkNotNull(ComposedIconStore.debugSink)(maneuver(it), bitmap)
            }
            assertTrue(base.listFiles().orEmpty().count { it.isDirectory } <= ManeuverIconDebugDumper.MAX_SESSIONS)
            assertTrue(base.walkTopDown().none { "PrivateRoad" in it.name })
        } finally { bitmap.recycle() }
    }

    @Test fun `enabling trims oversized exports from earlier runs`() {
        val legacy = File(base, "legacy").apply { mkdirs() }
        RandomAccessFile(File(legacy, "old.png"), "rw").use { it.setLength(ManeuverIconDebugDumper.MAX_BYTES + 1) }
        assertTrue(ManeuverIconDebugDumper.enable(context))
        assertTrue(base.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= ManeuverIconDebugDumper.MAX_BYTES)
    }

    private fun maneuver(index: Int) = Iap2ManeuverData(
        index, 1, "Turn at PrivateRoad", "PrivateRoad", 100, "100", 1, 0, 0, emptyList(), null,
    )
}
