package dev.foldman.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.foldman.deck.DeckUi
import dev.foldman.deck.Transport

enum class KeySymbol { STOP, REW, PLAY, FF, PAUSE, PREV, NEXT }

/**
 * The transport keys. Latched keys stay down, exactly as the mechanism would hold them. [vertical]
 * stacks them down the side strip, as on the folded phone.
 */
@Composable
fun TransportKeys(
    ui: DeckUi,
    onStop: () -> Unit,
    onRewind: () -> Unit,
    onPlay: () -> Unit,
    onFastForward: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 86.dp,
    vertical: Boolean = false,
) {
    val t = ui.transport
    // Captions sit on the silver strip when stacked, on the dark key well otherwise.
    val cc = if (vertical) DeckColors.Engrave else Color(0xFF9EA1A7)
    val keys: @Composable (Modifier, Modifier) -> Unit = { k, play ->
        val rew: @Composable () -> Unit = {
            DeckKey(KeySymbol.REW, "REW", latched = t == Transport.REWINDING, onPress = onRewind, modifier = k, height = keyHeight, captionColor = cc)
        }
        val playKey: @Composable () -> Unit = {
            DeckKey(
                KeySymbol.PLAY, "PLAY", latched = t == Transport.PLAYING || t == Transport.PAUSED, onPress = onPlay,
                modifier = play, height = keyHeight, captionColor = cc, accent = true,
            )
        }
        val ff: @Composable () -> Unit = {
            DeckKey(KeySymbol.FF, "FF", latched = t == Transport.FAST_FORWARDING, onPress = onFastForward, modifier = k, height = keyHeight, captionColor = cc)
        }
        // Stacked down the side strip the order is PLAY, FF, REW (as on the original); laid out in a row it is REW, PLAY, FF.
        if (vertical) { playKey(); ff(); rew() } else { rew(); playKey(); ff() }
        DeckKey(KeySymbol.PAUSE, "PAUSE", latched = t == Transport.PAUSED, onPress = onPause, modifier = k, height = keyHeight, captionColor = cc, mark = Color(0xFF3E9C5A))
        DeckKey(KeySymbol.STOP, "STOP·EJECT", latched = false, onPress = onStop, modifier = k, height = keyHeight, captionColor = cc, mark = Color(0xFFC8352B))
    }
    if (vertical) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) { keys(Modifier.fillMaxWidth(), Modifier.fillMaxWidth()) }
    } else {
        Row(
            modifier.recess(16.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { keys(Modifier.weight(1f), Modifier.weight(1.35f)) }
    }
}

/**
 * One key: a metal cap whose front edge disappears into the slot as it goes down. It acts on
 * touch-down, as a mechanical key does, and clicks and buzzes on the way down and up.
 */
@Composable
fun DeckKey(
    symbol: KeySymbol,
    caption: String,
    latched: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 86.dp,
    accent: Boolean = false,
    mark: Color? = null,
    captionColor: Color = Color(0xFF9EA1A7),
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    // The gesture loop outlives recompositions; it calls whatever the latest action is.
    val action by rememberUpdatedState(onPress)
    val depth by animateFloatAsState(
        when {
            pressed -> 1f
            latched -> 0.72f
            else -> 0f
        },
        spring(dampingRatio = 0.5f, stiffness = 2200f), label = "key",
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            caption, style = EngravedCaps.copy(color = captionColor, fontSize = 8.sp, letterSpacing = 1.sp),
            textAlign = TextAlign.Center, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    role = Role.Button
                    contentDescription = caption
                    stateDescription = if (latched) "Down" else "Up"
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        pressed = true
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                        sounds?.keyDown()
                        action()
                        tryAwaitRelease()
                        pressed = false
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                        sounds?.keyUp()
                    })
                },
        ) { drawKey(symbol, depth, accent, latched, mark) }
    }
}

private fun DrawScope.drawKey(symbol: KeySymbol, depth: Float, accent: Boolean, latched: Boolean, mark: Color?) {
    val w = size.width
    val h = size.height
    val travel = h * 0.16f
    val front = h * 0.17f * (1f - depth * 0.85f)
    val top = travel * depth
    val faceH = h - travel - h * 0.17f
    val r = CornerRadius(w * 0.09f)
    // Slot shadow under the key.
    drawRoundRect(Color.Black.copy(alpha = 0.7f), topLeft = Offset(-1f, top + 2f), size = Size(w + 2f, faceH + front + 2f), cornerRadius = r)
    // Front edge, seen as the key stands proud of the slot.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF8D9095), Color(0xFF55585C)), top + faceH - r.x, top + faceH + front),
        topLeft = Offset(0f, top + faceH - r.x), size = Size(w, front + r.x), cornerRadius = r,
    )
    val base = if (accent) Color(0xFFD9DBDE) else Color(0xFFCFD1D5)
    val shade = 0.12f * depth
    drawRoundRect(
        Brush.verticalGradient(
            listOf(lerp(Color(0xFFF7F8F9), Color.Black, shade), lerp(base, Color.Black, shade), lerp(Color(0xFFA9ACB1), Color.Black, shade)),
            top, top + faceH,
        ),
        topLeft = Offset(0f, top), size = Size(w, faceH), cornerRadius = r,
    )
    // Fine ribbing across the top of the cap for grip.
    for (i in 1..4) {
        val y = top + faceH * 0.08f * i
        drawLine(Color.Black.copy(alpha = 0.07f), Offset(w * 0.12f, y), Offset(w * 0.88f, y), 1f)
    }
    drawRoundRect(Color.White.copy(alpha = 0.8f), topLeft = Offset(0.8f, top + 0.8f), size = Size(w - 1.6f, faceH - 1.6f), cornerRadius = r, style = Stroke(1.2f))
    if (accent || mark != null) {
        drawRoundRect(mark ?: DeckColors.Accent, topLeft = Offset(w * 0.18f, top + faceH - h * 0.1f), size = Size(w * 0.64f, h * 0.035f), cornerRadius = CornerRadius(h))
    }
    val c = Offset(w / 2f, top + faceH * 0.48f)
    val s = minOf(w, faceH) * 0.16f
    val ink = if (accent && latched) DeckColors.AccentDeep else DeckColors.Engrave
    drawSymbol(symbol, c, s, ink)
}

fun DrawScope.drawSymbol(symbol: KeySymbol, c: Offset, s: Float, ink: Color) {
    fun tri(cx: Float, dir: Float) = Path().apply {
        moveTo(cx - s * 0.8f * dir, c.y - s)
        lineTo(cx + s * 0.8f * dir, c.y)
        lineTo(cx - s * 0.8f * dir, c.y + s)
        close()
    }
    when (symbol) {
        KeySymbol.STOP -> drawRect(ink, Offset(c.x - s * 0.85f, c.y - s * 0.85f), Size(s * 1.7f, s * 1.7f))
        KeySymbol.PLAY -> drawPath(tri(c.x + s * 0.1f, 1f), ink)
        KeySymbol.FF -> {
            drawPath(tri(c.x - s * 0.7f, 1f), ink); drawPath(tri(c.x + s * 0.7f, 1f), ink)
        }
        KeySymbol.REW -> {
            drawPath(tri(c.x - s * 0.7f, -1f), ink); drawPath(tri(c.x + s * 0.7f, -1f), ink)
        }
        KeySymbol.PAUSE -> {
            drawRect(ink, Offset(c.x - s * 0.8f, c.y - s), Size(s * 0.55f, s * 2f))
            drawRect(ink, Offset(c.x + s * 0.25f, c.y - s), Size(s * 0.55f, s * 2f))
        }
        KeySymbol.PREV -> {
            drawRect(ink, Offset(c.x - s * 1.25f, c.y - s), Size(s * 0.3f, s * 2f))
            drawPath(tri(c.x - s * 0.15f, -1f), ink); drawPath(tri(c.x + s * 1.0f, -1f), ink)
        }
        KeySymbol.NEXT -> {
            drawPath(tri(c.x - s * 1.0f, 1f), ink); drawPath(tri(c.x + s * 0.15f, 1f), ink)
            drawRect(ink, Offset(c.x + s * 0.95f, c.y - s), Size(s * 0.3f, s * 2f))
        }
    }
}

/** A small round push button (AMS skip, counter reset, tape shelf). */
@Composable
fun MiniButton(
    caption: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: KeySymbol? = null,
    diameter: Dp = 34.dp,
    enabled: Boolean = true,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val sounds = LocalMechSounds.current
    val haptics = LocalHaptics.current
    val action by rememberUpdatedState(onPress)
    val depth by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 3000f), label = "mini")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(diameter)
                .semantics { role = Role.Button; contentDescription = caption }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onPress = {
                        pressed = true
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sounds?.keyUp()
                        action()
                        tryAwaitRelease()
                        pressed = false
                    })
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val r = size.minDimension / 2f
                drawCircle(Color.Black.copy(alpha = 0.6f), r, c)
                drawCircle(Color.White.copy(alpha = 0.5f), r, c + Offset(0f, 1.2f), style = Stroke(1f))
                val cap = r * (0.84f - depth * 0.05f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(lerp(Color(0xFFF2F3F5), Color.Black, depth * 0.15f), Color(0xFF9B9EA3)),
                        c - Offset(cap * 0.35f, cap * 0.45f), cap * 1.7f,
                    ),
                    cap, c + Offset(0f, depth * 1.5f),
                )
                symbol?.let { drawSymbol(it, c + Offset(0f, depth * 1.5f), cap * 0.3f, if (enabled) DeckColors.Engrave else DeckColors.EngraveLight) }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(caption, style = EngravedCaps.copy(fontSize = 7.sp, letterSpacing = 1.sp), maxLines = 1)
    }
}
