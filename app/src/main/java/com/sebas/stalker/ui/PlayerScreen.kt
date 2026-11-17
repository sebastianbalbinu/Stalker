package com.sebas.stalker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    var showTimer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
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
            SoftButton(Ico.Down, "Volver a la biblioteca", onClick = onClose)
            Spacer(Modifier.width(10.dp))
            SoftButton(Ico.Moon, "Temporizador", tint = if (sleepAt != null || sleepEnd) C.Rojo else C.Carbon) { showTimer = true }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Wordmark() }
            SoftButton(Ico.Lyrics, "Letra") { showLyrics = true }
            Spacer(Modifier.width(10.dp))
            SoftButton(Ico.ListIco, "Pistas del disco") { if (queue.isNotEmpty()) showQueue = true }
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
            )
        }

        val t = track
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                t?.title ?: "Nada sonando",
                style = Titulo.copy(fontSize = 30.sp, lineHeight = 34.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(t?.artist ?: "Elige un disco en tu biblioteca", style = Cuerpo.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t != null && queue.isNotEmpty()) {
                val side = if (index < (queue.size + 1) / 2) "Lado A" else "Lado B"
                val name = queueTitle ?: t.album
                Text(
                    "$name · $side · ${index + 1} de ${queue.size}",
                    style = Secundario.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }

        ProgressBar(pos, dur, enabled = t != null) { vm.seek(it) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SoftButton(Ico.Shuffle, "Aleatorio", size = 48.dp, tint = if (shuffle) C.Rojo else C.Carbon) { vm.toggleShuffle() }
            SoftButton(Ico.Prev, "Anterior", size = 58.dp) { vm.previous() }
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
                Icon(if (playing) Ico.Pause else Ico.Play, if (playing) "Pausar" else "Reproducir", tint = C.SobreRojo, modifier = Modifier.size(30.dp))
            }
            SoftButton(Ico.Next, "Siguiente", size = 58.dp) { vm.next() }
            SoftButton(
                if (repeat == Player.REPEAT_MODE_ONE) Ico.RepeatOne else Ico.Repeat, "Repetir", size = 48.dp,
                tint = if (repeat == Player.REPEAT_MODE_OFF) C.Carbon else C.Rojo
            ) { vm.cycleRepeat() }
        }

        Spacer(Modifier.height(2.dp))
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
                        "El audio se comprime: no estás escuchando FLAC. Conecta tus audífonos por USB-C para la calidad completa.",
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
        QueueSheet(queueTitle, queue, index, onPick = { vm.jumpTo(it) }, onRemove = { vm.removeFromQueue(it) }, onDismiss = { showQueue = false })
    }
    if (showTimer) TimerSheet { showTimer = false }
    if (showLyrics) {
        LyricsSheet(tags, pos, track?.title, onSeek = { vm.seek(it) }, onDismiss = { showLyrics = false })
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

/**
 * Vinilo a 33⅓ rpm. El giro se calcula con el reloj de cada fotograma (no con animaciones del sistema),
 * así gira aunque el ahorro de batería desactive las animaciones. Arranca y frena suave, como un tocadiscos.
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
) {
    val angle = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(0f) }
    var scratching by remember { mutableStateOf(false) }
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
    // Brazo del tocadiscos: baja sobre el disco al sonar, vuelve a su apoyo en pausa.
    val arm = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        val target = if (playing) 1f else 0f
        var last = 0L
        while (kotlin.math.abs(arm.floatValue - target) > 0.002f) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
                last = now
                arm.floatValue += (target - arm.floatValue) * min(1f, dt * 5f)
            }
        }
        arm.floatValue = target
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
                // Mantén presionado y gira el disco con el dedo para adelantar o retroceder, como un DJ.
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
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.fillMaxSize(0.9f).graphicsLayer {
                translationX = swipeDx.floatValue * 0.25f
            }.shadow(22.dp, CircleShape, ambientColor = C.Negro, spotColor = C.Negro),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.floatValue }, contentAlignment = Alignment.Center) {
                VinylGrooves(Modifier.fillMaxSize(), gaps)
                Box(Modifier.fillMaxSize(0.37f).clip(CircleShape).background(C.Rojo), contentAlignment = Alignment.Center) {
                    if (art != null) {
                        Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        // Etiqueta como la del logo: roja con las ondas de sonido
                        Canvas(Modifier.fillMaxSize()) {
                            val rr = size.minDimension / 2f
                            drawCircle(Brush.radialGradient(listOf(Color(0xFFB0181A), C.Rojo, Color(0xFF5E0808)), center = center, radius = rr), rr)
                            drawCircle(Color.Black.copy(alpha = 0.25f), rr * 0.86f, style = Stroke(width = 1.5f))
                            val bars = listOf(0.30f, 0.55f, 0.80f, 0.55f, 0.30f)
                            val gap = rr * 0.17f
                            bars.forEachIndexed { i, h ->
                                val x = center.x + (i - 2) * gap
                                val half = rr * h / 2f
                                drawLine(
                                    Color(0xFFF1DFC4), Offset(x, center.y - half), Offset(x, center.y + half),
                                    strokeWidth = rr * 0.085f, cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                    // Eje metálico, como en el logo
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
                // Borde de la etiqueta
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.Black.copy(alpha = 0.35f), size.minDimension * 0.185f, style = Stroke(width = 2.5f))
                }
            }
            // Brillo fijo (no gira), como la luz de la sala sobre el disco.
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                // Reflejo de la luz en los surcos: dos brillos opuestos y el rojo de la sala
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
        Tonearm(arm.floatValue, progress, Modifier.fillMaxSize())
    }
}

/**
 * Brazo del tocadiscos, con la misma forma que el del logo: tubo champaña con un quiebre
 * y cápsula rectangular con franja roja. [down] va de 0 (en su apoyo) a 1 (sobre el disco);
 * sobre el disco avanza hacia el centro a medida que avanza la canción.
 */
@Composable
fun Tonearm(down: Float, progress: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val pivot = Offset(w * 0.99f, w * 0.02f)
        val rest = -8f
        val onDisc = -2f + 19f * progress.coerceIn(0f, 1f)
        val deg = rest + (onDisc - rest) * down
        val metal = Brush.linearGradient(
            listOf(Color(0xFFF3EADB), Color(0xFFC9BCA7), Color(0xFF8F8372), Color(0xFFDCCFBC)),
            start = Offset(pivot.x - w * 0.1f, pivot.y), end = Offset(pivot.x + w * 0.05f, pivot.y + w * 0.6f)
        )
        val a = Offset(pivot.x, pivot.y + w * 0.34f)
        val b = Offset(pivot.x - w * 0.07f, pivot.y + w * 0.52f)
        val tube = w * 0.016f
        // Sombra sobre el disco
        rotate(deg, pivot) {
            val sh = Offset(w * 0.012f, w * 0.018f)
            drawLine(Color.Black.copy(alpha = 0.28f), pivot + sh, a + sh, strokeWidth = tube, cap = StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = 0.28f), a + sh, b + sh, strokeWidth = tube, cap = StrokeCap.Round)
        }
        rotate(deg, pivot) {
            // Base del brazo
            drawCircle(Color(0xFF1F1C1A), w * 0.075f, pivot)
            drawCircle(metal, w * 0.05f, pivot)
            drawCircle(Color(0xFF5E564C), w * 0.018f, pivot)
            // Tubo con quiebre
            drawLine(metal, pivot, a, strokeWidth = tube, cap = StrokeCap.Round)
            drawLine(metal, a, b, strokeWidth = tube, cap = StrokeCap.Round)
            // Cápsula alineada con el último tramo
            val dirDeg = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat() - 90f
            rotate(dirDeg, b) {
                val hw = w * 0.05f
                val hl = w * 0.115f
                val top = Offset(b.x - hw / 2f, b.y - w * 0.01f)
                drawRoundRect(Color.Black.copy(alpha = 0.3f), top + Offset(w * 0.01f, w * 0.014f), Size(hw, hl), CornerRadius(5f, 5f))
                drawRoundRect(metal, top, Size(hw, hl), CornerRadius(5f, 5f))
                // franja oscura y franja roja, como en el logo
                drawRect(Color(0xFF2A2522), Offset(top.x, top.y + hl * 0.72f), Size(hw, hl * 0.09f))
                drawRect(C.Rojo, Offset(top.x, top.y + hl * 0.81f), Size(hw, hl * 0.07f))
                // aguja
                drawLine(Color(0xFF8F8372), Offset(b.x, top.y + hl), Offset(b.x, top.y + hl + w * 0.012f), strokeWidth = 2f)
            }
        }
    }
}
