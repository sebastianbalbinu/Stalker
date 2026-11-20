package com.sebas.stalker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.imageResource
import com.sebas.stalker.R
import com.sebas.stalker.Album
import com.sebas.stalker.AlbumQuality
import com.sebas.stalker.Engine
import com.sebas.stalker.tr

/**
 * Estado de la biblioteca que sobrevive al abrir un disco y volver (o al cambiar de pestaña):
 * pestaña, búsqueda y posición de cada lista.
 */
class LibraryUi {
    var tab by mutableStateOf("Álbumes")
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    private val lists = HashMap<String, LazyListState>()
    private val grids = HashMap<String, LazyGridState>()
    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }
    fun grid(key: String): LazyGridState = grids.getOrPut(key) { LazyGridState() }
}

/** Los criterios de orden de la estantería, en el orden en que se muestran. */
val SortOptions = listOf("Álbum", "Artista", "Año", "Calidad", "Recientes", "Más escuchados")

/** Un tramo de la estantería con su rótulo (letra, década, calidad…). */
data class ShelfSection(val label: String?, val albums: List<Album>)

/** Ordena los álbumes y los separa en tramos según el criterio. */
fun sortAlbums(
    albums: List<Album>,
    sort: String,
    reversed: Boolean,
    quality: Map<Long, AlbumQuality>,
    plays: Map<Long, Int>,
): List<ShelfSection> {
    fun letter(s: String): String {
        val c = s.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).first()
        return if (base.isLetter()) base.uppercaseChar().toString() else "#"
    }
    fun tier(a: Album): String {
        val q = quality[a.id] ?: return tr("Analizando…")
        return when {
            q.hiRes -> tr("Hi-Res · %s bit / %s kHz", q.bits, khz(q.rate))
            q.lossless -> tr("Calidad CD · sin pérdida")
            else -> tr("Con pérdida (%s)", q.codec)
        }
    }
    val byTitle = compareBy<Album> { it.title.lowercase() }
    val sorted: List<Album> = when (sort) {
        "Artista" -> albums.sortedWith(compareBy<Album> { it.artist.lowercase() }.thenBy { it.year }.then(byTitle))
        "Año" -> albums.sortedWith(compareBy<Album> { if (it.year > 0) it.year else Int.MAX_VALUE }.then(byTitle))
        "Calidad" -> albums.sortedWith(compareByDescending<Album> { quality[it.id]?.score ?: -1L }.then(byTitle))
        "Recientes" -> albums.sortedByDescending { a -> a.tracks.maxOf { it.id } }
        "Más escuchados" -> albums.sortedWith(
            compareByDescending<Album> { a -> a.tracks.sumOf { plays[it.id] ?: 0 } }.then(byTitle)
        )
        else -> albums.sortedWith(byTitle)
    }.let { if (reversed) it.reversed() else it }

    val key: ((Album) -> String)? = when (sort) {
        "Álbum" -> { a -> letter(a.title) }
        "Artista" -> { a -> letter(a.artist) }
        "Año" -> { a -> if (a.year > 0) "${a.year / 10 * 10}s" else tr("Sin año") }
        "Calidad" -> ::tier
        else -> null
    }
    if (key == null) return listOf(ShelfSection(null, sorted))
    val out = ArrayList<ShelfSection>()
    var label: String? = null
    var bucket = ArrayList<Album>()
    for (a in sorted) {
        val k = key(a)
        if (k != label && bucket.isNotEmpty()) { out += ShelfSection(label, bucket); bucket = ArrayList() }
        label = k
        bucket += a
    }
    if (bucket.isNotEmpty()) out += ShelfSection(label, bucket)
    return out
}

private fun khz(rate: Int): String =
    if (rate % 1000 == 0) "${rate / 1000}" else String.format("%.1f", rate / 1000.0)

/* ---------------------------------------------------------------- madera */

/** Texto claro sobre la madera. */
private val Crema = Color(0xFFEFE4D2)
private val Dorado = Color(0xFFD9BE8C)

/** Las texturas de nogal (las mismas del PC), cargadas una sola vez. */
@Composable
private fun woodBack(): ImageBitmap = ImageBitmap.imageResource(R.drawable.madera_fondo)
@Composable
private fun woodPlank(): ImageBitmap = ImageBitmap.imageResource(R.drawable.madera_repisa)

/** Pinceles de madera creados una sola vez (no en cada cuadro al deslizar). */
@Composable
private fun rememberWood(img: ImageBitmap): ShaderBrush =
    remember(img) { ShaderBrush(ImageShader(img, TileMode.Repeated, TileMode.Repeated)) }

private const val PLANK_TOP = 4   // cara de arriba de la tabla (dp)
private const val PLANK_FRONT = 11 // canto frontal (dp)
private const val PLANK_SHADOW = 14 // sombra sobre el fondo (dp)
/** Alto total que ocupa una repisa con su sombra. */
val PlankHeight = (PLANK_TOP + PLANK_FRONT + PLANK_SHADOW).dp

/**
 * El mueble: fondo de nogal de veta vertical, laterales con canto y una cornisa arriba.
 * Ocupa todo el ancho de la pantalla (sin márgenes crema).
 */
@Composable
fun WoodCabinet(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val back = rememberWood(woodBack())
    val woodH = rememberWood(woodPlank())
    val stile = 9.dp
    Box(
        modifier
            .fullBleed(22.dp)
            .drawBehind {
                // Fondo del mueble (la veta es vertical: al deslizar no se nota el corte).
                drawRect(back)
                drawRect(Color.Black.copy(alpha = 0.18f))
                // Luz cálida que cae desde arriba y rincones en penumbra.
                drawRect(
                    Brush.radialGradient(
                        0f to Color(0x2EFFC890), 1f to Color.Transparent,
                        center = Offset(size.width / 2, -size.width * 0.2f), radius = size.width * 1.1f
                    )
                )
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.45f), 0.12f to Color.Transparent,
                        0.88f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)
                    )
                )
            }
            .drawWithContent {
                drawContent()
                val sw = stile.toPx()
                // Laterales del mueble con su canto iluminado.
                for (left in listOf(true, false)) {
                    val x = if (left) 0f else size.width - sw
                    drawRect(back, Offset(x, 0f), Size(sw, size.height))
                    drawRect(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Size(sw, size.height))
                    val edge = if (left) x + sw - 1.dp.toPx() else x
                    drawRect(Color(0x55E9C9A0), Offset(edge, 0f), Size(1.dp.toPx(), size.height))
                    val sh = 10.dp.toPx()
                    drawRect(
                        Brush.horizontalGradient(
                            if (left) listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent) else listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                            startX = if (left) x + sw else x - sh, endX = if (left) x + sw + sh else x
                        ),
                        Offset(if (left) x + sw else x - sh, 0f), Size(sh, size.height)
                    )
                }
                // Cornisa de arriba.
                val ch = 12.dp.toPx()
                drawRect(woodH, Offset.Zero, Size(size.width, ch))
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.35f)), endY = ch),
                    Offset.Zero, Size(size.width, ch)
                )
                drawRect(Color(0x66E9C9A0), Offset(0f, ch - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                drawRect(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = ch, endY = ch + 16.dp.toPx()),
                    Offset(0f, ch), Size(size.width, 16.dp.toPx())
                )
            }
            .padding(start = stile + 6.dp, end = stile + 6.dp, top = 12.dp),
        content = content
    )
}

/** Ignora el margen lateral de la hoja para que el mueble llegue de borde a borde. */
private fun Modifier.fullBleed(side: Dp): Modifier = layout { m, c ->
    val p = side.roundToPx()
    val w = c.maxWidth + 2 * p
    val pl = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, pl.height) { pl.place(-p, 0) }
}

/** La repisa: tabla de nogal con cara de arriba iluminada, canto frontal y sombra sobre el fondo. */
@Composable
fun Plank(modifier: Modifier = Modifier) {
    val wood = rememberWood(woodPlank())
    Canvas(modifier.fillMaxWidth().height(PlankHeight)) {
        val top = PLANK_TOP.dp.toPx()
        val front = PLANK_FRONT.dp.toPx()
        // Cara de arriba, donde se apoyan los discos.
        drawRect(wood, Offset.Zero, Size(size.width, top))
        drawRect(Color.White.copy(alpha = 0.16f), Offset.Zero, Size(size.width, top))
        drawRect(Color.Black.copy(alpha = 0.25f), Offset.Zero, Size(size.width, 1.dp.toPx()))
        // Canto frontal.
        drawRect(wood, Offset(0f, top), Size(size.width, front))
        drawRect(
            Brush.verticalGradient(listOf(Color(0x30FFE2B8), Color.Transparent, Color.Black.copy(alpha = 0.35f)), startY = top, endY = top + front),
            Offset(0f, top), Size(size.width, front)
        )
        drawRect(Color(0x70F0D3A8), Offset(0f, top), Size(size.width, 1.dp.toPx()))
        // Sombra que la tabla proyecta sobre el fondo del mueble.
        drawRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), startY = top + front, endY = size.height),
            Offset(0f, top + front), Size(size.width, size.height - top - front)
        )
    }
}

/** Una portada (o funda con vinilo) apoyada en la repisa. */
@Composable
private fun ShelfCover(a: Album, disc: Boolean, playing: Boolean, modifier: Modifier) {
    if (disc) {
        Sleeve(a.tracks.first(), modifier.aspectRatio(1f), fast = playing)
    } else {
        Box(modifier.aspectRatio(1f).shadow(8.dp, RoundedCornerShape(2.dp), ambientColor = Color.Black, spotColor = Color.Black)) {
            Cover(a.tracks.first(), Modifier.aspectRatio(1f), corner = 2.dp)
        }
    }
}

@Composable
private fun ShelfLabel(a: Album?, size: Int) {
    Column(Modifier.fillMaxWidth().height((size * 2 + 8).dp)) {
        if (a == null) return@Column
        Text(
            a.title, style = Cuerpo.copy(fontSize = size.sp, fontWeight = FontWeight.SemiBold, color = Crema),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(a.artist, style = Secundario.copy(fontSize = (size - 1).sp, color = Crema.copy(alpha = 0.62f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Rótulo dentro del mueble (Favoritos, una letra, una década…). */
@Composable
fun ShelfTitle(text: String) {
    Text(
        text, style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = Dorado),
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp)
    )
}

/** Repisa horizontal (Favoritos, Volver a escuchar): los discos se deslizan sobre una tabla fija. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfRow(
    title: String,
    albums: List<Album>,
    disc: Boolean,
    coverSize: Dp,
    playingAlbum: Long?,
    onOpen: (Album) -> Unit,
    onLong: (Album) -> Unit,
) {
    Column {
        ShelfTitle(title)
        Box {
            Plank(Modifier.padding(top = 10.dp + coverSize))
            LazyRow {
                items(albums, key = { title + it.id }) { a ->
                    Column(
                        Modifier.width(coverSize + 14.dp).padding(horizontal = 7.dp)
                            .combinedClickable(onClick = { onOpen(a) }, onLongClick = { onLong(a) })
                    ) {
                        Spacer(Modifier.height(10.dp))
                        ShelfCover(a, disc, playingAlbum == a.id, Modifier.width(coverSize))
                        Spacer(Modifier.height((PLANK_TOP + PLANK_FRONT + 4).dp))
                        ShelfLabel(a, 12)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** Toda la colección en repisas de [columns] discos, con rótulos por tramo. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.shelfGrid(
    sections: List<ShelfSection>,
    columns: Int,
    disc: Boolean,
    playingAlbum: () -> Long?,
    onOpen: (Album) -> Unit,
    onLong: (Album) -> Unit,
) {
    val gap = when (columns) { 2 -> 12.dp; 3 -> 8.dp; 4 -> 6.dp; else -> 4.dp }
    val labels = columns <= 4
    val labelSize = if (columns >= 4) 11 else 12
    sections.forEachIndexed { si, sec ->
        if (sec.label != null) item(key = "sec$si" + sec.label) { ShelfTitle(sec.label) }
        sec.albums.chunked(columns).forEachIndexed { ri, row ->
            item(key = "row$si-$ri-" + row.first().id) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(top = gap + 8.dp)) {
                        for (i in 0 until columns) {
                            val a = row.getOrNull(i)
                            Box(
                                Modifier.weight(1f).padding(horizontal = gap)
                                    .then(if (a != null) Modifier.combinedClickable(onClick = { onOpen(a) }, onLongClick = { onLong(a) }) else Modifier)
                            ) {
                                if (a != null) ShelfCover(a, disc, playingAlbum() == a.id, Modifier.fillMaxWidth())
                                else Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                            }
                        }
                    }
                    // Una sola tabla para toda la fila: la veta corre de lado a lado sin cortes.
                    Box {
                        Plank()
                        if (labels) Row(Modifier.fillMaxWidth().padding(top = (PLANK_TOP + PLANK_FRONT + 4).dp)) {
                            for (i in 0 until columns) {
                                Box(Modifier.weight(1f).padding(horizontal = gap)) { ShelfLabel(row.getOrNull(i), labelSize) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pellizcar con dos dedos cambia cuántos discos caben por repisa (2 a 5). El scroll normal no se toca. */
fun Modifier.pinchToColumns(onChange: (Int) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zoom = 1f
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    zoom *= event.calculateZoom()
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    if (zoom > 1.3f) { onChange(-1); zoom = 1f }
                    else if (zoom < 0.77f) { onChange(+1); zoom = 1f }
                }
            } while (event.changes.any { it.pressed })
        }
    }
)

/** Hoja para ordenar la estantería y elegir cuántos discos van por repisa. */
@Composable
fun SortSheet(onDismiss: () -> Unit) {
    val sort by Engine.librarySort.collectAsState()
    val reversed by Engine.sortReversed.collectAsState()
    val columns by Engine.libraryColumns.collectAsState()
    StalkerSheet(onDismiss) {
        Text(tr("Ordenar estantería"), style = Titulo.copy(fontSize = 24.sp))
        Spacer(Modifier.height(14.dp))
        Card {
            SortOptions.forEachIndexed { i, o ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { Engine.setLibrarySort(o) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr(o), style = Cuerpo.copy(fontWeight = FontWeight.Medium, color = if (sort == o) C.Rojo else C.Carbon), modifier = Modifier.weight(1f))
                    if (sort == o) Icon(Ico.Check, null, tint = C.Rojo, modifier = Modifier.width(20.dp))
                }
                if (i < SortOptions.size - 1) HorizontalDivider(color = C.Arena, thickness = 1.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(naturalLabel(sort), selected = !reversed, modifier = Modifier.weight(1f)) { Engine.setSortReversed(false) }
            Pill(reversedLabel(sort), selected = reversed, modifier = Modifier.weight(1f)) { Engine.setSortReversed(true) }
        }
        Spacer(Modifier.height(18.dp))
        Text(tr("DISCOS POR REPISA"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        Text(tr("También puedes pellizcar la estantería con dos dedos."), style = Secundario.copy(fontSize = 12.sp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..5).forEach { n ->
                Pill("$n", selected = columns == n, modifier = Modifier.weight(1f)) { Engine.setLibraryColumns(n) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun naturalLabel(sort: String) = when (sort) {
    "Año" -> "Más antiguos primero"
    "Calidad" -> "Mejor calidad primero"
    "Recientes" -> "Más nuevos primero"
    "Más escuchados" -> "Más escuchados primero"
    else -> "A → Z"
}

private fun reversedLabel(sort: String) = when (sort) {
    "Año" -> "Más nuevos primero"
    "Calidad" -> "Menor calidad primero"
    "Recientes" -> "Más antiguos primero"
    "Más escuchados" -> "Menos escuchados primero"
    else -> "Z → A"
}

@Composable
fun rememberColumnsChanger(): (Int) -> Unit {
    val columns by Engine.libraryColumns.collectAsState()
    val current by rememberUpdatedState(columns)
    return { delta -> Engine.setLibraryColumns(current + delta) }
}
