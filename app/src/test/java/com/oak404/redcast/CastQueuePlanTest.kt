package com.oak404.redcast

import com.oak404.redcast.cast.CastQueuePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastQueuePlanTest {

    @Test
    fun topsUpOnlyWhenTheQueueRunsLow() {
        assertFalse(CastQueuePlan.needsMore(currentSeq = 0, nextSeq = 12))
        assertFalse(CastQueuePlan.needsMore(currentSeq = 7, nextSeq = 12))
        assertTrue(CastQueuePlan.needsMore(currentSeq = 8, nextSeq = 12))
        assertTrue(CastQueuePlan.needsMore(currentSeq = 11, nextSeq = 12))
    }

    @Test
    fun waitsForMoreItemsWhileTheFeedIsStillLoading() {
        assertNull(CastQueuePlan.feedIndex(seq = 40, feedSize = 40, exhausted = false))
        assertEquals((30 until 40).toList(), CastQueuePlan.nextIndices(30, 40, exhausted = false))
    }

    @Test
    fun loopsAFinishedFeedInsteadOfStopping() {
        assertEquals(0, CastQueuePlan.feedIndex(seq = 40, feedSize = 40, exhausted = true))
        assertEquals(
            listOf(38, 39, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            CastQueuePlan.nextIndices(38, 40, exhausted = true),
        )
    }

    @Test
    fun loopsAFeedShorterThanOneBatch() {
        assertEquals(12, CastQueuePlan.nextIndices(0, 3, exhausted = true).size)
        assertEquals(listOf(0, 1, 2, 0), CastQueuePlan.nextIndices(0, 3, exhausted = true).take(4))
    }

    @Test
    fun anEmptyFeedQueuesNothing() {
        assertTrue(CastQueuePlan.nextIndices(0, 0, exhausted = true).isEmpty())
    }

    @Test
    fun dropsOnlyClipsWellBehindThePlayhead() {
        assertEquals(listOf(5, 6), CastQueuePlan.staleSeqs(listOf(5, 6, 7, 8, 9, 10, 11), 10))
        assertTrue(CastQueuePlan.staleSeqs(listOf(9, 10, 11), 10).isEmpty())
    }
}
