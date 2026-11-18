package stalker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import java.awt.Cursor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/**
 * Geometría del tocadiscos en unidades del ancho (W = 1).
 * Todo se calcula aquí para que la aguja caiga exactamente donde el usuario la ve.
 */
object Deck {
    const val H = 0.74f
    val C = Offset(0.385f, 0.37f)        // centro del plato
    const val R_PLATTER = 0.336f
    const val R_VINYL = 0.322f
    const val R_OUTER = 0.311f           // primer surco con música
    const val R_INNER = 0.128f           // último surco
    const val R_LABEL = 0.105f
    val P = Offset(0.855f, 0.15f)        // pivote del brazo
    const val L = 0.5f                   // largo efectivo del brazo
    val D = hypot(C.x - P.x, C.y - P.y)
    val A0 = atan2(C.y - P.y, C.x - P.x)
    const val REST = 1.66f               // ángulo de descanso (rad)
    const val MAX = 2.52f

    fun tip(phi: Float) = Offset(P.x + L * cos(phi), P.y + L * sin(phi))

    fun phiFor(r: Float): Float {
        val c = ((L * L + D * D - r * r) / (2 * L * D)).coerceIn(-1f, 1f)
        return A0 - acos(c)
    }

    fun rFor(phi: Float): Float {
        val t = tip(phi)
        return hypot(t.x - C.x, t.y - C.y)
    }

    fun fracFor(r: Float) = ((R_OUTER - r) / (R_OUTER - R_INNER)).coerceIn(0f, 1f)
    fun rForFrac(f: Float) = R_OUTER - f.coerceIn(0f, 1f) * (R_OUTER - R_INNER)
}

private fun DrawScope.softCircle(center: Offset, radius: Float, color: Color, blur: Float) {
    drawIntoCanvas { c ->
        val p = Paint()
        p.color = color
        if (blur > 0.5f) p.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur)
        c.drawCircle(center, radius, p)
    }
}

private fun DrawScope.softPath(path: Path, color: Color, blur: Float) {
    drawIntoCanvas { c ->
        val p = Paint()
        p.color = color
        if (blur > 0.5f) p.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blur)
        c.drawPath(path, p)
    }
}

private fun DrawScope.softRRect(rect: Rect, radius: Float, color: Color, blur: Float) {
    softPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(radius))) }, color, blur)
}

/** Lo que dice la lupa cuando la aguja está sobre un punto del disco. */
private data class Spot(val track: Int, val ms: Long, val albumMs: Long, val snapped: Boolean)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Turntable(player: Player, art: ImageBitmap?, modifier: Modifier = Modifier, tilt: Float = 9f) {
    val queue = player.queue
    val total = player.albumDurationMs.coerceAtLeast(1)
    val bounds = remember(queue) {
        var acc = 0L
        queue.map { t -> acc.also { acc += t.durationMs } }
    }
    var peaksVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(queue) {
        withContext(Dispatchers.IO) {
            for (t in queue) {
                if (Peaks.get(t.path) == null && Peaks.load(t) != null) peaksVersion++
            }
        }
    }
    // brillo de cada surco según la música real que contiene
    val rings = remember(queue, peaksVersion) {
        val n = 150
        FloatArray(n) { i ->
            if (queue.isEmpty()) 0.35f else {
                val f0 = i / n.toFloat()
                val f1 = (i + 1) / n.toFloat()
                val ms0 = (f0 * total).toLong()
                val ms1 = (f1 * total).toLong()
                val k = bounds.indexOfLast { it <= ms0 }.coerceAtLeast(0)
                val t = queue[k]
                val pk = Peaks.get(t.path)
                if (pk == null) 0.35f else {
                    val a = ((ms0 - bounds[k]).toFloat() / t.durationMs * Peaks.N).toInt().coerceIn(0, Peaks.N - 1)
                    val b = ((ms1 - bounds[k]).toFloat() / t.durationMs * Peaks.N).toInt().coerceIn(a + 1, Peaks.N)
                    var s = 0f
                    for (j in a until b) s += pk[j]
                    (s / (b - a)).coerceIn(0f, 1f)
                }
            }
        }
    }

    // giro del plato con arranque y frenado suaves
    var spin by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player.playing) {
        var last = 0L
        while (true) {
            val target = if (player.playing) 200f else 0f // 33⅓ rpm = 200°/s
            if (target == 0f && speed < 0.5f) { speed = 0f; last = 0L; delay(120); if (!player.playing) continue }
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1e9f).coerceAtMost(0.05f)
                last = n
                val k = if (target > speed) 2.6f else 1.5f
                speed += (target - speed) * min(1f, dt * k)
                spin = (spin + speed * dt) % 360f
            }
        }
    }

    // brazo
    val arm = remember { Animatable(Deck.REST) }
    val lift = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var spot by remember { mutableStateOf<Spot?>(null) }
    val scope = rememberCoroutineScope()

    val progress = (player.albumPositionMs.toFloat() / total).coerceIn(0f, 1f)
    val engaged = queue.isNotEmpty() && (player.playing || player.positionMs > 0 || player.index > 0)
    val target = if (engaged) Deck.phiFor(Deck.rForFrac(progress)) else Deck.REST

    LaunchedEffect(target, dragging) {
        if (dragging) return@LaunchedEffect
        val d = abs(arm.value - target)
        if (d < 0.012f) arm.snapTo(target)
        else arm.animateTo(target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 60f))
    }
    LaunchedEffect(player.playing, dragging) {
        lift.animateTo(if (player.playing && !dragging) 0f else 1f, tween(if (player.playing) 900 else 350, easing = FastOutSlowInEasing))
    }

    fun spotAt(r: Float): Spot? {
        if (queue.isEmpty() || r < Deck.R_INNER - 0.006f || r > Deck.R_VINYL) return null
        var ms = (Deck.fracFor(r) * total).toLong()
        var snapped = false
        // imán: cerca del comienzo de una canción, la aguja cae justo ahí
        val tol = (total * 0.007f).toLong().coerceAtLeast(1500)
        for (b in bounds) if (abs(ms - b) < tol) { ms = b; snapped = true; break }
        if (r > Deck.R_OUTER) { ms = 0; snapped = true }
        val k = bounds.indexOfLast { it <= ms }.coerceAtLeast(0)
        return Spot(k, ms - bounds[k], ms, snapped)
    }

    val tm = rememberTextMeasurer()

    Canvas(
        modifier
            .aspectRatio(1f / Deck.H)
            .graphicsLayer {
                rotationX = tilt
                cameraDistance = 14f * density
            }
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            .pointerInput(queue) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: continue
                        val w = size.width.toFloat()
                        val m = Offset(ch.position.x / w, ch.position.y / w)
                        when (e.type) {
                            PointerEventType.Move -> {
                                hover = m
                                if (dragging) {
                                    val phi = atan2(m.y - Deck.P.y, m.x - Deck.P.x).coerceIn(Deck.REST, Deck.MAX)
                                    scope.launch { arm.snapTo(phi) }
                                    spot = spotAt(Deck.rFor(phi))
                                } else {
                                    val r = hypot(m.x - Deck.C.x, m.y - Deck.C.y)
                                    spot = if (r >= Deck.R_INNER - 0.006f) spotAt(r) else null
                                }
                            }
                            PointerEventType.Exit -> { hover = null; if (!dragging) spot = null }
                            PointerEventType.Press -> {
                                val tip = Deck.tip(arm.value)
                                val nearHead = hypot(m.x - tip.x, m.y - tip.y) < 0.075f
                                val r = hypot(m.x - Deck.C.x, m.y - Deck.C.y)
                                val start = Offset(0.085f, 0.685f)
                                val cue = Offset(0.935f, 0.43f)
                                val onArm = run {
                                    val a = Deck.P
                                    val b = tip
                                    val ab = Offset(b.x - a.x, b.y - a.y)
                                    val tt = (((m.x - a.x) * ab.x + (m.y - a.y) * ab.y) / (ab.x * ab.x + ab.y * ab.y)).coerceIn(0f, 1f)
                                    tt > 0.35f && hypot(m.x - (a.x + ab.x * tt), m.y - (a.y + ab.y * tt)) < 0.022f
                                }
                                when {
                                    (nearHead || onArm) && queue.isNotEmpty() -> { dragging = true; ch.consume() }
                                    hypot(m.x - start.x, m.y - start.y) < 0.045f -> player.toggle()
                                    hypot(m.x - cue.x, m.y - cue.y) < 0.04f -> player.toggle()
                                    r <= Deck.R_VINYL && r >= Deck.R_INNER - 0.006f && queue.isNotEmpty() -> {
                                        // un clic sobre los surcos: la aguja baja exactamente ahí
                                        val s = spotAt(r)
                                        if (s != null) player.dropNeedle(s.albumMs)
                                    }
                                    r < Deck.R_INNER - 0.006f && queue.isNotEmpty() -> player.toggle()
                                }
                            }
                            PointerEventType.Release -> {
                                if (dragging) {
                                    dragging = false
                                    val r = Deck.rFor(arm.value)
                                    val s = spotAt(r)
                                    if (s != null) {
                                        player.dropNeedle(s.albumMs)
                                        scope.launch { arm.animateTo(Deck.phiFor(Deck.rForFrac(s.albumMs.toFloat() / total)), tween(260)) }
                                    } else {
                                        player.pause()
                                        scope.launch { arm.animateTo(Deck.REST, tween(700, easing = FastOutSlowInEasing)) }
                                    }
                                }
                            }
                            PointerEventType.Scroll -> {
                                val dy = ch.scrollDelta.y
                                if (dy != 0f && queue.isNotEmpty()) player.seekBy(if (dy > 0) 5000 else -5000)
                                ch.consume()
                            }
                        }
                    }
                }
            }
    ) {
        val w = size.width
        fun o(x: Float, y: Float) = Offset(x * w, y * w)
        val h = Deck.H * w

        // sombra del mueble sobre el piso
        softRRect(Rect(o(0.03f, 0.05f), Size(w * 0.94f, h * 0.98f)), w * 0.03f, Color(0xCC000000), w * 0.035f)

        // mueble: laca negra con bisel
        val body = Rect(Offset.Zero, Size(w, h))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF241B18), Color(0xFF120D0B), Color(0xFF0A0706))), body.topLeft, body.size, CornerRadius(w * 0.022f))
        drawRoundRect(Brush.linearGradient(listOf(Color(0x22FFFFFF), Color(0x00FFFFFF), Color(0x00FFFFFF), Color(0x14FFFFFF)), start = Offset.Zero, end = Offset(w, h)),
            body.topLeft, body.size, CornerRadius(w * 0.022f))
        drawRoundRect(Color(0x30F1DFC4), body.topLeft + Offset(1.5f, 1.5f), Size(w - 3f, h - 3f), CornerRadius(w * 0.021f), style = Stroke(1.2f))
        // filo rojo
        drawRoundRect(C.Rojo.copy(alpha = 0.55f), Offset(w * 0.012f, w * 0.012f), Size(w * 0.976f, h - w * 0.024f), CornerRadius(w * 0.016f), style = Stroke(w * 0.0016f))

        // sombra del plato
        softCircle(o(Deck.C.x + 0.006f, Deck.C.y + 0.012f), Deck.R_PLATTER * w, Color(0xEE000000), w * 0.02f)

        // plato con puntos estroboscópicos
        drawCircle(Brush.sweepGradient(listOf(Color(0xFF4A4542), Color(0xFF9C958E), Color(0xFF3A3532), Color(0xFF8A847E), Color(0xFF4A4542)), o(Deck.C.x, Deck.C.y)),
            Deck.R_PLATTER * w, o(Deck.C.x, Deck.C.y))
        drawCircle(Color(0xFF151212), (Deck.R_PLATTER - 0.006f) * w, o(Deck.C.x, Deck.C.y))
        rotate(spin, o(Deck.C.x, Deck.C.y)) {
            val n = 90
            for (i in 0 until n) {
                val a = i * 2 * PI / n
                val rr = (Deck.R_PLATTER - 0.0028f) * w
                drawCircle(Color(0xFFCFC6BB), w * 0.0012f, Offset((Deck.C.x * w + rr * cos(a)).toFloat(), (Deck.C.y * w + rr * sin(a)).toFloat()))
            }
        }
        // lámpara estroboscópica (luz roja que baña el borde del plato)
        val lamp = o(0.035f, 0.58f)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FF2A1A), Color(0x00FF2A1A)), lamp, w * 0.16f), w * 0.16f, lamp)
        drawCircle(Color(0xFF2A0705), w * 0.011f, lamp)
        drawCircle(if (player.playing) Color(0xFFFF4A32) else Color(0xFF7A1A12), w * 0.006f, lamp)

        // vinilo
        val c = o(Deck.C.x, Deck.C.y)
        drawCircle(Color(0xFF0B0A0A), Deck.R_VINYL * w, c)
        // borde y entrada lisa
        drawCircle(Color(0x30FFFFFF), Deck.R_VINYL * w - 0.6f, c, style = Stroke(1.1f))
        // surcos: cada anillo brilla según la música que guarda
        val n = rings.size
        val step = (Deck.R_OUTER - Deck.R_INNER) * w / n
        for (i in 0 until n) {
            val r = Deck.R_OUTER * w - (i + 0.5f) * step
            val a = 0.018f + rings[i] * 0.075f
            drawCircle(Color.White.copy(alpha = a), r, c, style = Stroke(step * 0.55f))
        }
        // separación entre canciones (surco liso, más brillante)
        for ((k, b) in bounds.withIndex()) {
            if (k == 0) continue
            val r = Deck.rForFrac(b.toFloat() / total) * w
            drawCircle(Color(0xFF070606), r, c, style = Stroke(w * 0.0032f))
            drawCircle(Color.White.copy(alpha = 0.09f), r + w * 0.0018f, c, style = Stroke(0.8f))
            drawCircle(Color.White.copy(alpha = 0.06f), r - w * 0.0018f, c, style = Stroke(0.8f))
        }
        // brillo de la luz sobre el vinilo (no gira: la luz es de la sala)
        val ringW = (Deck.R_VINYL - Deck.R_LABEL) * w
        val ringR = (Deck.R_VINYL + Deck.R_LABEL) / 2 * w
        drawCircle(
            Brush.sweepGradient(
                0f to Color(0x00FFFFFF), 0.08f to Color(0x24FFF1DE), 0.16f to Color(0x00FFFFFF),
                0.5f to Color(0x00FFFFFF), 0.58f to Color(0x1CFFF1DE), 0.66f to Color(0x00FFFFFF), 1f to Color(0x00FFFFFF),
                center = c,
            ),
            ringR, c, style = Stroke(ringW),
        )
        // reflejos finos sobre los surcos
        for (k in 0 until 3) {
            val rr = ringR - ringW * 0.3f + k * ringW * 0.3f
            drawArc(Color(0x14FFF1DE), 205f, 22f, false, c - Offset(rr, rr), Size(rr * 2, rr * 2), style = Stroke(1.2f, cap = StrokeCap.Round))
            drawArc(Color(0x10FFF1DE), 25f, 18f, false, c - Offset(rr, rr), Size(rr * 2, rr * 2), style = Stroke(1.2f, cap = StrokeCap.Round))
        }

        // anillo donde está el cursor: muestra dónde caerá la aguja
        val sp = spot
        if (sp != null && queue.isNotEmpty()) {
            val r = Deck.rForFrac(sp.albumMs.toFloat() / total) * w
            drawCircle(C.Crema.copy(alpha = if (sp.snapped) 0.55f else 0.32f), r, c, style = Stroke(1.3f))
        }

        // etiqueta central (gira con el disco)
        rotate(spin, c) {
            val lr = Deck.R_LABEL * w
            val clip = Path().apply { addOval(Rect(c, lr)) }
            clipPath(clip) {
                if (art != null) {
                    drawCover(art, topLeft = c - Offset(lr, lr), w = lr * 2, h = lr * 2)
                    drawCircle(Brush.radialGradient(listOf(Color(0x00000000), Color(0x55000000)), c, lr), lr, c)
                } else {
                    drawCircle(C.Rojo, lr, c)
                    drawCircle(C.Crema.copy(alpha = 0.55f), lr * 0.82f, c, style = Stroke(1.2f))
                    val tl = tm.measure("STALKER", txt((lr / 7.2f / density).sp, FontWeight.SemiBold, C.Crema, (lr / 30f / density).sp))
                    drawText(tl, topLeft = c - Offset(tl.size.width / 2f, lr * 0.52f))
                    val t2 = tm.measure("33 RPM", txt((lr / 10f / density).sp, FontWeight.Medium, C.Crema.copy(alpha = 0.7f)))
                    drawText(t2, topLeft = c + Offset(-t2.size.width / 2f, lr * 0.36f))
                }
            }
            drawCircle(Color(0x40000000), lr, c, style = Stroke(1.5f))
        }
        // eje
        drawCircle(Brush.radialGradient(listOf(Color(0xFFEDE6DC), Color(0xFF7D766E)), c - Offset(w * 0.002f, w * 0.002f), w * 0.009f), w * 0.0072f, c)

        // botón de arranque, velocidades, marca
        val start = o(0.085f, 0.685f)
        drawRoundRect(Color(0xFF1E1816), start - Offset(w * 0.04f, w * 0.022f), Size(w * 0.08f, w * 0.044f), CornerRadius(w * 0.008f))
        drawRoundRect(Color(0x26F1DFC4), start - Offset(w * 0.04f, w * 0.022f), Size(w * 0.08f, w * 0.044f), CornerRadius(w * 0.008f), style = Stroke(1f))
        val stt = tm.measure("START · STOP", txt((w * 0.0105f / density).sp, FontWeight.Medium, C.Tenue, (w * 0.0008f / density).sp))
        drawText(stt, topLeft = start - Offset(stt.size.width / 2f, stt.size.height / 2f))
        val led = o(0.085f, 0.64f)
        drawCircle(if (player.playing) Color(0xFFFF3B2A) else Color(0xFF3A0D0A), w * 0.0035f, led)
        if (player.playing) drawCircle(Brush.radialGradient(listOf(Color(0x66FF3B2A), Color(0x00FF3B2A)), led, w * 0.02f), w * 0.02f, led)
        for ((k, label) in listOf("33", "45").withIndex()) {
            val bc = o(0.165f + k * 0.05f, 0.685f)
            drawRoundRect(Color(0xFF1E1816), bc - Offset(w * 0.02f, w * 0.016f), Size(w * 0.04f, w * 0.032f), CornerRadius(w * 0.006f))
            drawRoundRect(Color(0x22F1DFC4), bc - Offset(w * 0.02f, w * 0.016f), Size(w * 0.04f, w * 0.032f), CornerRadius(w * 0.006f), style = Stroke(1f))
            val tl = tm.measure(label, txt((w * 0.0115f / density).sp, FontWeight.SemiBold, if (k == 0) C.Crema else C.Apagado))
            drawText(tl, topLeft = bc - Offset(tl.size.width / 2f, tl.size.height / 2f))
            if (k == 0) drawCircle(Color(0xFFFF6A4A).copy(alpha = if (player.playing) 0.9f else 0.35f), w * 0.0022f, bc + Offset(0f, -w * 0.024f))
        }
        val brand = tm.measure("S T A L K E R", txt((w * 0.0135f / density).sp, FontWeight.SemiBold, C.Crema.copy(alpha = 0.42f), (w * 0.0012f / density).sp))
        drawText(brand, topLeft = o(0.955f, 0.69f) - Offset(brand.size.width.toFloat(), brand.size.height.toFloat()))
        val hifi = tm.measure("DIRECT DRIVE · BIT-PERFECT", txt((w * 0.0085f / density).sp, FontWeight.Medium, C.Apagado, (w * 0.0006f / density).sp))
        drawText(hifi, topLeft = o(0.955f, 0.705f) - Offset(hifi.size.width.toFloat(), 0f))

        // palanca de elevación (cue)
        val cue = o(0.935f, 0.43f)
        drawRoundRect(Color(0xFF1E1816), cue - Offset(w * 0.016f, w * 0.03f), Size(w * 0.032f, w * 0.06f), CornerRadius(w * 0.008f))
        val leverY = if (lift.value > 0.5f) -0.016f else 0.016f
        drawLine(Color(0xFFBDB5AC), cue, cue + Offset(0f, leverY * w), w * 0.006f, StrokeCap.Round)
        drawCircle(Color(0xFFE4DDD4), w * 0.0055f, cue + Offset(0f, leverY * w))

        // apoyo del brazo
        val restPost = Offset(Deck.P.x + 0.385f * cos(Deck.REST), Deck.P.y + 0.385f * sin(Deck.REST))
        drawCircle(Color(0xFF1A1513), w * 0.014f, o(restPost.x, restPost.y))
        drawCircle(Color(0xFF5D5751), w * 0.008f, o(restPost.x, restPost.y))

        // brazo
        drawTonearm(arm.value, lift.value, w)

        // lupa
        if (sp != null && queue.isNotEmpty()) {
            val hv = hover
            if (hv != null || dragging) {
                val anchor = if (dragging) Deck.tip(arm.value) else hv!!
                drawLoupe(tm, queue, sp, o(anchor.x, anchor.y), w, dragging)
            }
        }
    }
}

private fun DrawScope.drawTonearm(phi: Float, lift: Float, w: Float) {
    fun o(p: Offset) = Offset(p.x * w, p.y * w)
    val P = o(Deck.P)
    val tip = o(Deck.tip(phi))
    val psi = phi - 0.42f // cápsula girada para quedar tangente al surco
    val q = tip - Offset(cos(psi) * 0.068f * w, sin(psi) * 0.068f * w)
    val dir = Offset(q.x - P.x, q.y - P.y)
    val len = hypot(dir.x, dir.y)
    val ux = dir.x / len
    val uy = dir.y / len
    val cw = Offset(P.x - ux * 0.075f * w, P.y - uy * 0.075f * w)

    // sombra: más lejana cuando el brazo está levantado
    val sh = Offset(w * (0.008f + 0.014f * lift), w * (0.014f + 0.02f * lift))
    val shadow = Path().apply {
        moveTo(cw.x + sh.x, cw.y + sh.y); lineTo(q.x + sh.x, q.y + sh.y); lineTo(tip.x + sh.x, tip.y + sh.y)
    }
    drawIntoCanvas { c ->
        val p = Paint()
        p.color = Color(0x99000000)
        p.style = androidx.compose.ui.graphics.PaintingStyle.Stroke
        p.strokeWidth = w * 0.014f
        p.strokeCap = StrokeCap.Round
        p.asFrameworkPaint().maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, w * (0.004f + 0.006f * lift))
        c.drawPath(shadow, p)
    }

    // base del pivote
    softCircle(P + Offset(w * 0.006f, w * 0.01f), w * 0.058f, Color(0xAA000000), w * 0.012f)
    drawCircle(Brush.sweepGradient(listOf(Color(0xFF2B2522), Color(0xFF6C655F), Color(0xFF2B2522), Color(0xFF55504B), Color(0xFF2B2522)), P), w * 0.056f, P)
    drawCircle(Color(0xFF151110), w * 0.044f, P)
    drawCircle(Color(0x30F1DFC4), w * 0.044f, P, style = Stroke(1f))

    // contrapeso
    val cwEnd = Offset(P.x - ux * 0.11f * w, P.y - uy * 0.11f * w)
    drawLine(Color(0xFF9E978F), P, cwEnd, w * 0.008f, StrokeCap.Round)
    drawLine(Brush.linearGradient(listOf(Color(0xFF3A3431), Color(0xFFB8B0A6), Color(0xFF3A3431)),
        start = cwEnd + Offset(-uy * w * 0.02f, ux * w * 0.02f), end = cwEnd - Offset(-uy * w * 0.02f, ux * w * 0.02f)),
        cw, cwEnd, w * 0.036f, StrokeCap.Butt)
    for (i in 1..5) {
        val f = 0.08f + i * 0.006f
        val a = Offset(P.x - ux * f * w, P.y - uy * f * w)
        drawLine(Color(0x40000000), a + Offset(-uy * w * 0.018f, ux * w * 0.018f), a - Offset(-uy * w * 0.018f, ux * w * 0.018f), 1f)
    }

    // tubo cromado
    val nx = -uy
    val ny = ux
    drawLine(Brush.linearGradient(listOf(Color(0xFF6E6862), Color(0xFFF2ECE4), Color(0xFF8A847D)),
        start = P + Offset(nx * w * 0.006f, ny * w * 0.006f), end = P - Offset(nx * w * 0.006f, ny * w * 0.006f)),
        P, q, w * 0.0105f, StrokeCap.Round)
    // anillo de unión
    drawCircle(Color(0xFFD9D2C8), w * 0.0085f, q)

    // cápsula y cabezal
    val hx = cos(psi)
    val hy = sin(psi)
    val head = Path().apply {
        val hw = 0.017f * w
        val a = q + Offset(-hy * hw, hx * hw)
        val b = q - Offset(-hy * hw, hx * hw)
        val e = tip + Offset(hx * 0.012f * w, hy * 0.012f * w)
        moveTo(a.x, a.y)
        lineTo(e.x + -hy * hw, e.y + hx * hw)
        lineTo(e.x - -hy * hw, e.y - hx * hw)
        lineTo(b.x, b.y)
        close()
    }
    drawPath(head, Brush.linearGradient(listOf(Color(0xFF2A2421), Color(0xFF4C4541)), start = q, end = tip))
    drawPath(head, Color(0x40F1DFC4), style = Stroke(1f))
    // cápsula roja
    val cap = Path().apply {
        val hw = 0.011f * w
        val a = tip - Offset(hx * 0.03f * w, hy * 0.03f * w)
        val e = tip + Offset(hx * 0.004f * w, hy * 0.004f * w)
        moveTo(a.x - hy * hw, a.y + hx * hw)
        lineTo(e.x - hy * hw, e.y + hx * hw)
        lineTo(e.x + hy * hw, e.y - hx * hw)
        lineTo(a.x + hy * hw, a.y - hx * hw)
        close()
    }
    drawPath(cap, C.Rojo)
    // levantador
    val fl = q + Offset(-hy * 0.024f * w, hx * 0.024f * w)
    drawLine(Color(0xFFBDB5AC), q, fl + Offset(hx * 0.012f * w, hy * 0.012f * w), w * 0.004f, StrokeCap.Round)
    // punta
    drawCircle(Color(0xFFF1DFC4).copy(alpha = 0.85f - lift * 0.4f), w * 0.0022f, tip)
    // tapa del pivote
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF4EEE6), Color(0xFF6F6962)), P - Offset(w * 0.006f, w * 0.006f), w * 0.026f), w * 0.022f, P)
}

private fun DrawScope.drawLoupe(tm: TextMeasurer, queue: List<Track>, s: Spot, at: Offset, w: Float, dragging: Boolean) {
    val t = queue.getOrNull(s.track) ?: return
    val title = tm.measure("${s.track + 1}. ${t.title}".take(46), txt((w * 0.0145f / density).coerceAtLeast(11f).sp, FontWeight.SemiBold, C.Crema))
    val sub = tm.measure(
        (if (s.snapped) "Inicio de la canción" else fmt(s.ms) + " / " + fmt(t.durationMs)) + if (dragging) "  ·  suelta para bajar la aguja" else "  ·  clic para bajar la aguja",
        txt((w * 0.0118f / density).coerceAtLeast(9.5f).sp, FontWeight.Medium, C.Tenue),
    )
    val pad = w * 0.012f
    val bw = max(title.size.width, sub.size.width) + pad * 2
    val bh = title.size.height + sub.size.height + pad * 1.6f
    var x = at.x + w * 0.025f
    var y = at.y - bh - w * 0.02f
    if (x + bw > size.width) x = at.x - bw - w * 0.025f
    if (y < 0) y = at.y + w * 0.03f
    softRRect(Rect(Offset(x + 2, y + 6), Size(bw, bh)), w * 0.01f, Color(0x99000000), w * 0.012f)
    drawRoundRect(Color(0xF0140D0B), Offset(x, y), Size(bw, bh), CornerRadius(w * 0.01f))
    drawRoundRect(Color(0x33F1DFC4), Offset(x, y), Size(bw, bh), CornerRadius(w * 0.01f), style = Stroke(1f))
    drawRect(C.Rojo, Offset(x, y + w * 0.01f), Size(2.5f, bh - w * 0.02f))
    translate(x + pad, y + pad * 0.8f) {
        drawText(title)
        drawText(sub, topLeft = Offset(0f, title.size.height + pad * 0.1f))
    }
}
