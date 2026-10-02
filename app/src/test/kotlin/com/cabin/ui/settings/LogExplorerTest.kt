package com.cabin.ui.settings

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogExplorerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun log(text: String) = folder.newFile().apply { writeText(text) }
    private fun entry(level: String, tag: String, message: String) = "2026-09-30T12:34:56.789 $level [$tag] $message"

    @Test fun `search combines text severity and subsystem without matching body as level`() = runBlocking {
        val file = log(listOf("header", entry("E", "USB", "Device disconnected"), entry("I", "UI", "E [USB] Device disconnected"), entry("E", "AUDIO", "Device disconnected")).joinToString("\n"))
        val result = searchLogFile(file, LogQuery("DISCONNECTED device", "usb", LogSeverity.ERROR))
        assertEquals(listOf(2), result.lines.map { it.number })
        assertEquals(4, result.scanned)
        assertEquals("USB", result.lines.single().tag)
    }

    @Test fun `newest mode retains last matches with original line numbers`() = runBlocking {
        val file = log((1..20).joinToString("\n") { entry("W", "USB", "entry $it") })
        val result = searchLogFile(file, LogQuery(newestFirst = true), maxResults = 3)
        assertEquals(listOf(20, 19, 18), result.lines.map { it.number })
        assertEquals(20, result.matched)
        assertTrue(result.resultsLimited)
        assertFalse(result.inputLimited)
        assertFalse(result.linesShortened)
    }

    @Test fun `metadata export excludes headers identifiers tags and message contents`() = runBlocking {
        val file = log("private header\n" + entry("E", "owner@example.com", "token=secret phone=00:11:22:33:44:55 location=10,20"))
        val result = searchLogFile(file, LogQuery())
        assertEquals("2026-09-30T12:34:56.789 E line=2", result.export(true))
        assertTrue(result.export(false).contains("token=secret"))
        assertFalse(result.export(true).contains("owner"))
    }

    @Test fun `scan bounds input and individual lines without losing following records`() = runBlocking {
        val file = log("x".repeat(10000) + "\n" + entry("I", "UI", "small"))
        val result = searchLogFile(file, LogQuery(), maxLineBytes = 80)
        assertEquals(2, result.scanned)
        assertTrue(result.linesShortened)
        assertEquals(80, result.lines.first().text.length)
        assertTrue(result.lines.last().text.endsWith("small"))
        assertTrue(searchLogFile(file, LogQuery(), maxBytes = 100).inputLimited)
    }

    @Test fun `empty files and unmatched filters give empty results`() = runBlocking {
        assertEquals(0, searchLogFile(log(""), LogQuery()).scanned)
        assertEquals(0, searchLogFile(log(entry("I", "USB", "connected")), LogQuery(severity = LogSeverity.ERROR)).matched)
    }

    @Test fun `file filters and all sort modes preserve source snapshots`() {
        val files = listOf(LogFileSnapshot(File("b.log"), 100, 1), LogFileSnapshot(File("A.log"), 5, 3), LogFileSnapshot(File("c.log"), 50, 2))
        assertEquals(listOf("A.log", "c.log", "b.log"), arrangeLogFiles(files, "", LogFileOrder.NEWEST).map { it.file.name })
        assertEquals(listOf("b.log", "c.log", "A.log"), arrangeLogFiles(files, "", LogFileOrder.LARGEST).map { it.file.name })
        assertEquals(listOf("A.log", "b.log", "c.log"), arrangeLogFiles(files, "", LogFileOrder.NAME).map { it.file.name })
        assertEquals("A.log", arrangeLogFiles(files, "a.LO", LogFileOrder.OLDEST).single().file.name)
        assertEquals("b.log", files.first().file.name)
    }
}
