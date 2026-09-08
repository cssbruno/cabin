package com.cabin.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class FileLogManagerLifecycleTest {
    private lateinit var manager: FileLogManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.filesDir, "logs").listFiles()?.forEach { it.delete() }
        manager = FileLogManager(context)
    }

    @After
    fun tearDown() {
        manager.release()
    }

    @Test
    fun `late restore cannot enable logging after final release`() {
        manager.release()

        manager.enable()
        append("late-restore-must-not-be-written")

        assertTrue(manager.getLogFiles().isEmpty())
        assertEquals(0L, manager.getCurrentLogFileSize())
    }

    @Test
    fun `enable prunes oldest excess logs but preserves unrelated files and active writer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = File(context.filesDir, "logs")
        val now = System.currentTimeMillis()
        repeat(30) { index ->
            File(logs, "old-$index.log").apply {
                writeText("old")
                setLastModified(now - 100000 + index)
            }
        }
        val unrelated = File(logs, "keep.json").apply { writeText("keep") }
        manager.enable()
        append("active writer survives")
        assertEquals(FileLogManager.MAX_LOG_FILES, manager.getLogFiles().size)
        assertFalse(File(logs, "old-0.log").exists())
        assertTrue(File(logs, "old-29.log").exists())
        assertTrue(unrelated.exists())
        assertTrue(manager.getLogFiles().any { it.readText().contains("active writer survives") })
    }

    @Test
    fun `byte budget is enforced even with few oversized logs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = File(context.filesDir, "logs")
        repeat(3) { index ->
            val file = File(logs, "large-$index.log")
            RandomAccessFile(file, "rw").use { it.setLength(40 * 1024 * 1024L) }
            file.setLastModified(System.currentTimeMillis() - 100000 + index)
        }
        manager.enable()
        assertTrue(manager.getTotalLogSize() <= FileLogManager.MAX_TOTAL_LOG_BYTES)
        assertFalse(File(logs, "large-0.log").exists())
        assertTrue(File(logs, "large-2.log").exists())
    }

    @Test
    fun `rotation reapplies retention without toggling logging`() {
        manager.enable()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logs = File(context.filesDir, "logs")
        repeat(25) { index ->
            File(logs, "excess-$index.log").apply {
                writeText("old")
                setLastModified(1L)
            }
        }
        append("x".repeat(6 * 1024 * 1024))
        assertTrue(manager.getLogFiles().size <= FileLogManager.MAX_LOG_FILES)
        assertFalse(File(logs, "excess-0.log").exists())
        assertTrue(manager.getCurrentLogFileSize() > 0)
    }

    @Test
    fun `late enable cannot reopen an existing released log`() {
        manager.enable()
        append("before-release")
        manager.release()
        val saved = manager.getLogFiles().associate { it.name to it.readText() }

        manager.enable()
        append("after-release-must-not-be-written")

        assertEquals(saved, manager.getLogFiles().associate { it.name to it.readText() })
    }

    @Test
    fun `deleting the disabled current file does not create a replacement writer`() {
        manager.enable()
        val current = manager.getLogFiles().single()
        manager.disable()

        assertTrue(manager.deleteLogFile(current))
        assertTrue(manager.getLogFiles().isEmpty())
        assertEquals(0L, manager.getCurrentLogFileSize())

        // Disable is reversible; final release is not.
        manager.enable()
        append("enabled-again")
        assertTrue(manager.getLogFiles().single().readText().contains("enabled-again"))
    }

    @Test
    fun `pending delete after release cannot create another log file`() {
        manager.enable()
        val current = manager.getLogFiles().single()
        manager.release()

        assertTrue(manager.deleteLogFile(current))
        manager.enable()
        append("after-delete-must-not-be-written")

        assertFalse(current.exists())
        assertTrue(manager.getLogFiles().isEmpty())
        assertEquals(0L, manager.getCurrentLogFileSize())
    }

    @Test
    fun `deleting the active current file continues logging to a replacement`() {
        manager.enable()
        val current = manager.getLogFiles().single()

        assertTrue(manager.deleteLogFile(current))
        append("replacement-writer-is-live")

        assertEquals(1, manager.getLogFiles().size)
        assertTrue(manager.getLogFiles().single().readText().contains("replacement-writer-is-live"))
    }

    private fun append(message: String) {
        manager.onLog(Logger.Level.INFO, "TEST", message, System.currentTimeMillis())
        manager.flush()
    }
}
