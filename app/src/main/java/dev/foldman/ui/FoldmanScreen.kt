package dev.foldman.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import dev.foldman.deck.Transport
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import dev.foldman.deck.DeckEvent
import dev.foldman.deck.DeckUi
import dev.foldman.deck.DeckViewModel
import dev.foldman.deck.Source
import kotlinx.coroutines.launch

private enum class Posture { Cover, Unfolded, Tabletop }

/** Body enamel: the blue of the first portable cassette players (the unfolded deck). */
private val Enamel = Color(0xFF2F6FB5)

/** The folded player's case: dark brushed slate. */
private val Slate = Color(0xFF29303A)

/**
 * Picks a layout from the hinge:
 *  - folded (cover screen): the portable player stood upright, tape on end behind its window,
 *    keys and volume down a silver side strip;
 *  - unfolded: opened like a book, the cassette rack on the left and the player on the right; slide a
 *    tape from the rack onto the player and it seats itself and starts;
 *  - half-open on a table (Flex mode): the deck on the upper half, the library on the lower.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldmanScreen(
    vm: DeckViewModel,
    windowLayout: WindowLayoutInfo?,
    onPickFolder: () -> Unit,
    onInstallApp: (Source) -> Unit,
    onAppleSignIn: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val anim = rememberDeckAnimations(ui.transport, ui.progress)
    val sounds = remember { MechSounds() }
    DisposableEffect(sounds) { onDispose { sounds.release() } }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var shelfOpen by rememberSaveable { mutableStateOf(false) }
    val feel by rememberUpdatedState(ui)

    // A new tape drops into the deck whenever the source changes (and when the app opens).
    LaunchedEffect(ui.source) {
        anim.insert.snapTo(1f)
        anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
        if (feel.keySounds) sounds.autoStop()
    }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            if (feel.keySounds) sounds.autoStop()
            when (e) {
                DeckEvent.TapeEnd -> if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                DeckEvent.Eject -> {
                    if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    scope.launch {
                        anim.pop.animateTo(1f, tween(90))
                        anim.pop.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 500f))
                    }
                    shelfOpen = true
                }
            }
        }
    }

    val appleActions = remember(vm) {
        AppleActions(
            signIn = onAppleSignIn,
            signOut = vm::signOutOfApple,
            setDeveloperToken = vm::setAppleDeveloperToken,
            refresh = vm::loadApplePlaylists,
            openPlaylist = vm::openApplePlaylist,
            closePlaylist = vm::closeApplePlaylist,
            play = vm::playApplePlaylist,
        )
    }
    val shelfActions = remember(vm) {
        ShelfActions(
            selectSource = vm::selectSource,
            pickFolder = onPickFolder,
            playTrack = vm::playTrack,
            openAccess = vm::openAccessSettings,
            openApp = vm::openSourceApp,
            installApp = onInstallApp,
            apple = appleActions,
        )
    }
    val deck = remember(vm) { DeckControls(vm) }
    val fold = windowLayout?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()

    // Book layout: a tape slid from the rack onto the player.
    val tapeDrag = remember { TapeDrag() }
    var lastPosture by remember { mutableStateOf<Posture?>(null) }
    var resumeOnClose by remember { mutableStateOf(false) }
    var deckBounds by remember { mutableStateOf(Rect.Zero) }
    val loadTape: (Int) -> Unit = { index ->
        scope.launch {
            vm.playTrack(index)
            // The old cassette pops out and the new one seats itself.
            anim.insert.snapTo(1f)
            anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
            if (feel.keySounds) sounds.autoStop()
        }
    }
    val dropTape: (Float) -> Unit = { centerX ->
        val d = tapeDrag
        val index = d.index
        if (index >= 0) scope.launch {
            if (centerX >= deckBounds.left + deckBounds.width * 0.1f) {
                // Glide to the middle of the player, then seat the tape and play.
                val target = deckBounds.center.x - d.width / 2f - d.origin.x
                Animatable(d.dx).animateTo(target, tween(170)) { d.dx = value }
                d.clear()
                if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                loadTape(index)
            } else {
                Animatable(d.dx).animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 400f)) { d.dx = value }
                d.clear()
            }
        }
    }

    CompositionLocalProvider(
        LocalMechSounds provides sounds.takeIf { ui.keySounds },
        LocalHaptics provides ui.haptics,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Brush.radialGradient(listOf(Color(0xFF2A2420), DeckColors.Backdrop), radius = 1800f)),
        ) {
            val density = LocalDensity.current
            val sheetHeight = maxHeight * 0.8f
            val posture = when {
                fold != null && fold.state == FoldingFeature.State.HALF_OPENED && fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> Posture.Tabletop
                fold != null && fold.orientation == FoldingFeature.Orientation.VERTICAL -> Posture.Unfolded
                maxWidth >= 600.dp && maxWidth > maxHeight * 0.7f -> Posture.Unfolded
                else -> Posture.Cover
            }
            LaunchedEffect(posture) { view.showSystemBars(posture != Posture.Cover) }
            // Opening the phone opens the lid: the tape stops and starts to come out of the deck.
            // Closing it seats the tape again and, if it was playing, carries on.
            LaunchedEffect(posture) {
                val before = lastPosture
                lastPosture = posture
                if (before == Posture.Cover && posture == Posture.Unfolded) {
                    resumeOnClose = feel.transport == Transport.PLAYING
                    if (feel.transport != Transport.STOPPED) deck.stop()
                    if (feel.haptics) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    anim.insert.animateTo(0.42f, tween(550, easing = FastOutSlowInEasing))
                } else if (before == Posture.Unfolded && posture == Posture.Cover) {
                    anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
                    if (resumeOnClose) deck.play()
                    resumeOnClose = false
                }
            }
            // Pressing PLAY (or any key that moves the tape) on a half-ejected tape seats it first.
            LaunchedEffect(ui.transport) {
                if (ui.transport != Transport.STOPPED && anim.insert.value > 0.01f && anim.insert.value < 1f) {
                    anim.insert.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 220f))
                }
            }
            when (posture) {
                // The cover screen is the player itself: edge to edge, system bars hidden.
                Posture.Cover -> CoverWalkman(ui, anim, deck, onTapes = { shelfOpen = true }, modifier = Modifier.fillMaxSize())
                Posture.Unfolded -> Row(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TapeRack(
                        ui, shelfActions, tapeDrag,
                        onTap = loadTape,
                        onRelease = dropTape,
                        modifier = Modifier.weight(0.42f).fillMaxHeight(),
                    )
                    // The right-hand page is the upright player; STOP·EJECT on it just stops or pops the tape.
                    CoverWalkman(
                        ui, anim, deck, onTapes = { deck.stop() },
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .onGloballyPositioned { deckBounds = it.boundsInRoot() },
                    )
                }
                Posture.Tabletop -> {
                    val (topH, gap) = with(density) {
                        fold!!.bounds.top.toDp() to (fold.bounds.bottom - fold.bounds.top).toDp()
                    }
                    Column(Modifier.fillMaxSize()) {
                        DeckBand(
                            ui, anim, deck, vm,
                            Modifier
                                .height(topH)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        )
                        Spacer(Modifier.height(gap))
                        LibraryBand(
                            ui, shelfActions, vm::playQueueItem,
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
                        )
                    }
                }
            }
            // The cassette being carried, drawn above both pages so it can cross the spine.
            tapeDrag.track?.let { t ->
                TapeCard(
                    t,
                    Modifier
                        .offset { IntOffset((tapeDrag.origin.x + tapeDrag.dx).roundToInt(), tapeDrag.origin.y.roundToInt()) }
                        .width(with(density) { tapeDrag.width.toDp() })
                        .graphicsLayer { rotationZ = 3f; scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 30f },
                )
            }
            if (shelfOpen && posture == Posture.Cover) {
                ModalBottomSheet(
                    onDismissRequest = { shelfOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color(0xFF1C1510),
                ) {
                    TapeShelf(
                        ui,
                        ShelfActions(
                            selectSource = { vm.selectSource(it) },
                            pickFolder = onPickFolder,
                            playTrack = { vm.playTrack(it); shelfOpen = false },
                            openAccess = vm::openAccessSettings,
                            openApp = vm::openSourceApp,
                            installApp = onInstallApp,
                            apple = appleActions,
                        ),
                        Modifier.fillMaxWidth().height(sheetHeight),
                    )
                }
            }
        }
    }
}

/** Hides the status and navigation bars (a swipe from the edge brings them back briefly), or restores them. */
private fun android.view.View.showSystemBars(show: Boolean) {
    var ctx = context
    while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
    val window = (ctx as? android.app.Activity)?.window ?: return
    WindowCompat.getInsetsController(window, this).run {
        if (show) {
            show(WindowInsetsCompat.Type.systemBars())
        } else {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Key actions, bundled so the layouts don't each need the view model. */
private class DeckControls(vm: DeckViewModel) {
    val play = vm::play
    val pause = vm::pauseKey
    val stop = vm::stop
    val rewind = vm::rewind
    val fastForward = vm::fastForward
    val next = vm::next
    val previous = vm::previous
    val volume = vm::setVolume
}

private val Serif = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, color = Color(0xFFF1F3F6), letterSpacing = 2.sp)
private val OnEnamel = EngravedCaps.copy(color = Color(0xFFDCE6F2))

/**
 * The folded phone: an upright portable player in dark brushed metal. FOLDMAN across the top, the
 * tape on end in its window, the operate lamp and tape counter below it, OPEN to the library.
 */
@Composable
private fun CoverWalkman(ui: DeckUi, anim: DeckAnimations, deck: DeckControls, onTapes: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        Row(Modifier.fillMaxSize().paintedBody(Slate, capHeight = 0.dp, corner = 0.dp, framed = false)) {
            Column(
                Modifier
                    .weight(0.73f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 20.dp, end = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Clear of the camera hole.
                Spacer(Modifier.height((maxHeight * 0.07f).coerceAtLeast(36.dp)))
                Wordmark("FOLDMAN", 34.sp)
                Spacer(Modifier.height(6.dp))
                Text("STEREO CASSETTE PLAYER · FM-8", style = OnEnamel.copy(fontSize = 8.sp, letterSpacing = 1.6.sp), maxLines = 1)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        TapeWindow(ui, anim, vertical = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    EjectArrow(onTapes, Modifier.size(30.dp, 58.dp))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatusLed(ui.transport, caption = "OPERATION")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.bezel().padding(5.dp)) { TapeCounter(anim.reels, digitWidth = 16.dp) }
                        Spacer(Modifier.height(4.dp))
                        Text("TAPE COUNTER", style = OnEnamel.copy(fontSize = 7.sp, letterSpacing = 1.sp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "OPEN ▶",
                    style = OnEnamel.copy(fontSize = 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clickable(onClick = onTapes).padding(horizontal = 14.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
            }
            SideStrip(ui, deck, Modifier.weight(0.27f).fillMaxHeight())
        }
    }
}

/** The silver strip down the side of the case: mic, LED, skip, keys, phones, volume, talk. */
@Composable
private fun SideStrip(ui: DeckUi, deck: DeckControls, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val keyH = ((maxHeight - 450.dp) / 5 - 18.dp).coerceIn(34.dp, 64.dp)
        // Runs the full height of the screen; the padding keeps controls clear of the rounded corners.
        Column(
            Modifier
                .fillMaxSize()
                .chassis(corner = 0.dp, screws = false)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(start = 10.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MicGrille(Modifier.fillMaxWidth().height(26.dp))
            Text("MIC", style = EngravedCaps.copy(fontSize = 7.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                MiniButton("AMS", deck.previous, symbol = KeySymbol.PREV, diameter = 26.dp)
                MiniButton("AMS", deck.next, symbol = KeySymbol.NEXT, diameter = 26.dp, enabled = ui.hasNext)
            }
            Spacer(Modifier.height(10.dp))
            TransportKeys(ui, deck.stop, deck.rewind, deck.play, deck.fastForward, deck.pause, Modifier.fillMaxWidth(), keyHeight = keyH, vertical = true)
            Spacer(Modifier.height(10.dp))
            PhonesJack(Modifier.size(18.dp))
            Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
            Spacer(Modifier.height(6.dp))
            Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
            VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(8.dp))
            TalkButton(ui.volume, deck.volume)
        }
    }
}

/** TALK: holding it ducks the music to a fifth of its volume, so you can speak over it. */
@Composable
private fun TalkButton(volume: Float, setVolume: (Float) -> Unit) {
    val current by rememberUpdatedState(volume)
    val set by rememberUpdatedState(setVolume)
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFFFB35A), Color(0xFFF28A1F), Color(0xFFA24F05))))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val before = current
                        set(before * 0.2f)
                        try {
                            awaitRelease()
                        } finally {
                            set(before)
                        }
                    },
                )
            },
    )
    Text("TALK", style = EngravedCaps.copy(fontSize = 6.sp))
}

/** The unfolded phone: the player laid down as a deck. */
@Composable
private fun DeckBand(ui: DeckUi, anim: DeckAnimations, deck: DeckControls, vm: DeckViewModel, modifier: Modifier) {
    Row(modifier.paintedBody(Enamel, corner = 22.dp)) {
        Column(Modifier.weight(0.74f).fillMaxHeight().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("STEREO", style = Serif.copy(fontSize = 24.sp))
                Spacer(Modifier.weight(1f))
                Text("FM-F8 · STEREO CASSETTE PLAYER", style = OnEnamel.copy(fontSize = 8.sp))
                Spacer(Modifier.width(16.dp))
                Wordmark("FOLDMAN", 26.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(150.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Row(Modifier.fillMaxWidth().weight(1f).bezel().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 0)
                        VuMeter(ui.transport, ui.source, Modifier.weight(1f).fillMaxHeight(), channel = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.bezel().padding(5.dp)) { TapeCounter(anim.reels, digitWidth = 16.dp) }
                        Spacer(Modifier.width(8.dp))
                        MiniButton("RESET", { anim.reels.resetCounter() }, diameter = 20.dp)
                        Spacer(Modifier.width(6.dp))
                        StatusLed(ui.transport)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TapeWindow(ui, anim)
                }
                Spacer(Modifier.width(12.dp))
                EjectArrow({ deck.stop() }, Modifier.size(26.dp, 50.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                MiniButton("AMS", deck.previous, symbol = KeySymbol.PREV, diameter = 30.dp)
                Spacer(Modifier.width(10.dp))
                TransportKeys(ui, deck.stop, deck.rewind, deck.play, deck.fastForward, deck.pause, Modifier.weight(1f), keyHeight = 58.dp)
                Spacer(Modifier.width(10.dp))
                MiniButton("AMS", deck.next, symbol = KeySymbol.NEXT, diameter = 30.dp, enabled = ui.hasNext)
            }
        }
        // Silver end strip: mic, switches, volume and phones.
        Row(
            Modifier
                .weight(0.26f)
                .fillMaxHeight()
                .padding(6.dp)
                .chassis(corner = 18.dp, screws = false)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                MicGrille(Modifier.fillMaxWidth().height(30.dp))
                Text("MIC", style = EngravedCaps.copy(fontSize = 7.sp))
                Spacer(Modifier.weight(1f))
                SlideSwitch(ui.keySounds, vm::setKeySounds, "CLICK", height = 54.dp)
                Spacer(Modifier.height(8.dp))
                SlideSwitch(ui.haptics, vm::setHaptics, "HAPTIC", height = 54.dp)
                Spacer(Modifier.weight(1f))
                PhonesJack(Modifier.size(18.dp))
                Text("PHONES", style = EngravedCaps.copy(fontSize = 6.sp))
            }
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOLUME", style = EngravedCaps.copy(fontSize = 7.sp))
                VolumeSlider(ui.volume, deck.volume, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
