package com.oak404.redcast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.oak404.redcast.auth.Session
import com.oak404.redcast.auth.TokenCapture
import com.oak404.redcast.data.ApiException
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Feeds
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.Library
import com.oak404.redcast.data.RedgifsApi
import com.oak404.redcast.data.Signal
import com.oak404.redcast.ui.FakeRedgifs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What happens when the site, the network or the phone's own saved data misbehaves. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ResilienceTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun gif(id: String, tags: List<String> = listOf("Beach")) =
        Gif(id, "someone", "https://m/$id.mp4", "https://m/$id-mobile.mp4", "https://m/$id.jpg", "https://m/$id-s.jpg", 5.0, tags)


    /** [FakeRedgifs], except that clip searches are answered with [status] while [failing]. */
    private class Flaky(private val status: Int, private val retryAfter: String? = null) : Interceptor {
        val fake = FakeRedgifs()
        var failing = true
        var searches = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val search = url.encodedPath == "/v2/gifs/search" || url.encodedPath.contains("/users/")
            if (!search) return fake.intercept(chain)
            searches++
            if (!failing) return fake.intercept(chain)
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Nope")
                .apply { retryAfter?.let { header("Retry-After", it) } }
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun feedsOver(flaky: Flaky): Pair<Feeds, Library> {
        val http = OkHttpClient.Builder().addInterceptor(flaky).build()
        val library = Library(context)
        library.updateTaste { it.record(gif("liked", listOf("Blonde", "Beach")), Signal.Liked) }
        return Feeds(RedgifsApi(Session(context, http), http), library) to library
    }

    @Test
    fun aRateLimitedMixSaysSoInsteadOfEnding(): Unit = runBlocking {
        val flaky = Flaky(429)
        val (feeds, _) = feedsOver(flaky)
        val mix = feeds.create(FeedSource.Mix)

        mix.loadMore()

        val error = mix.error.value
        assertTrue("got $error", error is ApiException && error.status == 429)
        assertFalse(mix.exhausted.value)

        // And it picks up again once the site lets requests through.
        flaky.failing = false
        mix.loadMore()
        assertEquals(null, mix.error.value)
        assertTrue(mix.items.value.isNotEmpty())
    }

    @Test
    fun aShortRetryAfterIsWaitedOutOnce(): Unit = runBlocking {
        val flaky = Flaky(429, retryAfter = "1")
        val http = OkHttpClient.Builder().addInterceptor(flaky).build()
        val api = RedgifsApi(Session(context, http), http)

        val failed = runCatching { api.feed(FeedSource.Tag("Beach"), 1) }.exceptionOrNull()

        assertTrue(failed is ApiException)
        assertEquals(2, flaky.searches)
    }

    @Test
    fun oneUnreadableSavedClipDoesntCostTheRest() {
        val good = gif("good").toJson()
        context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
            .putString("history", JSONArray().put(good).put("not a clip").put(JSONObject().put("id", 7)).toString())
            .putString("lists", JSONObject().put("Keep", JSONArray().put("junk").put(good)).put("Odd", "not a list").toString())
            .putString("searches", JSONArray().put("beach").put(JSONObject.NULL).toString())
            .commit()

        val library = Library(context)

        assertEquals(listOf("good"), library.history.value.map { it.id })
        assertEquals(listOf("good"), library.lists.value.getValue("Keep").map { it.id })
        assertEquals(emptyList<Gif>(), library.lists.value.getValue("Odd"))
        assertEquals("beach", library.searches.value.first())
    }

    @Test
    fun leavingTheAppSavesWhatWasStillWaiting() {
        val library = Library(context)
        library.addToList("Keep", gif("justsaved"))

        library.flush()

        val saved = context.getSharedPreferences("library", Context.MODE_PRIVATE).getString("lists", null)
        assertTrue("got $saved", saved.orEmpty().contains("justsaved"))
    }

    @Test
    fun aSlowPageIsntMistakenForBeingSignedOut() {
        val ended = TokenCapture.Renewal.SessionEnded
        val failed = TokenCapture.Renewal.Failed
        // The site's first requests were a guest's, but the page never finished loading.
        assertEquals(failed, TokenCapture.renewalOutcome(null, sawGuestToken = true, pageLoaded = false))
        assertEquals(ended, TokenCapture.renewalOutcome(null, sawGuestToken = true, pageLoaded = true))
        assertEquals(failed, TokenCapture.renewalOutcome(null, sawGuestToken = false, pageLoaded = true))
        assertEquals(TokenCapture.Renewal.Renewed("t"), TokenCapture.renewalOutcome("t", sawGuestToken = true, pageLoaded = true))
    }
}
