package com.sebas.stalker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
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

@Composable
fun PlayerScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    val track by vm.current.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val pos by vm.positionMs.collectAsState()
    val dur by vm.durationMs.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val repeat by vm.repeat.collectAsState()
    val info by Engine.currentInfo.collectAsState()
    val status by Engine.outputStatus.collectAsState()
    val usb by Engine.usbDevice.collectAsState()

    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Down, "Volver a la biblioteca", onClick = onClose)
            Text("Stalker", style = Titulo.copy(fontSize = 28.sp, textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }

        Spacer(Modifier.weight(0.4f))
        Vinyl(track, playing, Modifier.fillMaxWidth(0.88f).aspectRatio(1f).align(Alignment.CenterHorizontally))
        Spacer(Modifier.weight(0.5f))

        val t = track
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                t?.title ?: "Nada sonando",
                style = Titulo.copy(fontSize = 32.sp, lineHeight = 36.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(t?.artist ?: "Elige un álbum en tu biblioteca", style = Cuerpo.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t != null) {
                val year = if (t.year > 0) " · ${t.year}" else ""
                Text(t.album + year, style = Secundario.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        var dragging by remember { mutableStateOf(false) }
        var dragVal by remember { mutableFloatStateOf(0f) }
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Column {
            Slider(
                value = if (dragging) dragVal else frac,
                onValueChange = { dragging = true; dragVal = it },
                onValueChangeFinished = {
                    vm.seek((dragVal * dur).toLong())
                    dragging = false
                },
                enabled = t != null,
                colors = SliderDefaults.colors(
                    thumbColor = C.Rojo,
                    activeTrackColor = C.Rojo,
                    inactiveTrackColor = C.Linea,
                    disabledThumbColor = C.Piedra,
                    disabledInactiveTrackColor = C.Linea,
                ),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(if (dragging) (dragVal * dur).toLong() else pos), style = Secundario.copy(fontSize = 12.sp))
                Spacer(Modifier.weight(1f))
                Text(fmtTime(dur), style = Secundario.copy(fontSize = 12.sp))
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SoftButton(Ico.Shuffle, "Aleatorio", size = 48.dp, tint = if (shuffle) C.Rojo else C.Carbon) { vm.toggleShuffle() }
            SoftButton(Ico.Prev, "Anterior", size = 58.dp) { vm.previous() }
            Box(
                Modifier.size(80.dp)
                    .shadow(12.dp, CircleShape, spotColor = C.Rojo)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(C.RojoClaro, C.Rojo, C.RojoOscuro)))
                    .clickable { vm.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Ico.Pause else Ico.Play, if (playing) "Pausar" else "Reproducir", tint = C.Marfil, modifier = Modifier.size(32.dp))
            }
            SoftButton(Ico.Next, "Siguiente", size = 58.dp) { vm.next() }
            SoftButton(
                if (repeat == Player.REPEAT_MODE_ONE) Ico.RepeatOne else Ico.Repeat, "Repetir", size = 48.dp,
                tint = if (repeat == Player.REPEAT_MODE_OFF) C.Carbon else C.Rojo
            ) { vm.cycleRepeat() }
        }

        Column(
            Modifier.fillMaxWidth()
                .shadow(3.dp, RoundedCornerShape(16.dp), ambientColor = C.Carbon.copy(alpha = 0.1f), spotColor = C.Carbon.copy(alpha = 0.1f))
                .clip(RoundedCornerShape(16.dp)).background(C.Tarjeta)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ico.Wave, null, tint = C.Rojo, modifier = Modifier.size(22.dp))
                Text(
                    info?.label() ?: "—", style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Icon(Ico.Headphones, if (usb != null) "DAC USB conectado" else "Sin DAC USB", tint = if (usb != null) C.Rojo else C.Piedra, modifier = Modifier.size(20.dp))
            }
            Text(status, style = Secundario.copy(fontSize = 12.sp), maxLines = 2)
        }
    }
}

@Composable
fun Vinyl(track: Track?, playing: Boolean, modifier: Modifier) {
    val rot = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            rot.animateTo(rot.value + 360f, tween(6000, easing = LinearEasing))
            rot.snapTo(rot.value % 360f)
        }
    }
    val art = rememberArt(track)
    Box(modifier.shadow(22.dp, CircleShape, ambientColor = C.Carbon, spotColor = C.Carbon), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = rot.value }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                drawCircle(C.Carbon, r)
                var rr = r - 4f
                val inner = r * 0.38f
                while (rr > inner) {
                    drawCircle(C.Surco, rr, style = Stroke(width = 1.2f))
                    rr -= 4.5f
                }
            }
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
        // Brillo fijo (no gira), como luz sobre el disco.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.06f to C.Rojo.copy(alpha = 0.55f),
                    0.16f to Color.Transparent,
                    0.55f to Color.Transparent,
                    0.62f to Color.White.copy(alpha = 0.10f),
                    0.70f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f)
                ),
                radius = r
            )
            drawCircle(C.Carbon, r * 0.385f, style = Stroke(width = 3f))
        }
    }
}
