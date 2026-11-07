package com.oak404.redcast

import com.oak404.redcast.data.Release
import com.oak404.redcast.data.Updates
import com.oak404.redcast.data.isNewer
import com.oak404.redcast.data.parseRelease
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class UpdatesTest {

    private val server = MockWebServer()

    @After
    fun stop() = server.shutdown()

    private fun releaseJson(tag: String = "v1.3", vararg assets: String = arrayOf("redcast-1.3.apk")) = JSONObject()
        .put("tag_name", tag)
        .put("body", "Faster casting")
        .put(
            "assets",
            JSONArray().also { array ->
                assets.forEach { array.put(JSONObject().put("name", it).put("browser_download_url", "https://x/$it").put("size", 10)) }
            },
        )

    @Test
    fun readsTheVersionNotesAndApk() {
        val release = parseRelease(releaseJson())!!
        assertEquals("1.3", release.version)
        assertEquals("Faster casting", release.notes)
        assertEquals("https://x/redcast-1.3.apk", release.apkUrl)
        assertEquals(10, release.apkBytes)
    }

    @Test
    fun ignoresDraftsPrereleasesAndReleasesWithoutAnApk() {
        assertNull(parseRelease(releaseJson().put("draft", true)))
        assertNull(parseRelease(releaseJson().put("prerelease", true)))
        assertNull(parseRelease(releaseJson("v1.3", "notes.txt")))
    }

    @Test
    fun comparesVersionsByNumberNotText() {
        assertTrue(isNewer("1.10", "1.9"))
        assertTrue(isNewer("1.2.1", "1.2"))
        assertTrue(isNewer("v2.0", "1.9.9"))
        assertFalse(isNewer("1.2", "1.2"))
        assertFalse(isNewer("1.2", "1.2.0"))
        assertFalse(isNewer("1.1", "1.2"))
    }

    @Test
    fun noReleaseYetIsNotAnError(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(Updates(OkHttpClient(), server.url("/latest").toString()).latest())
    }

    @Test
    fun downloadsWithProgressAndChecksTheSize(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("0123456789"))
        val dir = Files.createTempDirectory("updates").toFile()
        val progress = mutableListOf<Float>()
        val release = Release("1.3", "", server.url("/a.apk").toString(), 10)
        val file = Updates(OkHttpClient()).download(release, dir) { progress += it }
        assertEquals("0123456789", file.readText())
        assertEquals(1f, progress.last())
        dir.deleteRecursively()
    }

    @Test
    fun aShortDownloadIsRejected(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody("01234"))
        val dir: File = Files.createTempDirectory("updates").toFile()
        val release = Release("1.3", "", server.url("/a.apk").toString(), 10)
        try {
            Updates(OkHttpClient()).download(release, dir) {}
            fail("expected a failure")
        } catch (_: IOException) {
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
        dir.deleteRecursively()
    }
}
