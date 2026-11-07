package com.oak404.redcast

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.oak404.redcast.auth.Session
import com.oak404.redcast.cast.CastController
import com.oak404.redcast.cast.CastRelay
import com.oak404.redcast.data.Feeds
import com.oak404.redcast.data.Library
import com.oak404.redcast.data.RedgifsApi
import kotlinx.coroutines.MainScope
import okhttp3.OkHttpClient
import java.io.File

class RedcastApp : Application() {

    /** Outlives the UI so a Cast queue keeps topping up while the app is in the background. */
    val scope = MainScope()

    private val http by lazy { OkHttpClient() }
    val session by lazy { Session(this, http) }
    val api by lazy { RedgifsApi(session, http) }
    val library by lazy { Library(this) }
    val feeds by lazy { Feeds(api, library) }
    val cast by lazy { CastController(this, scope, CastRelay(http)) }

    @get:OptIn(UnstableApi::class)
    private val videoCache by lazy {
        SimpleCache(
            File(cacheDir, "video"),
            LeastRecentlyUsedCacheEvictor(VIDEO_CACHE_BYTES),
            StandaloneDatabaseProvider(this),
        )
    }

    @OptIn(UnstableApi::class)
    fun createPlayer(): ExoPlayer {
        val dataSource = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http))
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
    }

    companion object {
        private const val VIDEO_CACHE_BYTES = 300L * 1024 * 1024

        fun get(context: Context) = context.applicationContext as RedcastApp
    }
}
