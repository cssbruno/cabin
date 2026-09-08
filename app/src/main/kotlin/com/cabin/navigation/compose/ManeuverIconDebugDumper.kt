package com.cabin.navigation.compose

import android.content.Context
import android.graphics.Bitmap
import com.cabin.BuildConfig
import com.cabin.logging.Logger
import com.cabin.logging.logWarn
import com.cabin.navigation.Iap2ManeuverData
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Explicit, debug-build-only icon export. Disabled by default; normal navigation
 * never enables this exporter. Files stay in app-private storage and names contain
 * only maneuver indices/types, never road names or route instructions.
 *
 * Retention across trips and process restarts: at most three sessions, 128 files
 * and 16 MiB. Call [enable] deliberately during a debugging session, then [disable].
 */
object ManeuverIconDebugDumper {
    internal const val MAX_SESSIONS = 3
    internal const val MAX_FILES = 128
    internal const val MAX_BYTES = 16L * 1024 * 1024
    private var baseDir: File? = null
    private var sessionDir: File? = null
    private var generation = 0L
    private val sessionSequence = AtomicLong(0)
    private var currentSessionSequence = 0L

    /** Returns false in release builds; enabling does not change cluster settings. */
    @Synchronized
    fun enable(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val dir = File(context.filesDir, "composer_test")
        if (!dir.isDirectory && !dir.mkdirs()) return false
        prune(dir)
        baseDir = dir
        sessionDir = null
        val token = ++generation
        // A previously queued compose job may hold an older sink after disable/re-enable.
        ComposedIconStore.debugSink = { maneuver, bitmap -> onIconComposed(token, maneuver, bitmap) }
        return true
    }

    @Synchronized
    fun disable() {
        generation++
        ComposedIconStore.debugSink = null
        baseDir = null
        sessionDir = null
    }

    fun resetSession() {
        // Called from the USB/navigation thread: never wait for PNG compression/IO.
        sessionSequence.incrementAndGet()
    }

    @Synchronized
    private fun onIconComposed(token: Long, maneuver: Iap2ManeuverData, bitmap: Bitmap) {
        if (!BuildConfig.DEBUG || token != generation) return
        val base = baseDir ?: return
        val sequence = sessionSequence.get()
        if (sequence != currentSessionSequence) {
            sessionDir = null
            currentSessionSequence = sequence
        }
        val dir = sessionDir?.takeIf { it.isDirectory } ?: File(
            base, "session-${System.currentTimeMillis()}-${UUID.randomUUID()}",
        ).also {
            if (!it.mkdirs()) return
            sessionDir = it
        }
        val file = File(dir, "${maneuver.index}_${maneuver.cpManeuverType}.png")
        try {
            val written = FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written || file.length() > MAX_BYTES) file.delete()
        } catch (error: Exception) {
            file.delete()
            logWarn("[COMPOSER_DUMP] Icon export failed: ${error.message}", tag = Logger.Tags.NAVI)
        } finally {
            prune(base)
        }
    }

    /** Also bounds legacy exports already on disk when a developer opts in again. */
    private fun prune(base: File) {
        val sessions = base.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        sessions.drop(MAX_SESSIONS).forEach { it.deleteRecursively() }
        val files = base.walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.toList()
        var bytes = 0L
        var count = 0
        for (file in files) {
            val size = file.length()
            if (count >= MAX_FILES || size > MAX_BYTES - bytes) file.delete()
            else { count++; bytes += size }
        }
        base.listFiles()?.filter { it.isDirectory && it.listFiles()?.isEmpty() == true }?.forEach { it.delete() }
    }
}
