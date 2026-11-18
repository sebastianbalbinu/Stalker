package stalker

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/* ------------------------------------------------------------------ la sala */

@Composable
fun SalaScreen(app: AppState) {
    val p = app.player
    val art = rememberArt(p.current?.path)
    Box(Modifier.fillMaxSize()) {
        RoomBackdrop(Modifier.fillMaxSize(), horizon = 0.64f, pool = Offset(if (app.immersive) 0.5f else 0.36f, 0.84f), animate = p.playing && app.view == View.Sala)
        Row(Modifier.fillMaxSize().padding(top = 58.dp, start = 34.dp, end = if (app.immersive) 34.dp else 22.dp, bottom = 26.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                val dw = min(maxWidth.value * 0.97f, maxHeight.value / Deck.H * 0.9f).dp
                Turntable(p, art, Modifier.width(dw), active = app.view == View.Sala)
                if (p.queue.isEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).offset(x = -(dw * 0.115f), y = -(dw * 0.02f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(dw * 0.38f))
                        if (app.albums.isEmpty() && !app.scanning) {
                            Pill("Elegir carpeta de música", { app.addFolder() }, active = true, ico = Ico.Folder)
                        } else {
                            Pill("Elegir un disco de la estantería", { app.view = View.Shelf }, active = true, ico = Ico.Shelf)
                        }
                    }
                }
            }
            AnimatedVisibility(!app.immersive, enter = fadeIn(), exit = fadeOut()) {
                Row {
                    Spacer(Modifier.width(22.dp))
                    NowPanel(app, Modifier.width(360.dp).fillMaxHeight())
                }
            }
        }
        if (app.immersive) {
            val t = p.current
            if (t != null) Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(t.title, 18, FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                T(t.artist + "  ·  " + t.album, 13, color = C.Tenue)
            }
        }
    }
}

@Composable
fun NowPanel(app: AppState, modifier: Modifier) {
    Jam.version
    val p = app.player
    val t = p.current
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            T(if (p.playing) "SONANDO" else if (t != null) "EN EL PLATO" else "SALA", 10, FontWeight.SemiBold, C.Tenue, spacing = 2f)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(92.dp).shadow(14.dp, RoundedCornerShape(5.dp))) {
                    Cover(t?.path, Modifier.fillMaxSize(), radius = 5.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    T(t?.title ?: "Nada en el plato", 18, FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(4.dp))
                    T(t?.artist ?: "Elige un disco", 13, color = C.Tenue)
                    Spacer(Modifier.height(2.dp))
                    T(listOfNotNull(t?.album, t?.year?.ifBlank { null }).joinToString(" · "), 12, color = C.Apagado)
                }
                if (t != null) {
                    val fav = app.favorites.contains(t.albumKey)
                    RoundButton(if (fav) Ico.HeartOn else Ico.Heart, { app.toggleFavorite(t.albumKey) }, tint = if (fav) C.RojoVivo else C.Tenue)
                }
            }
            Spacer(Modifier.height(18.dp))
            SignalChain(p)
            Spacer(Modifier.height(18.dp))

            TrackProgress(p)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Ico.Prev, { p.prev() }, 44.dp, 18.dp)
                Spacer(Modifier.width(18.dp))
                RoundButton(if (p.playing) Ico.Pause else Ico.Play, { p.toggle() }, 58.dp, 22.dp, tint = C.Sombra, filled = C.Crema)
                Spacer(Modifier.width(18.dp))
                RoundButton(Ico.Next, { p.next() }, 44.dp, 18.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ico.Speaker, C.Tenue, 15.dp)
                Spacer(Modifier.width(10.dp))
                ThinSlider(p.volume, { p.changeVolume(it) }, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                T("${(p.volume * 100).toInt()} %", 11, color = C.Tenue, modifier = Modifier.width(38.dp))
            }
            if (p.volume < 0.999f && p.chain.exclusive) {
                T("Al 100 % la señal sale intacta. Baja el volumen en tus audífonos o DAC.", 10, color = C.Apagado, maxLines = 2)
            }
            p.message?.let {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x338E0E0E)).padding(10.dp)) {
                    T(it, 11, color = C.Crema, maxLines = 3)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Linea))
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0x12F1DFC4)).padding(3.dp),
            ) {
                PanelTab("Pistas", app.panelTab == 0) { app.panelTab = 0 }
                PanelTab(if (Jam.core.active) "Jam · ${Jam.core.peers.size.coerceAtLeast(1)}" else "Jam", app.panelTab == 1) { app.panelTab = 1 }
            }
            Spacer(Modifier.height(10.dp))
            if (app.panelTab == 0) Tracklist(app, Modifier.weight(1f)) else JamView(app, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PanelTab(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) C.Crema else Color.Transparent)
            .pointerHoverIcon(Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 5.dp)
    ) { T(label, 11, FontWeight.Medium, if (active) C.Sombra else C.Crema) }
}

/** Solo esta parte se actualiza con el avance de la canción (el resto del panel queda quieto). */
@Composable
fun TrackProgress(p: Player) {
    val dur = p.current?.durationMs ?: 0
    ThinSlider(
        if (dur > 0) (p.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
        onChange = {},
        onDone = { f -> if (dur > 0) p.seek((f * dur).toLong()) },
    )
    Row(Modifier.fillMaxWidth()) {
        T(fmt(p.positionMs), 11, color = C.Tenue)
        Spacer(Modifier.weight(1f))
        T("-" + fmt((dur - p.positionMs).coerceAtLeast(0)), 11, color = C.Tenue)
    }
}

@Composable
fun SignalChain(p: Player) {
    val t = p.current
    val c = p.chain
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x14F1DFC4)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("CADENA DE SEÑAL", 9, FontWeight.SemiBold, C.Tenue, spacing = 1.6f)
            Spacer(Modifier.weight(1f))
            if (t != null && c.mode != 0) {
                if (p.bitPerfect) Box(Modifier.clip(RoundedCornerShape(50)).background(C.Rojo).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    T("BIT-PERFECT", 9, FontWeight.SemiBold, C.Crema, spacing = 1.2f)
                } else Box(Modifier.clip(RoundedCornerShape(50)).border(1.dp, C.Linea, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    T(if (!t.lossless) "CON PÉRDIDA" else "ALTA CALIDAD", 9, FontWeight.SemiBold, C.Tenue, spacing = 1.2f)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ChainRow("Archivo", t?.quality ?: "—")
        val out = when {
            t == null || c.mode == 0 -> "—"
            c.exclusive -> "WASAPI exclusivo · ${c.outValid}-bit · ${khz(c.outRate)}"
            else -> "Windows (compartido) · ${khz(c.outRate)}"
        }
        ChainRow("Salida", out)
        val note = when {
            t == null || c.mode == 0 -> null
            p.bitPerfect -> "El DAC recibe exactamente los datos del archivo."
            !c.exclusive && p.exclusive -> "El DAC no aceptó el modo exclusivo; Windows mezcla y ajusta la señal."
            !c.exclusive -> "Windows mezcla con otras apps${if (c.outRate != c.srcRate) " y remuestrea a ${khz(c.outRate)}" else ""}."
            p.volume < 0.999f -> "El volumen digital modifica la señal."
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(6.dp))
            T(note, 10, color = C.Apagado, maxLines = 2)
        }
    }
}

@Composable
private fun ChainRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        T(k, 11, color = C.Apagado, modifier = Modifier.width(62.dp))
        T(v, 11, FontWeight.Medium, C.Crema)
    }
}

@Composable
fun Tracklist(app: AppState, modifier: Modifier) {
    val p = app.player
    val q = p.queue
    val total = p.albumDurationMs
    val st = rememberLazyListState()
    LaunchedEffect(p.index) { if (p.index >= 0) st.animateScrollToItem((p.index + 1).coerceAtLeast(0)) }
    if (q.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            T("Pon un disco y la lista aparece aquí.", 12, color = C.Apagado)
        }
        return
    }
    // lado A / lado B como en un vinilo de verdad
    var acc = 0L
    val sideB = q.indexOfFirst { t -> (acc >= total / 2).also { acc += t.durationMs } }.let { if (q.size < 4) -1 else it }
    LazyColumn(modifier.fillMaxWidth(), state = st) {
        item {
            Row(Modifier.padding(bottom = 8.dp)) {
                T(p.queueTitle.ifBlank { "Cola" }, 12, FontWeight.SemiBold, modifier = Modifier.weight(1f))
                T("${q.size} canciones · ${fmtLong(total)}", 11, color = C.Apagado)
            }
        }
        itemsIndexed(q) { i, t ->
            if (i == 0 && sideB > 0) SideHeader("LADO A")
            if (i == sideB && sideB > 0) SideHeader("LADO B")
            val cur = i == p.index
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (cur) Color(0x1FF1DFC4) else if (hovered) Color(0x0FF1DFC4) else Color.Transparent)
                    .hoverable(src)
                    .pointerHoverIcon(Hand)
                    .clickable(interactionSource = src, indication = null) { p.jump(i) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp)) {
                    if (cur && p.playing) Equalizer(Modifier.size(12.dp))
                    else T("${i + 1}", 11, color = if (cur) C.Crema else C.Apagado)
                }
                Column(Modifier.weight(1f)) {
                    T(t.title, 12, if (cur) FontWeight.SemiBold else FontWeight.Normal, if (cur) C.Crema else C.Crema.copy(alpha = 0.86f))
                    if (t.artist != p.queue.first().artist) T(t.artist, 10, color = C.Apagado)
                }
                T(fmt(t.durationMs), 11, color = C.Apagado)
            }
        }
    }
}

@Composable
private fun SideHeader(s: String) {
    T(s, 9, FontWeight.SemiBold, C.RojoVivo.copy(alpha = 0.9f), Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp), spacing = 2f)
}

@Composable
fun Equalizer(modifier: Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition()
    val a by t.animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(420), androidx.compose.animation.core.RepeatMode.Reverse))
    val b by t.animateFloat(1f, 0.25f, androidx.compose.animation.core.infiniteRepeatable(tween(560), androidx.compose.animation.core.RepeatMode.Reverse))
    val c by t.animateFloat(0.5f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(tween(330), androidx.compose.animation.core.RepeatMode.Reverse))
    Canvas(modifier) {
        val w = size.width / 5
        listOf(a, b, c).forEachIndexed { i, v ->
            drawRect(C.RojoVivo, Offset(i * w * 2, size.height * (1 - v)), androidx.compose.ui.geometry.Size(w, size.height * v))
        }
    }
}

/* ------------------------------------------------------------------ estantería */

@Composable
fun ShelfScreen(app: AppState) {
    Box(Modifier.fillMaxSize()) {
        val shelf3d = app.shelfMode == 0 || app.shelfMode == 1
        if (shelf3d) RoomBackdrop(Modifier.fillMaxSize(), horizon = 0.80f, pool = Offset(0.5f, 0.9f), animate = false)
        else WoodWall(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(top = 58.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T("Estantería", 30, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    val size = app.tracks.sumOf { it.size }
                    T(
                        if (app.scanning && app.tracks.isEmpty()) "Buscando música… ${app.scanCount}"
                        else "${app.albums.size} discos · ${app.tracks.size} canciones · ${gb(size)}" + if (app.scanning) "  ·  buscando…" else "",
                        12, color = C.Tenue,
                    )
                }
                SearchBox(app)
                Spacer(Modifier.width(12.dp))
                Pill("Estantería", { app.shelfMode = 0 }, active = app.shelfMode == 0)
                Spacer(Modifier.width(6.dp))
                Pill("Favoritos", { app.shelfMode = 1 }, active = app.shelfMode == 1)
                Spacer(Modifier.width(6.dp))
                Pill("Portadas", { app.shelfMode = 3 }, active = app.shelfMode == 3)
                Spacer(Modifier.width(6.dp))
                Pill("Canciones", { app.shelfMode = 2 }, active = app.shelfMode == 2)
            }
            Box(Modifier.weight(1f)) {
                when {
                    app.shelfMode == 2 -> SongList(app)
                    shelf3d && app.albums.isNotEmpty() -> Shelf3D(app)
                    else -> AlbumGrid(app)
                }
            }
            if (app.player.queue.isNotEmpty()) NowBar(app)
        }
    }
}

private fun gb(b: Long) = if (b > 1L shl 30) String.format("%.1f GB", b / (1024.0 * 1024 * 1024)) else "${b / (1024 * 1024)} MB"

@Composable
fun SearchBox(app: AppState) {
    Row(
        Modifier.width(240.dp).clip(RoundedCornerShape(50)).background(Color(0x14F1DFC4)).border(1.dp, C.Linea, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ico.Search, C.Tenue, 14.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (app.query.isEmpty()) T("Buscar discos, artistas…", 12, color = C.Apagado)
            BasicTextField(
                app.query, { app.query = it },
                singleLine = true,
                textStyle = txt(12.sp),
                cursorBrush = SolidColor(C.Crema),
                modifier = Modifier.fillMaxWidth().onFocusChanged { app.typing = it.isFocused },
            )
        }
    }
}

@Composable
fun AlbumGrid(app: AppState) {
    val q = app.query.trim().lowercase()
    val list = app.albums
        .filter { app.shelfMode != 1 || app.favorites.contains(it.key) }
        .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.tracks.any { t -> t.title.lowercase().contains(q) } }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(
                    when {
                        app.scanning -> "Buscando tu música…"
                        app.shelfMode == 1 -> "Marca discos con el corazón y aparecerán aquí."
                        q.isNotEmpty() -> "Nada coincide con “${app.query}”."
                        else -> "No encontramos música en tus carpetas."
                    }, 14, color = C.Tenue,
                )
                if (!app.scanning && app.albums.isEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Pill("Elegir carpeta de música", { app.addFolder() }, active = true, ico = Ico.Folder)
                }
            }
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(178.dp),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 64.dp, top = 70.dp, bottom = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(30.dp),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        items(list, key = { it.key }) { a -> SleeveCard(app, a) }
    }
}

/** Funda del disco: al pasar el mouse el vinilo sale de la funda, como cuando lo sacas para ponerlo. */
@Composable
fun SleeveCard(app: AppState, a: Album) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    // las animaciones se leen solo dentro de las capas: la tarjeta no se recompone en cada cuadro
    val out = animateFloatAsState(if (hovered) 1f else 0f, tween(if (hovered) 520 else 360, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    val onDeck = app.player.queue.firstOrNull()?.albumKey == a.key
    val art = rememberArt(a.cover)
    Column(Modifier.hoverable(src)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            // vinilo: sube y gira un poco al salir de la funda
            Canvas(Modifier.fillMaxSize().graphicsLayer {
                val o = out.value
                translationY = -size.height * 0.34f * o
                translationX = size.width * 0.03f * o
                rotationZ = 38f * o
            }.drawWithCache {
                val r = size.minDimension * 0.47f
                val c = Offset(size.width / 2f, size.height / 2f)
                onDrawBehind {
                    drawCircle(Color(0x88000000), r, c + Offset(3f, 6f))
                    drawCircle(Color(0xFF0C0B0B), r, c)
                    for (i in 0 until 18) drawCircle(Color.White.copy(alpha = 0.03f + (i % 3) * 0.012f), r * (0.4f + i * 0.033f), c, style = Stroke(0.8f))
                    drawCircle(Brush.sweepGradient(0f to Color(0x00FFFFFF), 0.1f to Color(0x1EFFFFFF), 0.2f to Color(0x00FFFFFF), 0.6f to Color(0x00FFFFFF), 0.7f to Color(0x16FFFFFF), 0.8f to Color(0x00FFFFFF), center = c), r * 0.7f, c, style = Stroke(r * 0.58f))
                    val lr = r * 0.34f
                    clipPath(Path().apply { addOval(Rect(c, lr)) }) {
                        if (art != null) drawCover(art, topLeft = c - Offset(lr, lr), w = lr * 2, h = lr * 2) else drawCircle(C.Rojo, lr, c)
                    }
                    drawCircle(C.Crema, r * 0.025f, c)
                }
            }) {}
            // funda
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val o = out.value
                        translationY = -4.dp.toPx() * o
                        shadowElevation = (6 + 14 * o).dp.toPx()
                        shape = RoundedCornerShape(3.dp)
                        clip = true
                    }
                    .pointerHoverIcon(Hand)
                    .clickable(interactionSource = src, indication = null) { app.placeOnDeck(a) }
            ) {
                Cover(a.cover, Modifier.fillMaxSize(), radius = 3.dp)
                Canvas(Modifier.fillMaxSize()) {
                    // brillo del plástico y borde gastado de la funda
                    drawRect(Brush.linearGradient(listOf(Color(0x1AFFFFFF), Color(0x00FFFFFF), Color(0x00FFFFFF), Color(0x0FFFFFFF)), start = Offset.Zero, end = Offset(size.width, size.height)))
                    drawRect(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x00000000)), endY = size.height * 0.08f))
                }
                if (art == null) {
                    Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                        T(a.title, 13, FontWeight.SemiBold, maxLines = 2)
                        T(a.artist, 11, color = C.Tenue)
                    }
                }
                if (a.hiRes) Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC0B0706)).padding(horizontal = 6.dp, vertical = 3.dp)) {
                    T("HI-RES", 8, FontWeight.SemiBold, C.Oro, spacing = 1f)
                }
                if (hovered) {
                    RoundButton(
                        Ico.Play, { app.playAlbum(a) }, 42.dp, 16.dp, tint = C.Sombra, filled = C.Crema,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).graphicsLayer { alpha = out.value },
                    )
                }
            }
        }
        // repisa de nogal sobre la que descansan las fundas
        Canvas(
            Modifier.fillMaxWidth().height(18.dp).layout { m, cs ->
                val extra = 15.dp.roundToPx()
                val w = cs.maxWidth + extra * 2
                val pl = m.measure(cs.copy(minWidth = w, maxWidth = w))
                layout(cs.maxWidth, pl.height) { pl.place(-extra, 0) }
            }
        ) {
            val top = 6.dp.toPx()
            val front = size.height * 0.78f
            val tex = ShaderBrush(ImageShader(Res.plank, TileMode.Repeated, TileMode.Repeated))
            drawRect(tex, size = size.copy(height = front))
            drawRect(Brush.verticalGradient(listOf(Color(0x22FFE6C8), Color(0x00000000)), endY = top), size = size.copy(height = top))
            drawRect(Color(0x40F1DFC4), size = size.copy(height = 1f))
            drawRect(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x66000000)), startY = top, endY = front),
                topLeft = Offset(0f, top), size = size.copy(height = front - top))
            drawRect(Color(0x22000000), topLeft = Offset(0f, top), size = size.copy(height = 1f))
            drawRect(Brush.verticalGradient(listOf(Color(0xAA000000), Color(0x00000000)), startY = front, endY = size.height),
                topLeft = Offset(0f, front), size = size.copy(height = size.height - front))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDeck) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(C.RojoVivo))
                Spacer(Modifier.width(6.dp))
            }
            T(a.title, 13, FontWeight.SemiBold)
        }
        Spacer(Modifier.height(2.dp))
        T(listOfNotNull(a.artist, a.year.ifBlank { null }).joinToString(" · "), 11, color = C.Tenue)
    }
}

/** Pared de nogal con la cortina roja arriba: la estantería de la sala. Se pinta una sola vez. */
@Composable
fun WoodWall(modifier: Modifier) {
    Spacer(modifier.drawWithCache {
        val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
        CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), size) {
            drawRect(ShaderBrush(ImageShader(Res.woodBack, TileMode.Repeated, TileMode.Repeated)))
            // luz cálida que cae desde arriba
            drawRect(Brush.radialGradient(
                0f to Color(0x33FFC890), 0.55f to Color(0x0AFFC890), 1f to Color(0x00000000),
                center = Offset(size.width * 0.5f, -size.height * 0.1f), radius = size.maxDimension * 0.8f,
            ), blendMode = BlendMode.Screen)
            drawRect(Brush.radialGradient(
                0f to Color(0x00000000), 0.6f to Color(0x33000000), 1f to Color(0xCC000000),
                center = Offset(size.width * 0.5f, size.height * 0.45f), radius = size.maxDimension * 0.72f,
            ))
            // cortina roja arriba
            val vh = 64.dp.toPx()
            drawCurtain(Size(size.width, vh))
            drawRect(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x00000000), Color(0x99000000)), endY = vh), size = size.copy(height = vh))
            drawRect(Brush.verticalGradient(listOf(Color(0xAA000000), Color(0x00000000)), startY = vh, endY = vh + 26.dp.toPx()),
                topLeft = Offset(0f, vh), size = size.copy(height = 26.dp.toPx()))
            drawRect(C.Oro.copy(alpha = 0.55f), topLeft = Offset(0f, vh - 2.dp.toPx()), size = size.copy(height = 1.5.dp.toPx()))
        }
        onDrawBehind { drawImage(img) }
    })
}

@Composable
fun SongList(app: AppState) {
    val q = app.query.trim().lowercase()
    val list = remember(app.tracks, q) {
        app.tracks.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q) }
            .sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.disc }, { it.trackNo }))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 24.dp)) {
        itemsIndexed(list, key = { _, t -> t.path }) { i, t ->
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val cur = app.player.current?.path == t.path
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (cur) Color(0x22F1DFC4) else if (hovered) Color(0x12F1DFC4) else Color.Transparent)
                    .hoverable(src).pointerHoverIcon(Hand)
                    .clickable(interactionSource = src, indication = null) { app.player.setQueue(list, i, "Canciones", true) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(t.path, Modifier.size(36.dp), radius = 3.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1.4f)) {
                    T(t.title, 13, if (cur) FontWeight.SemiBold else FontWeight.Medium)
                    T(t.artist, 11, color = C.Tenue)
                }
                T(t.album, 12, color = C.Tenue, modifier = Modifier.weight(1f))
                T(t.quality, 11, color = if (t.bits > 16 || t.rate > 48000) C.Oro else C.Apagado, modifier = Modifier.width(170.dp))
                T(fmt(t.durationMs), 11, color = C.Apagado)
            }
        }
    }
}

/** Barra inferior en la estantería: lo que está sonando, siempre a mano. */
@Composable
fun NowBar(app: AppState) {
    val p = app.player
    val t = p.current ?: return
    val art = rememberArt(t.path)
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Card(Modifier.fillMaxWidth().height(76.dp)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniVinyl(art, p.playing, Modifier.size(54.dp).clip(CircleShape).clickable { app.view = View.Sala })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    T(t.title, 14, FontWeight.SemiBold)
                    T(t.artist + "  ·  " + t.quality + if (p.bitPerfect) "  ·  bit-perfect" else "", 11, color = C.Tenue)
                }
                Box(Modifier.width(260.dp)) {
                    BarProgress(p, t)
                }
                Spacer(Modifier.width(16.dp))
                RoundButton(Ico.Prev, { p.prev() })
                RoundButton(if (p.playing) Ico.Pause else Ico.Play, { p.toggle() }, 42.dp, 16.dp, tint = C.Sombra, filled = C.Crema)
                RoundButton(Ico.Next, { p.next() })
                Spacer(Modifier.width(8.dp))
                RoundButton(Ico.Room, { app.view = View.Sala })
            }
        }
    }
}

@Composable
private fun BarProgress(p: Player, t: Track) {
    ThinSlider(if (t.durationMs > 0) p.positionMs.toFloat() / t.durationMs else 0f, {}, onDone = { p.seek((it * t.durationMs).toLong()) })
}
