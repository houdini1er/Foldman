package dev.foldman.radio

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.foldman.deck.BackendState
import dev.foldman.deck.DeckBackend
import dev.foldman.deck.NowPlaying
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.RadioStation
import dev.foldman.deck.SourceStatus
import dev.foldman.playback.Artwork
import dev.foldman.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Web radio, played by the same [PlaybackService] as the local folder (so it keeps the media
 * notification, the VU meters and background play). The loaded station list is the "tape":
 * AMS skips to the next or previous station. A live stream can't be wound or seeked.
 *
 * The song title comes from the stream's ICY metadata when the station sends it; the station
 * name is put in the artist field so it never hides that title.
 */
class RadioBackend(private val context: Context) : DeckBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BackendState())
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val whenConnected = mutableListOf<(MediaController) -> Unit>()

    /** What PLAY tunes to when the player holds something else (the local tape, or nothing yet). */
    var fallback: () -> Pair<List<RadioStation>, Int> = { RadioBrowser.defaults to 0 }

    private var artworkFor: Uri? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            whenConnected.forEach { it(c) }
            whenConnected.clear()
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let(block) ?: run {
            whenConnected += block
            connect()
        }
    }

    /** Loads [stations] into the player and starts [index]. */
    fun playStations(stations: List<RadioStation>, index: Int) = withController { c ->
        if (stations.isEmpty()) return@withController
        c.setMediaItems(stations.map(::toMediaItem), index.coerceIn(0, stations.size - 1), 0L)
        c.prepare()
        c.play()
    }

    /** The station on air, or null when the player isn't on the radio. */
    fun currentStation(): RadioStation? {
        val c = controller ?: return null
        if (!holdsRadio(c)) return null
        val item = c.currentMediaItem ?: return null
        return RadioStation(
            name = item.mediaMetadata.station?.toString() ?: item.mediaMetadata.artist?.toString() ?: "Radio",
            url = item.mediaId.removePrefix(PREFIX),
            favicon = item.mediaMetadata.artworkUri?.toString(),
            info = item.mediaMetadata.genre?.toString(),
        )
    }

    private fun toMediaItem(s: RadioStation) = MediaItem.Builder()
        .setMediaId(PREFIX + s.url)
        .setUri(s.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setStation(s.name)
                .setArtist(s.name)
                .setGenre(s.info)
                .setArtworkUri(s.favicon?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    private fun publish() {
        val c = controller
        if (c == null || !holdsRadio(c)) {
            _state.value = BackendState(status = SourceStatus.Ready)
            return
        }
        val meta = c.mediaMetadata
        val item = c.currentMediaItem
        val stationName = item?.mediaMetadata?.station?.toString() ?: meta.station?.toString() ?: "Radio"
        // ICY "StreamTitle" lands in the title; the item itself never sets one.
        val song = meta.title?.toString()?.takeIf { it.isNotBlank() && it != stationName }
        updateArtwork(item?.mediaMetadata?.artworkUri)
        _state.value = BackendState(
            status = SourceStatus.Ready,
            nowPlaying = NowPlaying(
                title = song ?: stationName,
                artist = if (song != null) stationName else "Live",
                album = item?.mediaMetadata?.genre?.toString(),
                artwork = artwork,
                artworkKey = artworkFor.takeIf { artwork != null },
                durationMs = 0L,
            ),
            isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            ended = false,
            trackIndex = c.currentMediaItemIndex,
            trackCount = c.mediaItemCount,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
            queue = (0 until c.mediaItemCount).map { i ->
                val m = c.getMediaItemAt(i).mediaMetadata
                QueueEntry(i.toLong(), m.station?.toString() ?: "Radio", m.genre?.toString(), artworkUri = m.artworkUri)
            },
            currentQueueId = c.currentMediaItemIndex.toLong(),
        )
    }

    private fun updateArtwork(uri: Uri?) {
        if (uri == artworkFor && (artwork != null || artworkJob?.isActive == true)) return
        artworkJob?.cancel()
        artworkFor = uri
        artwork = null
        if (uri == null) return
        artworkJob = scope.launch {
            val bmp = Artwork.load(context, uri)
            if (artworkFor == uri && bmp != null) {
                artwork = bmp
                publish()
            }
        }
    }

    override fun positionMs(): Long = controller?.takeIf { holdsRadio(it) }?.currentPosition ?: 0L

    override fun play() = withController { c ->
        if (!holdsRadio(c)) {
            val (stations, index) = fallback()
            playStations(stations, index)
            return@withController
        }
        // A live stream resumes at the live edge, not where it was paused.
        if (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED) c.prepare()
        c.seekToDefaultPosition()
        c.play()
    }

    override fun pause() { controller?.takeIf { holdsRadio(it) }?.pause() }
    override fun seekTo(positionMs: Long) {}
    override fun skipToNext() { controller?.takeIf { holdsRadio(it) && it.hasNextMediaItem() }?.run { seekToNextMediaItem(); play() } }
    override fun skipToPrevious() { controller?.takeIf { holdsRadio(it) && it.hasPreviousMediaItem() }?.run { seekToPreviousMediaItem(); play() } }
    override fun playQueueItem(id: Long) = withController { c ->
        if (!holdsRadio(c) || id.toInt() !in 0 until c.mediaItemCount) return@withController
        c.seekToDefaultPosition(id.toInt())
        c.prepare()
        c.play()
    }

    override fun refresh() = publish()

    override fun release() {
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        scope.cancel()
    }

    companion object {
        /** Marks the player's items as radio stations, so the local tape and the radio tell each other apart. */
        const val PREFIX = "radio:"

        fun holdsRadio(c: Player): Boolean = c.mediaItemCount > 0 && c.getMediaItemAt(0).mediaId.startsWith(PREFIX)
    }
}
