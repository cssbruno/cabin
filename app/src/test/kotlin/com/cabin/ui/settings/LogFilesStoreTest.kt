package com.cabin.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cabin.logging.FileLogManager
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LogFilesStoreTest {
    private lateinit var manager: FileLogManager
    private lateinit var store: LogFilesStore
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.filesDir, "logs").apply { mkdirs() }
        directory.listFiles()?.forEach { it.delete() }
        manager = FileLogManager(context)
        store = LogFilesStore(manager)
    }

    @After
    fun tearDown() {
        manager.release()
    }

    @Test
    fun `rendered metadata stays cached until the next explicit refresh`() = runBlocking {
        val file = File(directory, "sample.log").apply { writeText("before") }
        val first = store.snapshot()
        file.appendText("-after")

        assertEquals(6L, first.files.single().size)
        assertEquals(6L, first.totalSize)
        assertEquals(12L, store.snapshot().totalSize)

        val afterDelete = store.delete(file)
        assertTrue(afterDelete.files.isEmpty())
        assertEquals(0L, afterDelete.totalSize)
        assertFalse(store.prepareExport(file))
        assertEquals(6L, first.files.single().size)
    }

    @Test
    fun `logging changes return fresh file snapshots and flush before export`() = runBlocking {
        val enabled = store.setEnabled(true)
        assertTrue(enabled.files.isNotEmpty())
        val file = enabled.files.first().file
        assertTrue(store.prepareExport(file))
        val disabled = store.setEnabled(false)
        assertEquals(enabled.files.map { it.file }, disabled.files.map { it.file })
        assertTrue(disabled.totalSize > 0)
    }
}
