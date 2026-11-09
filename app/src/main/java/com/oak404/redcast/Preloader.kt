package com.oak404.redcast

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Fetches the clips after the one playing into the player's disk cache, so swiping to them
 * starts from the phone instead of the network. Only one batch runs at a time: moving to
 * another clip cancels the old batch and starts the next.
 */
@OptIn(UnstableApi::class)
class Preloader(cache: Cache, upstream: DataSource.Factory) {

    private val sources = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var batch: Job? = null

    /** Caches each of [urls] in turn, the first [bytesEach] of each, or all of it when null. */
    fun preload(urls: List<String>, bytesEach: Long?) {
        batch?.cancel()
        if (urls.isEmpty()) return
        batch = scope.launch {
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                val spec = DataSpec.Builder()
                    .setUri(url)
                    .setLength(bytesEach ?: C.LENGTH_UNSET.toLong())
                    .build()
                val writer = CacheWriter(sources.createDataSource(), spec, null, null)
                val stop = currentCoroutineContext().job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } catch (_: IOException) {
                    // A clip that won't load now is the player's problem when it gets there.
                } finally {
                    stop.dispose()
                }
            }
        }
    }

    fun cancel() {
        batch?.cancel()
    }
}
