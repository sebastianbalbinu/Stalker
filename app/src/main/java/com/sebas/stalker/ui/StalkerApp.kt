package com.sebas.stalker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.ArtCache
import com.sebas.stalker.Engine
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.R
import com.sebas.stalker.Track

@Composable
fun StalkerApp(vm: PlayerViewModel, hasPermission: Boolean, onRequestPermission: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(1) }
    val stack = remember { mutableStateListOf<Detail>() }
    val current by vm.current.collectAsState()
    val playing by vm.isPlaying.collectAsState()
    val detail = stack.lastOrNull()

    BackHandler(enabled = detail != null || tab != 1) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1) else tab = 1
    }

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
                1 -> LibraryScreen(vm, hasPermission, onRequestPermission, onOpen = { stack.add(it) })
                2 -> EqScreen()
                else -> SystemScreen(vm)
            }
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
                NavBar(tab) {
                    if (it == 1 && tab == 1) stack.clear()
                    tab = it
                }
            }
        }
    }
}

// ---------- Componentes compartidos ----------

val Titulo = TextStyle(fontFamily = Playfair, fontSize = 40.sp, color = C.Carbon, lineHeight = 44.sp)
val Cuerpo = TextStyle(fontFamily = Inter, fontSize = 15.sp, color = C.Carbon)
val Secundario = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = C.Tierra)

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
        Modifier.size(size).shadow(4.dp, CircleShape, ambientColor = C.Carbon.copy(alpha = 0.15f), spotColor = C.Carbon.copy(alpha = 0.15f))
            .clip(CircleShape).background(C.Tarjeta)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** Telón (o el fondo elegido) arriba y hoja marfil redondeada encima, como en el diseño. */
@Composable
fun CurtainSheet(content: @Composable ColumnScope.() -> Unit) {
    val theme by Engine.theme.collectAsState()
    Box(Modifier.fillMaxSize().background(C.Carbon)) {
        when (theme) {
            "Zigzag" -> Image(
                painterResource(R.drawable.zigzag), null,
                Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop
            )
            "Marfil" -> Box(Modifier.fillMaxWidth().height(170.dp).background(C.Arena))
            else -> Image(
                painterResource(R.drawable.telon), null,
                Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
        }
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
    Box(modifier.clip(RoundedCornerShape(corner)).background(C.Carbon)) {
        if (art != null) {
            Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            FallbackArt(((track?.albumId ?: 0L) % 3).toInt(), Modifier.fillMaxSize())
        }
    }
}

@Composable
fun FallbackArt(variant: Int, modifier: Modifier) {
    when (variant) {
        0 -> Box(modifier) {
            Image(painterResource(R.drawable.telon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        1 -> Column(modifier.background(C.Carbon)) {
            Spacer(Modifier.weight(1.1f))
            Image(painterResource(R.drawable.zigzag), null, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Crop)
        }
        else -> Column(modifier) {
            Image(painterResource(R.drawable.telon), null, Modifier.fillMaxWidth().weight(1.4f), contentScale = ContentScale.Crop)
            Image(painterResource(R.drawable.zigzag), null, Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Crop)
        }
    }
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
        Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.radialGradient(listOf(C.Surco, C.Carbon))), contentAlignment = Alignment.Center) {
            Cover(t, Modifier.size(22.dp), corner = 11.dp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, style = Cuerpo.copy(color = C.Marfil, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, style = Secundario.copy(color = Color(0xFFF0D9D2)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
            Icon(if (playing) Ico.Pause else Ico.Play, if (playing) "Pausar" else "Reproducir", tint = C.Marfil, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val items = listOf(
        Ico.Play to "Reproduciendo",
        Ico.Library to "Biblioteca",
        Ico.Sliders to "Ecualizador",
        Ico.Gear to "Sistema",
    )
    Row(
        Modifier.fillMaxWidth().height(68.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = C.Carbon.copy(alpha = 0.2f), spotColor = C.Carbon.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(24.dp)).background(C.Tarjeta)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, (icon, label) ->
            val on = i == tab
            Box(
                Modifier.weight(1f).padding(horizontal = 6.dp).height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) C.Rojo else Color.Transparent)
                    .clickable { onTab(i) },
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, label, tint = if (on) C.Marfil else C.Carbon, modifier = Modifier.size(23.dp))
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
        Text(label, style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) C.Marfil else C.Carbon))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(18.dp), ambientColor = C.Carbon.copy(alpha = 0.1f), spotColor = C.Carbon.copy(alpha = 0.1f))
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

