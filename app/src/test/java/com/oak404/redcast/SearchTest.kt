package com.oak404.redcast

import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.GifPage
import com.oak404.redcast.data.SearchOrder
import com.oak404.redcast.data.SearchQuery
import com.oak404.redcast.data.Taste
import com.oak404.redcast.data.WordFilter
import com.oak404.redcast.data.normalizeWord
import com.oak404.redcast.data.parseGif
import com.oak404.redcast.data.searchOf
import com.oak404.redcast.data.tasteFrom
import com.oak404.redcast.data.toJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private fun gif(id: String, tags: List<String> = emptyList(), description: String = "") =
        Gif(id, "someone", "https://m/$id.mp4", "https://m/$id-sd.mp4", "", "", 5.0, tags, description = description)

    @Test
    fun aMutedWordCatchesWholeWordsInTagsAndDescriptionsInAnyCase() {
        val filter = WordFilter(listOf("gay"))
        assertTrue(filter.matches(gif("a", listOf("Blonde", "Gay"))))
        assertTrue(filter.matches(gif("b", listOf("Gay Porn"))))
        assertTrue(filter.matches(gif("c", description = "my GAY friend")))
        assertFalse(filter.matches(gif("d", listOf("Gaysha", "Legaye"))))
        assertFalse(filter.matches(gif("e", listOf("Blonde"))))
    }

    @Test
    fun mutedPhrasesMatchAsAPhrase() {
        val filter = WordFilter(listOf("big dick"))
        assertTrue(filter.matches("Big Dick"))
        assertFalse(filter.matches("Big"))
        assertFalse(filter.matches("Dick"))
    }

    @Test
    fun wordsWithRegexCharactersAreTakenLiterally() {
        val filter = WordFilter(listOf("r/nsfw", "a+b"))
        assertTrue(filter.matches("r/NSFW"))
        assertTrue(filter.matches("A+B"))
        assertFalse(filter.matches("aab"))
    }

    @Test
    fun anEmptyFilterMatchesNothing() {
        val filter = WordFilter(listOf("", "  "))
        assertTrue(filter.isEmpty)
        assertFalse(filter.matches(gif("a", listOf("Gay"))))
    }

    @Test
    fun tasteHidesMutedClipsAndKeepsMutedTagsOutOfTheTopList() {
        val taste = Taste(tagScores = mapOf("Gay" to 5.0, "Blonde" to 3.0), mutedWords = setOf("gay"))
        assertTrue(taste.hides(gif("a", listOf("Blonde", "Gay"))))
        assertFalse(taste.hides(gif("b", listOf("Blonde"))))
        assertEquals(listOf("Blonde"), taste.topTags(5))
    }

    @Test
    fun mutedWordsSurviveSavingAndLoading() {
        val taste = Taste(mutedWords = setOf("gay", "big dick"))
        assertEquals(taste.mutedWords, tasteFrom(JSONObject(taste.toJson().toString())).mutedWords)
    }

    @Test
    fun typedWordsAreTidied() {
        assertEquals("big dick", normalizeWord("  Big   DICK "))
    }

    @Test
    fun descriptionsAreReadAndSaved() {
        val clip = gif("a", listOf("Blonde"), description = "hello")
        assertEquals(clip, parseGif(JSONObject(clip.toJson().toString())))
        val noDescription = JSONObject(clip.toJson().toString()).put("description", JSONObject.NULL)
        assertEquals("", parseGif(noDescription)?.description)
    }

    @Test
    fun aQueryAsksForEveryTagTheTextAndTheOrder() {
        val query = SearchQuery(tags = listOf("Blonde", "Asian"), text = " beach ", order = SearchOrder.Week)
        assertEquals("tags=Blonde,Asian&query=beach&order=top7", query.apiParams { it })
    }

    @Test
    fun simpleSearchesStayPlainListings() {
        assertEquals(FeedSource.Tag("Blonde"), SearchQuery(tags = listOf("Blonde")).toSource())
        assertEquals(FeedSource.Search("beach"), SearchQuery(text = "beach").toSource())
        val many = SearchQuery(tags = listOf("Blonde", "Asian"))
        assertEquals(FeedSource.Query(many), many.toSource())
        val sorted = SearchQuery(tags = listOf("Blonde"), order = SearchOrder.Latest)
        assertEquals(FeedSource.Query(sorted), sorted.toSource())
    }

    @Test
    fun aTagIsEitherWantedOrLeftOutNeverBoth() {
        val query = SearchQuery().withTag("Blonde").withTag("blonde").withExcluded("Blonde")
        assertEquals(emptyList<String>(), query.tags)
        assertEquals(listOf("Blonde"), query.exclude)
        assertEquals(listOf("Blonde"), query.withTag("Blonde").tags)
        assertTrue(query.withTag("Blonde").exclude.isEmpty())
    }

    @Test
    fun recentSearchesRoundTripAndOldTextOnesStillRead() {
        val query = SearchQuery(listOf("Blonde", "Asian"), listOf("gay"), "beach", SearchOrder.Month)
        assertEquals(query, SearchQuery.fromRecent(query.toRecent()))
        assertEquals("beach", SearchQuery(text = " beach ").toRecent())
        assertEquals(SearchQuery(text = "old search"), SearchQuery.fromRecent("old search"))
        assertEquals(SearchQuery(text = "{broken"), SearchQuery.fromRecent("{broken"))
    }

    @Test
    fun aListingRemembersItsSearch() {
        val query = SearchQuery(tags = listOf("Blonde", "Asian"))
        assertEquals(query, searchOf(FeedSource.Query(query)))
        assertEquals(SearchQuery(tags = listOf("Blonde")), searchOf(FeedSource.Tag("Blonde")))
        assertEquals(SearchQuery(text = "beach"), searchOf(FeedSource.Search("beach")))
        assertEquals(null, searchOf(FeedSource.Trending))
    }

    @Test
    fun titleReadsLikeTheSearch() {
        assertEquals("Blonde + Asian \"beach\" −gay", SearchQuery(listOf("Blonde", "Asian"), listOf("gay"), "beach").title)
    }

    @Test
    fun aFeedLooksPastPagesThatMutingEmptied(): Unit = runBlocking {
        val feed = Feed(FeedSource.Trending) { page ->
            if (page < 3) GifPage(emptyList(), pages = 10) else GifPage(listOf(gif("p$page")), pages = 10)
        }
        feed.ensureLoaded(1)
        assertEquals(listOf("p3"), feed.items.value.map { it.id })
        assertFalse(feed.exhausted.value)
    }

    @Test
    fun aFeedGivesUpAfterSeveralEmptyPages(): Unit = runBlocking {
        var calls = 0
        val feed = Feed(FeedSource.Trending) {
            calls++
            GifPage(emptyList(), pages = 1000)
        }
        feed.ensureLoaded(1)
        assertTrue(feed.exhausted.value)
        assertEquals(5, calls)
    }

    @Test
    fun aFeedKeepsTheRelatedTagsFromItsFirstPage(): Unit = runBlocking {
        val feed = Feed(FeedSource.Trending) { page ->
            GifPage(listOf(gif("p$page")), pages = 3, relatedTags = listOf("T$page"))
        }
        feed.ensureLoaded(2)
        assertEquals(listOf("T1"), feed.relatedTags.value)
    }
}
