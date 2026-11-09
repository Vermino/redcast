package com.oak404.redcast

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.oak404.redcast.auth.Session
import com.oak404.redcast.cast.CastController
import com.oak404.redcast.cast.CastRelay
import com.oak404.redcast.data.Feeds
import com.oak404.redcast.data.Library
import com.oak404.redcast.data.RedgifsApi
import com.oak404.redcast.data.Updates
import kotlinx.coroutines.MainScope
import okhttp3.OkHttpClient
import java.io.File

class RedcastApp : Application() {

    /** Outlives the UI so a Cast queue keeps topping up while the app is in the background. */
    val scope = MainScope()

    private val http by lazy { httpForTests ?: OkHttpClient() }
    val session by lazy { Session(this, http) }
    val api by lazy { RedgifsApi(session, http) }
    val library by lazy { Library(this) }
    val feeds by lazy { Feeds(api, library) }
    val updates by lazy { Updates(http) }
    val cast by lazy { CastController(this, scope, CastRelay(http)) }

    @get:OptIn(UnstableApi::class)
    private val videoCache by lazy {
        SimpleCache(
            File(cacheDir, "video"),
            LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_BYTES),
            StandaloneDatabaseProvider(this),
        )
    }

    @get:OptIn(UnstableApi::class)
    val preloader by lazy { Preloader(videoCache, OkHttpDataSource.Factory(http)) }

    @OptIn(UnstableApi::class)
    fun createPlayer(): ExoPlayer {
        val dataSource = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
        // Clips are short, so start as soon as a second is buffered instead of the default 2.5.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .build()
    }

    companion object {
        private const val VIDEO_CACHE_BYTES = 300L * 1024 * 1024
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 30_000
        private const val START_BUFFER_MS = 1_000
        private const val REBUFFER_MS = 2_000

        fun get(context: Context) = context.applicationContext as RedcastApp

        /** Set by UI tests before the app first makes a request, to answer from fakes. */
        @VisibleForTesting
        var httpForTests: OkHttpClient? = null
    }
}
