package com.oak404.redcast

import com.oak404.redcast.cast.CastRelay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class CastRelayTest {

    private val upstream = MockWebServer()
    private val client = OkHttpClient()
    private lateinit var relay: CastRelay
    private lateinit var mediaBase: String

    @Before
    fun setUp() {
        upstream.start()
        mediaBase = "http://${upstream.hostName}:${upstream.port}"
        relay = CastRelay(client, mediaBase)
        relay.start(InetAddress.getLoopbackAddress())
    }

    @After
    fun tearDown() {
        relay.stop()
        upstream.shutdown()
    }

    private fun get(url: String, range: String? = null, origin: String? = null) = client.newCall(
        Request.Builder().url(url)
            .apply { range?.let { header("Range", it) } }
            .apply { origin?.let { header("Referer", it) } }
            .build(),
    ).execute()

    @Test
    fun passesARangeRequestThroughWithoutTheReceiversReferer() {
        upstream.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Range", "bytes 0-4/100")
                .setHeader("Accept-Ranges", "bytes")
                .setBody("hello"),
        )

        val url = relay.relayUrl("$mediaBase/SomeClip.mp4")!!
        get(url, range = "bytes=0-4", origin = "https://www.gstatic.com/").use { response ->
            assertEquals(206, response.code)
            assertEquals("video/mp4", response.header("Content-Type"))
            assertEquals("bytes 0-4/100", response.header("Content-Range"))
            assertEquals("*", response.header("Access-Control-Allow-Origin"))
            assertEquals("hello", response.body!!.string())
        }

        val seen = upstream.takeRequest()
        assertEquals("/SomeClip.mp4", seen.path)
        assertEquals("bytes=0-4", seen.getHeader("Range"))
        assertNull(seen.getHeader("Referer"))
    }

    @Test
    fun rejectsRequestsWithoutTheSessionToken() {
        val url = relay.relayUrl("$mediaBase/SomeClip.mp4")!!
        val guessed = url.replace(Regex("/[0-9a-f]{32}/"), "/wrong/")
        get(guessed).use { assertEquals(404, it.code) }
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun cannotBeUsedToReachOtherFiles() {
        val url = relay.relayUrl("$mediaBase/SomeClip.mp4")!!
        val base = url.removeSuffix("SomeClip.mp4")
        listOf("secret.txt", "..%2Fadmin.mp4", "a/b.mp4").forEach { name ->
            get(base + name).use { assertEquals(404, it.code) }
        }
        assertEquals(0, upstream.requestCount)
    }

    @Test
    fun onlyRelaysFilesFromTheMediaHost() {
        assertNull(relay.relayUrl("https://example.com/SomeClip.mp4"))
        assertNull(relay.relayUrl("$mediaBase/nested/SomeClip.mp4"))
        assertNotNull(relay.relayUrl("$mediaBase/SomeClip-poster.jpg"))
    }

    @Test
    fun offersNoUrlOnceStopped() {
        relay.stop()
        assertNull(relay.relayUrl("$mediaBase/SomeClip.mp4"))
    }
}
