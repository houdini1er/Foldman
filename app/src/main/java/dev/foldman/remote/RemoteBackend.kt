package dev.foldman.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import dev.foldman.deck.BackendState
import dev.foldman.deck.DeckBackend
import dev.foldman.deck.NowPlaying
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.Source
import dev.foldman.deck.SourceStatus
import dev.foldman.playback.Artwork
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
 * Plays YT Music, Apple Music or Spotify by driving that app's own media session: Foldman is the deck,
 * the streaming app is the tape. Needs notification access ([MediaListenerService]) to see the session.
 */
class RemoteBackend(private val context: Context, source: Source) : DeckBackend {
    private val pkg = requireNotNull(source.packageName)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    private val _state = MutableStateFlow(BackendState(status = SourceStatus.AppNotRunning))
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var watchingSessions = false
    private var artworkKey: String? = null
    private var artwork: Bitmap? = null
    private var artworkJob: Job? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> bind(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = publish()
        override fun onSessionDestroyed() {
            unbind()
            publish()
        }
    }

    val hasAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    val isInstalled: Boolean
        get() = context.packageManager.getLaunchIntentForPackage(pkg) != null

    override fun refresh() {
        if (!hasAccess) {
            unbind()
            publish()
            return
        }
        try {
            if (!watchingSessions) {
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, handler)
                watchingSessions = true
            }
            bind(sessions.getActiveSessions(listenerComponent))
        } catch (_: SecurityException) {
            publish()
        }
    }

    private fun bind(active: List<MediaController>) {
        val match = active.firstOrNull { it.packageName == pkg }
        if (match?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        unbind()
        controller = match?.also { it.registerCallback(callback, handler) }
        publish()
    }

    private fun unbind() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = BackendState(
                status = when {
                    !hasAccess -> SourceStatus.NeedsAccess
                    !isInstalled -> SourceStatus.NotInstalled
                    else -> SourceStatus.AppNotRunning
                },
            )
            return
        }
        val meta = c.metadata
        val pb = c.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        updateArtwork(meta, "$title|$artist|$album")
        val actions = pb?.actions ?: 0L
        _state.value = BackendState(
            status = SourceStatus.Ready,
            nowPlaying = NowPlaying(
                title = title,
                artist = artist,
                album = album,
                artwork = artwork,
                artworkKey = artworkKey.takeIf { artwork != null },
                durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0L,
            ),
            isPlaying = pb?.state == PlaybackState.STATE_PLAYING || pb?.state == PlaybackState.STATE_BUFFERING,
            ended = false,
            hasNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            hasPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            queue = c.queue.orEmpty().take(200).map { q ->
                QueueEntry(
                    id = q.queueId,
                    title = q.description.title?.toString() ?: "",
                    subtitle = q.description.subtitle?.toString(),
                    artwork = q.description.iconBitmap,
                    artworkUri = q.description.iconUri,
                )
            },
            currentQueueId = pb?.activeQueueItemId ?: -1,
        )
    }

    private fun updateArtwork(meta: MediaMetadata?, key: String) {
        if (key == artworkKey && (artwork != null || artworkJob?.isActive == true)) return
        if (key != artworkKey) artwork = null
        artworkKey = key
        val bitmap = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bitmap != null) {
            artwork = bitmap
            return
        }
        val uri = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { meta?.getString(it)?.takeIf(String::isNotBlank) } ?: return
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val bmp = Artwork.load(context, Uri.parse(uri))
            if (artworkKey == key && bmp != null) {
                artwork = bmp
                publish()
            }
        }
    }

    override fun positionMs(): Long {
        val pb = controller?.playbackState ?: return 0L
        if (pb.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return 0L
        if (pb.state != PlaybackState.STATE_PLAYING) return pb.position
        val elapsed = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
        return pb.position + (elapsed * pb.playbackSpeed).toLong()
    }

    override fun play() {
        val c = controller
        if (c != null) {
            c.transportControls.play()
            return
        }
        // No session yet: ask the app to resume its last queue, then open it next to us if it doesn't.
        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY)
        handler.postDelayed({ if (controller == null) openApp() }, 2500)
    }

    override fun pause() { controller?.transportControls?.pause() }
    override fun seekTo(positionMs: Long) { controller?.transportControls?.seekTo(positionMs) }
    override fun playQueueItem(id: Long) { controller?.transportControls?.skipToQueueItem(id) }
    override fun skipToNext() { controller?.transportControls?.skipToNext() }
    override fun skipToPrevious() { controller?.transportControls?.skipToPrevious() }

    /** Opens the streaming app, beside Foldman when the screen is unfolded. */
    fun openApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching { context.startActivity(launch) }
    }

    private fun sendMediaButton(code: Int) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setPackage(pkg)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
            runCatching { context.sendBroadcast(intent) }
        }
    }

    override fun release() {
        if (watchingSessions) sessions.removeOnActiveSessionsChangedListener(sessionsChanged)
        unbind()
        scope.cancel()
    }
}
