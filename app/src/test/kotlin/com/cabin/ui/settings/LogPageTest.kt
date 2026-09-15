package com.cabin.ui.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogPageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `pages preserve lines and unicode without losing bytes`() {
        val file = temporary.newFile()
        val original = "start\n" + "ação🚗".repeat(50) + "\nend\n"
        file.writeText(original)
        val result = StringBuilder()
        var offset = 0L
        do {
            val page = readLogPage(file, offset, 17)
            assertTrue(page.nextOffset > offset)
            assertTrue(page.nextOffset - offset <= 17)
            result.append(page.text)
            offset = page.nextOffset
        } while (page.hasMore)
        assertEquals(original, result.toString())
        assertEquals(file.length(), offset)
    }

    @Test fun `empty files and offsets beyond a truncated file return empty pages`() {
        val file = temporary.newFile()
        assertEquals(LogPage("", 0, false), readLogPage(file, 0))
        file.writeText("short")
        assertEquals(LogPage("", 5, false), readLogPage(file, 100))
    }

    @Test fun `refresh reads newly appended content`() {
        val file = temporary.newFile()
        file.writeText("first\n")
        val first = readLogPage(file, 0)
        file.appendText("next\n")
        assertEquals("first\nnext\n", readLogPage(file, 0).text)
        assertEquals("next\n", readLogPage(file, first.nextOffset).text)
    }
}
