package com.oak404.redcast.data

import android.net.Uri
import com.oak404.redcast.auth.Session
import kotlinx.coroutines.Dispatchers
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

    suspend fun feed(source: ApiFeed, page: Int, count: Int = PAGE_SIZE): GifPage {
        val paging = "count=$count&page=$page"
        val path = when (source) {
            FeedSource.Trending -> "v2/feeds/trending/popular?$paging"
            FeedSource.ForYou -> "v2/feeds/for-you?$paging"
            FeedSource.Liked -> "v2/feeds/liked?$paging"
            is FeedSource.Search ->
                "v2/gifs/search?query=${Uri.encode(source.query)}&order=trending&$paging"
            is FeedSource.Tag ->
                "v2/gifs/search?tags=${Uri.encode(source.name)}&order=trending&$paging"
            is FeedSource.CreatorGifs ->
                "v2/users/${Uri.encode(source.userName)}/search?order=${source.order}&$paging"
        }
        return parseGifPage(call(path, requiresUser = source.requiresUser))
    }

    suspend fun recommended(gifId: String): List<Gif> =
        parseGifPage(call("v2/recommend/gifs/${Uri.encode(gifId)}/quick", requiresUser = false)).gifs

    suspend fun profile(userName: String): Profile =
        parseProfile(call("v1/users/${Uri.encode(userName)}", requiresUser = false))

    suspend fun trendingTags(): List<TrendingTag> {
        val tags = call("v2/tags/trending", requiresUser = false).optJSONArray("tags") ?: return emptyList()
        return List(tags.length()) { tags.getJSONObject(it) }
            .map { TrendingTag(it.optString("name"), it.optLong("count")) }
    }

    suspend fun suggestions(query: String): List<Suggestion> {
        val list = callArray("v2/search/suggest?query=${Uri.encode(query)}")
        return List(list.length()) { list.getJSONObject(it) }
            .map { Suggestion(it.optString("text"), it.optString("type") == "tag") }
            .filter { it.text.isNotEmpty() }
            .take(MAX_SUGGESTIONS)
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
        val first = send(path, token, method, body)
        if (first.status != 401) return first.unwrap()

        // Expired or revoked: get a new token once, then let any second failure surface.
        session.invalidate(token)
        return send(path, token(requiresUser), method, body).unwrap()
    }

    private suspend fun callArray(path: String): JSONArray {
        val reply = send(path, token(requiresUser = false), "GET")
        if (reply.status !in 200..299) reply.unwrap()
        return JSONArray(reply.text)
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
                Reply(response.code, response.body?.string().orEmpty())
            }
        }

    private class Reply(val status: Int, val text: String) {
        fun unwrap(): JSONObject {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (status in 200..299) return json
            val error = json.optJSONObject("error")
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
        private val JSON = "application/json".toMediaType()
    }
}

internal fun likeBody(from: FeedSource?, position: Int): JSONObject = JSONObject()
    .put("context", JSONObject.NULL)
    .put("source", from?.likeSource ?: "watch")
    .put("source_id", from?.likeSourceId ?: JSONObject.NULL)
    .put("position", position.coerceAtLeast(1))
