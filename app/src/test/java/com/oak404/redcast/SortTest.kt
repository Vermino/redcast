package com.oak404.redcast

import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.Taste
import com.oak404.redcast.data.collectionTaste
import com.oak404.redcast.ui.MAX_SORT_LISTS
import com.oak404.redcast.ui.NEW_LIST
import com.oak404.redcast.ui.sortTargets
import com.oak404.redcast.ui.targetAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SortTest {

    private fun gif(id: String, user: String, vararg tags: String) =
        Gif(id, user, "https://m/$id.mp4", "https://m/$id-mobile.mp4", "", "", 10.0, tags.toList())

    @Test
    fun bubblesEndWithNewListAndStayOnScreen() {
        val targets = sortTargets(List(10) { "List $it" }, 1080, 2400)
        assertEquals(MAX_SORT_LISTS + 1, targets.size)
        assertTrue(targets.last().isNew)
        assertEquals(NEW_LIST, targets.last().label)
        assertTrue(targets.all { it.x in 0f..1080f && it.y in 0f..2400f })
    }

    @Test
    fun withNoListsThereIsStillNewList() {
        val targets = sortTargets(emptyList(), 1080, 2400)
        assertEquals(1, targets.size)
        assertTrue(targets.single().isNew)
    }

    @Test
    fun aDropPicksTheNearestBubbleInReach() {
        val targets = sortTargets(listOf("A", "B", "C"), 1000, 2000)
        val a = targets.first()
        assertEquals("A", targetAt(targets, a.x + 10, a.y - 10, 60f)?.label)
        assertNull(targetAt(targets, 500f, 1000f, 60f))
    }

    @Test
    fun aCollectionsTasteFollowsItsTagsAndCreators() {
        val saved = listOf(gif("1", "ann", "x", "y"), gif("2", "ann", "x"), gif("3", "bob", "z"))
        val own = Taste(hiddenTags = setOf("y"), seen = listOf("9"))
        val taste = collectionTaste(saved, own)
        assertEquals(listOf("x", "z"), taste.topTags(2))
        assertEquals("ann", taste.topCreators(1).single())
        assertEquals(listOf("9"), taste.seen)
    }
}
