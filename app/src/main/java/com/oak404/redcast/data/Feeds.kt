package com.oak404.redcast.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** Builds the feed for a source, including the ones assembled on the phone. */
class Feeds(private val api: RedgifsApi, private val library: Library) {

    fun create(source: FeedSource): Feed = when (source) {
        is ApiFeed -> Feed(source) { page -> visible(api.feed(source, page)) }
        FeedSource.Mix -> Feed(source, ::loadMix)
        is FeedSource.Similar -> Feed(source) { page -> visible(loadSimilar(source.gif, page)) }
        is FeedSource.SavedList -> Feed(source) { page ->
            if (page > 1) GifPage(emptyList(), 1)
            else GifPage(library.lists.value[source.name].orEmpty(), 1)
        }
        FeedSource.History -> Feed(source) { page ->
            if (page > 1) GifPage(emptyList(), 1) else visible(GifPage(library.history.value, 1))
        }
    }

    private fun visible(page: GifPage): GifPage {
        val taste = library.taste.value
        return page.copy(gifs = page.gifs.filterNot(taste::hides))
    }

    /** One page of My Mix: fresh clips from the top tags and creators, ranked for this person. */
    private suspend fun loadMix(page: Int): GifPage {
        val taste = library.taste.value
        val sources = taste.topTags(MIX_TAGS).map { FeedSource.Tag(it) } +
            taste.topCreators(MIX_CREATORS).map { FeedSource.CreatorGifs(it) }
        if (sources.isEmpty()) return visible(api.feed(FeedSource.Trending, page))

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

    private suspend fun loadSimilar(gif: Gif, page: Int): GifPage {
        val tagged = FeedSource.Tag(gif.tags.take(SIMILAR_TAGS).joinToString(","))
        val fromTags = api.feed(tagged, page)
        val recommended = if (page == 1) api.recommended(gif.id) else emptyList()
        val merged = (recommended + fromTags.gifs).distinctBy { it.id }.filterNot { it.id == gif.id }
        return fromTags.copy(gifs = merged)
    }

    private companion object {
        const val MIX_TAGS = 5
        const val MIX_CREATORS = 4
        const val MIX_SOURCE_SIZE = 20
        const val SIMILAR_TAGS = 2
    }
}
