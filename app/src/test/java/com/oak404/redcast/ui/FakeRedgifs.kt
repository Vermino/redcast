package com.oak404.redcast.ui

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers the app's requests the way RedGIFs does, from a few made-up clips, so UI tests run
 * without the network. Every request is recorded in [requests].
 */
class FakeRedgifs : Interceptor {

    data class Clip(val id: String, val user: String, val tags: List<String>, val created: Long)

    var clips = listOf(
        Clip("clipzero", "user0", listOf("Blonde", "Beach", "Sunset"), NOW + 1000),
        Clip("clipone", "user1", listOf("Blonde"), NOW + 900),
        Clip("cliptwo", "user2", listOf("Asian"), NOW + 800),
        Clip("clipthree", "user3", listOf("Asian", "Beach"), NOW + 700),
    )

    /** How many clips the site says carry each tag. */
    var tagSizes = mapOf("Blonde" to 1_400_000L, "Beach" to 40_000L, "Sunset" to 2_000L, "Asian" to 900_000L)

    /** When set, clip listings come back in a shape the app doesn't know. */
    var listingsChanged = false

    val requests = CopyOnWriteArrayList<okhttp3.HttpUrl>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        requests += url
        val body: Any? = when {
            url.host != "api.redgifs.com" -> null
            url.encodedPath == "/v2/auth/temporary" -> JSONObject().put("token", TOKEN)
            url.encodedPath == "/v2/tags/trending" -> JSONObject().put(
                "tags",
                JSONArray(tagSizes.map { (name, count) -> JSONObject().put("name", name).put("count", count) }),
            )
            url.encodedPath == "/v2/search/suggest" -> {
                val query = url.queryParameter("query").orEmpty()
                JSONArray(
                    tagSizes.filterKeys { it.contains(query, ignoreCase = true) }
                        .map { (name, count) -> JSONObject().put("type", "tag").put("text", name).put("gifs", count) },
                )
            }
            listingsChanged -> JSONObject().put("results", JSONArray())
            url.encodedPath == "/v2/feeds/trending/popular" -> page(clips, url)
            url.encodedPath == "/v2/gifs/search" -> {
                val wanted = url.queryParameter("tags")?.split(",")?.filter { it.isNotBlank() }.orEmpty()
                val found = clips.filter { clip -> wanted.all { it in clip.tags } }
                val sorted = if (url.queryParameter("order") == "latest") found.sortedByDescending { it.created } else found
                page(sorted, url)
            }
            url.encodedPath.startsWith("/v2/recommend/") -> JSONObject().put("gifs", JSONArray())
            else -> null
        }
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(if (body == null) 404 else 200)
            .message(if (body == null) "Not Found" else "OK")
            .body((body?.toString() ?: "").toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** Searches made for clips with every one of these tags. */
    fun searchesFor(vararg tags: String): List<okhttp3.HttpUrl> =
        requests.filter { it.encodedPath == "/v2/gifs/search" && it.queryParameter("tags") == tags.joinToString(",") }

    private fun page(found: List<Clip>, url: okhttp3.HttpUrl): JSONObject {
        val count = url.queryParameter("count")?.toInt() ?: 40
        val page = url.queryParameter("page")?.toInt() ?: 1
        val pages = maxOf(1, (found.size + count - 1) / count)
        return JSONObject()
            .put("page", page)
            .put("pages", pages)
            .put("total", found.size)
            .put("gifs", JSONArray(found.drop((page - 1) * count).take(count).map(::clipJson)))
            .put("tags", JSONArray(found.flatMap { it.tags }.distinct()))
    }

    private fun clipJson(clip: Clip) = JSONObject()
        .put("id", clip.id)
        .put("type", 1)
        .put("userName", clip.user)
        .put("duration", 8.0)
        .put("createDate", clip.created)
        .put("tags", JSONArray(clip.tags))
        .put(
            "urls",
            JSONObject()
                .put("hd", "https://media.redgifs.com/${clip.id}.mp4")
                .put("sd", "https://media.redgifs.com/${clip.id}-mobile.mp4")
                .put("poster", "https://media.redgifs.com/${clip.id}-poster.jpg")
                .put("thumbnail", "https://media.redgifs.com/${clip.id}-mobile.jpg"),
        )

    companion object {
        val NOW = System.currentTimeMillis() / 1000

        /** A guest token the app accepts: only its expiry and subject are read. */
        private val TOKEN = run {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val claims = JSONObject().put("sub", "client/test").put("exp", NOW + 86_400).toString()
            "e30.${encoder.encodeToString(claims.toByteArray())}.sig"
        }
    }
}
