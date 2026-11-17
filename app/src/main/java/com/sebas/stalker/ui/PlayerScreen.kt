package com.sebas.stalker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding().navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Down, "Volver a la biblioteca", onClick = onClose)
            Text("Stalker", style = Titulo.copy(fontSize = 28.sp, textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
            SoftButton(Ico.ListIco, "Pistas del disco") { if (queue.isNotEmpty()) showQueue = true }
        }

        // El vinilo ocupa el espacio que sobre, sin empujar nada.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Vinyl(
                track, playing,
                Modifier.padding(vertical = 6.dp).aspectRatio(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.toggle()
                    }
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
                Icon(if (playing) Ico.Pause else Ico.Play, if (playing) "Pausar" else "Reproducir", tint = C.Marfil, modifier = Modifier.size(30.dp))
            }
            SoftButton(Ico.Next, "Siguiente", size = 58.dp) { vm.next() }
            SoftButton(
                if (repeat == Player.REPEAT_MODE_ONE) Ico.RepeatOne else Ico.Repeat, "Repetir", size = 48.dp,
                tint = if (repeat == Player.REPEAT_MODE_OFF) C.Carbon else C.Rojo
            ) { vm.cycleRepeat() }
        }

        Spacer(Modifier.height(2.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(C.Tarjeta)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ico.Wave, null, tint = C.Rojo, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(info?.label() ?: "—", style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(status, style = Secundario.copy(fontSize = 11.sp, lineHeight = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Ico.Headphones, if (usb != null) "DAC USB conectado" else "Sin DAC USB", tint = if (usb != null) C.Rojo else C.Piedra, modifier = Modifier.size(20.dp))
        }
    }

    if (showQueue) {
        QueueSheet(queueTitle, queue, index, onPick = { vm.jumpTo(it) }, onDismiss = { showQueue = false })
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

/** Surcos del vinilo. */
@Composable
fun VinylGrooves(modifier: Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(C.Carbon, r)
        var rr = r - 3f
        val inner = r * 0.38f
        while (rr > inner) {
            drawCircle(C.Surco, rr, style = Stroke(width = 1.1f))
            rr -= 4f
        }
    }
}

/**
 * Vinilo a 33⅓ rpm. El giro se calcula con el reloj de cada fotograma (no con animaciones del sistema),
 * así gira aunque el ahorro de batería desactive las animaciones. Arranca y frena suave, como un tocadiscos.
 */
@Composable
fun Vinyl(track: Track?, playing: Boolean, modifier: Modifier) {
    val angle = remember { mutableFloatStateOf(0f) }
    val speed = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
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
    val art = rememberArt(track)
    Box(modifier.shadow(22.dp, CircleShape, ambientColor = C.Carbon, spotColor = C.Carbon), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.floatValue }, contentAlignment = Alignment.Center) {
            VinylGrooves(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize(0.37f).clip(CircleShape).background(C.Rojo), contentAlignment = Alignment.Center) {
                if (art != null) {
                    Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Image(painterResource(R.drawable.telon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        Text("Stalker", style = Titulo.copy(fontSize = 18.sp, color = C.Marfil, textAlign = TextAlign.Center), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.weight(0.6f))
                        Image(
                            painterResource(R.drawable.zigzag), null,
                            Modifier.fillMaxWidth().weight(0.9f), contentScale = ContentScale.Crop
                        )
                    }
                }
                Box(Modifier.size(10.dp).clip(CircleShape).background(C.Carbon))
            }
        }
        // Brillo fijo (no gira), como la luz de la sala sobre el disco.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.06f to C.Rojo.copy(alpha = 0.5f),
                    0.16f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.62f to Color.White.copy(alpha = 0.10f),
                    0.70f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f)
                ),
                radius = r * 0.69f,
                style = Stroke(width = r * 0.62f)
            )
            drawCircle(C.Carbon, r * 0.385f, style = Stroke(width = 3f))
        }
    }
}
