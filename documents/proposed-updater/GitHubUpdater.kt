package com.cabin.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.cabin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal enum class UpdatePhase { IDLE, CHECKING, CURRENT, DOWNLOADING, READY, FAILED, UNCONFIGURED }
internal data class UpdateStatus(val phase: UpdatePhase = UpdatePhase.IDLE, val version: Long? = null)

/** Private download storage, bounded HTTPS redirects, and signer/package/version validation. */
internal class GitHubUpdater private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("github_updates_v1", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutable = MutableStateFlow(UpdateStatus(if (readyFile.exists()) UpdatePhase.READY else UpdatePhase.IDLE))
    val state = mutable.asStateFlow()
    val repository get() = prefs.getString("repository", BuildConfig.UPDATE_REPOSITORY).orEmpty()
    val automatic get() = prefs.getBoolean("automatic", true)
    private val readyFile get() = File(context.filesDir, "updates/update.apk")
    fun configure(repo: String, automatic: Boolean) {
        require(repo.isEmpty() || validUpdateRepository(repo))
        if (repo != repository) { readyFile.delete(); mutable.value = UpdateStatus() }
        prefs.edit().putString("repository", repo).putBoolean("automatic", automatic).apply()
        UpdateJobService.schedule(context)
    }
    suspend fun checkAndDownload() = withContext(Dispatchers.IO) { lock.withLock {
        val repo = repository
        if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE || !validUpdateRepository(repo)) {
            mutable.value = UpdateStatus(UpdatePhase.UNCONFIGURED)
            return@withLock
        }
        val partial = File(context.filesDir, "updates/download.tmp")
        try {
            mutable.value = UpdateStatus(UpdatePhase.CHECKING)
            val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            val response = open("https://api.github.com/repos/$repo/releases/latest")
            val body = try {
                if (response.responseCode == 404) {
                    mutable.value = UpdateStatus(if (readyFile.exists()) UpdatePhase.READY else UpdatePhase.CURRENT)
                    return@withLock
                }
                require(response.responseCode == 200)
                response.inputStream.use { stream ->
                    val bytes = stream.readNBytesBounded(1_048_576)
                    bytes.toString(Charsets.UTF_8)
                }
            } finally { response.disconnect() }
            val release = parseGitHubRelease(body, repo, installed)
            if (release == null) { mutable.value = UpdateStatus(if (readyFile.exists()) UpdatePhase.READY else UpdatePhase.CURRENT); return@withLock }
            mutable.value = UpdateStatus(UpdatePhase.DOWNLOADING, release.versionCode)
            partial.parentFile!!.mkdirs()
            val connection = open(release.url)
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                require(connection.responseCode == 200)
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        total += read; require(total <= release.size && total <= MAX_APK_BYTES)
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                    require(total == release.size)
                } }
            } finally { connection.disconnect() }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(release.sha256 == null || hash == release.sha256)
            validateApk(partial, release.versionCode)
            // A repository change during a download must not publish the old source's update.
            require(repository == repo)
            require(partial.renameTo(readyFile))
            prefs.edit().putLong("ready_version", release.versionCode).apply()
            mutable.value = UpdateStatus(UpdatePhase.READY, release.versionCode)
        } catch (_: Exception) {
            mutable.value = UpdateStatus(if (readyFile.exists()) UpdatePhase.READY else UpdatePhase.FAILED)
        } finally { partial.delete() }
    } }
    private fun validateApk(file: File, expectedVersion: Long) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Invalid APK")
        require(candidate.packageName == context.packageName && candidate.splitNames.isNullOrEmpty())
        require(PackageInfoCompat.getLongVersionCode(candidate) == expectedVersion && expectedVersion > PackageInfoCompat.getLongVersionCode(installed))
        require(candidate.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true)
        fun certificates(info: android.content.pm.PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet().orEmpty()
        val trusted = certificates(installed)
        require(trusted.isNotEmpty() && certificates(candidate) == trusted)
    }
    fun installIntent(): Intent {
        require(BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE)
        validateApk(readyFile, prefs.getLong("ready_version", 0))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", readyFile)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    companion object {
        @Volatile private var instance: GitHubUpdater? = null
        fun get(context: Context): GitHubUpdater = instance ?: synchronized(this) {
            instance ?: GitHubUpdater(context.applicationContext).also { instance = it }
        }
        private fun open(start: String): HttpURLConnection {
            var url = start
            repeat(5) {
                require(trustedUpdateDownload(url))
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "HeadUnit-Updater/${BuildConfig.VERSION_NAME}")
                val code = connection.responseCode
                if (code !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location") ?: error("Missing redirect")
                url = URL(URL(url), location).toString()
                connection.disconnect()
            }
            error("Too many redirects")
        }
    }
}

private fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(output.size() + count <= limit); output.write(buffer, 0, count) }
    return output.toByteArray()
}
