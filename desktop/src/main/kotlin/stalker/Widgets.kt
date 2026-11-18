package stalker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import java.awt.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Hand = PointerIcon(Cursor(Cursor.HAND_CURSOR))

@Composable
fun T(text: String, size: Int = 13, weight: FontWeight = FontWeight.Normal, color: Color = C.Crema, modifier: Modifier = Modifier, maxLines: Int = 1, spacing: Float = 0f) {
    BasicText(text, modifier, style = txt(size.sp, weight, color, spacing.sp), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Botón redondo discreto que se ilumina al pasar el mouse. */
@Composable
fun RoundButton(ico: Ico, onClick: () -> Unit, size: Dp = 34.dp, iconSize: Dp = 16.dp, tint: Color = C.Crema, hoverBg: Color = Color(0x1FF1DFC4), modifier: Modifier = Modifier, filled: Color? = null) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val a by animateFloatAsState(if (hovered) 1f else 0f, tween(160))
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(filled ?: hoverBg.copy(alpha = hoverBg.alpha * a))
            .hoverable(src)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(ico, tint, iconSize) }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, ico: Ico? = null) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) C.Crema else if (hovered) Color(0x22F1DFC4) else Color(0x12F1DFC4))
            .border(1.dp, if (active) C.Crema else C.Linea, RoundedCornerShape(50))
            .hoverable(src)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (ico != null) {
                Icon(ico, if (active) C.Sombra else C.Crema, 13.dp)
                androidx.compose.foundation.layout.Spacer(Modifier.size(7.dp))
            }
            T(text, 12, FontWeight.Medium, if (active) C.Sombra else C.Crema)
        }
    }
}

/** Barra fina que se puede arrastrar o tocar. */
@Composable
fun ThinSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, onDone: (Float) -> Unit = {}, color: Color = C.Crema, height: Dp = 18.dp) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val v = dragging ?: value
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(src)
            .pointerHoverIcon(Hand)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val c = down.changes.firstOrNull() ?: continue
                        if (!c.pressed) continue
                        var f = (c.position.x / size.width).coerceIn(0f, 1f)
                        dragging = f
                        onChange(f)
                        c.consume()
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            f = (ch.position.x / size.width).coerceIn(0f, 1f)
                            dragging = f
                            onChange(f)
                            ch.consume()
                            if (!ch.pressed) break
                        }
                        onDone(f)
                        dragging = null
                    }
                }
            }
    ) {
        val y = size.height / 2
        val th = if (hovered || dragging != null) 4.dp.toPx() else 2.5.dp.toPx()
        drawRoundRect(Color(0x26F1DFC4), Offset(0f, y - th / 2), Size(size.width, th), CornerRadius(th))
        drawRoundRect(color, Offset(0f, y - th / 2), Size(size.width * v, th), CornerRadius(th))
        if (hovered || dragging != null) drawCircle(color, 6.dp.toPx(), Offset(size.width * v, y))
    }
}

@Composable
fun rememberArt(path: String?, px: Int = 520): ImageBitmap? {
    val st by produceState(if (path != null) Art.cached(path) else null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) { Art.load(path, px) }
    }
    return st
}

/** Portada o, si no hay, el piso en zigzag de la red room. */
@Composable
fun Cover(path: String?, modifier: Modifier, title: String = "", radius: Dp = 4.dp) {
    val art = rememberArt(path)
    Canvas(modifier.clip(RoundedCornerShape(radius))) {
        if (art != null) drawCover(art)
        else {
            drawCover(Res.zigzag)
            drawRect(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x99000000))))
        }
    }
}

/** Vinilo pequeño que gira (lo usan la barra inferior y el modo mini). */
@Composable
fun MiniVinyl(art: ImageBitmap?, playing: Boolean, modifier: Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        var last = 0L
        while (playing || speed > 0.5f) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1e9f).coerceAtMost(0.05f)
                last = n
                val target = if (playing) 200f else 0f
                speed += (target - speed) * (dt * if (playing) 2.4f else 1.6f).coerceAtMost(1f)
                angle = (angle + speed * dt) % 360f
            }
        }
        speed = 0f
    }
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Color(0xAA000000), r, c + Offset(0f, r * 0.06f))
        drawCircle(Color(0xFF0C0B0B), r, c)
        for (i in 0 until 14) drawCircle(Color.White.copy(alpha = 0.035f + (i % 3) * 0.012f), r * (0.42f + i * 0.04f), c, style = Stroke(0.8f))
        drawCircle(Brush.sweepGradient(0f to Color(0x00FFFFFF), 0.1f to Color(0x22FFFFFF), 0.2f to Color(0x00FFFFFF), 0.6f to Color(0x00FFFFFF), 0.7f to Color(0x18FFFFFF), 0.8f to Color(0x00FFFFFF), center = c), r * 0.7f, c, style = Stroke(r * 0.6f))
        rotate(angle, c) {
            val lr = r * 0.36f
            clipPath(Path().apply { addOval(Rect(c, lr)) }) {
                if (art != null) drawCover(art, topLeft = c - Offset(lr, lr), w = lr * 2, h = lr * 2)
                else {
                    drawCircle(C.Rojo, lr, c)
                    drawCircle(C.Crema.copy(alpha = 0.5f), lr * 0.7f, c, style = Stroke(1f))
                }
            }
            drawCircle(Color(0xFF0C0B0B), r * 0.03f, c + Offset(lr * 0.62f, 0f))
        }
        drawCircle(C.Crema.copy(alpha = 0.9f), r * 0.035f, c)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xE61A110E), Color(0xF00E0907))))
            .border(1.dp, Color(0x1FF1DFC4), RoundedCornerShape(18.dp))
    ) { content() }
}
