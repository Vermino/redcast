package com.oak404.redcast

import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.FeedSource
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.GifPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedTest {

    private fun gif(id: String) = Gif(id, "someone", "", "", "", "", 5.0, emptyList())

    private fun likedFeed(vararg ids: String) =
        Feed(FeedSource.Liked) { GifPage(ids.map(::gif), pages = 1) }

    private fun Feed.ids() = items.value.map { it.id }

    @Test
    fun aNewLikeGoesFirstAndOnlyOnce(): Unit = runBlocking {
        val feed = likedFeed("a", "b")
        feed.ensureLoaded(2)
        feed.prepend(gif("c"))
        feed.prepend(gif("c"))
        feed.prepend(gif("a"))
        assertEquals(listOf("c", "a", "b"), feed.ids())
    }

    @Test
    fun aLikeBeforeTheFirstLoadIsntDoubledByIt(): Unit = runBlocking {
        val feed = likedFeed("a", "b")
        feed.prepend(gif("a"))
        feed.ensureLoaded(3)
        assertEquals(listOf("a", "b"), feed.ids())
    }

    @Test
    fun unlikesComeOut(): Unit = runBlocking {
        val feed = likedFeed("a", "b", "c")
        feed.ensureLoaded(3)
        feed.remove(setOf("a", "c"))
        assertEquals(listOf("b"), feed.ids())
    }
}
