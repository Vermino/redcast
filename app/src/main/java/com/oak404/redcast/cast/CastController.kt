package com.oak404.redcast.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.oak404.redcast.data.Feed
import com.oak404.redcast.data.Gif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress

/** What the receiver is showing right now. */
data class NowPlaying(val gifId: String, val paused: Boolean)

class CastController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relay: CastRelay,
) {

    private val castContext = runCatching { CastContext.getSharedInstance(context) }.getOrNull()

    /** False without Google Play services, which the Cast SDK depends on. */
    val available: Boolean get() = castContext != null

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Why casting stopped, shown to the user until the next attempt. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private val _sessionStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires for a newly connected device, not when rejoining one that's already playing. */
    val sessionStarted: SharedFlow<Unit> = _sessionStarted.asSharedFlow()

    private var client: RemoteMediaClient? = null
    private var feed: Feed? = null
    private var repeatSingle = false

    // Tags this app's queue so status from an older queue, or another sender's, is ignored.
    private var generation = 0L
    private var nextSeq = 0
    private var lastSeq = -1
    private var handledIdleSeq = -1
    private var failedInARow = 0
    private var topUpJob: Job? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onStatus()
        override fun onQueueStatusUpdated() = onStatus()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attach(session)
            _sessionStarted.tryEmit(Unit)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionStartFailed(session: CastSession, error: Int) = detach()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    init {
        castContext?.sessionManager?.let { manager ->
            manager.addSessionManagerListener(sessionListener, CastSession::class.java)
            manager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
        }
    }

    /** Replaces whatever is on the receiver with [source], starting at [index]. */
    fun play(source: Feed, index: Int) {
        if (client == null) return
        startQueue(source, index)
    }

    /** Moves to [index], staying inside the current queue when it already holds that item. */
    fun jumpTo(source: Feed, index: Int) {
        val client = client ?: return
        val queued = client.mediaStatus?.takeIf { source === feed }?.queueItems?.firstOrNull {
            seqOf(it) == index
        }
        if (queued != null) client.queueJumpToItem(queued.itemId, null) else play(source, index)
    }

    /** Disconnects from the receiver, which stops playback on the TV. */
    fun endSession() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun togglePlayback() {
        client?.togglePlayback()
    }

    /** With autoscroll off the receiver loops the current clip, matching the phone. */
    fun setAutoscroll(enabled: Boolean) {
        repeatSingle = !enabled
        if (client?.mediaStatus != null) client?.queueSetRepeatMode(repeatMode(), null)
    }

    private fun repeatMode() =
        if (repeatSingle) MediaStatus.REPEAT_MODE_REPEAT_SINGLE else MediaStatus.REPEAT_MODE_REPEAT_OFF

    private fun attach(session: CastSession) {
        client?.unregisterCallback(mediaCallback)
        client = session.remoteMediaClient
        client?.registerCallback(mediaCallback)
        val device = session.castDevice?.friendlyName ?: "TV"
        _deviceName.value = device
        networkAddress()?.let { address ->
            runCatching { relay.start(address) }.onFailure { Log.w(TAG, "Relay failed to start", it) }
        }
        CastKeepAliveService.start(context, device)
        onStatus()
    }

    private fun detach() {
        client?.unregisterCallback(mediaCallback)
        client = null
        feed = null
        topUpJob?.cancel()
        _deviceName.value = null
        _nowPlaying.value = null
        relay.stop()
        CastKeepAliveService.stop(context)
    }

    private fun startQueue(source: Feed, seq: Int) {
        val queueGeneration = System.currentTimeMillis()
        generation = queueGeneration
        feed = source
        handledIdleSeq = -1
        failedInARow = 0
        _problem.value = null
        topUpJob?.cancel()
        scope.launch {
            source.ensureLoaded(seq + CastQueuePlan.BATCH_SIZE)
            val items = queueItems(source, seq)
            if (items.isEmpty() || generation != queueGeneration) return@launch
            nextSeq = seq + items.size
            lastSeq = seq
            client?.queueLoad(items.toTypedArray(), 0, repeatMode(), 0L, null)
        }
    }

    private fun onStatus() {
        val client = client ?: return
        val status = client.mediaStatus ?: return
        val current = status.getQueueItemById(status.currentItemId)
        val data = current?.customData

        if (data != null) {
            _nowPlaying.value = NowPlaying(
                data.optString(KEY_GIF_ID),
                status.playerState == MediaStatus.PLAYER_STATE_PAUSED,
            )
        }
        if (status.playerState == MediaStatus.PLAYER_STATE_PLAYING) failedInARow = 0
        val source = feed ?: return

        if (data != null && data.optLong(KEY_GENERATION) == generation) {
            lastSeq = data.optInt(KEY_SEQ)
            trimPlayed(client, status, lastSeq)
            if (CastQueuePlan.needsMore(lastSeq, nextSeq)) topUp(client, source)
        }

        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE) onIdle(client, status, source, current)
    }

    /** The receiver stops on a broken clip or an empty queue; keep the show going. */
    private fun onIdle(
        client: RemoteMediaClient,
        status: MediaStatus,
        source: Feed,
        current: MediaQueueItem?,
    ) {
        val reason = status.idleReason
        if (reason != MediaStatus.IDLE_REASON_ERROR && reason != MediaStatus.IDLE_REASON_FINISHED) return
        val queueEnded = current == null
        val clipFailed = current != null && reason == MediaStatus.IDLE_REASON_ERROR
        if ((!queueEnded && !clipFailed) || handledIdleSeq == lastSeq) return

        // Status repeats while idle, so act once per clip.
        handledIdleSeq = lastSeq
        if (clipFailed) {
            Log.w(TAG, "Receiver failed clip $lastSeq")
            failedInARow++
            if (failedInARow >= MAX_FAILURES_IN_A_ROW) {
                // Something is wrong with every clip, so stop rather than race through the feed.
                feed = null
                _problem.value = "The TV couldn't play these videos"
                return
            }
        }
        if (queueEnded) startQueue(source, lastSeq + 1) else client.queueNext(null)
    }

    private fun topUp(client: RemoteMediaClient, source: Feed) {
        if (topUpJob?.isActive == true) return
        val queueGeneration = generation
        topUpJob = scope.launch {
            val from = nextSeq
            source.ensureLoaded(from + CastQueuePlan.BATCH_SIZE)
            val items = queueItems(source, from)
            if (items.isEmpty() || generation != queueGeneration) return@launch
            nextSeq = from + items.size
            client.queueInsertItems(items.toTypedArray(), MediaQueueItem.INVALID_ITEM_ID, null)
        }
    }

    private fun trimPlayed(client: RemoteMediaClient, status: MediaStatus, currentSeq: Int) {
        val queued = status.queueItems.filter { it.customData?.optLong(KEY_GENERATION) == generation }
        val stale = CastQueuePlan.staleSeqs(queued.map(::seqOf), currentSeq).toSet()
        if (stale.isEmpty()) return
        val ids = queued.filter { seqOf(it) in stale }.map { it.itemId }
        client.queueRemoveItems(ids.toIntArray(), null)
    }

    private fun queueItems(source: Feed, fromSeq: Int): List<MediaQueueItem> {
        val gifs = source.items.value
        val indices = CastQueuePlan.nextIndices(fromSeq, gifs.size, source.exhausted.value)
        return indices.mapIndexed { offset, index -> queueItem(gifs[index], fromSeq + offset) }
    }

    private fun queueItem(gif: Gif, seq: Int): MediaQueueItem {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, gif.userName)
            if (gif.posterUrl.isNotEmpty()) addImage(WebImage(Uri.parse(relayed(gif.posterUrl))))
        }
        val info = MediaInfo.Builder(relayed(gif.hdUrl))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("video/mp4")
            .setMetadata(metadata)
            .build()
        val custom = JSONObject()
            .put(KEY_GIF_ID, gif.id)
            .put(KEY_SEQ, seq)
            .put(KEY_GENERATION, generation)
        return MediaQueueItem.Builder(info)
            .setAutoplay(true)
            .setPreloadTime(PRELOAD_SECONDS)
            .setCustomData(custom)
            .build()
    }

    private fun relayed(url: String) = relay.relayUrl(url) ?: url

    /** This phone's address on its local network, which is where the receiver has to reach it. */
    private fun networkAddress(): InetAddress? {
        val network = context.getSystemService(ConnectivityManager::class.java)
        val links = network.getLinkProperties(network.activeNetwork) ?: return null
        return links.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }

    private fun seqOf(item: MediaQueueItem) = item.customData?.optInt(KEY_SEQ, -1) ?: -1

    private companion object {
        const val KEY_GIF_ID = "gifId"
        const val KEY_SEQ = "seq"
        const val KEY_GENERATION = "generation"
        const val PRELOAD_SECONDS = 8.0
        const val MAX_FAILURES_IN_A_ROW = 3
        const val TAG = "RedcastCast"
    }
}
