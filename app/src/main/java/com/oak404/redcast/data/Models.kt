package com.oak404.redcast.data

import org.json.JSONArray
import org.json.JSONObject

data class Gif(
    val id: String,
    val userName: String,
    val hdUrl: String,
    val sdUrl: String,
    val posterUrl: String,
    val thumbnailUrl: String,
    val durationSec: Double,
    val tags: List<String>,
    val width: Int = 0,
    val height: Int = 0,
    val likes: Long = 0,
    val promoted: Boolean = false,
    val description: String = "",
    /** When it was posted, in Unix seconds; 0 when unknown. */
    val created: Long = 0,
) {
    /** The API's own shape, so saved lists can be read back with [parseGif]. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", 1)
        .put("userName", userName)
        .put("duration", durationSec)
        .put("width", width)
        .put("height", height)
        .put("likes", likes)
        .put("promoted", promoted)
        .put("tags", JSONArray(tags))
        .put("description", description)
        .put("createDate", created)
        .put(
            "urls",
            JSONObject()
                .put("hd", hdUrl)
                .put("sd", sdUrl)
                .put("poster", posterUrl)
                .put("thumbnail", thumbnailUrl),
        )
}

data class Creator(val userName: String, val avatarUrl: String?)

data class Profile(
    val userName: String,
    val displayName: String,
    val description: String,
    val avatarUrl: String?,
    val followers: Long,
    val gifCount: Long,
)

/** [clips] is how many clips have the tag, when the site says. */
data class Suggestion(val text: String, val isTag: Boolean, val clips: Long = 0)

data class TrendingTag(val name: String, val count: Long)

/** [relatedTags] are the tags the site says go with this listing, for narrowing it down. */
data class GifPage(val gifs: List<Gif>, val pages: Int, val relatedTags: List<String> = emptyList())

/** A feed the app can page through. Each one maps to a single API listing. */
sealed interface FeedSource {
    val title: String
    val requiresUser: Boolean get() = false

    /** How the site names this listing when it reports a like; it rejects likes without one. */
    val likeSource: String get() = "watch"
    val likeSourceId: String? get() = null

    data object Trending : ApiFeed {
        override val title = "Trending"
        override val likeSource = "trending"
    }

    data object ForYou : ApiFeed {
        override val title = "For You"
        override val requiresUser = true
        override val likeSource = "for-you"
    }

    data object Liked : ApiFeed {
        override val title = "Liked"
        override val requiresUser = true
        override val likeSource = "favourites"
    }

    data class Search(val query: String) : ApiFeed {
        override val title = query
        override val likeSource = "search-gifs"
        override val likeSourceId = query
    }

    data class Tag(val name: String) : ApiFeed {
        override val title = name
        override val likeSource = "tag-gifs"
        override val likeSourceId = name
    }

    data class CreatorGifs(val userName: String, val order: String = "latest") : ApiFeed {
        override val title = userName
        override val likeSource = "profile"
        override val likeSourceId = userName
    }

    /** Several tags at once, with words to leave out, optional text and a sort order. */
    data class Query(val search: SearchQuery) : ApiFeed {
        override val title = search.title
        override val likeSource = "search-gifs"
        override val likeSourceId = search.title
    }

    data object Mix : FeedSource {
        override val title = "My Mix"
    }

    /** Clips like [gif]: the site's own picks, then clips with all of [tags]. */
    data class Similar(val gif: Gif, val tags: List<String>) : FeedSource {
        override val title = if (tags.isEmpty()) "More like this" else "Like this: ${tags.joinToString(" + ")}"
    }

    data class SavedList(val name: String) : FeedSource {
        override val title = name
    }

    data object History : FeedSource {
        override val title = "History"
    }
}

/** Feeds that map directly to one API listing. */
sealed interface ApiFeed : FeedSource

/**
 * Thrown when a reply no longer has the shape Redcast reads, which usually means RedGIFs changed
 * its unofficial API. Feeds show it instead of looking empty.
 */
class ApiChangedException(what: String) :
    java.io.IOException("Redcast can't read RedGIFs' $what any more. RedGIFs may have changed something; an update may fix it.")

internal fun parseGifPage(json: JSONObject): GifPage {
    val array = json.optJSONArray("gifs") ?: throw ApiChangedException("clip listings")
    val items = List(array.length()) { array.optJSONObject(it) }
    val gifs = items.mapNotNull { it?.let(::parseGif) }
    // Images, galleries and ads are skipped on purpose; a page where every clip fails to read is not.
    if (gifs.isEmpty() && items.isNotEmpty() && items.none { it != null && isSkippedOnPurpose(it) }) {
        throw ApiChangedException("clip listings")
    }
    val related = json.optJSONArray("tags")
    return GifPage(
        gifs,
        json.optInt("pages", 0),
        if (related == null) emptyList() else List(related.length()) { related.optString(it) }.filter { it.isNotEmpty() },
    )
}

private fun isSkippedOnPurpose(json: JSONObject) = json.optInt("type", 1) != 1 || !json.isNull("gallery")

/** Returns null for anything that isn't a plain playable video: images, galleries and ads. */
internal fun parseGif(json: JSONObject): Gif? {
    if (isSkippedOnPurpose(json)) return null
    if (json.optString("id").isEmpty()) return null

    val urls = json.optJSONObject("urls") ?: return null
    val hd = urls.optString("hd").ifEmpty { urls.optString("sd") }
    val sd = urls.optString("sd").ifEmpty { hd }
    if (hd.isEmpty()) return null

    val tags = json.optJSONArray("tags")
    return Gif(
        id = json.optString("id"),
        userName = json.optString("userName"),
        hdUrl = hd,
        sdUrl = sd,
        posterUrl = urls.optString("poster"),
        thumbnailUrl = urls.optString("thumbnail").ifEmpty { urls.optString("poster") },
        durationSec = json.optDouble("duration", 0.0),
        tags = if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) },
        width = json.optInt("width"),
        height = json.optInt("height"),
        likes = json.optLong("likes"),
        promoted = json.optBoolean("promoted", false),
        description = if (json.isNull("description")) "" else json.optString("description"),
        created = json.optLong("createDate"),
    )
}

internal fun parseProfile(json: JSONObject) = Profile(
    userName = json.optString("username"),
    displayName = json.optString("name").ifEmpty { json.optString("username") },
    description = json.optString("description"),
    avatarUrl = json.optString("profileImageUrl").ifEmpty { null },
    followers = json.optLong("followers"),
    gifCount = json.optLong("gifs"),
)

internal fun parseCreators(json: JSONObject): List<Creator> {
    val array = json.optJSONArray("users") ?: json.optJSONArray("following") ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val user = array.optJSONObject(i) ?: continue
            val name = user.optString("username").ifEmpty { user.optString("name") }
            if (name.isNotEmpty()) {
                add(Creator(name, user.optString("profileImageUrl").ifEmpty { null }))
            }
        }
    }
}
