package com.cabin.updates

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateImprovementsTest {
    @get:Rule val folder = TemporaryFolder()
    private val release = UpdateRelease(2000, "2.0", "https://github.com/$UPDATE_REPOSITORY/releases/download/v2.0/cabin.apk", 10, "a".repeat(64))
    private class Connection(private val code: Int, private val body: String, private val headers: Map<String, String>) : HttpURLConnection(URL("https://github.com/test")) {
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
    private fun saved(partial: File, metadata: File) {
        partial.writeText("01234")
        metadata.writeText(JSONObject().put("asset", release.assetIdentity()).put("etag", "\"same\"").toString())
    }
    @Test fun `valid ranges resume only same strong entity tag`() = runBlocking {
        val partial = folder.newFile(); val metadata = folder.newFile(); saved(partial, metadata)
        var progress = -1
        downloadUpdateAsset(release, partial, metadata, { _, headers ->
            assertEquals("bytes=5-", headers["Range"]); assertEquals("\"same\"", headers["If-Range"])
            Connection(206, "56789", mapOf("ETag" to "\"same\"", "Content-Range" to "bytes 5-9/10"))
        }, { Long.MAX_VALUE }, { true }, { progress = it })
        assertEquals("0123456789", partial.readText()); assertEquals(100, progress)
    }
    @Test fun `changed remote entity restarts instead of appending mixed content`() = runBlocking {
        val partial = folder.newFile(); val metadata = folder.newFile(); saved(partial, metadata)
        var calls = 0
        downloadUpdateAsset(release, partial, metadata, { _, headers ->
            calls++
            if (calls == 1) Connection(206, "WRONG", mapOf("ETag" to "\"changed\"", "Content-Range" to "bytes 5-9/10"))
            else { assertTrue(headers.isEmpty()); Connection(200, "abcdefghij", mapOf("ETag" to "\"changed\"")) }
        }, { Long.MAX_VALUE }, { true }, {})
        assertEquals(2, calls); assertEquals("abcdefghij", partial.readText())
    }
    @Test fun `storage preflight does not open network or remove a valid partial`() = runBlocking {
        val partial = folder.newFile(); val metadata = folder.newFile(); saved(partial, metadata)
        try { downloadUpdateAsset(release, partial, metadata, { _, _ -> error("network must stay closed") }, { 0 }, { true }, {}); fail() }
        catch (e: UpdateException) { assertEquals(com.cabin.R.string.ux_low_space, e.errorRes) }
        assertEquals("01234", partial.readText())
    }
    @Test fun `metered policy prevents network access and retains resumable data`() = runBlocking {
        val partial = folder.newFile(); val metadata = folder.newFile(); saved(partial, metadata)
        try { downloadUpdateAsset(release, partial, metadata, { _, _ -> error("network must stay closed") }, { Long.MAX_VALUE }, { false }, {}); fail() }
        catch (e: UpdateException) { assertEquals(com.cabin.R.string.ux_metered_wait, e.errorRes) }
        assertEquals(5L, partial.length())
    }
    @Test fun `early EOF preserves an entity validated partial for retry`() = runBlocking {
        val partial = folder.newFile(); val metadata = folder.newFile()
        try {
            downloadUpdateAsset(release, partial, metadata, { _, _ -> Connection(200, "01234", mapOf("ETag" to "\"same\"")) }, { Long.MAX_VALUE }, { true }, {})
            fail("Expected incomplete response")
        } catch (_: java.io.EOFException) { }
        assertEquals("01234", partial.readText())
        downloadUpdateAsset(release, partial, metadata, { _, headers ->
            assertEquals("bytes=5-", headers["Range"])
            Connection(206, "56789", mapOf("ETag" to "\"same\"", "Content-Range" to "bytes 5-9/10"))
        }, { Long.MAX_VALUE }, { true }, {})
        assertEquals("0123456789", partial.readText())
    }
    @Test fun `cached notes and preview channel survive restart without changing asset identity`() {
        val noted = release.copy(notes = "Release information", preview = true)
        assertEquals(noted, cachedRelease(noted.json()))
        assertEquals(release.assetIdentity(), noted.assetIdentity())
        assertNull(strongEtag("W/\"weak\"")); assertNull(strongEtag("\"bad\nvalue\""))
    }
}
