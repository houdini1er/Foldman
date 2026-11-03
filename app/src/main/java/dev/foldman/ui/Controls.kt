package dev.foldman.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Whether the keys buzz; the deck's HAPTIC switch sets it. */
val LocalHaptics = staticCompositionLocalOf { true }

/**
 * Lays the content out a quarter-turn anticlockwise: measured with width and height swapped, then
 * rotated. The cover screen stands the cassette on its end this way, label to the left.
 */
fun Modifier.quarterTurn(enabled: Boolean = true): Modifier = if (!enabled) this else layout { measurable, c ->
    val p = measurable.measure(Constraints(c.minHeight, c.maxHeight, c.minWidth, c.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer((p.height - p.width) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

/** Enamel-painted metal, like the coloured walkmans: a soft gradient with a fine powder-coat grain. */
fun Modifier.paintedBody(color: Color, capHeight: Dp = 0.dp, corner: Dp = 34.dp, framed: Boolean = true): Modifier = drawWithCache {
    val r = CornerRadius(corner.toPx())
    val rnd = Random(5)
    val grain = Path()
    repeat((size.width * size.height / 260f).toInt().coerceAtMost(30000)) {
        val x = rnd.nextFloat() * size.width
        val y = rnd.nextFloat() * size.height
        grain.addRect(androidx.compose.ui.geometry.Rect(x, y, x + 1.2f, y + 1.2f))
    }
    val body = Brush.verticalGradient(listOf(lerp(color, Color.White, 0.16f), color, lerp(color, Color.Black, 0.22f)))
    val cap = capHeight.toPx()
    onDrawBehind {
        // Unframed, the body is the whole screen: no drop shadow and no lit edge.
        if (framed) drawRoundRect(Color.Black.copy(alpha = 0.5f), topLeft = Offset(0f, 8f), cornerRadius = r)
        drawRoundRect(body, cornerRadius = r)
        drawPath(grain, Color.White.copy(alpha = 0.045f))
        if (cap > 0f) {
            // The top cap is a separate, slightly lighter piece with a seam below it.
            drawRoundRect(
                Brush.verticalGradient(listOf(lerp(color, Color.White, 0.3f), lerp(color, Color.White, 0.12f)), 0f, cap),
                size = Size(size.width, cap + r.x), cornerRadius = r,
            )
            drawRect(lerp(color, Color.White, 0.12f), Offset(0f, cap), Size(size.width, r.x))
            drawRect(body, Offset(0f, cap), Size(size.width, r.x))
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(0f, cap), Offset(size.width, cap), 2f)
            drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, cap + 2f), Offset(size.width, cap + 2f), 1f)
        }
        drawRoundRect(
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Black.copy(alpha = 0.12f)), Offset.Zero, Offset(size.width, size.height)),
            cornerRadius = r,
        )
        if (framed) drawRoundRect(Color.White.copy(alpha = 0.35f), cornerRadius = r, style = Stroke(2f))
    }
}

/** Raised chrome lettering for the name on the case. */
@Composable
fun Wordmark(text: String, size: TextUnit, modifier: Modifier = Modifier, light: Boolean = true) {
    Text(
        text,
        modifier,
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = size,
            letterSpacing = size * 0.04f,
            brush = if (light) {
                Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFDDE1E6), Color(0xFFA9AFB7), Color(0xFFE9ECEF)))
            } else {
                Brush.verticalGradient(listOf(Color(0xFF55585E), Color(0xFF2B2D31)))
            },
            shadow = Shadow(Color.Black.copy(alpha = if (light) 0.45f else 0.2f), Offset(0f, 3f), 4f),
        ),
        maxLines = 1,
    )
}

/** A two-position slide switch with a ridged metal nub, labelled on its left. */
@Composable
fun SlideSwitch(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    caption: String,
    modifier: Modifier = Modifier,
    onLabel: String = "ON",
    offLabel: String = "OFF",
    height: Dp = 62.dp,
) {
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val sounds = LocalMechSounds.current
    val toggle by rememberUpdatedState(onToggle)
    val state by rememberUpdatedState(on)
    val pos by animateFloatAsState(if (on) 0f else 1f, spring(dampingRatio = 0.7f, stiffness = 1600f), label = "slide")
    val measurer = rememberTextMeasurer()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption, style = EngravedCaps.copy(fontSize = 8.sp, color = Color(0xFF2E3034)))
        Canvas(
            Modifier
                .size(46.dp, height)
                .semantics {
                    role = Role.Switch
                    contentDescription = caption
                    stateDescription = if (on) onLabel else offLabel
                }
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        sounds?.keyDown()
                        toggle(!state)
                    }
                },
        ) {
            val small = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E3034))
            val trackW = size.width * 0.38f
            val x = size.width - trackW - 2f
            val top = 4f
            val trackH = size.height - 8f
            val onL = measurer.measure(onLabel, small)
            val offL = measurer.measure(offLabel, small)
            drawText(onL, topLeft = Offset(0f, top + trackH * 0.18f - onL.size.height / 2f))
            drawText(offL, topLeft = Offset(0f, top + trackH * 0.82f - offL.size.height / 2f))
            drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(x, top + 1.5f), Size(trackW, trackH), CornerRadius(4f))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF070708), Color(0xFF2A2B2E))), Offset(x, top), Size(trackW, trackH), CornerRadius(4f))
            val nubH = trackH * 0.42f
            val ny = top + 2f + (trackH - nubH - 4f) * pos
            drawRoundRect(
                Brush.horizontalGradient(listOf(Color(0xFFE9EAEC), Color(0xFF9A9DA2), Color(0xFFD5D7DA)), x, x + trackW),
                Offset(x + 2f, ny), Size(trackW - 4f, nubH), CornerRadius(3f),
            )
            for (i in 1..4) {
                val y = ny + nubH * i / 5f
                drawLine(Color.Black.copy(alpha = 0.35f), Offset(x + 4f, y), Offset(x + trackW - 4f, y), 1f)
            }
        }
    }
}

/** Engraved outline arrow, pointing the way the tape comes out. Tapping it opens the tape shelf. */
@Composable
fun EjectArrow(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color(0xFFE6EAF0), up: Boolean = true) {
    val action by rememberUpdatedState(onClick)
    Canvas(
        modifier
            .semantics { role = Role.Button; contentDescription = "Eject: choose a tape" }
            .pointerInput(Unit) { detectTapGestures { action() } },
    ) {
        val w = size.width
        val h = size.height
        val p = if (up) Path().apply {
            moveTo(w / 2f, 0f); lineTo(w, h * 0.35f); lineTo(w * 0.68f, h * 0.35f); lineTo(w * 0.68f, h)
            lineTo(w * 0.32f, h); lineTo(w * 0.32f, h * 0.35f); lineTo(0f, h * 0.35f); close()
        } else Path().apply {
            moveTo(0f, h / 2f); lineTo(w * 0.3f, 0f); lineTo(w * 0.3f, h * 0.3f); lineTo(w, h * 0.3f)
            lineTo(w, h * 0.7f); lineTo(w * 0.3f, h * 0.7f); lineTo(w * 0.3f, h); close()
        }
        drawPath(p, Color.Black.copy(alpha = 0.25f), style = Stroke(3.5f))
        drawPath(p, color, style = Stroke(2.2f))
    }
}

/** A dark bezel holding gauges, like the meter window on a deck's faceplate. */
fun Modifier.bezel(corner: Dp = 6.dp): Modifier = drawWithCache {
    val r = CornerRadius(corner.toPx())
    onDrawBehind {
        drawRoundRect(Color.White.copy(alpha = 0.7f), topLeft = Offset(0f, 1.5f), cornerRadius = r)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF55585D), Color(0xFF2A2C30))), size = Size(size.width, size.height - 1.5f), cornerRadius = r)
        drawRoundRect(Color.Black, topLeft = Offset(3f, 3f), size = Size(size.width - 6f, size.height - 7.5f), cornerRadius = CornerRadius(r.x * 0.6f))
    }
}

/**
 * The side-strip volume slider. The original had separate LEFT and RIGHT sliders; here one ridged
 * nub spans both tracks so they move together.
 */
@Composable
fun VolumeSlider(volume: Float, onVolume: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(volume)
    val set by rememberUpdatedState(onVolume)
    val measurer = rememberTextMeasurer()
    Canvas(
        modifier
            .semantics {
                contentDescription = "Volume"
                progressBarRangeInfo = ProgressBarRangeInfo(current, 0f..1f)
                setProgress { set(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    set((current - drag.y / (size.height * 0.8f)).coerceIn(0f, 1f))
                }
            },
    ) {
        val ink = Color(0xFF34363B)
        val small = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.Bold, color = ink)
        val labelH = 12.dp.toPx()
        val top = 4f
        val bottom = size.height - labelH - 4f
        val trackW = size.width * 0.13f
        val xs = listOf(size.width * 0.42f, size.width * 0.66f)
        // Scale 0–10 printed on the left.
        for (i in 0..10) {
            val y = bottom - (bottom - top) * i / 10f
            drawLine(ink, Offset(size.width * 0.22f, y), Offset(size.width * (if (i % 5 == 0) 0.32f else 0.28f), y), 1.2f)
            if (i % 2 == 0) {
                val l = measurer.measure(i.toString(), small)
                drawText(l, topLeft = Offset(size.width * 0.18f - l.size.width, y - l.size.height / 2f))
            }
        }
        xs.forEach { x ->
            drawRoundRect(Color.White.copy(alpha = 0.6f), Offset(x - trackW / 2f, top + 1.5f), Size(trackW, bottom - top), CornerRadius(3f))
            drawRoundRect(Color(0xFF0A0A0B), Offset(x - trackW / 2f, top), Size(trackW, bottom - top), CornerRadius(3f))
        }
        val nubH = 30.dp.toPx().coerceAtMost((bottom - top) * 0.3f)
        val ny = bottom - nubH - (bottom - top - nubH) * current
        val left = xs[0] - trackW * 1.6f
        val right = xs[1] + trackW * 1.6f
        drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(left, ny + 2f), Size(right - left, nubH), CornerRadius(3f))
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFFF2F3F5), Color(0xFFA6A9AE), Color(0xFFE2E4E7), Color(0xFF9EA1A6)), left, right),
            Offset(left, ny), Size(right - left, nubH), CornerRadius(3f),
        )
        for (i in 1..8) {
            val y = ny + nubH * i / 9f
            drawLine(Color.Black.copy(alpha = 0.3f), Offset(left + 3f, y), Offset(right - 3f, y), 1f)
        }
        val l = measurer.measure("LEFT", small)
        val r = measurer.measure("RIGHT", small)
        drawText(l, topLeft = Offset(xs[0] - l.size.width / 2f, size.height - labelH))
        drawText(r, topLeft = Offset(xs[1] - r.size.width / 2f + 4f, size.height - labelH))
    }
}

/** The orange hotline-style button on top of the case; here it opens the tape shelf. */
@Composable
fun HotlineButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val action by rememberUpdatedState(onClick)
    val view = LocalView.current
    val haptics = LocalHaptics.current
    val sounds = LocalMechSounds.current
    var down by remember { androidx.compose.runtime.mutableStateOf(false) }
    val depth by animateFloatAsState(if (down) 1f else 0f, spring(stiffness = 2500f), label = "hotline")
    Canvas(
        modifier
            .semantics { role = Role.Button; contentDescription = "Tapes" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    down = true
                    if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    sounds?.keyDown()
                    action()
                    tryAwaitRelease()
                    down = false
                })
            },
    ) {
        val lift = size.height * 0.25f * (1f - depth)
        drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(0f, size.height * 0.25f), Size(size.width, size.height * 0.75f), CornerRadius(4f))
        drawRoundRect(Color(0xFFC77A0A), Offset(0f, size.height * 0.25f - lift + size.height * 0.3f), Size(size.width, size.height * 0.45f), CornerRadius(4f))
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFFFD25A), Color(0xFFF5A915)), 0f, size.height),
            Offset(0f, size.height * 0.25f - lift), Size(size.width, size.height * 0.5f), CornerRadius(4f),
        )
        drawLine(Color.White.copy(alpha = 0.6f), Offset(4f, size.height * 0.25f - lift + 2f), Offset(size.width - 4f, size.height * 0.25f - lift + 2f), 1.2f)
    }
}

/** Perforated microphone grille, as on the side of the original. */
@Composable
fun MicGrille(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val step = 5.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(Color.White.copy(alpha = 0.5f), 1.2f, Offset(x, y + 0.8f))
                drawCircle(Color(0xFF26272A), 1.2f, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Headphone socket: decoration, but a walkman without one would look wrong. */
/** True while headphones (wired, USB or Bluetooth) are connected to the phone. */
@Composable
fun rememberHeadphonesConnected(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    var connected by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(context) {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        fun check() {
            connected = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                when (it.type) {
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_HEARING_AID -> true
                    else -> false
                }
            }
        }
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) = check()
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) = check()
        }
        check()
        am.registerAudioDeviceCallback(callback, null)
        onDispose { am.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}

/** The headphone jack; its ring lights up green while headphones are connected. */
@Composable
fun PhonesJack(modifier: Modifier = Modifier) {
    val plugged = rememberHeadphonesConnected()
    val green = Color(0xFF3DDC84)
    Canvas(modifier) {
        val r = size.minDimension / 2f
        if (plugged) drawCircle(Brush.radialGradient(listOf(green.copy(alpha = 0.6f), Color.Transparent), center, r * 1.5f), r * 1.5f)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE6E8EB), Color(0xFF8E9196)), center, r), r)
        drawCircle(Color(0xFF050505), r * 0.62f)
        drawCircle(if (plugged) green else Color(0xFF2A2B2E), r * 0.3f)
    }
}
