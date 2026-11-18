package stalker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ------------------------------------------------------------------ lomos */

/**
 * Colores del lomo de cada disco, sacados de su portada una sola vez (portada reducida a 12 px)
 * y guardados en disco. Así mil lomos ocupan unos KB y nunca hay que cargar mil portadas.
 */
object Spines {
    private val mem = ConcurrentHashMap<String, Long>() // dos colores en un Long: principal | acento
    private val file get() = File(Prefs.dataDir, "lomos.tsv")
    var version by mutableIntStateOf(0); private set
    @Volatile private var loaded = false

    private fun loadFile() {
        if (loaded) return
        loaded = true
        try {
            if (file.exists()) file.readLines().forEach { l ->
                val i = l.lastIndexOf('\t')
                if (i > 0) l.substring(i + 1).toLongOrNull()?.let { mem[l.substring(0, i)] = it }
            }
        } catch (_: Throwable) {}
    }

    fun colors(a: Album): Pair<Color, Color> {
        val v = mem[a.cover]
        if (v != null) return Color((v ushr 32).toInt()) to Color(v.toInt())
        // mientras tanto, un tono cálido estable según el nombre
        val h = (a.key.hashCode() and 0x7fffffff)
        val tones = listOf(0xFF5A1E1A, 0xFF2E2A26, 0xFF6B4A2B, 0xFF233040, 0xFF3D2B3F, 0xFF4E5A3A, 0xFF7A6A55, 0xFF1F1B19)
        return Color(tones[h % tones.size]) to Color(tones[(h / 7) % tones.size]).copy(alpha = 1f)
    }

    /** Calcula en segundo plano los colores que falten (despacio, para no molestar al audio). */
    suspend fun fill(albums: List<Album>) = withContext(Dispatchers.IO) {
        loadFile()
        var changed = 0
        for (a in albums) {
            if (mem.containsKey(a.cover)) continue
            val c = try { compute(Art.bytes(a.cover)) } catch (_: Throwable) { null } ?: (0xFF3A2A22L shl 32 or 0xFF1A1310L)
            mem[a.cover] = c
            if (++changed % 12 == 0) { version++; kotlinx.coroutines.delay(15) }
        }
        if (changed > 0) {
            version++
            try { file.writeText(mem.entries.joinToString("\n") { "${it.key}\t${it.value}" }) } catch (_: Throwable) {}
        }
    }

    private fun compute(b: ByteArray?): Long? {
        if (b == null) return null
        val img = ImageIO.read(ByteArrayInputStream(b)) ?: return null
        val s = java.awt.image.BufferedImage(12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = s.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(img, 0, 0, 12, 12, null)
        g.dispose()
        var best = 0; var bestScore = -1f
        var sr = 0L; var sg = 0L; var sb = 0L
        for (y in 0 until 12) for (x in 0 until 12) {
            val p = s.getRGB(x, y)
            val r = (p shr 16) and 255; val gg = (p shr 8) and 255; val bb = p and 255
            sr += r; sg += gg; sb += bb
            val mx = max(r, max(gg, bb)); val mn = min(r, min(gg, bb))
            val sat = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
            val score = sat * 0.7f + (mx / 255f) * 0.3f - if (mx < 40) 0.5f else 0f
            if (score > bestScore) { bestScore = score; best = p }
        }
        val avg = (0xFF shl 24) or ((sr / 144).toInt() shl 16) or ((sg / 144).toInt() shl 8) or (sb / 144).toInt()
        // el lomo toma el color medio un poco más oscuro (como cartón impreso) y el acento más vivo
        fun dim(c: Int, k: Float): Int {
            val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
            val gg = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
            val bb = ((c and 255) * k).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (gg shl 8) or bb
        }
        val main = dim(avg, 0.78f)
        val acc = dim(best or (0xFF shl 24), 0.95f)
        return (main.toLong() shl 32) or (acc.toLong() and 0xFFFFFFFFL)
    }
}

/* ------------------------------------------------------------------ geometría */

const val PER_CUBBY = 30
const val COLS = 4
const val ROWS = 2
const val PER_UNIT = PER_CUBBY * COLS * ROWS

/** Medidas de un mueble de cubos en unidades del ancho del mueble. */
private object Cab {
    const val T = 0.016f                  // grosor de tabla
    val cell = (1f - T * (COLS + 1)) / COLS // lado de cada cubo
    val boxH = cell * ROWS + T * (ROWS + 1)
    const val LEGS = 0.06f
    val H = boxH + LEGS
    fun cellRect(col: Int, row: Int, w: Float) = Rect(
        Offset((T + col * (cell + T)) * w, (T + row * (cell + T)) * w),
        Size(cell * w, cell * w),
    )
}

/* ------------------------------------------------------------------ pantalla */

data class Pulled(val album: Album, val from: Rect)

@Composable
fun Shelf3D(app: AppState, modifier: Modifier = Modifier) {
    val albums = app.albums
    LaunchedEffect(albums) { Spines.fill(albums) }
    val q = app.query.trim().lowercase()
    val units = max(1, (albums.size + PER_UNIT - 1) / PER_UNIT)
    var unit by remember { mutableIntStateOf(0) }
    if (unit >= units) unit = units - 1
    // al buscar, la estantería viaja al primer disco que coincide
    LaunchedEffect(q, albums) {
        if (q.isEmpty()) return@LaunchedEffect
        val i = albums.indexOfFirst { matches(it, q) }
        if (i >= 0) unit = i / PER_UNIT
    }
    val pan by animateFloatAsState(unit.toFloat(), tween(650, easing = FastOutSlowInEasing))
    val tm = rememberTextMeasurer()
    var pulled by remember { mutableStateOf<Pulled?>(null) }
    val nowKey = app.player.queue.firstOrNull()?.albumKey

    BoxWithConstraints(modifier.fillMaxSize()) {
        val availW = maxWidth.value
        val availH = maxHeight.value
        val cabW = min(availW * 0.80f, availH * 0.80f / Cab.H)
        val cabH = cabW * Cab.H
        val left = (availW - cabW) / 2
        val top = (availH - cabH) / 2 + availH * 0.05f
        val gap = availW

        Box(Modifier.fillMaxSize().pointerInput(units) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.type == PointerEventType.Scroll) {
                        val d = e.changes.first().scrollDelta
                        val v = if (abs(d.x) > abs(d.y)) d.x else d.y
                        if (v > 0.5f && unit < units - 1) unit++ else if (v < -0.5f && unit > 0) unit--
                    }
                }
            }
        }) {
            for (u in 0 until units) {
                if (abs(u - pan) > 1.2f) continue // solo se arman los muebles cerca de la cámara
                val slice = albums.subList(u * PER_UNIT, min(albums.size, (u + 1) * PER_UNIT))
                Box(
                    Modifier.offset(left.dp, top.dp).size(cabW.dp, cabH.dp)
                        .graphicsLayer {
                            translationX = (u - pan) * gap * density
                            val d = abs(u - pan)
                            alpha = 1f - (d * 0.6f).coerceIn(0f, 0.9f)
                            scaleX = 1f - d * 0.08f; scaleY = scaleX
                        }
                ) {
                    Cabinet(Modifier.fillMaxSize())
                    for (c in 0 until COLS * ROWS) {
                        val from = c * PER_CUBBY
                        val items = if (from < slice.size) slice.subList(from, min(slice.size, from + PER_CUBBY)) else emptyList()
                        val col = c % COLS
                        val row = c / COLS
                        val r = Cab.cellRect(col, row, cabW)
                        Cubby(
                            items, tm, q, nowKey, app.favorites, app.shelfMode == 1,
                            Modifier.offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp),
                        ) { a, rect -> pulled = Pulled(a, rect) }
                    }
                    // en exhibición sobre el mueble: lo que suena o el último que llegó
                    val show = albums.firstOrNull { it.key == nowKey } ?: albums.maxByOrNull { it.added }
                    if (u == unit && show != null) Display(show, nowKey == show.key, cabW, Modifier.offset((cabW * 0.70f).dp, (-cabW * 0.155f).dp)) {
                        pulled = Pulled(show, Rect(Offset.Zero, Size.Zero))
                    }
                }
            }
            // flechas cuando hay más de un mueble
            if (units > 1) {
                if (unit > 0) RoundButton(Ico.Prev, { unit-- }, 46.dp, 16.dp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 18.dp), hoverBg = Color(0x33F1DFC4))
                if (unit < units - 1) RoundButton(Ico.Next, { unit++ }, 46.dp, 16.dp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp), hoverBg = Color(0x33F1DFC4))
            }
            // contador
            val inUnit = (albums.size - unit * PER_UNIT).coerceIn(0, PER_UNIT)
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(
                    (if (units > 1) "Estantería ${unit + 1} de $units  ·  " else "") + "$inUnit de $PER_UNIT discos",
                    11, FontWeight.Medium, C.Crema.copy(alpha = 0.8f), spacing = 0.6f,
                )
                if (albums.size < PER_UNIT / 3) {
                    Spacer(Modifier.height(3.dp))
                    T("Cada FLAC que agregues encuentra su lugar aquí.", 10, color = C.Tenue)
                }
            }
        }

        pulled?.let { p ->
            PulledOut(app, p, Modifier.fillMaxSize()) { pulled = null }
        }
    }
}

private fun matches(a: Album, q: String) =
    a.title.lowercase().contains(q) || a.artist.lowercase().contains(q) || a.tracks.any { it.title.lowercase().contains(q) }

/** El mueble de nogal con sus cubos, patas y sombra: se pinta una vez. */
@Composable
private fun Cabinet(modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val pad = (size.width * 0.06f).toInt()
        val img = ImageBitmap(size.width.toInt() + pad * 2, (size.height + pad * 2).toInt())
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), Size(img.width.toFloat(), img.height.toFloat())) {
            withTransform({ translate(pad.toFloat(), pad.toFloat()) }) { drawCabinet(size.width - pad * 2, Res.woodBack, Res.plank) }
        }
        onDrawBehind { drawImage(img, topLeft = Offset(-pad.toFloat(), -pad.toFloat())) }
    })
}


private fun DrawScope.drawCabinet(w: Float, back: ImageBitmap, plank: ImageBitmap) {
    val t = Cab.T * w
    val boxH = Cab.boxH * w
    val legH = Cab.LEGS * w
    // sombra sobre el piso
    for (k in 0 until 6) {
        val g = k * w * 0.012f
        drawOval(Color.Black.copy(alpha = 0.10f), Offset(-g, boxH + legH - w * 0.02f - g * 0.3f), Size(w + g * 2, w * 0.06f + g * 0.6f))
    }
    // patas de metal negro
    for (x in listOf(0.08f, 0.5f, 0.92f)) {
        val cx = x * w
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF0E0D0D), Color(0xFF3B3836), Color(0xFF0E0D0D)), startX = cx - w * 0.008f, endX = cx + w * 0.008f),
            Offset(cx - w * 0.008f, boxH), Size(w * 0.016f, legH * 0.92f))
        drawRoundRect(Color(0xFF151313), Offset(cx - w * 0.035f, boxH + legH * 0.86f), Size(w * 0.07f, legH * 0.14f), CornerRadius(w * 0.004f))
    }
    val backBrush = ShaderBrush(ImageShader(back, TileMode.Repeated, TileMode.Repeated))
    val frontBrush = ShaderBrush(ImageShader(plank, TileMode.Repeated, TileMode.Repeated))
    // caja exterior (canto de las tablas)
    drawRect(frontBrush, Offset.Zero, Size(w, boxH))
    drawRect(Color(0x55000000), Offset.Zero, Size(w, boxH))
    drawRect(Brush.verticalGradient(listOf(Color(0x2AFFE2C0), Color(0x00000000)), endY = t * 1.2f), Offset.Zero, Size(w, t * 1.2f))
    // cada cubo: fondo hundido con paredes en perspectiva hacia el centro del mueble
    val vp = Offset(w * 0.5f, boxH * 0.45f)
    for (row in 0 until ROWS) for (col in 0 until COLS) {
        val r = Cab.cellRect(col, row, w)
        val depth = 0.14f
        val inner = Rect(
            Offset(r.left + (vp.x - r.left) * depth * 0.35f + r.width * depth * 0.5f, r.top + (vp.y - r.top) * depth * 0.35f + r.height * depth * 0.5f),
            Size(r.width * (1 - depth), r.height * (1 - depth)),
        )
        // fondo del cubo
        drawRect(backBrush, r.topLeft, r.size)
        drawRect(Color(0x99000000), r.topLeft, r.size)
        drawRect(backBrush, inner.topLeft, inner.size, alpha = 0.9f)
        drawRect(Color(0x77000000), inner.topLeft, inner.size)
        // paredes: techo (oscuro), piso (con luz), laterales
        fun face(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, frontBrush)
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close() }, color)
        }
        face(r.topLeft, r.topRight, inner.topRight, inner.topLeft, Color(0xC0000000))
        face(r.bottomLeft, r.bottomRight, inner.bottomRight, inner.bottomLeft, Color(0x55000000))
        face(r.topLeft, inner.topLeft, inner.bottomLeft, r.bottomLeft, Color(0x88000000))
        face(r.topRight, inner.topRight, inner.bottomRight, r.bottomRight, Color(0x99000000))
        // sombra que cae del techo del cubo
        drawRect(Brush.verticalGradient(listOf(Color(0xAA000000), Color(0x00000000)), startY = r.top, endY = r.top + r.height * 0.3f), r.topLeft, Size(r.width, r.height * 0.3f))
        // filo iluminado de la tabla inferior
        drawRect(Color(0x30FFE2C0), Offset(r.left, r.bottom), Size(r.width, 1.2f))
    }
    // bisel exterior
    drawRect(Color(0x26FFE2C0), Offset.Zero, Size(w, boxH), style = Stroke(1.2f))
}

/* ------------------------------------------------------------------ un cubo con sus discos */

@Composable
private fun Cubby(
    items: List<Album>, tm: TextMeasurer, q: String, nowKey: String?, favs: Set<String>, onlyFavs: Boolean,
    modifier: Modifier, onPull: (Album, Rect) -> Unit,
) {
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val focus = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Canvas(
        modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerHoverIcon(if (items.isEmpty()) androidx.compose.ui.input.pointer.PointerIcon.Default else Hand)
            .pointerInput(items) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: continue
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move -> {
                                if (hoverX == null) scope.launch { focus.animateTo(1f, tween(220)) }
                                hoverX = ch.position.x / size.width
                            }
                            PointerEventType.Exit -> {
                                scope.launch { focus.animateTo(0f, tween(260)); hoverX = null }
                            }
                            PointerEventType.Press -> {
                                val hh = size.height.toFloat()
                                val l = layoutSpines(items.size, size.width.toFloat(), hh, hoverX, focus.value)
                                val i = l.indexAt(ch.position.x + l.lean * (hh - ch.position.y))
                                if (i in items.indices) {
                                    val sx = l.x[i]
                                    onPull(items[i], Rect(Offset(bounds.left + sx, bounds.top), Size(l.w[i], bounds.height)))
                                }
                            }
                        }
                    }
                }
            }
    ) {
        if (items.isEmpty()) return@Canvas
        if (Spines.version < 0) return@Canvas
        val h = size.height
        val l = layoutSpines(items.size, size.width, h, hoverX, focus.value)
        val recH = h * 0.93f
        clipRect {
            withTransform({
                // pocos discos: se recuestan contra la pared izquierda, como en la vida real
                if (l.lean != 0f) {
                    val m = Matrix()
                    m.values[Matrix.SkewX] = l.lean
                    translate(-l.lean * h, 0f)
                    transform(m)
                }
            }) {
                for (i in items.indices) {
                    val a = items[i]
                    val (main, acc) = Spines.colors(a)
                    val x = l.x[i]
                    val w = l.w[i]
                    val lift = l.lift[i] * h * 0.07f
                    val dimmed = (q.isNotEmpty() && !matches(a, q)) || (onlyFavs && a.key !in favs)
                    val y0 = h - recH - lift
                    // cartón del lomo
                    drawRect(main, Offset(x, y0), Size(w, recH))
                    drawRect(acc, Offset(x, y0 + recH * 0.10f), Size(w, recH * 0.035f))
                    drawRect(acc.copy(alpha = 0.7f), Offset(x, y0 + recH * 0.86f), Size(w, recH * 0.02f))
                    // volumen: luz a la izquierda, sombra a la derecha
                    drawRect(Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF), Color(0x00000000), Color(0x66000000)), startX = x, endX = x + w),
                        Offset(x, y0), Size(w, recH))
                    drawRect(Color(0x55000000), Offset(x + w - 0.8f, y0), Size(0.8f, recH))
                    // desgaste arriba
                    drawRect(Color(0x22FFFFFF), Offset(x, y0), Size(w, 1.2f))
                    if (a.key == nowKey) drawRect(C.RojoVivo, Offset(x + w * 0.25f, y0 + recH * 0.03f), Size(w * 0.5f, w * 0.5f))
                    if (a.key in favs && !onlyFavs) drawRect(C.Oro.copy(alpha = 0.9f), Offset(x, y0 + recH - 3f), Size(w, 2f))
                    // nombre vertical cuando el lomo se agranda lo suficiente
                    if (w > 13f) {
                        val ts = (w * 0.42f / density).coerceIn(7f, 12f)
                        val tl = tm.measure(a.title.uppercase().take(40), txt(ts.sp, FontWeight.SemiBold, C.Crema.copy(alpha = 0.92f), (ts * 0.08f).sp), maxLines = 1)
                        val maxLen = recH * 0.62f
                        rotate(-90f, Offset(x + w / 2, y0 + recH * 0.5f)) {
                            clipRect(x + w / 2 - maxLen / 2, y0 + recH * 0.5f - w / 2, x + w / 2 + maxLen / 2, y0 + recH * 0.5f + w / 2) {
                                drawText(tl, topLeft = Offset(x + w / 2 - min(tl.size.width.toFloat(), maxLen) / 2, y0 + recH * 0.5f - tl.size.height / 2f))
                            }
                        }
                    }
                    if (dimmed) drawRect(Color(0xB0000000), Offset(x, y0), Size(w, recH))
                }
            }
        }
        // sombra bajo los discos sobre la tabla
        drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0x55000000)), startY = h * 0.9f, endY = h), Offset(0f, h * 0.9f), Size(size.width, h * 0.1f))
    }
}

private class SpineLayout(val x: FloatArray, val w: FloatArray, val lift: FloatArray, val lean: Float) {
    fun indexAt(px: Float): Int {
        for (i in x.indices) if (px >= x[i] && px < x[i] + w[i]) return i
        return if (x.isNotEmpty() && px >= x.last()) x.size - 1 else -1
    }
}

/**
 * Ancho y posición de cada lomo. Cerca del mouse los lomos crecen en curva (como el Dock de Mac)
 * y se levantan un poco; el resto se aparta para dejarles espacio.
 */
private fun layoutSpines(n: Int, width: Float, height: Float, hover: Float?, focus: Float): SpineLayout {
    val base = width * 0.94f / PER_CUBBY
    val w = FloatArray(n) { base }
    val lift = FloatArray(n)
    if (hover != null && focus > 0f && n > 0) {
        val center = (hover * width) / base
        for (i in 0 until n) {
            val d = (i + 0.5f) - center
            val g = exp(-(d * d) / (2f * 2.2f * 2.2f))
            w[i] = base * (1f + 1.9f * g * focus)
            lift[i] = g * focus
        }
    }
    val total = w.sum()
    // si no se llena el cubo, los discos se recuestan; el ángulo depende del espacio libre
    val free = width * 0.96f - n * base
    val lean = if (n in 1 until PER_CUBBY - 2) (free / width).coerceIn(0f, 1f).let { f -> (sqrt(f) * 0.42f).coerceAtMost(0.38f) } else 0f
    val x = FloatArray(n)
    var acc = if (total > width) (width - total) * ((hover ?: 0.5f).coerceIn(0f, 1f)) else width * 0.02f + lean * height * 0.93f
    for (i in 0 until n) { x[i] = acc; acc += w[i] }
    return SpineLayout(x, w, lift, lean)
}

/* ------------------------------------------------------------------ disco en exhibición */

@Composable
private fun Display(a: Album, playing: Boolean, cabW: Float, modifier: Modifier, onClick: () -> Unit) {
    val s = cabW * 0.15f
    Box(
        modifier.size((s * 1.35f).dp, s.dp)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        val art = rememberArt(a.cover)
        // vinilo asomando detrás
        Canvas(Modifier.offset((s * 0.32f).dp, 0.dp).size(s.dp)) {
            val c = center
            val r = size.minDimension * 0.47f
            drawCircle(Color(0xFF0C0B0B), r, c)
            for (i in 0 until 10) drawCircle(Color.White.copy(alpha = 0.04f), r * (0.45f + i * 0.05f), c, style = Stroke(0.7f))
            drawCircle(if (art != null) C.Rojo else C.Rojo, r * 0.3f, c)
        }
        // funda apoyada contra la pared
        Box(Modifier.size(s.dp).graphicsLayer {
            rotationZ = -4f
            shadowElevation = 10.dp.toPx()
            shape = RoundedCornerShape(2.dp)
            clip = true
        }) {
            Cover(a.cover, Modifier.fillMaxSize(), radius = 2.dp)
        }
        if (playing) Box(Modifier.align(Alignment.BottomStart).offset(y = 14.dp).clip(RoundedCornerShape(4.dp)).background(C.Rojo).padding(horizontal = 6.dp, vertical = 2.dp)) {
            T("SONANDO", 8, FontWeight.SemiBold, C.Crema, spacing = 1f)
        }
    }
}

/* ------------------------------------------------------------------ disco sacado */

/** El disco sale del lomo hacia ti, gira y muestra su portada. */
@Composable
private fun PulledOut(app: AppState, p: Pulled, modifier: Modifier, onClose: () -> Unit) {
    val a = p.album
    val k = remember(p) { Animatable(0f) }
    LaunchedEffect(p) { k.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
    val scope = rememberCoroutineScope()
    fun close() { scope.launch { k.animateTo(0f, tween(260)); onClose() } }
    val art = rememberArt(a.cover, 520)
    BoxWithConstraints(
        modifier
            .graphicsLayer { alpha = 1f }
            .background(Color.Black.copy(alpha = 0f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black.copy(alpha = 0.62f * k.value)) }
        val cover = min(maxWidth.value * 0.3f, maxHeight.value * 0.52f)
        Row(
            Modifier.align(Alignment.Center).graphicsLayer {
                val v = k.value
                val fromX = if (p.from.width > 0) p.from.center.x - (this@BoxWithConstraints.constraints.maxWidth / 2f) else 0f
                val fromY = if (p.from.width > 0) p.from.center.y - (this@BoxWithConstraints.constraints.maxHeight / 2f) else 0f
                translationX = fromX * (1 - v)
                translationY = fromY * (1 - v)
                rotationY = 78f * (1 - v)
                scaleX = 0.25f + 0.75f * v; scaleY = scaleX
                alpha = (v * 1.6f).coerceAtMost(1f)
                cameraDistance = 18f * density
            }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((cover * 1.32f).dp, cover.dp)) {
                // vinilo que se asoma de la funda
                Canvas(Modifier.offset((cover * 0.30f).dp, 0.dp).size(cover.dp).graphicsLayer {
                    translationX = (cover * 0.06f) * k.value * density
                    rotationZ = 40f * k.value
                }) {
                    val c = center
                    val r = size.minDimension * 0.48f
                    drawCircle(Color(0xAA000000), r, c + Offset(4f, 10f))
                    drawCircle(Color(0xFF0C0B0B), r, c)
                    for (i in 0 until 22) drawCircle(Color.White.copy(alpha = 0.03f + (i % 3) * 0.012f), r * (0.38f + i * 0.028f), c, style = Stroke(0.8f))
                    drawCircle(Brush.sweepGradient(0f to Color(0x00FFFFFF), 0.1f to Color(0x22FFFFFF), 0.2f to Color(0x00FFFFFF), 0.6f to Color(0x00FFFFFF), 0.7f to Color(0x18FFFFFF), 0.8f to Color(0x00FFFFFF), center = c), r * 0.7f, c, style = Stroke(r * 0.58f))
                    val lr = r * 0.33f
                    clipPath(Path().apply { addOval(Rect(c, lr)) }) {
                        if (art != null) drawCover(art, topLeft = c - Offset(lr, lr), w = lr * 2, h = lr * 2) else drawCircle(C.Rojo, lr, c)
                    }
                    drawCircle(C.Crema, r * 0.022f, c)
                }
                Box(Modifier.size(cover.dp).graphicsLayer {
                    shadowElevation = 28.dp.toPx(); shape = RoundedCornerShape(3.dp); clip = true
                }) { Cover(a.cover, Modifier.fillMaxSize(), radius = 3.dp) }
            }
            Spacer(Modifier.width(34.dp))
            Column(Modifier.width(300.dp)) {
                T(a.artist.uppercase(), 11, FontWeight.SemiBold, C.Tenue, spacing = 1.6f)
                Spacer(Modifier.height(6.dp))
                T(a.title, 28, FontWeight.SemiBold, maxLines = 3)
                Spacer(Modifier.height(8.dp))
                T(listOfNotNull(a.year.ifBlank { null }, "${a.tracks.size} canciones", fmtLong(a.durationMs)).joinToString("  ·  "), 12, color = C.Tenue)
                Spacer(Modifier.height(4.dp))
                T(a.tracks.first().quality, 11, color = if (a.hiRes) C.Oro else C.Apagado)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Poner en el tocadiscos", { onClose(); app.placeOnDeck(a) }, active = true, ico = Ico.Room)
                    Pill("Reproducir", { onClose(); app.playAlbum(a) }, ico = Ico.Play)
                }
                Spacer(Modifier.height(10.dp))
                val fav = a.key in app.favorites
                Pill(if (fav) "En favoritos" else "Agregar a favoritos", { app.toggleFavorite(a.key) }, ico = if (fav) Ico.HeartOn else Ico.Heart)
                Spacer(Modifier.height(18.dp))
                a.tracks.take(8).forEachIndexed { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        T("${i + 1}", 11, color = C.Apagado, modifier = Modifier.width(22.dp))
                        T(t.title, 11, color = C.Crema.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                        T(fmt(t.durationMs), 11, color = C.Apagado)
                    }
                }
                if (a.tracks.size > 8) T("y ${a.tracks.size - 8} más…", 10, color = C.Apagado)
            }
        }
    }
}
