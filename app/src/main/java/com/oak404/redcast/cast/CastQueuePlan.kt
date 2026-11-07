package com.oak404.redcast.cast

/**
 * Decides what the receiver's queue should hold.
 *
 * The receiver only ever gets a short window of the feed. Queue items carry a sequence number
 * that keeps counting past the end of the feed, so a finished feed loops instead of stopping
 * the TV, and the window is topped up as playback moves through it.
 */
internal object CastQueuePlan {

    const val BATCH_SIZE = 12
    private const val LOW_WATER = 4
    private const val KEEP_BEHIND = 3

    fun needsMore(currentSeq: Int, nextSeq: Int): Boolean = nextSeq - currentSeq - 1 < LOW_WATER

    /** Feed index for [seq], or null when the feed hasn't loaded that far. */
    fun feedIndex(seq: Int, feedSize: Int, exhausted: Boolean): Int? = when {
        feedSize == 0 -> null
        seq < feedSize -> seq
        exhausted -> seq % feedSize
        else -> null
    }

    /** The run of feed indices that can be queued starting at [fromSeq]. */
    fun nextIndices(fromSeq: Int, feedSize: Int, exhausted: Boolean): List<Int> = buildList {
        for (seq in fromSeq until fromSeq + BATCH_SIZE) {
            add(feedIndex(seq, feedSize, exhausted) ?: break)
        }
    }

    /** Played items worth dropping so the queue doesn't grow through a long session. */
    fun staleSeqs(queuedSeqs: List<Int>, currentSeq: Int): List<Int> =
        queuedSeqs.filter { it < currentSeq - KEEP_BEHIND }
}
