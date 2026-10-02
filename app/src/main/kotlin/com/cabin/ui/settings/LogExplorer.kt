package com.cabin.ui.settings

import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class LogSeverity { ALL, ERROR, WARNING, INFO, DEBUG, VERBOSE }
internal enum class LogFileOrder { NEWEST, OLDEST, LARGEST, NAME }
internal data class LogQuery(
    val text: String = "",
    val tag: String = "",
    val severity: LogSeverity = LogSeverity.ALL,
    val newestFirst: Boolean = false,
)
internal data class ExplorerLine(val number: Int, val text: String) {
    private val header get() = LOG_HEADER.find(text)
    val severity: String? get() = header?.groupValues?.get(2)
    val tag: String? get() = header?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }
    // Only structural data leaves the app in metadata mode. Never include arbitrary tags or messages.
    fun metadata(): String? = header?.let { "${it.groupValues[1]} ${it.groupValues[2]} line=$number" }
}
private val LOG_HEADER = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}) ([EWIDV]) (?:\\[([^]\\r\\n]+)] )?")
internal data class LogSearchResult(
    val lines: List<ExplorerLine> = emptyList(),
    val matched: Int = 0,
    val scanned: Int = 0,
    val inputLimited: Boolean = false,
    val linesShortened: Boolean = false,
) {
    val resultsLimited get() = matched > lines.size
    fun export(metadataOnly: Boolean): String = lines.mapNotNull {
        if (metadataOnly) it.metadata() else "${it.number}: ${it.text}"
    }.joinToString("\n")
}

/** Bounded, cancellable scan. Long lines are drained without allocating an unbounded String. */
internal suspend fun searchLogFile(
    file: File,
    query: LogQuery,
    maxBytes: Long = 16 * 1024 * 1024,
    maxResults: Int = 1000,
    maxLineBytes: Int = 4096,
): LogSearchResult {
    require(maxBytes > 0 && maxResults > 0 && maxLineBytes > 0)
    val lines = ArrayDeque<ExplorerLine>()
    var scanned = 0
    var matched = 0
    var shortened = false
    val terms = query.text.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val tag = query.tag.trim()
    val context = currentCoroutineContext()
    return file.inputStream().buffered().use { input ->
        val line = java.io.ByteArrayOutputStream()
        var readBytes = 0L
        var hasContent = false
        fun acceptLine() {
            scanned++
            val value = ExplorerLine(scanned, line.toString(Charsets.UTF_8.name()).trimEnd('\r'))
            val levelMatches = query.severity == LogSeverity.ALL || value.severity == query.severity.name.take(1)
            if (levelMatches && (tag.isEmpty() || value.tag?.contains(tag, ignoreCase = true) == true) &&
                terms.all { value.text.contains(it, ignoreCase = true) }) {
                matched++
                if (query.newestFirst || lines.size < maxResults) lines.addLast(value)
                if (lines.size > maxResults) lines.removeFirst()
            }
            line.reset()
            hasContent = false
        }
        while (readBytes < maxBytes) {
            if (readBytes % 8192 == 0L) context.ensureActive()
            val byte = input.read()
            if (byte < 0) break
            readBytes++
            if (byte == 10) acceptLine() else {
                hasContent = true
                if (line.size() < maxLineBytes) line.write(byte) else shortened = true
            }
        }
        val inputLimited = readBytes == maxBytes && input.read() != -1
        if (hasContent) acceptLine()
        LogSearchResult(if (query.newestFirst) lines.toList().asReversed() else lines.toList(), matched, scanned, inputLimited, shortened)
    }
}

internal fun arrangeLogFiles(files: List<LogFileSnapshot>, query: String, order: LogFileOrder): List<LogFileSnapshot> {
    val matches = files.filter { it.file.name.contains(query.trim(), ignoreCase = true) }
    return when (order) {
        LogFileOrder.NEWEST -> matches.sortedByDescending { it.modifiedAt }
        LogFileOrder.OLDEST -> matches.sortedBy { it.modifiedAt }
        LogFileOrder.LARGEST -> matches.sortedByDescending { it.size }
        LogFileOrder.NAME -> matches.sortedBy { it.file.name.lowercase(java.util.Locale.ROOT) }
    }
}
