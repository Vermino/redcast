package com.oak404.redcast.data

import android.net.Uri
import com.oak404.redcast.auth.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

class SignInRequiredException : IOException("Sign in to see this")

class RedgifsApi(private val session: Session, private val http: OkHttpClient) {

    private val _changed = MutableStateFlow<ApiChangedException?>(null)

    /** The last reply Redcast couldn't read, so the app can say RedGIFs changed something. */
    val changed: StateFlow<ApiChangedException?> = _changed.asStateFlow()

    fun dismissChanged() {
        _changed.value = null
    }

    suspend fun feed(source: ApiFeed, page: Int, count: Int = PAGE_SIZE): GifPage = reading {
        val paging = "count=$count&page=$page"
        val path = when (source) {
            FeedSource.Trending -> "v2/feeds/trending/popular?$paging"
            FeedSource.ForYou -> "v2/feeds/for-you?$paging"
            FeedSource.Liked -> "v2/feeds/liked?$paging"
            is FeedSource.Search ->
                "v2/gifs/search?query=${Uri.encode(source.query)}&order=trending&$paging"
            is FeedSource.Tag ->
                "v2/gifs/search?tags=${Uri.encode(source.name)}&order=trending&$paging"
            is FeedSource.Query ->
                "v2/gifs/search?${source.search.apiParams(Uri::encode)}&$paging"
            is FeedSource.CreatorGifs ->
                "v2/users/${Uri.encode(source.userName)}/search?order=${source.order}&$paging"
        }
        parseGifPage(call(path, requiresUser = source.requiresUser))
    }

    /** How many clips match [query], capped by the site at 10,000. One tiny request. */
    suspend fun count(query: SearchQuery): Int = reading {
        val json = call("v2/gifs/search?${query.apiParams(Uri::encode)}&count=1&page=1", requiresUser = false)
        if (!json.has("total") && !json.has("pages")) throw ApiChangedException("search results")
        json.optInt("total", json.optInt("pages"))
    }

    suspend fun recommended(gifId: String): List<Gif> = reading {
        parseGifPage(call("v2/recommend/gifs/${Uri.encode(gifId)}/quick", requiresUser = false)).gifs
    }

    suspend fun profile(userName: String): Profile =
        parseProfile(call("v1/users/${Uri.encode(userName)}", requiresUser = false))

    suspend fun trendingTags(): List<TrendingTag> = reading {
        val tags = call("v2/tags/trending", requiresUser = false).optJSONArray("tags")
            ?: throw ApiChangedException("tag list")
        List(tags.length()) { tags.getJSONObject(it) }
            .map { TrendingTag(it.optString("name"), it.optLong("count")) }
    }

    suspend fun suggestions(query: String, limit: Int = MAX_SUGGESTIONS): List<Suggestion> = reading {
        val list = callArray("v2/search/suggest?query=${Uri.encode(query)}")
        List(list.length()) { list.optJSONObject(it) ?: JSONObject() }
            .map { Suggestion(it.optString("text"), it.optString("type") == "tag", it.optLong("gifs")) }
            .filter { it.text.isNotEmpty() }
            .take(limit)
    }

    /** How many clips carry exactly [tag], or null when the site doesn't know the tag. */
    suspend fun tagSize(tag: String): Long? =
        suggestions(tag, limit = Int.MAX_VALUE).firstOrNull { it.isTag && it.text.equals(tag, ignoreCase = true) }?.clips

    private inline fun <T> reading(block: () -> T): T = try {
        block()
    } catch (e: ApiChangedException) {
        _changed.value = e
        throw e
    }

    suspend fun setFollowing(userName: String, following: Boolean) {
        call(
            "v1/me/follows/${Uri.encode(userName)}",
            requiresUser = true,
            method = if (following) "PUT" else "DELETE",
        )
    }

    suspend fun following(page: Int): List<Creator> =
        parseCreators(call("v2/me/following?count=$PAGE_SIZE&page=$page", requiresUser = true))

    suspend fun myUserName(): String? =
        call("v1/me", requiresUser = true).optString("username").ifEmpty { null }

    /** [position] counts from 1, as on the site. */
    suspend fun setLiked(gifId: String, liked: Boolean, from: FeedSource?, position: Int) {
        val body = if (liked) likeBody(from, position) else JSONObject().put("context", JSONObject.NULL)
        call(
            "v2/gifs/${Uri.encode(gifId)}/like",
            requiresUser = true,
            method = if (liked) "PUT" else "DELETE",
            body = body,
        )
    }

    private suspend fun call(
        path: String,
        requiresUser: Boolean,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        val token = token(requiresUser)
        var first = send(path, token, method, body)
        // Rate limited: one more try if the site asks for only a short wait.
        if (first.status == 429 && first.retryAfterSec in 0..MAX_RETRY_AFTER_SEC) {
            delay(first.retryAfterSec.coerceAtLeast(1) * 1000L)
            first = send(path, token, method, body)
        }
        if (first.status != 401) return first.unwrap()

        // Expired or revoked: get a new token once, then let any second failure surface.
        session.invalidate(token)
        return send(path, token(requiresUser), method, body).unwrap()
    }

    private suspend fun callArray(path: String): JSONArray {
        val reply = send(path, token(requiresUser = false), "GET")
        if (reply.status !in 200..299) reply.unwrap()
        return runCatching { JSONArray(reply.text) }.getOrNull() ?: throw ApiChangedException("search suggestions")
    }

    private suspend fun token(requiresUser: Boolean): String =
        if (requiresUser) session.userToken() ?: throw SignInRequiredException()
        else session.anyToken()

    private suspend fun send(path: String, token: String, method: String, json: JSONObject? = null): Reply =
        withContext(Dispatchers.IO) {
            val body = (json ?: if (method == "PUT") JSONObject() else null)?.toString()?.toRequestBody(JSON)
            val request = Request.Builder()
                .url("$API_BASE/$path")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", Session.USER_AGENT)
                .method(method, body)
                .build()
            http.newCall(request).execute().use { response ->
                Reply(
                    response.code,
                    response.body?.string().orEmpty(),
                    response.header("Retry-After")?.trim()?.toIntOrNull() ?: -1,
                )
            }
        }

    /** [retryAfterSec] is the reply's Retry-After in seconds, or -1 without one. */
    private class Reply(val status: Int, val text: String, val retryAfterSec: Int = -1) {
        fun unwrap(): JSONObject {
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status in 200..299) {
                // An empty reply is fine for a like or a follow; one that isn't JSON at all is not.
                return json ?: if (text.isBlank()) JSONObject() else throw ApiChangedException("replies")
            }
            if (status == 429) {
                throw ApiException(status, "RateLimited", "RedGIFs is limiting requests. Try again in a minute.")
            }
            val error = json?.optJSONObject("error")
            throw ApiException(
                status,
                error?.optString("code"),
                error?.optString("message").orEmpty().ifEmpty { "Request failed ($status)" },
            )
        }
    }

    companion object {
        const val API_BASE = "https://api.redgifs.com"
        const val PAGE_SIZE = 40
        private const val MAX_SUGGESTIONS = 8
        private const val MAX_RETRY_AFTER_SEC = 5
        private val JSON = "application/json".toMediaType()
    }
}

internal fun likeBody(from: FeedSource?, position: Int): JSONObject = JSONObject()
    .put("context", JSONObject.NULL)
    .put("source", from?.likeSource ?: "watch")
    .put("source_id", from?.likeSourceId ?: JSONObject.NULL)
    .put("position", position.coerceAtLeast(1))
