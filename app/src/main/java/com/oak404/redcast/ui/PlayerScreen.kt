package com.oak404.redcast.ui

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.oak404.redcast.R
import com.oak404.redcast.RedcastApp
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.Gif
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun PlayerScreen(viewModel: MainViewModel, feed: Feed, startIndex: Int) {
    val context = LocalContext.current
    val cast = viewModel.cast
    val gifs by feed.items.collectAsStateWithLifecycle()
    val exhausted by feed.exhausted.collectAsStateWithLifecycle()
    val deviceName by cast.deviceName.collectAsStateWithLifecycle()
    val nowPlaying by cast.nowPlaying.collectAsStateWithLifecycle()
    val castProblem by cast.problem.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val signedIn by viewModel.session.signedIn.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scope = rememberCoroutineScope()

    val pager = rememberPagerState(initialPage = startIndex) { gifs.size }
    val player = remember { RedcastApp.get(context).createPlayer() }
    val currentGifs by rememberUpdatedState(gifs)
    var queuedCount by remember { mutableIntStateOf(0) }
    var muted by rememberSaveable { mutableStateOf(false) }
    var userPaused by rememberSaveable { mutableStateOf(false) }
    var firstSettle by remember { mutableStateOf(true) }
    var savingGif by remember { mutableStateOf<Gif?>(null) }
    val settings by viewModel.library.settings.collectAsStateWithLifecycle()
    var sortHint by remember { mutableStateOf(!viewModel.library.sortHintSeen) }
    val lists by viewModel.library.lists.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var sorting by remember { mutableStateOf<Gif?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val reach = with(LocalDensity.current) { SORT_REACH.toPx() }
    val targets = remember(lists.keys, screen) { sortTargets(lists.keys.toList(), screen.width, screen.height) }
    var lastSettled by remember { mutableIntStateOf(startIndex) }
    var tagsFor by remember { mutableStateOf<Gif?>(null) }

    val casting = deviceName != null
    val autoscroll = viewModel.autoscroll

    DisposableEffect(player) {
        var failuresInARow = 0
        var lastFinishedId: String? = null
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    // A looping clip ends over and over: watching it through counts once.
                    val finished = currentGifs.getOrNull(oldPosition.mediaItemIndex) ?: return
                    if (finished.id != lastFinishedId) viewModel.onClipFinished(finished)
                    lastFinishedId = finished.id
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    scope.launch { pager.animateScrollToPage(player.currentMediaItemIndex) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // One dead clip shouldn't end the run, but a run of them (offline, say) shouldn't
                // spin through the whole playlist or retry forever either.
                if (++failuresInARow > MAX_FAILURES_IN_A_ROW) return
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    // A seek, so the pager isn't told as it is for a clip that ends: bring it along,
                    // or the screen keeps showing the dead clip's poster over the next one's sound.
                    scope.launch { pager.animateScrollToPage(player.currentMediaItemIndex) }
                }
                player.prepare()
            }

            override fun onRenderedFirstFrame() {
                failuresInARow = 0
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            viewModel.stopPreloading()
            viewModel.onPlayerClosed()
        }
    }

    // The whole feed sits in one ExoPlayer playlist, which gives gapless advances and
    // buffers the next clip early.
    LaunchedEffect(gifs.size) {
        if (gifs.size <= queuedCount) return@LaunchedEffect
        val added = gifs.subList(queuedCount, gifs.size).map { mediaItem(it, viewModel.playbackUrl(it)) }
        if (queuedCount == 0) {
            player.setMediaItems(added, startIndex.coerceAtMost(added.lastIndex), 0L)
            player.prepare()
        } else {
            player.addMediaItems(added)
            if (player.playbackState == Player.STATE_ENDED) player.seekToNextMediaItem()
        }
        queuedCount = gifs.size
    }

    LaunchedEffect(pager.settledPage, gifs.isEmpty()) {
        val page = pager.settledPage
        if (gifs.isEmpty()) return@LaunchedEffect
        // A clip muted or hidden since this feed loaded is stepped over, in the direction of travel.
        val forward = page >= lastSettled
        lastSettled = page
        if (!casting && viewModel.library.taste.value.hidesByRule(gifs[page])) {
            val next = if (forward) page + 1 else page - 1
            if (next in gifs.indices) {
                pager.animateScrollToPage(next)
                return@LaunchedEffect
            }
            if (forward) feed.loadMore()
        }
        viewModel.onPlayerPosition(feed, page)
        viewModel.onClipSeen(gifs[page])
        val playing = player.currentMediaItemIndex
        if (page != playing) {
            if (!casting && player.currentPosition < SKIP_THRESHOLD_MS) {
                gifs.getOrNull(playing)?.let(viewModel::onClipSkipped)
            }
            player.seekTo(page, 0L)
        }
        if (page >= gifs.size - LOAD_AHEAD) feed.loadMore()
        // Reopening this screen shouldn't drag the TV back to where the phone last was.
        val userMoved = !firstSettle
        firstSettle = false
        if (userMoved && casting && nowPlaying != null && nowPlaying?.gifId != gifs[page].id) {
            cast.jumpTo(feed, page)
        }
    }

    // Once this clip is playing, fetch the next few so swiping to them doesn't wait on the network.
    LaunchedEffect(pager.settledPage, gifs.size, casting) {
        if (casting || gifs.isEmpty()) return@LaunchedEffect
        withTimeoutOrNull(PRELOAD_WAIT_MS) {
            while (player.playbackState != Player.STATE_READY) delay(PRELOAD_POLL_MS)
        }
        viewModel.preloadAfter(gifs, pager.settledPage)
        gifs.drop(pager.settledPage + 1).take(PRELOAD_POSTERS).forEach { gif ->
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(gif.posterUrl).build())
        }
    }

    // Follow the receiver as its queue moves on, so the phone shows what's on the TV.
    LaunchedEffect(nowPlaying?.gifId) {
        val index = gifs.indexOfFirst { it.id == nowPlaying?.gifId }
        if (index >= 0 && index != pager.currentPage) pager.animateScrollToPage(index)
    }

    LaunchedEffect(casting, userPaused, lifecycleState, viewModel.sleeping) {
        player.playWhenReady = !casting && !userPaused && !viewModel.sleeping &&
            lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    }
    LaunchedEffect(autoscroll, exhausted) {
        player.repeatMode = when {
            !autoscroll -> Player.REPEAT_MODE_ONE
            exhausted -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        cast.setAutoscroll(autoscroll)
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val current = gifs.getOrNull(pager.currentPage)
    val goToNext: () -> Unit = {
        scope.launch {
            if (pager.currentPage + 1 < gifs.size) pager.animateScrollToPage(pager.currentPage + 1)
        }
    }
    val drop: () -> Unit = {
        val gif = sorting
        val target = targetAt(targets, finger.x, finger.y, reach)
        sorting = null
        if (gif != null && target != null) {
            sortHint = false
            viewModel.library.sortHintSeen = true
            if (target.isNew) savingGif = gif else viewModel.saveTo(target.label, gif)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { screen = it }) {
        VerticalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { gifs[it].id },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val gif = gifs[page]
            GifPage(
                gif = gif,
                player = player.takeIf { page == pager.settledPage && !casting },
                onTap = {
                    if (casting) cast.togglePlayback() else userPaused = !userPaused
                },
                onDoubleTap = {
                    if (signedIn && gif.id !in likedIds) viewModel.toggleLike(gif, feed, page)
                },
                onHold = { at ->
                    sorting = gif
                    finger = at
                },
                onHoldMove = { finger = it },
                onRelease = drop,
            )
        }

        val paused = if (casting) nowPlaying?.paused == true else userPaused || viewModel.sleeping
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::back) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    castProblem ?: deviceName?.let { "Playing on $it" }.orEmpty(),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                CastButton(viewModel, onDark = true)
            }
            if (viewModel.findingSimilar) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("finding-similar"))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sortHint) {
                    Text(
                        "Hold a clip to sort it into a list.",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(Color(0x99000000), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (paused) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Paused",
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.align(Alignment.Center).size(72.dp),
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (current != null && signedIn) {
                        val liked = current.id in likedIds
                        OverlayButton(
                            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (liked) "Unlike" else "Like",
                            tint = if (liked) MaterialTheme.colorScheme.primary else Color.White,
                        ) { viewModel.toggleLike(current, feed, pager.currentPage) }
                    }
                    if (!casting) {
                        OverlayButton(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            if (muted) "Unmute" else "Mute",
                        ) { muted = !muted }
                    }
                    if (casting) {
                        OverlayButton(
                            if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (paused) "Play" else "Pause",
                        ) { cast.togglePlayback() }
                    }
                    OverlayButton(
                        if (autoscroll) Icons.Default.SwipeUp else Icons.Default.Repeat,
                        if (autoscroll) "Autoscroll on, tap to loop this video" else "Looping, tap for autoscroll",
                    ) { viewModel.autoscroll = !autoscroll }
                    if (current != null) {
                        ClipMenu(
                            viewModel,
                            current,
                            goToNext,
                            onSave = { savingGif = current },
                            onTags = { tagsFor = current },
                        )
                    }
                }
            }
            if (current != null) {
                ClipDetails(viewModel, current, goToNext)
            }
            if (!casting) PlaybackProgress(player)
        }
    }

    sorting?.let { gif ->
        SortOverlay(gif, targets, finger, reach)
    }

    savingGif?.let { gif ->
        SaveDialog(viewModel, gif, onDismiss = { savingGif = null })
    }

    tagsFor?.let { gif ->
        TagsDialog(viewModel, gif, goToNext, onDismiss = { tagsFor = null })
    }
}

/** Hold-to-sort: collection bubbles around the screen, with the held clip under the finger. */
@Composable
private fun SortOverlay(gif: Gif, targets: List<SortTarget>, finger: Offset, reach: Float) {
    val over = targetAt(targets, finger.x, finger.y, reach)
    val density = LocalDensity.current
    val bubble = with(density) { SORT_BUBBLE.toPx() }
    val thumb = with(density) { SORT_THUMB.toPx() }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000))) {
        targets.forEach { target ->
            val active = target == over
            Box(
                Modifier
                    .offset { IntOffset((target.x - bubble / 2).roundToInt(), (target.y - bubble / 2).roundToInt()) }
                    .size(SORT_BUBBLE)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color(0xCC1B1B1F))
                    .border(2.dp, Color.White.copy(alpha = if (active) 1f else 0.4f), CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (target.isNew) "+ ${target.label}" else target.label,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        AsyncImage(
            model = gif.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset { IntOffset((finger.x - thumb / 2).roundToInt(), (finger.y - thumb * 0.9f).roundToInt()) }
                .size(SORT_THUMB)
                .clip(RoundedCornerShape(10.dp))
                .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
        )
        Text(
            over?.let { if (it.isNew) "Drop to make a new list" else "Drop into ${it.label}" } ?: "Drag onto a list",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
        )
    }
}

/** Creator button and tags, both of which open their own pages. Holding a tag offers more. */
@Composable
private fun ClipDetails(viewModel: MainViewModel, gif: Gif, goToNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier
                .background(Color(0x66000000), RoundedCornerShape(20.dp))
                .clickable { viewModel.openCreator(gif.userName) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(gif.userName, color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        if (gif.promoted) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Promoted",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(Color(0x66000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(gif.tags, key = { it }) { tag ->
                TagChip(viewModel, gif, tag, goToNext)
            }
        }
    }
}

@Composable
private fun TagChip(viewModel: MainViewModel, gif: Gif, tag: String, goToNext: () -> Unit) {
    val taste by viewModel.library.taste.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            tag,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .background(Color(0x66000000), RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClickLabel = "Open $tag",
                    onLongClickLabel = "More for $tag",
                    onLongClick = { open = true },
                    onClick = { viewModel.openTag(tag) },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Open $tag") }, onClick = {
                open = false
                viewModel.openTag(tag)
            })
            val pinned = tag in taste.pinnedTags
            DropdownMenuItem(text = { Text(if (pinned) "Unpin from My Mix" else "Pin to My Mix") }, onClick = {
                open = false
                viewModel.togglePinned(tag)
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Mute “${tag.lowercase()}” everywhere") }, onClick = {
                open = false
                viewModel.muteTag(tag)
                goToNext()
            })
            DropdownMenuItem(text = { Text("Hide the $tag tag") }, onClick = {
                open = false
                viewModel.hideTag(tag)
                goToNext()
            })
        }
    }
}

/** Every tag on a clip, each with a way to mute or hide it. */
@Composable
private fun TagsDialog(viewModel: MainViewModel, gif: Gif, goToNext: () -> Unit, onDismiss: () -> Unit) {
    val act: (() -> Unit) -> Unit = { action ->
        action()
        onDismiss()
        goToNext()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mute or hide a tag") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Muting a word hides any clip with it in a tag or description. Hiding a tag hides clips with that exact tag.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                gif.tags.forEach { tag ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tag, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { act { viewModel.muteTag(tag) } }) { Text("Mute") }
                        TextButton(onClick = { act { viewModel.hideTag(tag) } }) { Text("Hide") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ClipMenu(
    viewModel: MainViewModel,
    gif: Gif,
    goToNext: () -> Unit,
    onSave: () -> Unit,
    onTags: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OverlayButton(Icons.Default.MoreVert, "More options") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, action: () -> Unit): @Composable () -> Unit = {
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
            item("More like this") { viewModel.openSimilar(gif) }()
            item("Save to a list") { onSave() }()
            HorizontalDivider()
            item("Hide this video") {
                viewModel.hideGif(gif)
                goToNext()
            }()
            item("Hide ${gif.userName}") {
                viewModel.hideCreator(gif.userName)
                goToNext()
            }()
            if (gif.tags.isNotEmpty()) item("Mute or hide a tag") { onTags() }()
            HorizontalDivider()
            SLEEP_MINUTES.forEach { minutes ->
                item("Sleep in $minutes minutes") { viewModel.setSleepTimer(minutes) }()
            }
            item("Cancel sleep timer") { viewModel.setSleepTimer(null) }()
        }
    }
}

@Composable
private fun SaveDialog(viewModel: MainViewModel, gif: Gif, onDismiss: () -> Unit) {
    val lists by viewModel.library.lists.collectAsStateWithLifecycle()
    var newName by remember { mutableStateOf("") }
    val save: (String) -> Unit = { name ->
        viewModel.saveTo(name, gif)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to a list") },
        text = {
            Column {
                lists.keys.forEach { name ->
                    Text(
                        name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { save(name) }
                            .padding(vertical = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New list") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { save(newName.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun GifPage(
    gif: Gif,
    player: ExoPlayer?,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onHold: (Offset) -> Unit,
    onHoldMove: (Offset) -> Unit,
    onRelease: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val hold by rememberUpdatedState(onHold)
    val holdMove by rememberUpdatedState(onHoldMove)
    val release by rememberUpdatedState(onRelease)
    val haptics = LocalHapticFeedback.current

    // The one player hops between pages and briefly shows whatever it last drew, so the video
    // stays hidden behind this clip's poster until the player has drawn a frame of this clip.
    var showingThisClip by remember(player, gif.id) { mutableStateOf(false) }
    DisposableEffect(player, gif.id) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player?.currentMediaItem?.mediaId == gif.id) showingThisClip = true
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                if (item?.mediaId != gif.id) showingThisClip = false
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // A long press here only stops the hold from also counting as a tap.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap() }, onDoubleTap = { doubleTap() }, onLongPress = {})
            }
            // Holding still is sort; lifting or moving first (the pager's swipe) is not.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var endedEarly = false
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                        endedEarly = true
                    }
                    if (endedEarly) return@awaitEachGesture
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    hold(down.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        holdMove(change.position)
                    }
                    release()
                }
            },
    ) {
        // Sits behind the transparent shutter until the first video frame arrives.
        AsyncImage(
            model = gif.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (player != null) {
            AndroidView(
                factory = { context ->
                    LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().alpha(if (showingThisClip) 1f else 0f),
            )
        }
    }
}

@Composable
private fun OverlayButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.background(Color(0x66000000), MaterialTheme.shapes.extraLarge),
    ) {
        Icon(icon, label, tint = tint)
    }
}

@Composable
private fun PlaybackProgress(player: Player) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            progress = if (duration > 0) player.currentPosition.toFloat() / duration else 0f
            delay(PROGRESS_TICK_MS)
        }
    }
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

private fun mediaItem(gif: Gif, url: String): MediaItem =
    MediaItem.Builder().setUri(url).setMediaId(gif.id).build()

private const val LOAD_AHEAD = 6
private const val KEPT_FLASH_MS = 1_200L
private val SORT_BUBBLE = 92.dp
private val SORT_THUMB = 72.dp
private val SORT_REACH = 56.dp
private const val PROGRESS_TICK_MS = 200L
private const val SKIP_THRESHOLD_MS = 3_000L
private val SLEEP_MINUTES = listOf(15, 30, 60)
private const val MAX_FAILURES_IN_A_ROW = 5
private const val PRELOAD_WAIT_MS = 4_000L
private const val PRELOAD_POLL_MS = 200L
private const val PRELOAD_POSTERS = 3
