package dev.foldman.deck

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import dev.foldman.BuildConfig
import dev.foldman.apple.AppleAuthExpired
import dev.foldman.apple.AppleKitBackend
import dev.foldman.apple.AppleMusicApi
import dev.foldman.apple.ApplePlaylist
import dev.foldman.apple.MusicKit
import dev.foldman.playback.LocalBackend
import dev.foldman.playback.LocalLibrary
import dev.foldman.remote.RemoteBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Cassette label colours, taken from the album art when there is some. */
data class LabelPalette(val paper: Color, val stripe: Color, val stripe2: Color, val ink: Color) {
    companion object {
        val Default = LabelPalette(Color(0xFFF2EBDC), Color(0xFFE8642C), Color(0xFFF2B33D), Color(0xFF2A2522))
    }
}

data class DeckUi(
    val source: Source = Source.LOCAL,
    val transport: Transport = Transport.STOPPED,
    val status: SourceStatus = SourceStatus.NeedsFolder,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: ImageBitmap? = null,
    val label: LabelPalette = LabelPalette.Default,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val trackIndex: Int = -1,
    val trackCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val tracks: List<LocalTrack> = emptyList(),
    val folderName: String? = null,
    val volume: Float = 0.5f,
    val queue: List<QueueEntry> = emptyList(),
    val currentQueueId: Long = -1,
    val keySounds: Boolean = true,
    val haptics: Boolean = true,
    val apple: AppleUi = AppleUi(),
) {
    /** How far through the tape we are: the left reel empties into the right one as this grows. */
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

sealed interface DeckEvent {
    /** The tape ran out while winding: the mechanism thunks back to stop. */
    data object TapeEnd : DeckEvent
    /** STOP pressed while already stopped: the cassette door pops. */
    data object Eject : DeckEvent
}

class DeckViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("foldman", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val library = LocalLibrary(app)
    private val local = LocalBackend(app).also { it.connect() }
    /** In-app Apple Music, when MusicKit's playback AAR is in the build; otherwise the Apple Music app is driven remotely. */
    private val appleKit = if (MusicKit.hasPlayback) AppleKitBackend(app) else null
    private val apple = MutableStateFlow(AppleUi(sdkAuth = MusicKit.hasAuth, sdkPlayback = appleKit != null))
    private var appleJob: Job? = null
    private val remotes = mapOf(
        Source.YT_MUSIC to RemoteBackend(app, Source.YT_MUSIC),
        Source.APPLE_MUSIC to RemoteBackend(app, Source.APPLE_MUSIC),
        Source.SPOTIFY to RemoteBackend(app, Source.SPOTIFY),
    )

    private val _ui = MutableStateFlow(DeckUi())
    val ui: StateFlow<DeckUi> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<DeckEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DeckEvent> = _events

    private var source = runCatching { Source.valueOf(prefs.getString(KEY_SOURCE, null) ?: "") }.getOrDefault(Source.LOCAL)
    private val backend: DeckBackend
        get() = when (source) {
            Source.LOCAL -> local
            Source.APPLE_MUSIC -> appleKit ?: remotes.getValue(source)
            else -> remotes.getValue(source)
        }

    private var tracks: List<LocalTrack> = emptyList()
    private var folderName: String? = null
    private var scanJob: Job? = null

    /** STOP was pressed (or the tape ended): show STOPPED rather than PAUSED while not playing. */
    private var stopped = true
    /** Play was just pressed; keep the play key down while the player catches up. */
    private var playRequestedAt = 0L
    /** -1 rewinding, +1 fast-forwarding, 0 neither. */
    private var winding = 0
    private var windPosition = 0L
    private var windLastTick = 0L
    private var windLastSeek = 0L
    private var windWaitingForTrack = -1

    private var paletteKey: Any? = null
    private var label = LabelPalette.Default
    private var artBitmap: ImageBitmap? = null
    private var artSource: Any? = null

    init {
        prefs.getString(KEY_FOLDER, null)?.let { loadFolder(Uri.parse(it), firstLoad = true) }
        syncAppleTokens()
        if (apple.value.signedIn) loadApplePlaylists()
        appleKit?.let { kit ->
            viewModelScope.launch {
                kit.messages.collect { m -> if (m != null) apple.value = apple.value.copy(message = m) }
            }
        }
        backend.refresh()
        viewModelScope.launch {
            while (true) {
                tick()
                delay(if (winding != 0) 33 else 100)
            }
        }
    }

    // region Keys

    fun play() {
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        backend.play()
        tick()
    }

    fun pauseKey() {
        when (_ui.value.transport) {
            Transport.PLAYING -> {
                stopped = false
                playRequestedAt = 0
                backend.pause()
            }
            Transport.PAUSED -> play()
            else -> return
        }
        tick()
    }

    fun stop() {
        when {
            winding != 0 -> finishWinding()
            _ui.value.transport == Transport.STOPPED -> _events.tryEmit(DeckEvent.Eject)
            else -> backend.pause()
        }
        stopped = true
        playRequestedAt = 0
        tick()
    }

    fun rewind() = startWinding(-1)
    fun fastForward() = startWinding(+1)

    /** AMS back: to the top of this song, or the previous one when already near its start. */
    fun previous() {
        if (winding != 0) finishWinding()
        if (backend.positionMs() > 3000 || !_ui.value.hasPrevious) backend.seekTo(0) else backend.skipToPrevious()
        tick()
    }

    fun next() {
        if (winding != 0) finishWinding()
        backend.skipToNext()
        tick()
    }

    private fun startWinding(direction: Int) {
        if (winding == direction) return
        if (_ui.value.status != SourceStatus.Ready) return
        if (winding == 0) {
            backend.pause()
            windPosition = backend.positionMs()
        }
        winding = direction
        windLastTick = SystemClock.elapsedRealtime()
        windWaitingForTrack = -1
        playRequestedAt = 0
        tick()
    }

    private fun finishWinding() {
        winding = 0
        windWaitingForTrack = -1
        backend.seekTo(windPosition)
    }

    private fun tapeEnd() {
        finishWinding()
        stopped = true
        _events.tryEmit(DeckEvent.TapeEnd)
    }

    /** Moves the tape at [WIND_SPEED]× while REW/FF is latched, crossing into neighbouring songs like a real tape. */
    private fun windStep(state: BackendState) {
        val now = SystemClock.elapsedRealtime()
        val dt = now - windLastTick
        windLastTick = now
        val duration = state.nowPlaying.durationMs
        if (windWaitingForTrack >= 0) {
            // Just rewound into the previous song: wait until we know how long it is, then start from its end.
            if (state.trackIndex != windWaitingForTrack || duration <= 0) return
            windWaitingForTrack = -1
            windPosition = duration
        }
        windPosition += winding * dt * WIND_SPEED
        val isLocal = source == Source.LOCAL
        when {
            windPosition <= 0 -> {
                windPosition = 0
                if (isLocal && state.hasPrevious) {
                    windWaitingForTrack = state.trackIndex - 1
                    backend.skipToPrevious()
                } else tapeEnd()
            }
            duration > 0 && windPosition >= duration -> {
                if (state.hasNext) {
                    windPosition = 0
                    backend.skipToNext()
                    if (isLocal) backend.pause() else tapeEnd()
                } else {
                    windPosition = duration
                    tapeEnd()
                }
            }
            // Other apps' sessions get fewer seeks; ours can take one per frame.
            isLocal || now - windLastSeek > 250 -> {
                backend.seekTo(windPosition)
                windLastSeek = now
            }
        }
    }

    // endregion

    // region Sources

    fun selectSource(newSource: Source) {
        if (newSource == source) return
        if (winding != 0) finishWinding()
        // One tape in the deck at a time: ejecting stops the old one.
        if (backend.state.value.isPlaying) backend.pause()
        source = newSource
        prefs.edit { putString(KEY_SOURCE, newSource.name) }
        stopped = !backend.state.value.isPlaying
        backend.refresh()
        tick()
    }

    fun setFolder(uri: Uri) {
        prefs.edit { putString(KEY_FOLDER, uri.toString()) }
        selectSource(Source.LOCAL)
        loadFolder(uri, firstLoad = false)
    }

    fun playTrack(index: Int) {
        selectSource(Source.LOCAL)
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        local.playTrack(index)
    }

    private fun loadFolder(uri: Uri, firstLoad: Boolean) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            local.setLibraryStatus(SourceStatus.Scanning(0))
            val found = runCatching {
                library.scan(uri) { n -> viewModelScope.launch { local.setLibraryStatus(SourceStatus.Scanning(n)) } }
            }.getOrElse {
                local.setLibraryStatus(SourceStatus.NeedsFolder)
                return@launch
            }
            folderName = found.firstOrNull()?.folder ?: uri.lastPathSegment?.substringAfterLast(':')
            tracks = found
            local.setTracks(found, startIndex = if (firstLoad) null else 0)
            if (!firstLoad) stopped = true
            tick()
            val tagged = library.readTags(found)
            tracks = tagged
            local.setTracks(tagged)
            tick()
        }
    }

    fun refresh() {
        backend.refresh()
        tick()
    }

    fun openAccessSettings() {
        val app = getApplication<Application>()
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    fun openSourceApp() {
        (remotes[source])?.openApp()
    }

    private var queueFor: List<LocalTrack>? = null
    private var queueCache: List<QueueEntry> = emptyList()

    private fun localQueue(): List<QueueEntry> {
        if (queueFor !== tracks) {
            queueFor = tracks
            queueCache = tracks.mapIndexed { i, t ->
                QueueEntry(i.toLong(), t.title, t.artist ?: t.folder, artworkUri = t.coverUri)
            }
        }
        return queueCache
    }

    fun playQueueItem(id: Long) {
        if (source == Source.LOCAL) return playTrack(id.toInt())
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        backend.playQueueItem(id)
    }

    fun setKeySounds(on: Boolean) {
        prefs.edit { putBoolean(KEY_SOUNDS, on) }
        tick()
    }

    fun setHaptics(on: Boolean) {
        prefs.edit { putBoolean(KEY_HAPTICS, on) }
        tick()
    }

    // region Apple Music

    private val developerToken: String?
        get() = prefs.getString(KEY_APPLE_DEV, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.APPLE_DEVELOPER_TOKEN.takeIf { it.isNotBlank() }
    private val musicUserToken: String? get() = prefs.getString(KEY_APPLE_USER, null)

    private fun syncAppleTokens() {
        appleKit?.setTokens(developerToken, musicUserToken)
        apple.value = apple.value.copy(hasDeveloperToken = developerToken != null, signedIn = musicUserToken != null)
    }

    fun setAppleDeveloperToken(token: String) {
        prefs.edit { putString(KEY_APPLE_DEV, token.trim()) }
        syncAppleTokens()
        tick()
    }

    /** Apple's sign-in screen, or null when it can't be shown (no SDK or no developer token). */
    fun appleSignInIntent(): Intent? {
        val dev = developerToken ?: return null
        return MusicKit.signInIntent(getApplication(), dev).also {
            if (it == null) apple.value = apple.value.copy(message = "MusicKit couldn't start sign-in.")
        }
    }

    fun onAppleSignInResult(data: Intent?) {
        MusicKit.readSignInResult(getApplication(), data)
            .onSuccess { token ->
                prefs.edit { putString(KEY_APPLE_USER, token) }
                apple.value = apple.value.copy(message = null)
                syncAppleTokens()
                loadApplePlaylists()
            }
            .onFailure { e -> apple.value = apple.value.copy(message = "Sign-in didn't finish (${e.message}).") }
        tick()
    }

    fun signOutOfApple() {
        prefs.edit { remove(KEY_APPLE_USER) }
        apple.value = apple.value.copy(playlists = emptyList(), openPlaylist = null, tracks = emptyList())
        syncAppleTokens()
        tick()
    }

    fun loadApplePlaylists() = appleCall { api ->
        apple.value = apple.value.copy(playlists = api.playlists())
    }

    fun openApplePlaylist(playlist: ApplePlaylist) {
        apple.value = apple.value.copy(openPlaylist = playlist, tracks = emptyList())
        appleCall { api ->
            val tracks = api.tracks(playlist.id)
            if (apple.value.openPlaylist?.id == playlist.id) apple.value = apple.value.copy(tracks = tracks)
        }
    }

    fun closeApplePlaylist() {
        apple.value = apple.value.copy(openPlaylist = null, tracks = emptyList())
        tick()
    }

    /** Plays the open playlist from [trackIndex]: in-app with MusicKit, otherwise handed to the Apple Music app. */
    fun playApplePlaylist(trackIndex: Int = 0) {
        val ui = apple.value
        val playlist = ui.openPlaylist ?: return
        selectSource(Source.APPLE_MUSIC)
        val kit = appleKit
        if (kit == null) {
            openInAppleMusic(playlist)
            return
        }
        // Uploaded songs have no catalog id and can't be streamed; skip them, keeping the start song.
        val playable = ui.tracks.withIndex().filter { it.value.catalogId != null }
        if (playable.isEmpty()) {
            apple.value = ui.copy(message = "Nothing in this playlist can be streamed.")
            return
        }
        val start = playable.indexOfFirst { it.index >= trackIndex }.coerceAtLeast(0)
        if (winding != 0) finishWinding()
        stopped = false
        playRequestedAt = SystemClock.elapsedRealtime()
        kit.messages.value = null
        apple.value = apple.value.copy(message = null)
        kit.playSongs(playable.map { it.value.catalogId!! }, start)
        tick()
    }

    private fun openInAppleMusic(playlist: ApplePlaylist) {
        val url = playlist.globalId?.let { "https://music.apple.com/playlist/$it" }
            ?: "https://music.apple.com/library/playlist/${playlist.id}"
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        val app = getApplication<Application>()
        runCatching { app.startActivity(Intent(base).setPackage(Source.APPLE_MUSIC.packageName)) }
            .recoverCatching { app.startActivity(base) }
    }

    private fun appleCall(block: suspend (AppleMusicApi) -> Unit) {
        val dev = developerToken ?: return
        val user = musicUserToken ?: return
        appleJob?.cancel()
        appleJob = viewModelScope.launch {
            apple.value = apple.value.copy(loading = true, message = null)
            try {
                block(AppleMusicApi(dev, user))
            } catch (e: AppleAuthExpired) {
                prefs.edit { remove(KEY_APPLE_USER) }
                syncAppleTokens()
                apple.value = apple.value.copy(message = "Apple Music sign-in expired or the developer token was rejected. Sign in again.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                apple.value = apple.value.copy(message = e.message ?: "Couldn't reach Apple Music.")
            } finally {
                apple.value = apple.value.copy(loading = false)
                tick()
            }
        }
    }

    // endregion

    fun setVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        if (target != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        _ui.value = _ui.value.copy(volume = fraction.coerceIn(0f, 1f))
    }

    // endregion

    private fun tick() {
        val state = backend.state.value
        val now = SystemClock.elapsedRealtime()
        if (winding != 0) windStep(state)
        if (state.ended) stopped = true
        if (state.isPlaying && winding == 0) {
            stopped = false
            playRequestedAt = 0
        }
        val playPending = now - playRequestedAt < 1500
        val transport = when {
            winding < 0 -> Transport.REWINDING
            winding > 0 -> Transport.FAST_FORWARDING
            state.isPlaying || playPending -> Transport.PLAYING
            stopped -> Transport.STOPPED
            else -> Transport.PAUSED
        }
        val np = state.nowPlaying
        updateLabel(np)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        val prev = _ui.value
        _ui.value = DeckUi(
            source = source,
            transport = transport,
            status = state.status,
            title = np.title.orEmpty(),
            artist = np.artist.orEmpty(),
            album = np.album.orEmpty(),
            artwork = artBitmap,
            label = label,
            positionMs = if (winding != 0) windPosition else backend.positionMs(),
            durationMs = np.durationMs,
            trackIndex = state.trackIndex,
            trackCount = state.trackCount,
            hasNext = state.hasNext,
            hasPrevious = state.hasPrevious,
            tracks = tracks,
            folderName = folderName,
            // While the wheel is being turned our own value leads; otherwise follow the hardware keys.
            volume = if (kotlin.math.abs(prev.volume - volume) * max < 1f) prev.volume else volume,
            queue = if (source == Source.LOCAL) localQueue() else state.queue,
            currentQueueId = if (source == Source.LOCAL) state.trackIndex.toLong() else state.currentQueueId,
            keySounds = prefs.getBoolean(KEY_SOUNDS, true),
            haptics = prefs.getBoolean(KEY_HAPTICS, true),
            apple = apple.value,
        )
    }

    private fun updateLabel(np: NowPlaying) {
        val bitmap = np.artwork
        if (bitmap == null) {
            artBitmap = null
            artSource = null
            paletteKey = null
            label = LabelPalette.Default
            return
        }
        if (artSource !== bitmap) {
            artSource = bitmap
            artBitmap = bitmap.asImageBitmap()
        }
        val key = np.artworkKey ?: bitmap
        if (key == paletteKey) return
        paletteKey = key
        viewModelScope.launch {
            val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).maximumColorCount(16).generate() }
            if (paletteKey != key) return@launch
            val d = LabelPalette.Default
            label = LabelPalette(
                paper = palette.lightMutedSwatch?.rgb?.let { blend(Color(it), d.paper, 0.75f) } ?: d.paper,
                stripe = (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.let(::Color) ?: d.stripe,
                stripe2 = (palette.lightVibrantSwatch ?: palette.mutedSwatch)?.rgb?.let(::Color) ?: d.stripe2,
                ink = palette.darkMutedSwatch?.rgb?.let { blend(Color(it), d.ink, 0.5f) } ?: d.ink,
            )
            tick()
        }
    }

    override fun onCleared() {
        local.release()
        remotes.values.forEach { it.release() }
        appleKit?.release()
    }

    companion object {
        private const val KEY_SOURCE = "source"
        private const val KEY_FOLDER = "folder"
        private const val KEY_SOUNDS = "key_sounds"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_APPLE_DEV = "apple_developer_token"
        private const val KEY_APPLE_USER = "apple_music_user_token"
        /** Winding speed relative to play: a four-minute song winds through in about 20 s. */
        const val WIND_SPEED = 12L

        private fun blend(a: Color, b: Color, t: Float) = Color(
            red = a.red * (1 - t) + b.red * t,
            green = a.green * (1 - t) + b.green * t,
            blue = a.blue * (1 - t) + b.blue * t,
        )
    }
}
