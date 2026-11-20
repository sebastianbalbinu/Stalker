package com.sebas.stalker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.tr
import com.sebas.stalker.ArtCache
import com.sebas.stalker.Engine
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.R
import com.sebas.stalker.Track
import com.sebas.stalker.UpdateInfo
import com.sebas.stalker.Updates

@Composable
fun StalkerApp(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onImportDisc: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val stack = remember { mutableStateListOf<Detail>() }
    val current by vm.current.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val detail = stack.lastOrNull()
    val ctx = LocalContext.current
    val browser = remember { BrowserState(ctx) }

    BackHandler(enabled = detail != null || tab != 1) {
        when {
            stack.isNotEmpty() -> stack.removeAt(stack.size - 1)
            tab == 4 -> tab = 3
            else -> tab = 1
        }
    }

    // Reloj de giro compartido por todas las miniaturas de vinilo.
    val spinSlow = remember { mutableFloatStateOf(0f) }
    val spinFast = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    spinSlow.floatValue = (spinSlow.floatValue + dt * 36f) % 360f
                    spinFast.floatValue = (spinFast.floatValue + dt * 200f) % 360f
                }
                last = now
            }
        }
    }

    CompositionLocalProvider(LocalSpin provides Spin(spinSlow, spinFast)) {
    Box(Modifier.fillMaxSize().background(C.Marfil)) {
        Crossfade(targetState = if (tab == 1) detail ?: tab else tab, label = "pantalla") { s ->
            when (s) {
                is Detail.PickSongs -> PickSongsScreen(s.discoId, onDone = { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) })
                is Detail -> CollectionScreen(
                    s, vm,
                    onBack = { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) },
                    onOpen = { stack.add(it) }
                )
                0 -> PlayerScreen(vm, onClose = { tab = 1 })
                1 -> LibraryScreen(vm, hasPermission, onRequestPermission, onOpen = { stack.add(it) }, onImportDisc = onImportDisc)
                2 -> EqScreen()
                4 -> StoreScreen(browser, if (current != null) 180.dp else 96.dp, onExit = { tab = 3 })
                else -> SystemScreen(vm, onExport, onImport, onOpenStore = { tab = 4 })
            }
        }

        val sharingMsg by com.sebas.stalker.Sharing.working.collectAsState()
        sharingMsg?.let { msg ->
            Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
                    .clip(RoundedCornerShape(20.dp)).background(C.Rojo).padding(horizontal = 18.dp, vertical = 10.dp)
            ) { Text(msg, style = Cuerpo.copy(color = C.SobreRojo, fontSize = 14.sp, fontWeight = FontWeight.Medium)) }
        }

        val update by Updates.available.collectAsState()
        val dismissed by Updates.dismissed.collectAsState()
        update?.let { u ->
            if (!dismissed) UpdateSheet(u) { Updates.dismissed.value = true }
        }

        if (detail !is Detail.PickSongs || tab != 1) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val t = current
                if (t != null && tab != 0) {
                    MiniPlayer(t, playing, onOpen = { tab = 0 }, onToggle = { vm.toggle() })
                }
                NavBar(if (tab == 4) 3 else tab) {
                    if (it == 1 && tab == 1) stack.clear()
                    tab = it
                }
            }
        }
    }
}
}

// ---------- Componentes compartidos ----------

/** Ángulos de giro para las miniaturas: lento (siempre) y a 33⅓ rpm (lo que suena). */
class Spin(val slow: androidx.compose.runtime.FloatState, val fast: androidx.compose.runtime.FloatState)

val LocalSpin = staticCompositionLocalOf { Spin(mutableFloatStateOf(0f), mutableFloatStateOf(0f)) }

/** Vinilo pequeño que gira, con la carátula (o las rayas) en la etiqueta. */
@Composable
fun MiniVinyl(track: Track?, modifier: Modifier, fast: Boolean = false) {
    val spin = LocalSpin.current
    Box(
        modifier.clip(CircleShape).background(C.Negro)
            .graphicsLayer { rotationZ = if (fast) spin.fast.floatValue else spin.slow.floatValue },
        contentAlignment = Alignment.Center
    ) {
        VinylGrooves(Modifier.fillMaxSize(), 4)
        Box(Modifier.fillMaxSize(0.4f).clip(CircleShape)) { Cover(track, Modifier.fillMaxSize(), corner = 0.dp) }
        Box(Modifier.fillMaxSize(0.06f).clip(CircleShape).background(Color(0xFFCFC6B8)))
    }
}

/** Encabezado centrado, con botones opcionales a los lados. */
@Composable
fun ScreenHeader(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        if (leading != null) Box(Modifier.align(Alignment.CenterStart)) { leading() }
        Text(
            title,
            style = Titulo.copy(fontSize = 26.sp, textAlign = TextAlign.Center),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
            maxLines = 1
        )
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** El logo de texto: ondas de sonido sobre STALKER espaciado, como en el ícono. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, color: Color = C.Carbon, scale: Float = 1f) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = (22 * scale).dp, height = (16 * scale).dp)) {
            val bars = listOf(0.35f, 0.65f, 1f, 0.65f, 0.35f)
            val gap = size.width / (bars.size - 1)
            bars.forEachIndexed { i, h ->
                val x = gap * i
                val bh = size.height * h
                drawLine(
                    if (i == 2) C.Rojo else color, Offset(x, (size.height - bh) / 2f), Offset(x, (size.height + bh) / 2f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }
        Spacer(Modifier.height((4 * scale).dp))
        Text(
            "STALKER",
            style = TextStyle(
                fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = (15 * scale).sp,
                letterSpacing = (6 * scale).sp, color = color
            ),
            modifier = Modifier.padding(start = (6 * scale).dp)
        )
    }
}

val Titulo: TextStyle get() = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 32.sp,
    color = C.Carbon, lineHeight = 38.sp, letterSpacing = (-0.5).sp
)
val Cuerpo: TextStyle get() = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = C.Carbon)
val Secundario: TextStyle get() = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = C.Tierra)

@Composable
fun SoftButton(
    icon: ImageVector,
    desc: String,
    size: Dp = 44.dp,
    tint: Color = C.Carbon,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(size).shadow(4.dp, CircleShape, ambientColor = C.Negro.copy(alpha = 0.15f), spotColor = C.Negro.copy(alpha = 0.15f))
            .clip(CircleShape).background(C.Tarjeta)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Telón (o el fondo elegido) arriba y hoja marfil redondeada encima, como en el diseño. */
/** Telón rojo arriba y hoja redondeada encima, como en el diseño. */
@Composable
fun CurtainSheet(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(C.Negro)) {
        Image(
            painterResource(R.drawable.telon), null,
            Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 64.dp)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(C.Marfil)
                .padding(start = 22.dp, end = 22.dp, top = 26.dp),
            content = content
        )
    }
}

@Composable
fun rememberArt(track: Track?): ImageBitmap? {
    val ctx = LocalContext.current
    val art by produceState(initialValue = track?.let { ArtCache.peek(it.albumId) }, track?.albumId) {
        value = if (track == null) null else ArtCache.load(ctx, track)
    }
    return art
}

/** Carátula del álbum; si el archivo no trae, arte de la sala roja. */
@Composable
fun Cover(track: Track?, modifier: Modifier, corner: Dp = 12.dp) {
    val art = rememberArt(track)
    Box(modifier.clip(RoundedCornerShape(corner)).background(C.Negro)) {
        if (art != null) {
            Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            FallbackArt(((track?.albumId ?: 0L) % 3).toInt(), Modifier.fillMaxSize())
        }
    }
}

/** Sin carátula: las rayas en zigzag. */
@Composable
fun FallbackArt(@Suppress("UNUSED_PARAMETER") variant: Int, modifier: Modifier) {
    Image(painterResource(R.drawable.zigzag), null, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun MiniPlayer(t: Track, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = C.Rojo)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF9E1414), C.Rojo, C.RojoOscuro)))
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniVinyl(t, Modifier.size(52.dp), fast = playing)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, style = Cuerpo.copy(color = C.SobreRojo, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = Secundario.copy(color = Color(0xFFF0D9D2)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (playing) Ico.Pause else Ico.Play, if (playing) tr("Pausar") else tr("Reproducir"), tint = C.SobreRojo, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        Triple(0, Ico.Turntable, tr("Tocadiscos")),
        Triple(1, Ico.Library, tr("Biblioteca")),
        Triple(2, Ico.Sliders, tr("Ecualizador")),
        Triple(3, Ico.Gear, tr("Sistema")),
    )
    Row(
        Modifier.fillMaxWidth().height(68.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = C.Negro.copy(alpha = 0.2f), spotColor = C.Negro.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(24.dp)).background(C.Tarjeta)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (id, icon, label) ->
            val on = id == tab
            // seleccionado: ícono en rojo con un punto debajo, como el piloto de un equipo de audio
            Column(
                Modifier.weight(1f).padding(horizontal = 4.dp).height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onTab(id) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, label, tint = if (on) C.Rojo else C.Tierra, modifier = Modifier.size(if (on) 25.dp else 23.dp))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) C.Rojo else Color.Transparent))
            }
        }
    }
}

@Composable
fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp)
            .then(if (selected) Modifier.shadow(6.dp, RoundedCornerShape(22.dp), spotColor = C.Rojo) else Modifier)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) C.Rojo else C.Arena)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(tr(label), style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) C.SobreRojo else C.Carbon))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(18.dp), ambientColor = C.Negro.copy(alpha = 0.1f), spotColor = C.Negro.copy(alpha = 0.1f))
            .clip(RoundedCornerShape(18.dp)).background(C.Tarjeta),
        content = content
    )
}

@Composable
fun BoxScope.EmptyHint(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = Titulo.copy(fontSize = 26.sp))
        Text(body, style = Secundario.copy(fontSize = 15.sp))
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            Pill(action, selected = true, onClick = onAction)
        }
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}


@Composable
fun UpdateSheet(u: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    StalkerSheet(onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
            Spacer(Modifier.height(14.dp))
            Text(tr("Hay una versión nueva"), style = Titulo.copy(fontSize = 24.sp))
            Text("STALKER ${u.version}", style = Secundario)
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Se instala encima de la que tienes, sin perder tus discos ni tus favoritos."),
                style = Cuerpo.copy(color = C.Tierra, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(18.dp))
            Pill("Descargar ahora", selected = true, modifier = Modifier.fillMaxWidth()) {
                try {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u.url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                }
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            Pill("Más tarde", selected = false, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}
