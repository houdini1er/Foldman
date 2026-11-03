package dev.foldman.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dev.foldman.deck.BackendState
import dev.foldman.deck.DeckBackend
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.NowPlaying
import dev.foldman.deck.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The local folder, played by [PlaybackService] and driven through a Media3 controller. */
class LocalBackend(private val context: Context) : DeckBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BackendState(status = SourceStatus.NeedsFolder))
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var whenConnected = mutableListOf<(MediaController) -> Unit>()

    private var tracks: List<LocalTrack> = emptyList()
    /** Library status while there is nothing to play yet (no folder, scanning, empty). */
    private var libraryStatus: SourceStatus = SourceStatus.NeedsFolder

    private var artworkFor: Any? = null
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

    fun setLibraryStatus(status: SourceStatus) {
        libraryStatus = status
        publish()
    }

    /**
     * Loads [newTracks] as the tape. A queue the service is already playing (the app was reopened
     * mid-song) is kept as it is, so playback doesn't jump.
     */
    fun setTracks(newTracks: List<LocalTrack>, startIndex: Int? = null) {
        tracks = newTracks
        libraryStatus = if (newTracks.isEmpty()) SourceStatus.EmptyFolder else SourceStatus.Ready
        withController { c ->
            val sameQueue = c.mediaItemCount == newTracks.size &&
                newTracks.isNotEmpty() &&
                c.getMediaItemAt(0).mediaId == newTracks[0].uri.toString()
            if (!sameQueue || startIndex != null) {
                c.setMediaItems(newTracks.map(::toMediaItem), startIndex ?: 0, 0L)
                c.prepare()
            } else {
                // Refresh titles from tags without disturbing playback. Two bulk replacements around
                // the current song: one replaceMediaItem per song rebuilt the whole timeline once per
                // song (thousands of times on a big library), keeping PLAY waiting behind them.
                val cur = c.currentMediaItemIndex.coerceIn(0, newTracks.size - 1)
                if (cur > 0) {
                    c.replaceMediaItems(0, cur, newTracks.subList(0, cur).map(::toMediaItem))
                }
                if (cur < newTracks.size - 1) {
                    c.replaceMediaItems(cur + 1, newTracks.size, newTracks.subList(cur + 1, newTracks.size).map(::toMediaItem))
                }
            }
            publish()
        }
        publish()
    }

    fun playTrack(index: Int) = withController { c ->
        if (index !in 0 until c.mediaItemCount) return@withController
        c.seekTo(index, 0L)
        c.prepare()
        c.play()
    }

    private fun toMediaItem(t: LocalTrack) = MediaItem.Builder()
        .setMediaId(t.uri.toString())
        .setUri(t.uri)
        .setMediaMetadata(
            // Title/artist come from the tags (read by LocalLibrary) or the file name, so the system
            // media controls always have something; the folder cover backs up embedded art.
            MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setArtworkUri(t.coverUri).setAlbumTitle(t.folder).build(),
        )
        .build()

    private fun publish() {
        val c = controller
        if (c == null || c.mediaItemCount == 0) {
            _state.value = BackendState(status = libraryStatus)
            return
        }
        val index = c.currentMediaItemIndex
        val track = tracks.getOrNull(index)?.takeIf { it.uri.toString() == c.currentMediaItem?.mediaId }
        val meta = c.mediaMetadata
        updateArtwork(meta)
        _state.value = BackendState(
            status = SourceStatus.Ready,
            nowPlaying = NowPlaying(
                title = meta.title?.toString() ?: track?.title ?: c.currentMediaItem?.mediaId?.substringAfterLast("%2F"),
                artist = meta.artist?.toString() ?: meta.albumArtist?.toString() ?: track?.artist,
                album = meta.albumTitle?.toString() ?: track?.folder,
                artwork = artwork,
                artworkKey = artworkFor.takeIf { artwork != null },
                durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
            ),
            isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            ended = c.playbackState == Player.STATE_ENDED,
            trackIndex = index,
            trackCount = c.mediaItemCount,
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    private fun updateArtwork(meta: MediaMetadata) {
        // Keyed by the image itself, not the song: right after a track change the controller can
        // still report the previous song's metadata for a moment, and keying by song would keep
        // that stale cover for the new one.
        val data = meta.artworkData
        val uri = meta.artworkUri
        val key: Any? = data?.contentHashCode() ?: uri
        if (key == artworkFor && (artwork != null || artworkJob?.isActive == true)) return
        artworkJob?.cancel()
        artworkFor = key
        artwork = null
        if (key == null) return
        artworkJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) { data?.let(Artwork::decode) } ?: uri?.let { Artwork.load(context, it) }
            if (artworkFor == key && bmp != null) {
                artwork = bmp
                publish()
            }
        }
    }

    override fun positionMs(): Long = controller?.currentPosition ?: 0L
    override fun play() = withController { c ->
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }
    override fun pause() { controller?.pause() }
    override fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    override fun playQueueItem(id: Long) = playTrack(id.toInt())
    override fun skipToNext() { controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }
    override fun skipToPrevious() { controller?.takeIf { it.hasPreviousMediaItem() }?.seekToPreviousMediaItem() }

    override fun release() {
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
        scope.cancel()
    }
}
