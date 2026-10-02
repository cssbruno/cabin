package com.cabin.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.cabin.logging.SupportExportQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SupportWorkflowTest {
    private fun report() = JSONObject().put("schemaVersion", 2).put("projectionState", "IDLE").put("vehicleHealth", "WAITING")
    @Test fun `report comparison separates changes missing values and stable values`() {
        val before = report().put("oldField", 1)
        val after = report().put("projectionState", "STREAMING").put("newField", true)
        val differences = compareHealthReports(before.toString(), after.toString()).associateBy { it.field }
        assertEquals("changed", differences.getValue("/projectionState").kind)
        assertEquals("missing", differences.getValue("/oldField").kind)
        assertEquals("missing", differences.getValue("/newField").kind)
        assertEquals("unchanged", differences.getValue("/vehicleHealth").kind)
    }
    @Test fun `report comparison rejects incompatible schemas unbounded nesting and nonreports`() {
        assertThrows(Exception::class.java) { compareHealthReports(report().toString(), report().put("schemaVersion", 1).toString()) }
        assertThrows(Exception::class.java) { validatedHealthReport("[".repeat(100) + "]".repeat(100)) }
        assertThrows(Exception::class.java) { validatedHealthReport("{}") }
    }
    @Test fun `frozen export survives queue recreation and only explicit completion discards it`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val queue = SupportExportQueue(context)
        queue.pending().forEach { queue.discard(it.id) }
        val saved = queue.enqueueText("report.json", report().toString())
        val recreated = SupportExportQueue(context)
        val pending = recreated.pending().single()
        assertEquals(saved.id, pending.id); assertEquals(report().toString(), pending.file.readText())
        recreated.discard(saved.id)
        assertTrue(recreated.pending().isEmpty()); assertFalse(pending.file.exists())
    }
    @Test fun `failed preparation never publishes a partial export`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val queue = SupportExportQueue(context)
        queue.pending().forEach { queue.discard(it.id) }
        try { queue.enqueue("bad.txt", "text/plain") { it.write("partial".toByteArray()); throw java.io.IOException() }; fail() } catch (_: java.io.IOException) { }
        assertTrue(queue.pending().isEmpty())
    }
}
