package com.cabin.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubReleaseTest {
    private fun release(tag: String = "v0.1.0-alpha.3", preview: Boolean = true, repo: String = UPDATE_REPOSITORY): JSONObject = JSONObject()
        .put("tag_name", tag).put("draft", false).put("prerelease", preview).put("assets", JSONArray().apply {
            for (name in listOf("cabin.apk", "update.json")) put(JSONObject().put("name", name).put("state", "uploaded")
                .put("size", if (name.endsWith("apk")) 100000 else 300)
                .put("browser_download_url", "https://github.com/$repo/releases/download/$tag/$name"))
        })
    private fun manifest(code: Long = 1004) = JSONObject().put("versionCode", code).put("versionName", "0.1.0-alpha.3")
        .put("packageName", "zeno.carlink").put("size", 100000).put("sha256", "a".repeat(64))
    private fun assets() = releaseAssets(JSONArray().put(release()).toString(), true).single()
    @Test fun `alpha installs see alpha releases and stable installs skip them`() {
        val body = JSONArray().put(release()).put(release("v0.1.0", false)).toString()
        assertEquals(2, releaseAssets(body, true).size)
        assertEquals(listOf("0.1.0"), releaseAssets(body, false).map { it.versionName })
    }
    @Test fun `wrong repository drafts missing assets and incomplete uploads are ignored`() {
        assertTrue(releaseAssets(JSONArray().put(release(repo = "other/repo")).toString(), true).isEmpty())
        assertTrue(releaseAssets(JSONArray().put(release().put("draft", true)).toString(), true).isEmpty())
        val incomplete = release()
        incomplete.getJSONArray("assets").getJSONObject(0).put("state", "new")
        assertTrue(releaseAssets(JSONArray().put(incomplete).toString(), true).isEmpty())
    }
    @Test fun `numeric version code prevents reinstall and downgrade`() {
        val candidate = parseUpdateManifest(manifest().toString(), assets(), 1003)!!
        assertEquals(1004, candidate.versionCode)
        assertEquals(candidate, cachedRelease(candidate.json()))
        assertNull(parseUpdateManifest(manifest().toString(), assets(), 1004))
        assertNull(parseUpdateManifest(manifest().toString(), assets(), 1005))
    }
    @Test fun `manifest must match package version asset size and digest`() {
        for ((key, value) in listOf("packageName" to "other.app", "versionName" to "9.0.0", "size" to 1, "sha256" to "bad")) {
            assertThrows(Exception::class.java) { parseUpdateManifest(manifest().put(key, value).toString(), assets(), 1003) }
        }
    }
    @Test fun `download destinations reject cleartext credentials ports and unrelated hosts`() {
        assertTrue(trustedUpdateDownload("https://release-assets.githubusercontent.com/signed/path?token=example"))
        for (url in listOf("http://github.com/file", "https://github.com.evil.test/file", "https://user@github.com/file", "https://github.com:8443/file", "file:///tmp/file")) {
            assertFalse(url, trustedUpdateDownload(url))
        }
    }
}
