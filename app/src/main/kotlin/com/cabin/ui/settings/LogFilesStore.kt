package com.cabin.ui.settings

import com.cabin.logging.FileLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal data class LogFileSnapshot(val file: File, val size: Long, val modifiedAt: Long)

internal data class LogFilesSnapshot(
    val files: List<LogFileSnapshot> = emptyList(),
    val currentFileSize: Long = 0,
) {
    val totalSize: Long = files.sumOf { it.size }
}

/** Serializes the Logs tab's disk work and returns metadata safe to render on Main. */
internal class LogFilesStore(private val manager: FileLogManager) {
    private val mutex = Mutex()

    suspend fun snapshot(): LogFilesSnapshot = onIo { readSnapshot() }

    suspend fun setEnabled(enabled: Boolean): LogFilesSnapshot = onIo {
        if (enabled) manager.enable() else manager.disable()
        readSnapshot()
    }

    suspend fun delete(file: File): LogFilesSnapshot = onIo {
        manager.deleteLogFile(file)
        readSnapshot()
    }

    suspend fun prepareExport(file: File): Boolean = onIo {
        manager.flush()
        file.isFile
    }

    private fun readSnapshot(): LogFilesSnapshot =
        LogFilesSnapshot(
            files = manager.getLogFiles().map { LogFileSnapshot(it, it.length(), it.lastModified()) },
            currentFileSize = manager.getCurrentLogFileSize(),
        )

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }
}
