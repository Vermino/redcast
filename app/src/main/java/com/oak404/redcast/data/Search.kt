package com.oak404.redcast.data

import org.json.JSONArray
import org.json.JSONObject

/** How the site sorts a search. [api] is its own name for the order. */
enum class SearchOrder(val api: String, val label: String) {
    Trending("trending", "Trending"),
    Latest("latest", "Newest"),
    Week("top7", "Top this week"),
    Month("top28", "Top this month"),
    AllTime("top", "Top of all time"),
}

/**
 * A search built up in the search bar: clips must have every one of [tags], may match [text],
 * and are dropped when a tag or the description has one of the [exclude] words.
 */
data class SearchQuery(
    val tags: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val text: String = "",
    val order: SearchOrder = SearchOrder.Trending,
) {
    val isEmpty: Boolean get() = tags.isEmpty() && text.isBlank()

    val title: String
        get() = buildList {
            if (tags.isNotEmpty()) add(tags.joinToString(" + "))
            if (text.isNotBlank()) add("\"${text.trim()}\"")
            exclude.forEach { add("−$it") }
        }.joinToString(" ")

    /** Adds [tag] to the tags, or moves it there from the exclusions. */
    fun withTag(tag: String): SearchQuery = copy(
        tags = if (tags.any { it.equals(tag, ignoreCase = true) }) tags else tags + tag,
        exclude = exclude.filterNot { it.equals(tag, ignoreCase = true) },
    )

    fun withExcluded(word: String): SearchQuery = copy(
        tags = tags.filterNot { it.equals(word, ignoreCase = true) },
        exclude = if (exclude.any { it.equals(word, ignoreCase = true) }) exclude else exclude + word,
    )

    fun without(word: String): SearchQuery =
        copy(tags = tags - word, exclude = exclude - word)

    /** The plain listing when one will do, so a single tag can still be pinned or hidden. */
    fun toSource(): FeedSource = when {
        exclude.isEmpty() && order == SearchOrder.Trending && text.isBlank() && tags.size == 1 ->
            FeedSource.Tag(tags.single())
        exclude.isEmpty() && order == SearchOrder.Trending && tags.isEmpty() ->
            FeedSource.Search(text.trim())
        else -> FeedSource.Query(this)
    }

    /** The query string for v2/gifs/search, without paging. */
    fun apiParams(encode: (String) -> String): String = buildList {
        if (tags.isNotEmpty()) add("tags=" + encode(tags.joinToString(",")))
        if (text.isNotBlank()) add("query=" + encode(text.trim()))
        add("order=${order.api}")
    }.joinToString("&")

    fun toJson(): JSONObject = JSONObject()
        .put("tags", JSONArray(tags))
        .put("exclude", JSONArray(exclude))
        .put("text", text)
        .put("order", order.name)

    /** How a recent search is stored: plain text for a text-only search, JSON otherwise. */
    fun toRecent(): String =
        if (tags.isEmpty() && exclude.isEmpty() && order == SearchOrder.Trending) text.trim() else toJson().toString()

    companion object {
        /** Reads back [toRecent]; anything that isn't JSON is a plain text search from before. */
        fun fromRecent(stored: String): SearchQuery {
            if (!stored.startsWith("{")) return SearchQuery(text = stored)
            val json = runCatching { JSONObject(stored) }.getOrNull() ?: return SearchQuery(text = stored)
            return fromJson(json)
        }

        fun fromJson(json: JSONObject): SearchQuery {
            return SearchQuery(
                tags = json.strings("tags"),
                exclude = json.strings("exclude"),
                text = json.optString("text"),
                order = SearchOrder.entries.firstOrNull { it.name == json.optString("order") } ?: SearchOrder.Trending,
            )
        }

        private fun JSONObject.strings(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { array.optString(it) }.filter { it.isNotEmpty() }
        }
    }
}

/** The search a listing came from, when it came from one. */
fun searchOf(source: FeedSource): SearchQuery? = when (source) {
    is FeedSource.Query -> source.search
    is FeedSource.Tag -> SearchQuery(tags = source.name.split(",").map { it.trim() }.filter { it.isNotEmpty() })
    is FeedSource.Search -> SearchQuery(text = source.query)
    is FeedSource.Similar -> source.tags.takeIf { it.isNotEmpty() }?.let { SearchQuery(tags = it) }
    else -> null
}

/** A search kept on the Searches tab. [seenUpTo] is when it was last opened, in Unix seconds. */
data class SavedSearch(val query: SearchQuery, val seenUpTo: Long) {
    fun toJson(): JSONObject = JSONObject().put("query", query.toJson()).put("seenUpTo", seenUpTo)

    companion object {
        fun fromJson(json: JSONObject): SavedSearch? {
            val query = json.optJSONObject("query")?.let(SearchQuery::fromJson) ?: return null
            return if (query.isEmpty) null else SavedSearch(query, json.optLong("seenUpTo"))
        }
    }
}

/** Clips posted after [since], from a page sorted newest first. */
internal fun countNew(newestFirst: List<Gif>, since: Long): Int = newestFirst.count { it.created > since }

/**
 * A clip's tags, least common first, for finding more like it. Tags too rare to turn anything
 * up are dropped, and when the sizes are unknown the clip's own order stands.
 */
internal fun distinctiveTags(tags: List<String>, sizes: Map<String, Long>, minSize: Long): List<String> {
    val usable = tags.filter { (sizes[it] ?: 0) >= minSize }.sortedBy { sizes[it] }
    return usable.ifEmpty { tags }
}
