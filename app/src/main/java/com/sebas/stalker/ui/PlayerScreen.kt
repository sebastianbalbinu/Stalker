package com.sebas.stalker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.sebas.stalker.tr
import com.sebas.stalker.Engine
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.R
import com.sebas.stalker.Track
import kotlin.math.min

@Composable
fun PlayerScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    val track by vm.current.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val pos by vm.positionMs.collectAsState()
    val dur by vm.durationMs.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val repeat by vm.repeat.collectAsState()
    val queue by vm.queue.collectAsState()
    val index by vm.index.collectAsState()
    val info by Engine.currentInfo.collectAsState()
    val status by Engine.outputStatus.collectAsState()
    val usb by Engine.usbDevice.collectAsState()
    val queueTitle by Engine.queueTitle.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    val flipPending by Engine.flipPending.collectAsState()
    val flipAnim = remember { mutableFloatStateOf(0f) }
    var flipping by remember { mutableStateOf(false) }
    LaunchedEffect(flipping) {
        if (!flipping) return@LaunchedEffect
        var start = 0L
        var t = 0f
        while (t < 1f) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                t = ((now - start) / 800_000_000f).coerceIn(0f, 1f)
                flipAnim.floatValue = t
            }
        }
        flipAnim.floatValue = 0f
        flipping = false
        Engine.flipPending.value = false
        vm.toggle()
    }
    var showTimer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showJam by remember { mutableStateOf(false) }
    val sleepAt by Engine.sleepAt.collectAsState()
    val sleepEnd by Engine.sleepEndOfDisco.collectAsState()
    val tags by Engine.currentTags.collectAsState()
    val rgStatus by Engine.replayGainStatus.collectAsState()
    val route by Engine.route.collectAsState()
    val routeName by Engine.routeName.collectAsState()
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding().navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Down, tr("Volver a la biblioteca"), onClick = onClose)
            Spacer(Modifier.width(10.dp))
            SoftButton(Ico.Moon, tr("Temporizador"), tint = if (sleepAt != null || sleepEnd) C.Rojo else C.Carbon) { showTimer = true }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Wordmark() }
            SoftButton(Ico.Lyrics, tr("Letra")) { showLyrics = true }
            Spacer(Modifier.width(10.dp))
            SoftButton(Ico.ListIco, tr("Pistas del disco")) { if (queue.isNotEmpty()) showQueue = true }
        }

        // El vinilo ocupa el espacio que sobre, sin empujar nada.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Vinyl(
                track, playing,
                Modifier.padding(vertical = 6.dp).aspectRatio(1f),
                onTap = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.toggle()
                },
                onSwipe = { forward ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (forward) vm.next() else vm.previous()
                },
                onScratchStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onScratch = { deltaMs -> vm.seek((vm.positionMs.value + deltaMs).coerceIn(0L, dur.coerceAtLeast(0L))) },
                progress = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                gaps = if (queue.isEmpty()) 6 else queue.size,
                index = index,
                discKey = queueTitle to queue.firstOrNull()?.id,
                flip = flipAnim.floatValue,
                onArmGrab = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                onNeedleDrop = { i, within ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val t = queue.getOrNull(i)
                    if (t != null) vm.dropNeedle(i, (t.durationMs * within).toLong())
                },
                onLift = { if (playing) vm.toggle() },
            )
        }

        if (flipPending && !flipping) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Rojo)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        flipping = true
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Ico.Refresh, null, tint = C.SobreRojo, modifier = Modifier.size(20.dp))
                Text(
                    tr("Terminó el Lado A · toca para darle vuelta al disco"),
                    style = Cuerpo.copy(color = C.SobreRojo, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        val t = track
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                t?.title ?: tr("Nada sonando"),
                style = Titulo.copy(fontSize = 30.sp, lineHeight = 34.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(t?.artist ?: tr("Elige un disco en tu biblioteca"), style = Cuerpo.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t != null && queue.isNotEmpty()) {
                val side = if (index < (queue.size + 1) / 2) tr("Lado A") else tr("Lado B")
                val name = queueTitle?.let { Engine.displayTitle(it) } ?: t.album
                Text(
                    tr("%s · %s · %s de %s", name, side, index + 1, queue.size),
                    style = Secundario.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        ProgressBar(pos, dur, enabled = t != null) { vm.seek(it) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SoftButton(Ico.Shuffle, tr("Aleatorio"), size = 48.dp, tint = if (shuffle) C.Rojo else C.Carbon) { vm.toggleShuffle() }
            SoftButton(Ico.Prev, tr("Anterior"), size = 58.dp) { vm.previous() }
            Box(
                Modifier.size(78.dp)
                    .shadow(10.dp, CircleShape, spotColor = C.Rojo)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(C.RojoClaro, C.Rojo, C.RojoOscuro)))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.toggle()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Ico.Pause else Ico.Play, if (playing) tr("Pausar") else tr("Reproducir"), tint = C.SobreRojo, modifier = Modifier.size(30.dp))
            }
            SoftButton(Ico.Next, tr("Siguiente"), size = 58.dp) { vm.next() }
            SoftButton(
                if (repeat == Player.REPEAT_MODE_ONE) Ico.RepeatOne else Ico.Repeat, tr("Repetir"), size = 48.dp,
                tint = if (repeat == Player.REPEAT_MODE_OFF) C.Carbon else C.Rojo
            ) { vm.cycleRepeat() }
        }

        JamBar { showJam = true }

        val bt = route == "bluetooth"
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (bt) C.Rojo.copy(alpha = 0.14f) else C.Tarjeta)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(if (bt) Ico.Bluetooth else Ico.Wave, null, tint = C.Rojo, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (bt) {
                    Text(
                        "Bluetooth${routeName?.let { " · $it" } ?: ""}",
                        style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Rojo), maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        tr("El audio se comprime: no estás escuchando FLAC. Conecta tus audífonos por USB-C para la calidad completa."),
                        style = Secundario.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(info?.label() ?: "—", style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    Text(
                        if (rgStatus.isNotBlank()) "$status · $rgStatus" else status,
                        style = Secundario.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            val outIcon = when (route) {
                "parlante" -> Ico.Volume
                else -> Ico.Headphones
            }
            Icon(outIcon, route, tint = if (usb != null && !bt) C.Rojo else C.Piedra, modifier = Modifier.size(20.dp))
        }
    }

    if (showQueue) {
        QueueSheet(queueTitle?.let { Engine.displayTitle(it) }, queue, index, onPick = { vm.jumpTo(it) }, onRemove = { vm.removeFromQueue(it) }, onMove = { f, t -> vm.moveInQueue(f, t) }, onDismiss = { showQueue = false })
    }
    if (showTimer) TimerSheet { showTimer = false }
    if (showJam) JamSheet { showJam = false }
    if (showLyrics) {
        LyricsSheet(tags, pos, track, onSeek = { vm.seek(it) }, onDismiss = { showLyrics = false })
    }
}

/** Barra de progreso fina, como en el diseño. Toca o arrastra para moverte. */
@Composable
fun ProgressBar(pos: Long, dur: Long, enabled: Boolean, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val frac = if (dragging) dragFrac else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(28.dp)
                .pointerInput(dur, enabled) {
                    if (!enabled || dur <= 0) return@pointerInput
                    detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
                }
                .pointerInput(dur, enabled) {
                    if (!enabled || dur <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { onSeek((dragFrac * dur).toLong()); dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) },
                    )
                }
        ) {
            val y = size.height / 2f
            val h = 3.dp.toPx()
            drawRoundRect(C.Linea, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h, h))
            drawRoundRect(C.Rojo, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h, h))
            val r = if (dragging) 9.dp.toPx() else 7.dp.toPx()
            drawCircle(C.Marfil, r + 3.dp.toPx(), Offset(size.width * frac, y))
            drawCircle(if (enabled) C.Rojo else C.Piedra, r, Offset(size.width * frac, y))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (dragging) (dragFrac * dur).toLong() else pos), style = Secundario.copy(fontSize = 12.sp))
            Spacer(Modifier.weight(1f))
            Text("-" + fmtTime((dur - if (dragging) (dragFrac * dur).toLong() else pos).coerceAtLeast(0)), style = Secundario.copy(fontSize = 12.sp))
        }
    }
}

/** Disco de vinilo: brillo de la pasta, surcos finos, pausas entre pistas y zona lisa antes de la etiqueta. */
@Composable
fun VinylGrooves(modifier: Modifier, gaps: Int = 6) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        // Pasta negra con un poco de profundidad
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF181614), 0.55f to Color(0xFF121110), 0.97f to Color(0xFF0E0D0C), 1f to Color(0xFF26231F),
                center = c, radius = r
            ),
            r, c
        )
        // Borde levantado
        drawCircle(Color(0xFF34302C), r * 0.992f, c, style = Stroke(width = r * 0.012f))
        drawCircle(Color(0xFF0A0909), r * 0.975f, c, style = Stroke(width = r * 0.006f))
        // Surcos: líneas muy finas con brillo que varía
        val outer = r * 0.955f
        val inner = r * 0.43f
        val step = (r * 0.0065f).coerceAtLeast(1.6f)
        var rr = outer
        var k = 0
        while (rr > inner) {
            val a = 0.035f + 0.03f * kotlin.math.abs(kotlin.math.sin(k * 0.37f))
            drawCircle(Color.White.copy(alpha = a), rr, c, style = Stroke(width = step * 0.45f))
            rr -= step
            k++
        }
        // Pausas entre canciones: bandas lisas y más brillantes
        val n = gaps.coerceIn(3, 14)
        for (i in 1 until n) {
            val gr = outer - (outer - inner) * i / n
            drawCircle(Color(0xFF0B0A09), gr, c, style = Stroke(width = step * 2.2f))
            drawCircle(Color.White.copy(alpha = 0.07f), gr + step * 1.2f, c, style = Stroke(width = 0.8f))
        }
        // Zona lisa de salida antes de la etiqueta
        drawCircle(Color(0xFF151312), r * 0.405f, c, style = Stroke(width = r * 0.05f))
        drawCircle(Color.White.copy(alpha = 0.05f), r * 0.43f, c, style = Stroke(width = 1f))
    }
}

/** Geometría del tocadiscos, en fracciones del ancho del cuadro. */
private object Deck {
    const val PX = 0.99f        // pivote del brazo
    const val PY = 0.02f
    const val TX = -0.108f      // punta de la aguja, con el brazo sin girar
    const val TY = 0.618f
    const val R = 0.45f         // radio del disco
    const val OUTER = 0.955f * R
    const val INNER = 0.43f * R
    const val REST = -10f

    fun tip(deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(a).toFloat()
        val s = kotlin.math.sin(a).toFloat()
        return Offset(PX + TX * c - TY * s, PY + TX * s + TY * c)
    }

    fun radius(deg: Float): Float {
        val t = tip(deg)
        return kotlin.math.hypot(t.x - 0.5f, t.y - 0.5f)
    }

    /** Ángulo del brazo para que la aguja quede en el radio [r]. */
    fun degFor(r: Float): Float {
        var lo = -8f
        var hi = 24f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (radius(mid) > r) lo = mid else hi = mid
        }
        return (lo + hi) / 2f
    }

    fun degFromPointer(px: Float, py: Float): Float {
        val phi = kotlin.math.atan2((py - PY).toDouble(), (px - PX).toDouble())
        val phi0 = kotlin.math.atan2(TY.toDouble(), TX.toDouble())
        return Math.toDegrees(phi - phi0).toFloat()
    }
}

/** Para no repetir la animación de "poner el disco" cada vez que se abre el reproductor. */
private var lastDropped: Any? = null

/**
 * El tocadiscos: vinilo a 33⅓ rpm (con el reloj de cada fotograma, así gira aunque el ahorro de batería
 * apague las animaciones), brazo que sigue la pista real y que puedes tomar con el dedo para soltar la aguja
 * en el surco que quieras.
 */
@Composable
fun Vinyl(
    track: Track?,
    playing: Boolean,
    modifier: Modifier,
    onTap: () -> Unit = {},
    onSwipe: (Boolean) -> Unit = {},
    onScratchStart: () -> Unit = {},
    onScratch: (Long) -> Unit = {},
    progress: Float = 0f,
    gaps: Int = 6,
    index: Int = 0,
    discKey: Any? = null,
    flip: Float = 0f,
    onArmGrab: () -> Unit = {},
    onNeedleDrop: (Int, Float) -> Unit = { _, _ -> },
    onLift: () -> Unit = {},
) {
    val angle = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(0f) }
    var scratching by remember { mutableStateOf(false) }
    var armDragging by remember { mutableStateOf(false) }
    LaunchedEffect(playing, scratching) {
        if (scratching) return@LaunchedEffect
        var last = 0L
        while (true) {
            val stop = withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                val target = if (playing) 200f else 0f // grados por segundo = 33⅓ rpm
                speed.floatValue += (target - speed.floatValue) * min(1f, dt * 2.2f)
                angle.floatValue = (angle.floatValue + speed.floatValue * dt) % 360f
                !playing && speed.floatValue < 0.5f
            }
            if (stop) {
                speed.floatValue = 0f
                break
            }
        }
    }

    // El brazo: en reposo fuera del disco; sonando, sobre el surco de la pista actual.
    val n = gaps.coerceAtLeast(1)
    val latest = rememberUpdatedState(Triple(playing, index, progress))
    val dragging = rememberUpdatedState(armDragging)
    val armDeg = remember { mutableFloatStateOf(Deck.REST) }
    LaunchedEffect(n) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                if (!dragging.value) {
                    val (pl, idx, pr) = latest.value
                    val target = if (pl) {
                        val frac = ((idx + pr.coerceIn(0f, 1f)) / n).coerceIn(0f, 1f)
                        Deck.degFor(Deck.OUTER - (Deck.OUTER - Deck.INNER) * frac)
                    } else Deck.REST
                    armDeg.floatValue += (target - armDeg.floatValue) * min(1f, dt * 4f)
                }
            }
        }
    }

    // "Poner el disco": sale de abajo y cae en el plato cuando empieza un disco nuevo.
    val drop = remember { mutableFloatStateOf(if (discKey == lastDropped) 1f else 0f) }
    LaunchedEffect(discKey) {
        if (discKey == null || discKey == lastDropped) {
            drop.floatValue = 1f
            return@LaunchedEffect
        }
        lastDropped = discKey
        drop.floatValue = 0f
        var start = 0L
        while (drop.floatValue < 1f) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                val t = ((now - start) / 700_000_000f).coerceIn(0f, 1f)
                drop.floatValue = 1f - (1f - t) * (1f - t) * (1f - t)
            }
        }
    }

    val art = rememberArt(track)
    val swipeDx = remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDx.floatValue = 0f },
                    onDragEnd = {
                        if (kotlin.math.abs(swipeDx.floatValue) > size.width * 0.22f) onSwipe(swipeDx.floatValue < 0f)
                        swipeDx.floatValue = 0f
                    },
                    onDragCancel = { swipeDx.floatValue = 0f },
                    onHorizontalDrag = { _, dx -> swipeDx.floatValue += dx },
                )
            }
            .pointerInput(Unit) {
                // Mantén presionado y gira el disco con el dedo para adelantar o retroceder.
                var prev = 0f
                var accum = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { o ->
                        scratching = true
                        accum = 0f
                        prev = kotlin.math.atan2(o.y - size.height / 2f, o.x - size.width / 2f)
                        onScratchStart()
                    },
                    onDragEnd = {
                        scratching = false
                        onScratch((accum * 100).toLong())
                    },
                    onDragCancel = { scratching = false },
                    onDrag = { change, _ ->
                        val p = change.position
                        val a = kotlin.math.atan2(p.y - size.height / 2f, p.x - size.width / 2f)
                        var d = Math.toDegrees((a - prev).toDouble()).toFloat()
                        if (d > 180f) d -= 360f
                        if (d < -180f) d += 360f
                        prev = a
                        accum += d
                        angle.floatValue = (angle.floatValue + d) % 360f
                    },
                )
            }
            .pointerInput(n) {
                // Tomar el brazo: si el dedo empieza sobre la cápsula, se mueve el brazo y al soltarlo
                // la aguja cae en ese surco (cada franja es una canción). Fuera del disco, se levanta.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val tip = Deck.tip(armDeg.floatValue)
                    val head = Offset(tip.x * w, tip.y * w)
                    if ((down.position - head).getDistance() > w * 0.15f) return@awaitEachGesture
                    down.consume()
                    armDragging = true
                    onArmGrab()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                        ch.consume()
                        armDeg.floatValue = Deck.degFromPointer(ch.position.x / w, ch.position.y / w).coerceIn(-14f, 22f)
                    }
                    armDragging = false
                    val r = Deck.radius(armDeg.floatValue)
                    if (r <= Deck.OUTER + 0.01f && r >= Deck.INNER) {
                        val f = ((Deck.OUTER - r) / (Deck.OUTER - Deck.INNER)).coerceIn(0f, 0.9999f) * n
                        val idx = f.toInt().coerceIn(0, n - 1)
                        onNeedleDrop(idx, (f - idx).coerceIn(0f, 0.98f))
                    } else {
                        onLift()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // El plato de aluminio cepillado: queda debajo del vinilo y su borde estroboscópico gira con él.
        Platter(angle, Modifier.fillMaxSize(0.955f))
        Box(
            Modifier.fillMaxSize(0.9f).graphicsLayer {
                translationX = swipeDx.floatValue * 0.25f
                translationY = (1f - drop.floatValue) * size.height * 0.8f
                alpha = (0.2f + drop.floatValue).coerceAtMost(1f)
                rotationY = flip * 180f
                cameraDistance = 14f * density
            }.shadow(22.dp, CircleShape, ambientColor = C.Negro, spotColor = C.Negro),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.floatValue }, contentAlignment = Alignment.Center) {
                VinylGrooves(Modifier.fillMaxSize(), gaps)
                Box(Modifier.fillMaxSize(0.37f).clip(CircleShape).background(C.Rojo), contentAlignment = Alignment.Center) {
                    if (art != null) {
                        Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        // Sin carátula: las rayas
                        Image(painterResource(R.drawable.zigzag), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    // Eje metálico
                    Canvas(Modifier.size(16.dp)) {
                        val rr = size.minDimension / 2f
                        drawCircle(Color.Black.copy(alpha = 0.35f), rr, center + Offset(1.5f, 2f))
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color(0xFFF4EEE4), Color(0xFFB9B0A3), Color(0xFF6F675D)),
                                center = center + Offset(-rr * 0.35f, -rr * 0.35f), radius = rr * 1.3f
                            ),
                            rr
                        )
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.Black.copy(alpha = 0.35f), size.minDimension * 0.185f, style = Stroke(width = 2.5f))
                }
            }
            // Reflejo de la luz en los surcos: dos brillos opuestos y el rojo de la sala
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.05f to Color(0x55A8191A),
                        0.11f to Color(0x22FFFFFF),
                        0.17f to Color.Transparent,
                        0.50f to Color.Transparent,
                        0.56f to Color(0x1FFFFFFF),
                        0.62f to Color(0x0FFFFFFF),
                        0.68f to Color.Transparent,
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height / 2f)
                    ),
                    radius = r * 0.695f,
                    style = Stroke(width = r * 0.53f)
                )
            }
        }
        Tonearm(armDeg.floatValue, Modifier.fillMaxSize())
        // Piloto de encendido y velocidad, como en las tornamesas de verdad.
        Row(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val glow = if (playing) C.Rojo else C.Piedra.copy(alpha = 0.5f)
            Box(
                Modifier.size(7.dp)
                    .then(if (playing) Modifier.shadow(6.dp, CircleShape, ambientColor = C.Rojo, spotColor = C.Rojo) else Modifier)
                    .clip(CircleShape).background(glow)
            )
            Spacer(Modifier.width(7.dp))
            Text("33⅓", style = Secundario.copy(fontSize = 11.sp, letterSpacing = 1.sp, color = if (playing) C.Carbon else C.Piedra))
        }
    }
}

/** Plato de aluminio cepillado con puntos estroboscópicos en el canto (giran a 33⅓). */
@Composable
private fun Platter(angle: androidx.compose.runtime.MutableFloatState, modifier: Modifier) {
    Box(modifier.shadow(16.dp, CircleShape, ambientColor = C.Negro, spotColor = C.Negro)) {
        // Aluminio: el brillo no gira (la luz está quieta), solo los puntos.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            drawCircle(
                Brush.sweepGradient(
                    0f to Color(0xFF9C9891), 0.08f to Color(0xFFE6E3DD), 0.16f to Color(0xFFA9A59E),
                    0.33f to Color(0xFFCFCBC4), 0.42f to Color(0xFF8E8A83), 0.58f to Color(0xFFDAD6CF),
                    0.66f to Color(0xFF9A968F), 0.83f to Color(0xFFEDEAE4), 0.92f to Color(0xFFA19D96), 1f to Color(0xFF9C9891),
                    center = c
                ),
                r, c
            )
            drawCircle(Color.White.copy(alpha = 0.55f), r - 1f, c, style = Stroke(width = 1.4f))
            drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.955f, c, style = Stroke(width = 1.2f))
            // Goma bajo el disco
            drawCircle(Color(0xFF151413), r * 0.95f, c)
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.floatValue }) {
            val r = size.minDimension / 2f
            val c = center
            // Cuatro filas de puntos estroboscópicos en el canto.
            val rows = listOf(r * 0.992f to 120, r * 0.979f to 108, r * 0.966f to 100)
            for ((rr, n) in rows) {
                for (i in 0 until n) {
                    val a = (i * 2 * Math.PI / n).toFloat()
                    val p = Offset(c.x + rr * kotlin.math.cos(a), c.y + rr * kotlin.math.sin(a))
                    drawCircle(Color(0xFF3C3935).copy(alpha = 0.55f), r * 0.0042f, p)
                }
            }
        }
    }
}

/** Brazo del tocadiscos con la forma del logo: tubo champaña con un quiebre y cápsula con franja roja. */
@Composable
fun Tonearm(deg: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val pivot = Offset(w * Deck.PX, w * Deck.PY)
        val metal = Brush.linearGradient(
            listOf(Color(0xFFF3EADB), Color(0xFFC9BCA7), Color(0xFF8F8372), Color(0xFFDCCFBC)),
            start = Offset(pivot.x - w * 0.1f, pivot.y), end = Offset(pivot.x + w * 0.05f, pivot.y + w * 0.6f)
        )
        val a = Offset(pivot.x, pivot.y + w * 0.34f)
        val b = Offset(pivot.x - w * 0.07f, pivot.y + w * 0.52f)
        val tube = w * 0.016f
        rotate(deg, pivot) {
            val sh = Offset(w * 0.012f, w * 0.018f)
            drawLine(Color.Black.copy(alpha = 0.28f), pivot + sh, a + sh, strokeWidth = tube, cap = StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = 0.28f), a + sh, b + sh, strokeWidth = tube, cap = StrokeCap.Round)
        }
        // Apoyabrazo: el poste donde descansa el brazo cuando no suena.
        run {
            val restA = Math.toRadians(Deck.REST.toDouble())
            val along = w * 0.30f
            val rest = Offset(
                pivot.x - along * kotlin.math.sin(restA).toFloat() + w * 0.03f,
                pivot.y + along * kotlin.math.cos(restA).toFloat()
            )
            drawCircle(Color.Black.copy(alpha = 0.25f), w * 0.022f, rest + Offset(w * 0.006f, w * 0.009f))
            drawCircle(Color(0xFF2A2623), w * 0.02f, rest)
            drawCircle(Color(0xFF8F8372), w * 0.012f, rest)
        }
        // Base del pivote: anillo moleteado.
        drawCircle(Color.Black.copy(alpha = 0.3f), w * 0.088f, pivot + Offset(w * 0.008f, w * 0.012f))
        drawCircle(Color(0xFF1A1816), w * 0.085f, pivot)
        for (i in 0 until 36) {
            val a = (i * 2 * Math.PI / 36).toFloat()
            drawLine(
                Color(0xFF3A3531), Offset(pivot.x + w * 0.072f * kotlin.math.cos(a), pivot.y + w * 0.072f * kotlin.math.sin(a)),
                Offset(pivot.x + w * 0.083f * kotlin.math.cos(a), pivot.y + w * 0.083f * kotlin.math.sin(a)), strokeWidth = 1.2f
            )
        }
        rotate(deg, pivot) {
            // Contrapeso detrás del pivote.
            val cw = Offset(pivot.x, pivot.y - w * 0.045f)
            drawLine(metal, pivot, cw, strokeWidth = tube * 0.8f, cap = StrokeCap.Round)
            drawRoundRect(Color(0xFF26221F), Offset(cw.x - w * 0.034f, cw.y - w * 0.03f), Size(w * 0.068f, w * 0.032f), CornerRadius(6f, 6f))
            drawRoundRect(metal, Offset(cw.x - w * 0.034f, cw.y - w * 0.03f), Size(w * 0.068f, w * 0.008f), CornerRadius(4f, 4f))
            drawCircle(Color(0xFF1F1C1A), w * 0.075f, pivot)
            drawCircle(metal, w * 0.05f, pivot)
            drawCircle(Color(0xFF5E564C), w * 0.018f, pivot)
            drawLine(metal, pivot, a, strokeWidth = tube, cap = StrokeCap.Round)
            drawLine(metal, a, b, strokeWidth = tube, cap = StrokeCap.Round)
            val dirDeg = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat() - 90f
            rotate(dirDeg, b) {
                val hw = w * 0.05f
                val hl = w * 0.115f
                val top = Offset(b.x - hw / 2f, b.y - w * 0.01f)
                drawRoundRect(Color.Black.copy(alpha = 0.3f), top + Offset(w * 0.01f, w * 0.014f), Size(hw, hl), CornerRadius(5f, 5f))
                drawRoundRect(metal, top, Size(hw, hl), CornerRadius(5f, 5f))
                drawRect(Color(0xFF2A2522), Offset(top.x, top.y + hl * 0.72f), Size(hw, hl * 0.09f))
                drawRect(C.Rojo, Offset(top.x, top.y + hl * 0.81f), Size(hw, hl * 0.07f))
                // Uñeta para levantar el brazo con el dedo.
                drawLine(metal, Offset(top.x + hw, top.y + hl * 0.35f), Offset(top.x + hw + w * 0.035f, top.y + hl * 0.28f), strokeWidth = w * 0.007f, cap = StrokeCap.Round)
                drawLine(Color(0xFF8F8372), Offset(b.x, top.y + hl), Offset(b.x, top.y + hl + w * 0.012f), strokeWidth = 2f)
            }
        }
    }
}
