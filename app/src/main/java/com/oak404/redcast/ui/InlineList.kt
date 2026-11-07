package com.oak404.redcast.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.oak404.redcast.R
import com.oak404.redcast.RedcastApp
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.Gif
import com.oak404.redcast.data.aspectRatio
import kotlinx.coroutines.delay
import kotlin.math.abs

/** The list layout: the card nearest the middle of the screen plays muted, in place. */
@OptIn(UnstableApi::class)
@Composable
fun InlineList(viewModel: MainViewModel, feed: Feed, gifs: List<Gif>, state: LazyListState) {
    val context = LocalContext.current
    val deviceName by viewModel.cast.deviceName.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val currentGifs by rememberUpdatedState(gifs)
    val casting = deviceName != null

    val player = remember(feed) {
        RedcastApp.get(context).createPlayer().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    val active by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }?.index ?: 0
        }
    }

    LaunchedEffect(player, active) {
        // A fling passes over many cards, and only the one it stops on should load.
        delay(SETTLE_MS)
        val gif = currentGifs.getOrNull(active) ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.Builder().setUri(viewModel.playbackUrl(gif)).setMediaId(gif.id).build())
        player.prepare()
    }
    LaunchedEffect(player, casting, lifecycleState) {
        player.playWhenReady = !casting && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(gifs, key = { _, gif -> gif.id }) { index, gif ->
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(gif.aspectRatio)
                        .background(Color.Black)
                        .clickable { viewModel.openGif(feed, index) },
                ) {
                    AsyncImage(
                        model = gif.posterUrl,
                        contentDescription = "Video by ${gif.userName}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (index == active && !casting) {
                        AndroidView(
                            factory = {
                                LayoutInflater.from(it).inflate(R.layout.player_view, null) as PlayerView
                            },
                            update = { it.player = player },
                            onRelease = { it.player = null },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    "${gif.userName} · ${gif.durationSec.toInt()}s",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable { viewModel.openCreator(gif.userName) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private const val SETTLE_MS = 300L
