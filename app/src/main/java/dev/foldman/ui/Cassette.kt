package dev.foldman.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.foldman.deck.DeckUi
import dev.foldman.deck.LabelPalette
import dev.foldman.deck.Source
import dev.foldman.deck.Transport
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// Cassette geometry in millimetres (a real Compact Cassette is 100.4 × 63.8).
private val HUB_L = Offset(28.75f, 32f)
private val HUB_R = Offset(71.25f, 32f)
private val WINDOW = Rect(15f, 21f, 85f, 43f)

/**
 * The part of the cassette the case's window shows: both hubs, the tape packs and the printed
 * label around them. The rest of the tape stays hidden behind the body, as on the real thing.
 */
private val CROP = Rect(8f, 12.5f, 92f, 48.5f)
const val TAPE_WINDOW_ASPECT = 84f / 36f

/**
 * The window in the case with the cassette behind it. [vertical] stands it on end (label side to
 * the left) for the folded phone, like the original portable players.
 */
@Composable
fun TapeWindow(ui: DeckUi, anim: DeckAnimations, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val shell by androidx.compose.animation.animateColorAsState(DeckColors.shell(ui.source), tween(600), label = "shell")
    Canvas(
        modifier
            .quarterTurn(vertical)
            .aspectRatio(TAPE_WINDOW_ASPECT)
            .graphicsLayer(),
    ) {
        val u = size.width / CROP.width
        val r = CornerRadius(3.2f * u)
        val rim = 1.1f * u
        // Recess and chrome rim.
        drawRoundRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, rim * 0.6f), cornerRadius = r)
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFFF4F5F7), Color(0xFF8C9096), Color(0xFFDADCDF))),
            cornerRadius = r,
        )
        val inner = RoundRect(rim, rim, size.width - rim, size.height - rim, CornerRadius(r.x - rim))
        val innerPath = Path().apply { addRoundRect(inner) }
        drawPath(innerPath, Color(0xFF0C0C0D))
        val slide = anim.insert.value
        val pop = anim.pop.value
        // Stood on end, the tape leaves through the top of the window and stays visible outside it,
        // so the clip is the window plus an open strip beyond its top edge.
        val tapePath = if (vertical && slide > 0.001f) {
            Path.combine(
                androidx.compose.ui.graphics.PathOperation.Union,
                innerPath,
                Path().apply { addRect(Rect(size.width * 0.5f, rim, size.width * 2.4f, size.height - rim)) },
            )
        } else innerPath
        // In and out through the top of the case (the right of the canvas when stood on end).
        val dx = if (vertical) slide * size.width * 1.1f + pop * u * 5f else 0f
        val dy = if (vertical) 0f else -slide * size.height * 1.3f - pop * u * 5f
        clipPath(tapePath) {
            translate(dx, dy) {
                withTransform({
                    translate(-CROP.left * u, -CROP.top * u)
                    scale(u, u, Offset.Zero)
                }) {
                    drawCassette(ui, anim.reels, shell, measurer, u)
                }
            }
        }
        clipPath(innerPath) {
            // Smoked glass: tint, a long diagonal reflection and a soft top glare.
            drawRect(Color(0xFF26303C).copy(alpha = 0.22f))
            drawPath(
                Path().apply {
                    moveTo(size.width * 0.52f, 0f); lineTo(size.width * 0.70f, 0f)
                    lineTo(size.width * 0.46f, size.height); lineTo(size.width * 0.28f, size.height); close()
                },
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.04f), Color.Transparent),
                    size.width * 0.28f, size.width * 0.70f,
                ),
            )
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), 0f, size.height * 0.3f))
            // Inner shadow where the case overhangs the glass.
            drawPath(innerPath, Color.Black.copy(alpha = 0.6f), style = Stroke(rim * 1.4f))
        }
        drawPath(innerPath, Color.White.copy(alpha = 0.35f), style = Stroke(0.6f))
    }
}

/** Draws the cassette in its own millimetre space; [u] only scales stroke widths of text. */
private fun DrawScope.drawCassette(ui: DeckUi, reels: ReelMotion, shell: Color, measurer: TextMeasurer, u: Float) {
    val light = shell.luminance() > 0.5f
    val shellRR = RoundRect(0f, 0f, 100f, 64f, CornerRadius(3.4f))
    val shellPath = Path().apply { addRoundRect(shellRR) }

    // Drop shadow on the bay floor.
    translate(0.8f, 1.6f) { drawPath(shellPath, Color.Black.copy(alpha = 0.45f)) }
    drawPath(
        shellPath,
        Brush.verticalGradient(listOf(lerp(shell, Color.White, 0.18f), shell, lerp(shell, Color.Black, 0.22f)), 0f, 64f),
    )
    // Moulded grip ridges at the sides.
    for (i in 0 until 7) {
        val y = 22f + i * 3f
        drawLine(lerp(shell, Color.Black, 0.25f), Offset(1.4f, y), Offset(3.2f, y), 0.5f)
        drawLine(lerp(shell, Color.Black, 0.25f), Offset(96.8f, y), Offset(98.6f, y), 0.5f)
    }

    // Window: tape packs and hubs seen through smoked plastic.
    val windowRR = RoundRect(WINDOW, CornerRadius(3.2f))
    val windowPath = Path().apply { addRoundRect(windowRR) }
    clipPath(windowPath) {
        drawRect(Color(0xFF1A1714), WINDOW.topLeft, WINDOW.size)
        drawPack(HUB_L, reels.leftRadius, reels.leftAngle)
        drawPack(HUB_R, reels.rightRadius, reels.rightAngle)
        // Tape running from each pack down to the guide rollers.
        drawLine(DeckColors.Tape, Offset(HUB_L.x - reels.leftRadius, HUB_L.y), Offset(HUB_L.x - reels.leftRadius - 1f, 64f), 0.6f)
        drawLine(DeckColors.Tape, Offset(HUB_R.x + reels.rightRadius, HUB_R.y), Offset(HUB_R.x + reels.rightRadius + 1f, 64f), 0.6f)
        drawRect(Color(0xFF6F7680).copy(alpha = 0.18f), WINDOW.topLeft, WINDOW.size)
    }
    drawHub(HUB_L, reels.leftAngle, reels.leftDelta)
    drawHub(HUB_R, reels.rightAngle, reels.rightDelta)
    // Window glare and moulded frame.
    clipPath(windowPath) {
        drawPath(
            Path().apply {
                moveTo(WINDOW.left + 18f, WINDOW.top); lineTo(WINDOW.left + 30f, WINDOW.top)
                lineTo(WINDOW.left + 20f, WINDOW.bottom); lineTo(WINDOW.left + 8f, WINDOW.bottom); close()
            },
            Color.White.copy(alpha = 0.07f),
        )
    }
    drawPath(windowPath, Color.Black.copy(alpha = 0.55f), style = Stroke(0.5f))

    drawLabel(ui, ui.label, measurer, windowRR, u)
    drawBottomBridge(shell, light)

    listOf(Offset(4.4f, 4.4f), Offset(95.6f, 4.4f), Offset(4.4f, 59.6f), Offset(95.6f, 59.6f), Offset(50f, 55.5f))
        .forEachIndexed { i, c -> drawScrew(c, 1.5f, 15f + i * 40f) }
    drawPath(shellPath, Color.Black.copy(alpha = 0.5f), style = Stroke(0.35f))
    drawPath(Path().apply { addRoundRect(RoundRect(0.4f, 0.4f, 99.6f, 63.6f, CornerRadius(3.1f))) }, Color.White.copy(alpha = 0.25f), style = Stroke(0.25f))
}

/** A pack of wound tape: concentric layers with a faint sheen that turns with the reel. */
private fun DrawScope.drawPack(center: Offset, radius: Float, angle: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFF241510), 0.5f to DeckColors.Tape, 0.9f to Color(0xFF5A3A26), 1f to Color(0xFF2E1C12),
            center = center, radius = radius,
        ),
        radius, center,
    )
    var r = ReelMotion.R_EMPTY + 1f
    while (r < radius - 0.4f) {
        drawCircle(Color.Black.copy(alpha = 0.16f), r, center, style = Stroke(0.12f))
        r += 1.35f
    }
    // Uneven winding shows as soft bright sectors that go round with the pack.
    rotate(angle, center) {
        for (k in 0 until 3) {
            drawArc(
                Color(0xFFB08360).copy(alpha = 0.10f + 0.05f * k),
                startAngle = k * 120f, sweepAngle = 26f, useCenter = true,
                topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2),
            )
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.5f), radius, center, style = Stroke(0.25f))
}

/**
 * The white plastic hub and the deck's drive spindle inside it. When the reel turns faster than
 * the eye can follow, the spokes are drawn several times across the frame's sweep as motion blur.
 */
private fun DrawScope.drawHub(center: Offset, angle: Float, delta: Float) {
    drawCircle(Color.Black.copy(alpha = 0.35f), 6.6f, center + Offset(0.2f, 0.4f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFAFAF8), Color(0xFFD9D8D3)), center, 6.4f), 6.3f, center)
    drawCircle(Color(0xFF151515), 4.5f, center)
    val sweep = abs(delta)
    val copies = if (sweep > 5f) min(7, (sweep / 3.5f).roundToInt() + 1) else 1
    val alpha = if (copies == 1) 1f else (1.7f / copies).coerceAtMost(1f)
    for (i in 0 until copies) {
        val a = angle - delta * i / copies
        rotate(a, center) {
            // Six teeth on the inside of the hub.
            for (t in 0 until 6) {
                rotate(t * 60f, center) {
                    drawRect(Color(0xFFEDECE8).copy(alpha = alpha), Offset(center.x - 0.55f, center.y - 4.6f), Size(1.1f, 1.4f))
                }
            }
            // Drive spindle with three splines.
            for (t in 0 until 3) {
                rotate(t * 120f + 30f, center) {
                    drawRect(Color(0xFF8C8F94).copy(alpha = alpha), Offset(center.x - 0.5f, center.y - 3.3f), Size(1f, 3.3f))
                }
            }
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color(0xFFCDD0D4), Color(0xFF6D7075)), center - Offset(0.5f, 0.5f), 2.6f), 2.1f, center)
    drawCircle(Color(0xFF2B2C2F), 0.5f, center)
}

private fun DrawScope.drawLabel(ui: DeckUi, palette: LabelPalette, measurer: TextMeasurer, window: RoundRect, u: Float) {
    val labelRect = RoundRect(5.5f, 4f, 94.5f, 48.5f, CornerRadius(2f))
    val labelPath = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(labelRect)
        addRoundRect(RoundRect(window.left - 1f, window.top - 1f, window.right + 1f, window.bottom + 1f, CornerRadius(4f)))
    }
    val art = ui.artwork
    clipPath(labelPath) {
        if (art != null) {
            drawCroppedImage(art, Rect(labelRect.left, labelRect.top, labelRect.right, labelRect.bottom))
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Transparent, Color.Black.copy(alpha = 0.3f)), 4f, 48.5f), Offset(5.5f, 4f), Size(89f, 44.5f))
        } else {
            drawRect(palette.paper, Offset(5.5f, 4f), Size(89f, 44.5f))
            drawRect(palette.stripe, Offset(5.5f, 27f), Size(89f, 3.6f))
            drawRect(palette.stripe2, Offset(5.5f, 31.4f), Size(89f, 2.2f))
            drawRect(palette.stripe.copy(alpha = 0.55f), Offset(5.5f, 34.4f), Size(89f, 1.0f))
        }
    }
    drawPath(labelPath, Color.Black.copy(alpha = 0.18f), style = Stroke(0.2f))

    // The paper strip you write the songs on.
    val strip = RoundRect(10f, 13.4f, 90f, 19.4f, CornerRadius(0.9f))
    drawPath(Path().apply { addRoundRect(strip) }, palette.paper.copy(alpha = if (art != null) 0.94f else 1f))
    drawLine(DeckColors.PaperLine.copy(alpha = 0.7f), Offset(17.5f, 18.4f), Offset(89f, 18.4f), 0.12f)
    drawCircle(palette.ink, 2.4f, Offset(13.6f, 16.4f), style = Stroke(0.3f))
    val ink = palette.ink
    // Text is laid out in pixels and scaled down into millimetre space with the rest.
    fun text(s: String, x: Float, y: Float, sizeMm: Float, maxWidthMm: Float, style: TextStyle) {
        if (s.isBlank()) return
        val layout = measurer.measure(
            s, style.copy(fontSize = (sizeMm * u).toSp()),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (maxWidthMm * u).toInt().coerceAtLeast(1)),
        )
        withTransform({ translate(x, y); scale(1f / u, 1f / u, Offset.Zero) }) { drawText(layout) }
    }
    val hand = TextStyle(fontFamily = FontFamily.Cursive, color = ink, fontWeight = FontWeight.Bold)
    val print = TextStyle(fontFamily = FontFamily.SansSerif, color = ink, fontWeight = FontWeight.Bold, letterSpacing = (0.3f * u).toSp())
    text("A", 12.75f, 14.4f, 3.6f, 5f, print)
    val title = ui.title.ifBlank { placeholderTitle(ui) }
    val line = listOf(title, ui.artist).filter { it.isNotBlank() }.joinToString("  —  ")
    text(line, 17.5f, 13.0f, 4.6f, 71f, hand)

    val tag = TextStyle(fontFamily = FontFamily.SansSerif, color = if (art != null) Color.White else ink, fontWeight = FontWeight.Black)
    text(when (ui.source) { Source.LOCAL -> "FM 60"; Source.YT_MUSIC -> "YT ∞"; Source.APPLE_MUSIC -> "AM ∞" }, 79f, 44.2f, 2.6f, 14f, tag)
    text("TYPE I  NORMAL", 12f, 44.6f, 2.2f, 30f, tag.copy(fontWeight = FontWeight.Bold))
}

private fun placeholderTitle(ui: DeckUi) = when (ui.source) {
    Source.LOCAL -> "my mixtape"
    Source.YT_MUSIC -> "YT Music"
    Source.APPLE_MUSIC -> "Apple Music"
}

/** Centre-crops [image] to fill [dst], like a printed label. */
private fun DrawScope.drawCroppedImage(image: ImageBitmap, dst: Rect) {
    val dstRatio = dst.width / dst.height
    val srcRatio = image.width.toFloat() / image.height
    val (sw, sh) = if (srcRatio > dstRatio) (image.height * dstRatio) to image.height.toFloat() else image.width.toFloat() to (image.width / dstRatio)
    val sx = ((image.width - sw) / 2f).roundToInt()
    // Album covers keep their faces in the upper middle; bias the crop up a little.
    val sy = ((image.height - sh) * 0.4f).roundToInt()
    drawImage(
        image,
        srcOffset = IntOffset(sx, sy), srcSize = IntSize(sw.roundToInt(), sh.roundToInt()),
        dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
        dstSize = IntSize(dst.width.roundToInt().coerceAtLeast(1), dst.height.roundToInt().coerceAtLeast(1)),
    )
}

/** The trapezoid at the bottom with the head openings, capstan holes and the exposed tape. */
private fun DrawScope.drawBottomBridge(shell: Color, light: Boolean) {
    val bridge = Path().apply {
        moveTo(16.5f, 64f); lineTo(21f, 50.5f); lineTo(79f, 50.5f); lineTo(83.5f, 64f); close()
    }
    drawPath(bridge, lerp(shell, if (light) Color.Black else Color.White, 0.08f))
    drawPath(bridge, Color.Black.copy(alpha = 0.35f), style = Stroke(0.3f))
    listOf(31.5f, 68.5f).forEach { x ->
        drawCircle(Color(0xFF0E0E0F), 1.9f, Offset(x, 58.5f))
        drawCircle(Color.White.copy(alpha = 0.2f), 1.9f, Offset(x, 58.5f), style = Stroke(0.2f))
    }
    listOf(24.5f, 45.5f, 72f).forEachIndexed { i, x ->
        val w = if (i == 1) 9f else 3.5f
        drawRect(Color(0xFF0E0E0F), Offset(x, 61.2f), Size(w, 2.8f))
    }
    // The tape itself, running across the openings.
    drawRect(DeckColors.Tape, Offset(18f, 62.6f), Size(64f, 0.9f))
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
