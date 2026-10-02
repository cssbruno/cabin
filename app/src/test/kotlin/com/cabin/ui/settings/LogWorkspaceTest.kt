package com.cabin.ui.settings

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogWorkspaceTest {
    @get:Rule val folder = TemporaryFolder()
    private fun file(text: String) = folder.newFile().apply { writeText(text) }
    private fun entry(message: String, severity: String = "I", time: String = "12:00:00.000") = "2026-09-30T$time $severity [USB] $message\n"
    @Test fun `continuation preserves all matches across files and page byte boundaries`() = runBlocking {
        val sources = listOf(file((1..70).joinToString("") { entry("a$it") }), file((1..30).joinToString("") { entry("b$it") })).map(LogSource::capture)
        var cursor: WorkspaceCursor? = WorkspaceCursor(sources)
        val rows = mutableListOf<WorkspaceRow>()
        var scanned = 0
        while (cursor != null) {
            val page = scanLogWorkspace(cursor, WorkspaceQuery(), maxRows = 3, maxBytes = 110)
            rows += page.rows; scanned += page.scanned; cursor = page.next
        }
        assertEquals(100, rows.size); assertEquals(100, rows.map { it.key }.distinct().size)
        assertEquals(100, scanned); assertTrue(rows.last().text.endsWith("b30"))
    }
    @Test fun `changed source rejects continuation instead of skipping records`() = runBlocking {
        val sourceFile = file(entry("first") + entry("second"))
        val next = scanLogWorkspace(WorkspaceCursor(listOf(LogSource.capture(sourceFile))), WorkspaceQuery(), maxRows = 1).next!!
        sourceFile.appendText(entry("third"))
        try { scanLogWorkspace(next, WorkspaceQuery()); fail("Mutation must invalidate cursor") } catch (_: LogSourceChanged) { }
    }
    @Test fun `long lines match undisplayed tails and exports retain complete contents`() = runBlocking {
        val text = entry("x".repeat(30_000) + " café TARGET")
        val source = LogSource.capture(file(text))
        val query = WorkspaceQuery("CAFÉ target")
        val row = scanLogWorkspace(WorkspaceCursor(listOf(source)), query).rows.single()
        assertTrue(row.shortened); assertFalse(row.text.contains("TARGET"))
        val output = ByteArrayOutputStream()
        assertEquals(1, exportWorkspace(listOf(source), query, false, output))
        assertTrue(output.toString("UTF-8").contains(text))
    }
    @Test fun `time ranges are inclusive and unstructured timestamps are counted explicitly`() = runBlocking {
        val log = file("header\n" + entry("first") + entry("later", time = "12:00:01.000"))
        val instant = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
        val page = scanLogWorkspace(WorkspaceCursor(listOf(LogSource.capture(log))), WorkspaceQuery(from = instant, until = instant, zone = "UTC"))
        assertEquals(1, page.rows.size); assertEquals(1, page.unknownTime)
        assertEquals(2, page.severities["I"])
        assertNull(logTime("2026-11-01T01:30:00.000 I ambiguous", "America/New_York"))
    }
    @Test fun `metadata export omits private content and unstructured records`() = runBlocking {
        val log = file("private header\n" + entry("password=secret", "E"))
        val output = ByteArrayOutputStream()
        exportWorkspace(listOf(LogSource.capture(log)), WorkspaceQuery(), true, output)
        assertEquals("2026-09-30T12:00:00.000 E line=2\n", output.toString("UTF-8"))
    }
    @Test fun `context retains source line numbers without changing query results`() = runBlocking {
        val log = file((1..12).joinToString("") { entry("line $it" + if (it == 6) " target-record" else "") })
        val row = scanLogWorkspace(WorkspaceCursor(listOf(LogSource.capture(log))), WorkspaceQuery("target-record")).rows.single()
        assertEquals((3..9).toList(), logContext(row).map { it.line })
        assertEquals(6, row.line)
    }
    @Test fun `session following waits for complete lines and does not replay fragments`() = runBlocking {
        val log = file(entry("first") + "2026-09-30T12:00:00.000 I [USB] sec")
        val source = captureCompleteLog(log)
        val first = scanLogWorkspace(WorkspaceCursor(listOf(source)), WorkspaceQuery())
        assertEquals(1, first.rows.size)
        log.appendText("ond\n")
        val next = captureCompleteLog(log)
        val page = scanLogWorkspace(WorkspaceCursor(listOf(next), offset = source.size, line = first.scanned), WorkspaceQuery())
        assertEquals(2, page.rows.single().line); assertTrue(page.rows.single().text.endsWith("second"))
    }
    @Test fun `append snapshots detect replaced or rewritten files without confusing real appends`() = runBlocking {
        val log = file(entry("first") + entry("second"))
        val snapshot = captureCompleteLog(log)
        log.appendText(entry("third"))
        assertTrue(snapshot.unchanged())
        val size = log.length()
        log.writeText(entry("other") + entry("second") + entry("third"))
        assertEquals(size, log.length())
        assertFalse(snapshot.unchanged())
    }
    @Test fun `invalid continuation offsets are rejected before opening any record`() = runBlocking {
        val source = LogSource.capture(file(entry("test")))
        try { scanLogWorkspace(WorkspaceCursor(listOf(source), offset = -1), WorkspaceQuery()); fail("Negative offset") } catch (_: IllegalArgumentException) { }
        try { scanLogWorkspace(WorkspaceCursor(listOf(source), offset = source.size + 1), WorkspaceQuery()); fail("Past source end") } catch (_: IllegalArgumentException) { }
    }

}
