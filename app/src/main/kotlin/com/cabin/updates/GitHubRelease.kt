package com.cabin.updates

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal const val UPDATE_REPOSITORY = "cssbruno/cabin"
internal const val MAX_APK_BYTES = 200L * 1024 * 1024
internal data class UpdateRelease(val versionCode: Long, val versionName: String, val url: String, val size: Long, val sha256: String) {
    fun json(): String = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("url", url).put("size", size).put("sha256", sha256).toString()
}
internal data class ReleaseAssets(val versionName: String, val manifestUrl: String, val apkUrl: String, val size: Long)
internal fun trustedUpdateDownload(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}
private fun releaseAssetUrl(url: String, tag: String, name: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return trustedUpdateDownload(url) && uri.host == "github.com" && uri.query == null &&
        uri.path == "/$UPDATE_REPOSITORY/releases/download/$tag/$name"
}
/** Accept two- or three-part versions; legacy preview parsing remains available for compatibility. */
internal fun releaseAssets(body: String, previews: Boolean): List<ReleaseAssets> {
    require(body.length <= 1_048_576)
    val releases = JSONArray(body)
    require(releases.length() <= 30)
    return (0 until releases.length()).mapNotNull { i ->
        val root = releases.optJSONObject(i) ?: return@mapNotNull null
        val tag = root.optString("tag_name")
        if (root.optBoolean("draft") || (!previews && (root.optBoolean("prerelease") || '-' in tag)) ||
            !Regex("v[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:-[A-Za-z0-9.-]+)?").matches(tag)) return@mapNotNull null
        val assets = root.optJSONArray("assets") ?: return@mapNotNull null
        require(assets.length() <= 200)
        fun asset(name: String) = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.singleOrNull {
            it.optString("name") == name && it.optString("state") == "uploaded" &&
                releaseAssetUrl(it.optString("browser_download_url"), tag, name)
        }
        val apk = asset("cabin.apk") ?: return@mapNotNull null
        val manifest = asset("update.json") ?: return@mapNotNull null
        val size = apk.optLong("size")
        if (size !in 1..MAX_APK_BYTES || manifest.optLong("size") !in 1..16_384) return@mapNotNull null
        ReleaseAssets(tag.removePrefix("v"), manifest.getString("browser_download_url"), apk.getString("browser_download_url"), size)
    }
}
internal fun parseUpdateManifest(body: String, assets: ReleaseAssets, installed: Long): UpdateRelease? {
    require(body.length <= 16_384)
    val root = JSONObject(body)
    val code = root.getLong("versionCode")
    require(root.getString("packageName") == "zeno.carlink" && root.getString("versionName") == assets.versionName)
    val hash = root.getString("sha256").lowercase()
    require(Regex("[a-f0-9]{64}").matches(hash) && code in 1..2_100_000_000 && root.getLong("size") == assets.size)
    if (code <= installed) return null
    return UpdateRelease(code, assets.versionName, assets.apkUrl, assets.size, hash)
}
internal fun cachedRelease(json: String?): UpdateRelease? = runCatching {
    val root = JSONObject(json ?: return null)
    val release = UpdateRelease(root.getLong("versionCode"), root.getString("versionName"), root.getString("url"), root.getLong("size"), root.getString("sha256"))
    require(releaseAssetUrl(release.url, "v${release.versionName}", "cabin.apk") && release.size in 1..MAX_APK_BYTES &&
        Regex("[a-f0-9]{64}").matches(release.sha256))
    release
}.getOrNull()
