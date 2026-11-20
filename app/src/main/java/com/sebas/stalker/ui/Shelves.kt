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

private val WoodEdge get() = if (C.dark) Color(0xFF6E5038) else Color(0xFF9A7253)
private val WoodTop get() = if (C.dark) Color(0xFF5A3E2B) else Color(0xFF7B5638)
private val WoodBottom get() = if (C.dark) Color(0xFF3A271B) else Color(0xFF5A3C27)

/** La repisa: una tabla delgada de nogal con su sombra. Sin adornos. */
@Composable
fun Plank(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WoodEdge))
        Box(Modifier.fillMaxWidth().height(8.dp).background(Brush.verticalGradient(listOf(WoodTop, WoodBottom))))
        Box(
            Modifier.fillMaxWidth().height(10.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (C.dark) 0.45f else 0.16f), Color.Transparent)))
        )
    }
}

/** Una portada (o funda con vinilo) apoyada en la repisa. */
@Composable
private fun ShelfCover(a: Album, disc: Boolean, playing: Boolean, modifier: Modifier) {
    if (disc) {
        Sleeve(a.tracks.first(), modifier.aspectRatio(1f), fast = playing)
    } else {
        Box(modifier.aspectRatio(1f).shadow(5.dp, RoundedCornerShape(3.dp), ambientColor = C.Negro, spotColor = C.Negro)) {
            Cover(a.tracks.first(), Modifier.aspectRatio(1f), corner = 3.dp)
        }
    }
}

@Composable
private fun ShelfLabel(a: Album, size: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            a.title, style = Cuerpo.copy(fontSize = size.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(a.artist, style = Secundario.copy(fontSize = (size - 1).sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ShelfTitle(text: String) {
    Text(
        text, style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
    )
}

/** Repisa horizontal (Favoritos, Volver a escuchar): se desliza de lado. */
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
        LazyRow {
            items(albums, key = { title + it.id }) { a ->
                Column(
                    Modifier.width(coverSize + 16.dp)
                        .combinedClickable(onClick = { onOpen(a) }, onLongClick = { onLong(a) })
                ) {
                    ShelfCover(a, disc, playingAlbum == a.id, Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).width(coverSize))
                    Plank()
                    Box(Modifier.padding(horizontal = 8.dp)) { ShelfLabel(a, 12) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
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
    val gap = when (columns) { 2 -> 10.dp; 3 -> 8.dp; 4 -> 6.dp; else -> 4.dp }
    val labels = columns <= 4
    sections.forEachIndexed { si, sec ->
        if (sec.label != null) item(key = "sec$si" + sec.label) { ShelfTitle(sec.label) }
        sec.albums.chunked(columns).forEachIndexed { ri, row ->
            item(key = "row$si-$ri-" + row.first().id) {
                Row(Modifier.fillMaxWidth().padding(bottom = if (labels) 14.dp else 8.dp)) {
                    for (i in 0 until columns) {
                        val a = row.getOrNull(i)
                        Column(
                            Modifier.weight(1f)
                                .then(if (a != null) Modifier.combinedClickable(onClick = { onOpen(a) }, onLongClick = { onLong(a) }) else Modifier)
                        ) {
                            Box(Modifier.fillMaxWidth().padding(start = gap, end = gap, top = gap + 4.dp).aspectRatio(1f)) {
                                if (a != null) ShelfCover(a, disc, playingAlbum() == a.id, Modifier.fillMaxWidth())
                            }
                            Plank()
                            if (labels) Box(Modifier.padding(horizontal = gap)) {
                                if (a != null) ShelfLabel(a, if (columns >= 4) 11 else 12)
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
