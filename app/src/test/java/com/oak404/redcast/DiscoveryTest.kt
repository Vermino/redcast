package com.oak404.redcast

import com.oak404.redcast.data.ApiChangedException
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.ImportResult
import com.oak404.redcast.data.NewClips
import com.oak404.redcast.data.SavedSearch
import com.oak404.redcast.data.SearchOrder
import com.oak404.redcast.data.SearchQuery
import com.oak404.redcast.data.Settings
import com.oak404.redcast.data.Taste
import com.oak404.redcast.data.VideoQuality
import com.oak404.redcast.data.countNew
import com.oak404.redcast.data.distinctiveTags
import com.oak404.redcast.data.mergeLists
import com.oak404.redcast.data.mergeRecent
import com.oak404.redcast.data.parseGif
import com.oak404.redcast.data.parseGifPage
import com.oak404.redcast.data.readExport
import com.oak404.redcast.data.searchOf
import com.oak404.redcast.data.toJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    private fun gif(id: String, tags: List<String> = emptyList(), created: Long = 0) =
        Gif(id, "someone", "https://m/$id.mp4", "https://m/$id-sd.mp4", "", "", 5.0, tags, created = created)

    // Saved searches

    @Test
    fun savedSearchesSurviveSavingAndLoading() {
        val saved = SavedSearch(SearchQuery(listOf("Blonde", "Asian"), listOf("gay"), "beach", SearchOrder.Week), 1_700_000_000)
        assertEquals(saved, SavedSearch.fromJson(JSONObject(saved.toJson().toString())))
    }

    @Test
    fun aSavedSearchWithNothingToSearchForIsDropped() {
        val empty = SavedSearch(SearchQuery(exclude = listOf("gay")), 5).toJson()
        assertNull(SavedSearch.fromJson(empty))
        assertNull(SavedSearch.fromJson(JSONObject().put("seenUpTo", 5)))
    }

    @Test
    fun onlyClipsPostedAfterTheLastVisitAreNew() {
        val newestFirst = listOf(gif("a", created = 300), gif("b", created = 200), gif("c", created = 100))
        assertEquals(2, countNew(newestFirst, since = 150))
        assertEquals(0, countNew(newestFirst, since = 300))
        assertEquals(0, countNew(listOf(gif("undated")), since = 0))
    }

    @Test
    fun newCountsSayWhenThereAreMoreThanCounted() {
        assertEquals("3 new", NewClips(3).label)
        assertEquals("40+ new", NewClips(40, atLeast = true).label)
    }

    @Test
    fun clipDatesAreReadAndSaved() {
        val clip = gif("a", created = 1_794_217_177)
        assertEquals(clip, parseGif(JSONObject(clip.toJson().toString())))
    }

    // More like this

    @Test
    fun theLeastCommonUsableTagsComeFirst() {
        val sizes = mapOf("Blonde" to 1_400_000L, "Beach" to 40_000L, "Tiny" to 12L, "Pov" to 1_000_000L)
        assertEquals(
            listOf("Beach", "Pov", "Blonde"),
            distinctiveTags(listOf("Blonde", "Pov", "Tiny", "Beach", "Unknown"), sizes, minSize = 500),
        )
    }

    @Test
    fun withoutSizesTheClipsOwnOrderStands() {
        assertEquals(listOf("A", "B"), distinctiveTags(listOf("A", "B"), emptyMap(), minSize = 500))
    }

    @Test
    fun moreLikeThisCanBeEditedAsASearchOfItsTags() {
        val source = FeedSource.Similar(gif("a"), listOf("Beach", "Pov"))
        assertEquals(SearchQuery(tags = listOf("Beach", "Pov")), searchOf(source))
        assertEquals("Like this: Beach + Pov", source.title)
        assertNull(searchOf(FeedSource.Similar(gif("a"), emptyList())))
    }

    // API changes

    @Test(expected = ApiChangedException::class)
    fun aListingWithoutClipsMeansTheApiChanged() {
        parseGifPage(JSONObject().put("results", JSONArray()))
    }

    @Test(expected = ApiChangedException::class)
    fun aListingWhereNoClipCanBeReadMeansTheApiChanged() {
        val unreadable = JSONObject().put("id", "a").put("type", 1).put("media", JSONObject())
        parseGifPage(JSONObject().put("gifs", JSONArray().put(unreadable)).put("pages", 1))
    }

    @Test
    fun skippedImagesAndEmptyPagesAreStillFine() {
        val image = JSONObject().put("id", "img").put("type", 2)
        assertTrue(parseGifPage(JSONObject().put("gifs", JSONArray().put(image)).put("pages", 1)).gifs.isEmpty())
        assertTrue(parseGifPage(JSONObject().put("gifs", JSONArray()).put("pages", 0)).gifs.isEmpty())
    }

    @Test
    fun clipsWithoutAnIdAreSkipped() {
        assertNull(parseGif(JSONObject(gif("a").toJson().toString()).put("id", "")))
    }

    // Import

    @Test
    fun importingFoldsTasteInWithoutLosingAnything() {
        val here = Taste(
            tagScores = mapOf("Blonde" to 5.0),
            hiddenTags = setOf("Feet"),
            mutedWords = setOf("gay"),
            seen = listOf("a", "b"),
        )
        val file = Taste(
            tagScores = mapOf("Blonde" to 1.0, "Asian" to 3.0),
            hiddenCreators = setOf("spammer"),
            mutedWords = setOf("trans"),
            seen = listOf("b", "c"),
        )
        val merged = here.mergedWith(file)
        assertEquals(mapOf("Blonde" to 5.0, "Asian" to 3.0), merged.tagScores)
        assertEquals(setOf("Feet"), merged.hiddenTags)
        assertEquals(setOf("spammer"), merged.hiddenCreators)
        assertEquals(setOf("gay", "trans"), merged.mutedWords)
        assertEquals(setOf("a", "b", "c"), merged.seen.toSet())
        assertEquals(merged, merged.mergedWith(file))
    }

    @Test
    fun listsKeepTheirOrderAndGainWhatTheyLacked() {
        val local = mapOf("Faves" to listOf(gif("a"), gif("b")))
        val imported = mapOf("Faves" to listOf(gif("b"), gif("c")), "New" to listOf(gif("d")))
        val merged = mergeLists(local, imported)
        assertEquals(listOf("a", "b", "c"), merged.getValue("Faves").map { it.id })
        assertEquals(listOf("d"), merged.getValue("New").map { it.id })
        assertEquals(merged, mergeLists(merged, imported))
    }

    @Test
    fun recentItemsFromThisPhoneStayFirst() {
        assertEquals(listOf("x", "y", "z"), mergeRecent(listOf("x", "y"), listOf("y", "z", "w"), 3) { it })
    }

    @Test
    fun anExportIsReadBackAndBrokenPartsAreSkipped() {
        val saved = SavedSearch(SearchQuery(tags = listOf("Blonde")), 10)
        val json = JSONObject()
            .put("settings", Settings(quality = VideoQuality.Sd).toJson())
            .put("taste", Taste(mutedWords = setOf("gay")).toJson())
            .put("lists", JSONObject().put("Faves", JSONArray().put(gif("a").toJson()).put("junk")))
            .put("history", JSONArray().put(gif("h").toJson()))
            .put("searches", JSONArray().put("beach"))
            .put("savedSearches", JSONArray().put(saved.toJson()).put(JSONObject()))
        val export = readExport(JSONObject(json.toString()))
        assertEquals(VideoQuality.Sd, export.settings?.quality)
        assertEquals(setOf("gay"), export.taste?.mutedWords)
        assertEquals(listOf("a"), export.lists.getValue("Faves").map { it.id })
        assertEquals(listOf("h"), export.history.map { it.id })
        assertEquals(listOf("beach"), export.searches)
        assertEquals(listOf(saved), export.savedSearches)
    }

    @Test
    fun anImportSaysWhatItBroughtIn() {
        assertEquals(
            "Imported 2 lists (5 clips), 1 saved search, 30 clips of history, 2 muted words",
            ImportResult(lists = 2, clips = 5, history = 30, savedSearches = 1, mutedWords = 2).summary,
        )
        assertEquals("Imported settings and taste", ImportResult(0, 0, 0, 0, 0).summary)
    }
}
