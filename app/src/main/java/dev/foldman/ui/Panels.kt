package dev.foldman.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.LocalTrack
import dev.foldman.deck.QueueEntry
import dev.foldman.deck.Source
import dev.foldman.deck.SourceStatus
import dev.foldman.playback.Artwork

private val Title = TextStyle(color = Color(0xFFF4EFE9), fontSize = 15.sp, fontWeight = FontWeight.Medium)
private val Sub = TextStyle(color = Color(0xFFA79C92), fontSize = 12.sp)
private val Header = TextStyle(color = Color(0xFFF4EFE9), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)

/** Smoked dark glass the library and now-playing sit behind on the unfolded screen. */
fun Modifier.glassPanel(corner: Dp = 18.dp): Modifier = drawWithCache {
    val r = CornerRadius(corner.toPx())
    val fill = Brush.verticalGradient(listOf(Color(0xFF2E2622), Color(0xFF1E1916)))
    onDrawBehind {
        drawRoundRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, 3f), cornerRadius = r)
        drawRoundRect(fill, cornerRadius = r)
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = r, style = Stroke(1.5f))
    }
}

/** Library band: sources, then albums (folders) or the streaming app's state, then the queue. */
@Composable
fun LibraryBand(ui: DeckUi, actions: ShelfActions, onPlayQueue: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.glassPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Scrolls if the panel is ever too short for every source.
        Column(Modifier.weight(0.3f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text("Sources", style = Header)
            Spacer(Modifier.height(6.dp))
            Source.entries.forEach { s ->
                val selected = s == ui.source
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.09f) else Color.Transparent)
                        .clickable { actions.selectSource(s) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceTile(s, Modifier.size(30.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.label, style = Title, maxLines = 1)
                        Text(if (selected) statusLine(ui) else "Tap to load", style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(Modifier.weight(0.36f).fillMaxHeight()) {
            when (ui.source) {
                Source.LOCAL -> Albums(ui, actions)
                Source.APPLE_MUSIC -> {
                    Text(ui.apple.openPlaylist?.let { "Playlist" } ?: "Playlists", style = Header)
                    Spacer(Modifier.height(8.dp))
                    AppleBrowser(ui.apple, actions.apple, fallback = { RemoteState(ui, actions, showHeader = false) })
                }
                else -> RemoteState(ui, actions)
            }
        }
        Column(Modifier.weight(0.34f).fillMaxHeight()) {
            Text("Queue", style = Header)
            Spacer(Modifier.height(8.dp))
            QueueList(ui, onPlayQueue)
        }
    }
}

private fun statusLine(ui: DeckUi): String = when (val s = ui.status) {
    SourceStatus.Ready -> if (ui.source == Source.LOCAL) "${ui.tracks.size} songs" else "Connected"
    SourceStatus.NeedsFolder -> "Choose a folder"
    is SourceStatus.Scanning -> "Scanning… ${s.found}"
    SourceStatus.EmptyFolder -> "No music found"
    SourceStatus.NeedsAccess -> "Needs notification access"
    SourceStatus.AppNotRunning -> "Not playing"
    SourceStatus.NotInstalled -> "Not installed"
    SourceStatus.NeedsDeveloperToken -> "Needs developer token"
    SourceStatus.NeedsAppleSignIn -> "Sign in to browse"
}

@Composable
private fun SourceTile(source: Source, modifier: Modifier) {
    val shell = DeckColors.shell(source)
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Brush.linearGradient(listOf(androidx.compose.ui.graphics.lerp(shell, Color.White, 0.25f), shell))),
        contentAlignment = Alignment.Center,
    ) {
        // A tiny cassette window.
        Box(Modifier.fillMaxWidth(0.66f).height(9.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF1A1714)))
    }
}

@Composable
private fun Albums(ui: DeckUi, actions: ShelfActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Albums", style = Header, modifier = Modifier.weight(1f))
        Text(
            if (ui.status == SourceStatus.NeedsFolder) "Choose folder" else "Change folder",
            style = Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clickable(onClick = actions.pickFolder).padding(4.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    when (val s = ui.status) {
        SourceStatus.NeedsFolder -> Text("Pick a folder of music on this phone. Covers come from the files or a cover.jpg beside them.", style = Sub)
        is SourceStatus.Scanning -> Text("Scanning… ${s.found} songs", style = Sub)
        SourceStatus.EmptyFolder -> Text("No music in that folder.", style = Sub)
        else -> {
            // Each folder is an album: its first track's index, cover and size.
            val albums = remember(ui.tracks) {
                ui.tracks.withIndex().groupBy { it.value.folder }.map { (folder, items) ->
                    Triple(folder, items.first(), items.size)
                }
            }
            LazyColumn {
                items(albums, key = { it.first }) { (folder, first, count) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { actions.playTrack(first.index) }
                            .padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumb(null, first.value.coverUri, Modifier.size(38.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder, style = Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${first.value.artist ?: "Album"} · $count songs", style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteState(ui: DeckUi, actions: ShelfActions, showHeader: Boolean = true) {
    val app = ui.source.label
    if (showHeader) {
        Text(app, style = Header)
        Spacer(Modifier.height(8.dp))
    }
    val (text, button, action) = when (ui.status) {
        SourceStatus.NeedsAccess -> Triple(
            "Foldman plays $app through its media session, which Android only shares with apps that have notification access. If the switch is greyed out: App info → ⋮ → Allow restricted settings.",
            "Grant access", actions.openAccess,
        )
        SourceStatus.NotInstalled -> Triple("$app isn't installed.", "Get $app", { actions.installApp(ui.source) })
        SourceStatus.AppNotRunning -> Triple("Press PLAY to resume your last $app queue, or open it and pick something.", "Open $app", actions.openApp)
        else -> Triple("Choose playlists and albums in $app; the deck follows along with art, queue and transport.", "Open $app", actions.openApp)
    }
    Text(text, style = Sub.copy(lineHeight = 16.sp))
    Spacer(Modifier.height(10.dp))
    Text(
        button.uppercase(),
        style = Sub.copy(color = Color(0xFF1E1916), fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(DeckColors.Accent)
            .clickable(onClick = action)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun QueueList(ui: DeckUi, onPlay: (Long) -> Unit) {
    if (ui.queue.isEmpty()) {
        val empty = when {
            ui.source == Source.LOCAL -> "Nothing loaded"
            ui.source == Source.APPLE_MUSIC && ui.apple.sdkPlayback -> "Pick a playlist to load the queue"
            else -> "This app doesn't share its queue"
        }
        Text(empty, style = Sub)
        return
    }
    val list = rememberLazyListState()
    val currentIndex = ui.queue.indexOfFirst { it.id == ui.currentQueueId }
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) list.animateScrollToItem(currentIndex) }
    LazyColumn(state = list) {
        itemsIndexed(ui.queue, key = { _, q -> q.id }) { _, q ->
            QueueRow(q, current = q.id == ui.currentQueueId, onClick = { onPlay(q.id) })
        }
    }
}

@Composable
private fun QueueRow(q: QueueEntry, current: Boolean, onClick: () -> Unit, showArt: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawWithCache {
                onDrawBehind {
                    drawLine(Color.White.copy(alpha = 0.07f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Thumb(q.artwork, q.artworkUri, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(q.title, style = Title.copy(color = if (current) DeckColors.Accent else Title.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
            q.subtitle?.let { Text(it, style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (current) Text("▶", style = Sub.copy(color = DeckColors.Accent))
    }
}

@Composable
fun UrlThumb(url: String?, modifier: Modifier) = Thumb(null, url?.let(Uri::parse), modifier)

/** Cover thumbnail from a bitmap the source handed us, or loaded (and cached) from a URI. */
@Composable
private fun Thumb(bitmap: Bitmap?, uri: Uri?, modifier: Modifier) {
    val context = LocalContext.current
    // produceState keeps its last value when the keys change, so always replace it: a reused row
    // would otherwise go on showing the previous song's cover.
    val image by produceState<ImageBitmap?>(bitmap?.asImageBitmap(), bitmap, uri) {
        value = bitmap?.asImageBitmap() ?: uri?.let { Artwork.thumbnail(context, it)?.asImageBitmap() }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A302A)), contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text("♪", style = Sub.copy(fontSize = 16.sp))
    }
}

/** Now-playing band: the cover big, what's up next, and liner notes. */
@Composable
fun NowPlayingBand(ui: DeckUi, onPlayQueue: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.glassPanel().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(ui.label.stripe, ui.label.stripe2))),
            contentAlignment = Alignment.Center,
        ) {
            ui.artwork?.let { Image(it, "Album art", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Text("A", style = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = 0.85f)))
        }
        Column(Modifier.weight(0.5f).fillMaxHeight()) {
            Text("Up next", style = Header)
            Spacer(Modifier.height(6.dp))
            val i = ui.queue.indexOfFirst { it.id == ui.currentQueueId }
            val next = if (i >= 0) ui.queue.drop(i + 1).take(4) else emptyList()
            if (next.isEmpty()) Text("End of side A", style = Sub)
            next.forEach { q -> QueueRow(q, current = false, onClick = { onPlayQueue(q.id) }, showArt = true) }
        }
        Column(Modifier.weight(0.5f).fillMaxHeight()) {
            Text("Liner notes", style = Header)
            Spacer(Modifier.height(8.dp))
            Text(ui.title.ifBlank { "No tape playing" }, style = Title.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (ui.artist.isNotBlank()) Text(ui.artist, style = Sub.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.album.isNotBlank()) Text(ui.album, style = Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            val details = buildList {
                add(ui.source.label)
                if (ui.trackCount > 0) add("Track ${ui.trackIndex + 1} of ${ui.trackCount}")
                add("${formatTime(ui.positionMs)} / ${formatTime(ui.durationMs)}")
            }
            details.forEach { Text(it, style = Sub) }
            Spacer(Modifier.weight(1f))
            // Tape-position hairline.
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxWidth(ui.progress).fillMaxHeight().background(DeckColors.Accent))
            }
        }
    }
}

// region Cassette rack (unfolded "book" layout)

/** A cassette being carried from the rack to the deck: where its card started and how far it has slid. */
class TapeDrag {
    var track by mutableStateOf<LocalTrack?>(null)
    var index by mutableIntStateOf(-1)
    /** Top-left of the card where the drag began, in root coordinates. */
    var origin by mutableStateOf(Offset.Zero)
    var width by mutableIntStateOf(0)
    var dx by mutableFloatStateOf(0f)

    fun begin(t: LocalTrack, i: Int, at: Offset, w: Int) {
        track = t; index = i; origin = at; width = w; dx = 0f
    }

    fun clear() {
        track = null; index = -1; dx = 0f
    }
}

/** Card colours (body, ink), picked per folder so an album's tapes look alike. */
private val TapeColors = listOf(
    Color(0xFF18223F) to Color(0xFFF2F0E8),
    Color(0xFFECE6D4) to Color(0xFF2A2A2A),
    Color(0xFFF0B25A) to Color(0xFF2A2118),
    Color(0xFF24407F) to Color(0xFFF2F0E8),
    Color(0xFFD9D3C0) to Color(0xFF2A2A2A),
)

/** One tape in the rack: a cassette spine with its cover, handwritten title and artist. */
@Composable
fun TapeCard(t: LocalTrack, modifier: Modifier = Modifier, current: Boolean = false) {
    val (body, ink) = TapeColors[(t.folder.hashCode() and 0x7fffffff) % TapeColors.size]
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .height(54.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        androidx.compose.ui.graphics.lerp(body, Color.White, 0.14f),
                        body,
                        androidx.compose.ui.graphics.lerp(body, Color.Black, 0.2f),
                    ),
                ),
            )
            .then(if (current) Modifier.border(2.dp, DeckColors.Accent, shape) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(null, t.coverUri, Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title,
                style = Title.copy(color = ink, fontFamily = FontFamily.Cursive, fontSize = 18.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            t.artist?.let { Text(it, style = Sub.copy(color = ink.copy(alpha = 0.7f)), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/**
 * The library on the left of the unfolded phone. Local tapes can be slid to the right: the card
 * follows the finger ([drag]) and [onRelease] is told where it was let go (root x of its centre).
 * A tap loads a tape too ([onTap]).
 */
@Composable
fun TapeRack(
    ui: DeckUi,
    actions: ShelfActions,
    drag: TapeDrag,
    onTap: (Int) -> Unit,
    onRelease: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.glassPanel().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CASSETTE RACK", style = Header.copy(letterSpacing = 1.5.sp), modifier = Modifier.weight(1f))
            if (ui.source == Source.LOCAL) {
                Text(
                    if (ui.status == SourceStatus.NeedsFolder) "Choose folder" else "Change folder",
                    style = Sub.copy(color = DeckColors.Accent, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable(onClick = actions.pickFolder).padding(4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Source.entries.forEach { s ->
                val selected = s == ui.source
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                        .clickable { actions.selectSource(s) }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SourceTile(s, Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.label.substringBefore(' '), style = Sub.copy(color = Title.color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.source == Source.LOCAL && ui.status == SourceStatus.Ready -> {
                    val list = rememberLazyListState()
                    // Open on the tape that's in the deck.
                    LaunchedEffect(Unit) { if (ui.trackIndex in ui.tracks.indices) list.scrollToItem(ui.trackIndex) }
                    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(ui.tracks, key = { i, _ -> i }) { i, t ->
                            RackTape(t, i, current = i == ui.trackIndex, drag = drag, onTap = onTap, onRelease = onRelease)
                        }
                    }
                }
                ui.source == Source.LOCAL -> Column { Albums(ui, actions) }
                ui.source == Source.APPLE_MUSIC -> Column {
                    Text(ui.apple.openPlaylist?.let { "Playlist" } ?: "Playlists", style = Header)
                    Spacer(Modifier.height(8.dp))
                    AppleBrowser(ui.apple, actions.apple, fallback = { RemoteState(ui, actions, showHeader = false) })
                }
                else -> Column { RemoteState(ui, actions) }
            }
        }
        if (ui.source == Source.LOCAL && ui.status == SourceStatus.Ready) {
            Spacer(Modifier.height(8.dp))
            Text("Slide a tape to the right to load it", style = Sub.copy(fontSize = 11.sp, letterSpacing = 1.sp))
        }
    }
}

@Composable
private fun RackTape(
    t: LocalTrack,
    index: Int,
    current: Boolean,
    drag: TapeDrag,
    onTap: (Int) -> Unit,
    onRelease: (Float) -> Unit,
) {
    var pos by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableIntStateOf(0) }
    TapeCard(
        t,
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { pos = it.positionInRoot(); width = it.size.width }
            .graphicsLayer { alpha = if (drag.index == index) 0.25f else 1f }
            .clickable { onTap(index) }
            .pointerInput(index) {
                detectHorizontalDragGestures(
                    onDragStart = { drag.begin(t, index, pos, width) },
                    onDragEnd = { onRelease(drag.origin.x + drag.dx + drag.width / 2f) },
                    onDragCancel = { onRelease(Float.NEGATIVE_INFINITY) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag.dx = (drag.dx + amount).coerceAtLeast(-24f)
                    },
                )
            },
        current = current,
    )
}

// endregion
