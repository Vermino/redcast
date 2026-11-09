package com.oak404.redcast.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Builds the feed for a source, including the ones assembled on the phone. */
class Feeds(private val api: RedgifsApi, private val library: Library) {

    /** How many clips carry each tag, kept for the session; tag sizes barely move. */
    private val tagSizes = ConcurrentHashMap<String, Long>()

    fun create(source: FeedSource): Feed = when (source) {
        is FeedSource.Query -> Feed(source) { page -> searchPage(source.search, page) }
        is ApiFeed -> Feed(source) { page -> visible(api.feed(source, page)) }
        FeedSource.Mix -> Feed(source, ::loadMix)
        is FeedSource.Similar -> Feed(source) { page -> visible(loadSimilar(source.gif, source.tags, page)) }
        is FeedSource.SavedList -> {
            val saved = library.lists.value[source.name].orEmpty()
            val discover = library.settings.value.listDiscovery && saved.isNotEmpty()
            Feed(source) { page ->
                when {
                    page == 1 -> GifPage(saved, if (discover) Int.MAX_VALUE else 1)
                    else -> loadLike(saved, page - 1)
                }
            }
        }
        FeedSource.History -> Feed(source) { page ->
            if (page > 1) GifPage(emptyList(), 1) else visible(GifPage(library.history.value, 1))
        }
    }

    /** One page of [query], without hidden clips or the words it leaves out. */
    private suspend fun searchPage(query: SearchQuery, page: Int, count: Int = RedgifsApi.PAGE_SIZE): GifPage {
        val excluded = WordFilter(query.exclude)
        val found = visible(api.feed(FeedSource.Query(query), page, count))
        return found.copy(
            gifs = found.gifs.filterNot(excluded::matches),
            relatedTags = found.relatedTags.filterNot(excluded::matches),
        )
    }

    /** Clips posted to [saved] since it was last opened, from its newest page. */
    suspend fun newClips(saved: SavedSearch): NewClips {
        val newest = searchPage(saved.query.copy(order = SearchOrder.Latest), 1, NEW_CHECK_SIZE)
        val count = countNew(newest.gifs, saved.seenUpTo)
        // A page that's new all the way down means there are more than it shows.
        return NewClips(count, atLeast = count > 0 && count == newest.gifs.size && newest.pages > 1)
    }

    /**
     * Up to three of [gif]'s least common tags that together still turn up plenty of clips,
     * so "more like this" follows what's particular about it rather than what's everywhere.
     */
    suspend fun similarTags(gif: Gif): List<String> {
        val taste = library.taste.value
        val tags = gif.tags.filterNot { it in taste.hiddenTags || taste.mutes(it) }.take(MAX_TAGS_CONSIDERED)
        if (tags.isEmpty()) return emptyList()
        coroutineScope {
            tags.filterNot(tagSizes::containsKey).map { tag ->
                async {
                    try {
                        api.tagSize(tag)?.let { tagSizes[tag] = it }
                    } catch (_: IOException) {
                        // An unknown size just leaves the tag in its original place.
                    }
                }
            }.awaitAll()
        }
        val ranked = distinctiveTags(tags, tagSizes, MIN_TAG_SIZE)
        val tries = (minOf(SIMILAR_TAGS, ranked.size) downTo 2).map { ranked.take(it) }
        val counts = coroutineScope {
            tries.map { candidate ->
                async {
                    try {
                        api.count(SearchQuery(tags = candidate))
                    } catch (_: IOException) {
                        0
                    }
                }
            }.awaitAll()
        }
        return tries.zip(counts).firstOrNull { (_, found) -> found >= MIN_SIMILAR_RESULTS }?.first ?: ranked.take(1)
    }

    private fun visible(page: GifPage): GifPage {
        val taste = library.taste.value
        val showPromoted = library.settings.value.showPromoted
        return page.copy(
            gifs = page.gifs.filterNot { taste.hides(it) || (it.promoted && !showPromoted) },
            relatedTags = page.relatedTags.filterNot { it in taste.hiddenTags || taste.mutes(it) },
        )
    }

    /** One page of My Mix: fresh clips from the top tags and creators, ranked for this person. */
    private suspend fun loadMix(page: Int): GifPage {
        val taste = library.taste.value
        if (taste.topTags(MIX_TAGS).isEmpty() && taste.topCreators(MIX_CREATORS).isEmpty()) {
            return visible(api.feed(FeedSource.Trending, page))
        }
        return ranked(taste, page)
    }

    /** More like a collection: its own tags and creators, ranked by how often they appear in it. */
    private suspend fun loadLike(saved: List<Gif>, page: Int): GifPage {
        val inList = saved.mapTo(HashSet()) { it.id }
        val found = ranked(collectionTaste(saved, library.taste.value), page)
        return visible(found.copy(gifs = found.gifs.filterNot { it.id in inList }))
    }

    private suspend fun ranked(taste: Taste, page: Int): GifPage {
        val sources = taste.topTags(MIX_TAGS).map { FeedSource.Tag(it) } +
            taste.topCreators(MIX_CREATORS).map { FeedSource.CreatorGifs(it) }
        if (sources.isEmpty()) return GifPage(emptyList(), 0)

        val candidates = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        api.feed(source, page, MIX_SOURCE_SIZE).gifs
                    } catch (_: IOException) {
                        // One dead creator or tag shouldn't sink the whole page.
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        return GifPage(MixRanker.rank(candidates, taste), Int.MAX_VALUE)
    }

    private suspend fun loadSimilar(gif: Gif, tags: List<String>, page: Int): GifPage {
        val fromTags = api.feed(FeedSource.Tag(tags.ifEmpty { gif.tags.take(1) }.joinToString(",")), page)
        val recommended = if (page == 1) {
            try {
                api.recommended(gif.id)
            } catch (_: IOException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val merged = (recommended + fromTags.gifs).distinctBy { it.id }.filterNot { it.id == gif.id }
        return fromTags.copy(gifs = merged)
    }

    private companion object {
        const val MIX_TAGS = 5
        const val MIX_CREATORS = 4
        const val MIX_SOURCE_SIZE = 20
        const val SIMILAR_TAGS = 3
        const val MAX_TAGS_CONSIDERED = 12
        const val MIN_TAG_SIZE = 500L
        const val MIN_SIMILAR_RESULTS = 60
        const val NEW_CHECK_SIZE = 40
    }
}

/** New clips in a saved search; [atLeast] when there are more than were counted. */
data class NewClips(val count: Int, val atLeast: Boolean = false) {
    val label: String get() = "$count${if (atLeast) "+" else ""} new"
}

/**
 * What a collection is about, as a taste: every clip in it counts as liked. The person's own
 * hidden tags, creators and seen clips still apply, so discovery doesn't repeat itself.
 */
internal fun collectionTaste(saved: List<Gif>, own: Taste): Taste =
    saved.fold(Taste()) { taste, gif -> taste.record(gif, Signal.Liked) }.copy(
        hiddenGifs = own.hiddenGifs,
        hiddenCreators = own.hiddenCreators,
        hiddenTags = own.hiddenTags,
        mutedWords = own.mutedWords,
        seen = own.seen,
    )
