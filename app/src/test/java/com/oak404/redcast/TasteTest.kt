package com.oak404.redcast

import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.MixRanker
import com.oak404.redcast.data.Signal
import com.oak404.redcast.data.Taste
import com.oak404.redcast.data.parseGif
import com.oak404.redcast.data.tasteFrom
import com.oak404.redcast.data.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteTest {

    private fun gif(
        id: String,
        user: String = "creator-$id",
        tags: List<String> = emptyList(),
        likes: Long = 0,
    ) = Gif(id, user, "https://m/$id.mp4", "https://m/$id-mobile.mp4", "", "", 10.0, tags, likes = likes)

    @Test
    fun likingAClipRaisesItsTagsAndCreator() {
        val taste = Taste().record(gif("a", "ann", listOf("x", "y")), Signal.Liked)
        assertEquals(3.0, taste.tagScores["x"]!!, 0.0)
        assertEquals(3.0, taste.creatorScores["ann"]!!, 0.0)
    }

    @Test
    fun skippingLowersAScoreAndItLeavesTheTopList() {
        val clip = gif("a", "ann", listOf("x"))
        val taste = Taste().record(clip, Signal.Finished).record(clip, Signal.Skipped).record(clip, Signal.Skipped)
        assertTrue(taste.topTags(5).isEmpty())
        assertTrue(taste.topCreators(5).isEmpty())
    }

    @Test
    fun pinnedTagsComeFirstEvenWithoutAScore() {
        val taste = Taste().record(gif("a", tags = listOf("learned")), Signal.Liked).togglePinned("pinned")
        assertEquals(listOf("pinned", "learned"), taste.topTags(5))
        assertEquals(listOf("learned"), taste.togglePinned("pinned").topTags(5))
    }

    @Test
    fun hidesByClipCreatorOrTag() {
        val taste = Taste(hiddenGifs = setOf("a"), hiddenCreators = setOf("bob"), hiddenTags = setOf("no"))
        assertTrue(taste.hides(gif("a")))
        assertTrue(taste.hides(gif("b", "bob")))
        assertTrue(taste.hides(gif("c", tags = listOf("no", "ok"))))
        assertFalse(taste.hides(gif("d", tags = listOf("ok"))))
    }

    @Test
    fun rankingPutsAFittingClipAboveAMerelyPopularOne() {
        val taste = Taste().record(gif("old", tags = listOf("loved")), Signal.Liked)
        val fit = gif("fit", tags = listOf("loved"), likes = 10)
        val popular = gif("popular", tags = listOf("other"), likes = 100_000)
        assertEquals(listOf("fit", "popular"), MixRanker.rank(listOf(popular, fit), taste).map { it.id })
    }

    @Test
    fun popularityBreaksTiesBetweenEquallyFittingClips() {
        val ranked = MixRanker.rank(listOf(gif("low", likes = 1), gif("high", likes = 5000)), Taste())
        assertEquals(listOf("high", "low"), ranked.map { it.id })
    }

    @Test
    fun rankingDropsSeenHiddenAndDuplicateClips() {
        val taste = Taste(seen = listOf("seen"), hiddenGifs = setOf("hidden"))
        val ranked = MixRanker.rank(listOf(gif("seen"), gif("hidden"), gif("new"), gif("new")), taste)
        assertEquals(listOf("new"), ranked.map { it.id })
    }

    @Test
    fun oneCreatorCannotFillTheWholeMix() {
        val own = (1..8).map { gif("c$it", "busy") }
        val ranked = MixRanker.rank(own + gif("other", "someone-else"), Taste())
        assertEquals(3, ranked.count { it.userName == "busy" })
        assertTrue(ranked.any { it.id == "other" })
    }

    @Test
    fun seenListStaysBounded() {
        val taste = (1..3100).fold(Taste()) { acc, i -> acc.markSeen("id$i") }
        assertEquals(3000, taste.seen.size)
        assertEquals("id3100", taste.seen.last())
    }

    @Test
    fun tasteSurvivesSavingAndLoading() {
        val taste = Taste(
            tagScores = mapOf("x" to 2.5),
            creatorScores = mapOf("ann" to 1.0),
            pinnedTags = listOf("p"),
            hiddenGifs = setOf("g"),
            hiddenCreators = setOf("c"),
            hiddenTags = setOf("t"),
            seen = listOf("s"),
        )
        assertEquals(taste, tasteFrom(JSONObject(taste.toJson().toString())))
    }

    @Test
    fun savedClipsSurviveSavingAndLoading() {
        val clip = gif("a", "ann", listOf("x"), likes = 7).copy(width = 1920, height = 1080)
        assertEquals(clip, parseGif(JSONObject(clip.toJson().toString())))
    }

    @Test
    fun passingOutweighsASkipAndLowersTheCreatorAndTags() {
        val pass = Taste().record(gif("a", "ann", listOf("x")), Signal.Passed)
        val skip = Taste().record(gif("a", "ann", listOf("x")), Signal.Skipped)
        assertTrue(pass.tagScores.getValue("x") < skip.tagScores.getValue("x"))
        assertTrue(pass.creatorScores.getValue("ann") < 0)
    }
}
