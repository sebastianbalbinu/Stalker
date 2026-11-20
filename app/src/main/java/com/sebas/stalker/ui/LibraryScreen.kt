package com.sebas.stalker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.tr
import com.sebas.stalker.Album
import com.sebas.stalker.Disco
import com.sebas.stalker.Engine
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.Sharing
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.sebas.stalker.Track

/** A qué pantalla de detalle entró el usuario desde la biblioteca. */
sealed class Detail {
    data class OfAlbum(val albumId: Long) : Detail()
    data class OfDisco(val discoId: Long) : Detail()
    data class PickSongs(val discoId: Long) : Detail()
}

private fun matches(q: String, vararg fields: String): Boolean =
    q.isBlank() || fields.any { it.contains(q.trim(), ignoreCase = true) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpen: (Detail) -> Unit,
    onImportDisc: () -> Unit = {},
    ui: LibraryUi = remember { LibraryUi() },
) {
    val all by Engine.tracks.collectAsState()
    val view by Engine.libraryView.collectAsState()
    val discView = view == "Discos"
    val playing by vm.isPlaying.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    val favs by Engine.favorites.collectAsState()
    val discos by Engine.discos.collectAsState()
    val scanned by Engine.scanned.collectAsState()
    val lastPlayed by Engine.albumLastPlayed.collectAsState()
    val current by vm.current.collectAsState()

    // Viven fuera de esta pantalla: al volver de un disco todo sigue donde estaba.
    var tab by ui::tab
    var searching by ui::searching
    var query by ui::query
    var sorting by remember { mutableStateOf(false) }
    val columns by Engine.libraryColumns.collectAsState()
    val sort by Engine.librarySort.collectAsState()
    val reversed by Engine.sortReversed.collectAsState()
    val quality by Engine.albumQuality.collectAsState()
    val plays by Engine.playCounts.collectAsState()
    val changeColumns = rememberColumnsChanger()
    var albumMenu by remember { mutableStateOf<Album?>(null) }
    var songMenu by remember { mutableStateOf<Track?>(null) }
    var addToDisco by remember { mutableStateOf<List<Long>?>(null) }
    var naming by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val tracks = remember(all, hAlbums, hTracks) { Engine.visible(all, hAlbums, hTracks) }
    val albums = remember(tracks) { Engine.albums(tracks) }
    val songs = remember(tracks) { tracks.sortedBy { it.title.lowercase() } }

    val shownAlbums = albums.filter { matches(query, it.title, it.artist) }
    val recent = remember(albums, lastPlayed) {
        albums.filter { it.id in lastPlayed }.sortedByDescending { lastPlayed[it.id] ?: 0L }.take(10)
    }
    val shownFavs = albums.filter { it.id in favs && matches(query, it.title, it.artist) }
    val shownSongs = songs.filter { matches(query, it.title, it.artist, it.album) }
    val shownDiscos = discos.filter { matches(query, it.name) }
    val received = remember(tracks) { tracks.filter { it.source != null }.sortedByDescending { it.id } }
    val shownReceived = received.filter { matches(query, it.title, it.artist, it.album) }
    val currentAlbum = current?.albumId

    CurtainSheet {
        ScreenHeader(
            tr("Biblioteca"),
            leading = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoftButton(if (searching) Ico.Close else Ico.Search, if (searching) tr("Cerrar búsqueda") else tr("Buscar")) {
                        searching = !searching
                        if (!searching) query = ""
                    }
                    SoftButton(Ico.Shuffle, if (tab == "Favoritos") tr("Favoritos al azar") else tr("Rocola · todo al azar")) {
                        vm.rocola(onlyFavorites = tab == "Favoritos")
                    }
                }
            },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoftButton(Ico.Sort, tr("Ordenar estantería")) { sorting = true }
                    SoftButton(if (discView) Ico.Library else Ico.Disc, if (discView) tr("Ver portadas") else tr("Ver como discos")) {
                        Engine.setLibraryView(if (discView) "Portadas" else "Discos")
                    }
                }
            },
            sidePadding = 104.dp,
        )
        if (searching) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(tr("Canción, artista o álbum"), style = Cuerpo.copy(color = C.Piedra)) },
                leadingIcon = { Icon(Ico.Search, null, tint = C.Tierra, modifier = Modifier.size(20.dp)) },
                textStyle = Cuerpo,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Rojo,
                    unfocusedBorderColor = C.Linea,
                    cursorColor = C.Rojo,
                    focusedContainerColor = C.Tarjeta,
                    unfocusedContainerColor = C.Tarjeta,
                ),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            (listOf("Álbumes", "Mis discos", "Favoritos") + (if (received.isNotEmpty()) listOf("Recibidos") else emptyList()) + "Canciones").forEach { l ->
                Pill(l, selected = tab == l) { tab = l }
            }
        }
        Spacer(Modifier.height(18.dp))

        val bottom = if (current != null) 200.dp else 120.dp
        Box(Modifier.fillMaxSize()) {
            when {
                !hasPermission -> EmptyHint(
                    tr("Necesito permiso"),
                    tr("Stalker lee la música guardada en tu teléfono."),
                    tr("Dar permiso"), onRequestPermission
                )
                scanned && all.isEmpty() -> EmptyHint(
                    tr("Sin música aún"),
                    tr("Copia tus FLAC a la carpeta Música del teléfono y toca actualizar."),
                    tr("Actualizar")
                ) { vm.rescan() }

                tab == "Álbumes" || tab == "Favoritos" -> {
                    val list = if (tab == "Álbumes") shownAlbums else shownFavs
                    if (list.isEmpty()) {
                        EmptyHint(
                            if (tab == "Favoritos") tr("Sin favoritos") else tr("Nada por aquí"),
                            if (tab == "Favoritos") tr("Mantén presionado un álbum, o toca el corazón dentro de él, para guardarlo aquí.")
                            else tr("No hay álbumes que coincidan.")
                        )
                    } else {
                        val sections = remember(list, sort, reversed, quality, plays) {
                            sortAlbums(list, sort, reversed, quality, plays)
                        }
                        val favAlbums = remember(albums, favs) { albums.filter { it.id in favs }.sortedBy { it.title.lowercase() } }
                        val openAlbum: (Album) -> Unit = { onOpen(Detail.OfAlbum(it.id)) }
                        val longAlbum: (Album) -> Unit = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); albumMenu = it }
                        val nowAlbum = if (playing) currentAlbum else null
                        WoodCabinet(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = ui.list(if (tab == "Álbumes") "albums" else "favs"),
                            contentPadding = PaddingValues(bottom = bottom),
                            modifier = Modifier.fillMaxSize().pinchToColumns(changeColumns),
                        ) {
                            if (tab == "Álbumes" && query.isBlank()) {
                                if (favAlbums.isNotEmpty()) item(key = "favoritos") {
                                    ShelfRow(tr("FAVORITOS"), favAlbums, discView, 124.dp, nowAlbum, openAlbum, longAlbum)
                                }
                                if (recent.isNotEmpty()) item(key = "recientes") {
                                    ShelfRow(tr("VOLVER A ESCUCHAR"), recent, discView, 100.dp, nowAlbum, openAlbum, longAlbum)
                                }
                                item(key = "todos") { ShelfTitle(tr("TODOS LOS ÁLBUMES") + " · " + tr(sort).uppercase()) }
                            }
                            shelfGrid(sections, columns, discView, { nowAlbum }, openAlbum, longAlbum)
                        }
                        }
                    }
                }

                tab == "Mis discos" -> LazyVerticalGrid(
                    state = ui.grid("discos"),
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = bottom),
                ) {
                    item(span = { GridItemSpan(1) }) { NewDiscoCard { naming = true } }
                    item(span = { GridItemSpan(1) }) { ImportDiscCard(onImportDisc) }
                    items(shownDiscos, key = { it.id }) { d ->
                        val dt = Engine.discoTracks(d)
                        SleeveCard(
                            cover = dt.firstOrNull(),
                            title = d.name,
                            subtitle = if (dt.size == 1) tr("1 canción") else tr("%s canciones", dt.size),
                            onClick = { onOpen(Detail.OfDisco(d.id)) },
                            onLong = null,
                            fast = playing && Engine.queueTitle.value == d.name,
                            mine = true,
                        )
                    }
                }

                tab == "Recibidos" -> LazyColumn(state = ui.list("recibidos"), contentPadding = PaddingValues(bottom = bottom)) {
                    item {
                        Text(
                            tr("Canciones que te mandaron por WhatsApp o Telegram. Las notas de voz nunca aparecen aquí."),
                            style = Secundario.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    itemsIndexed(shownReceived, key = { _, t -> "rc" + t.id }) { i, t ->
                        SongRow(
                            t, playing = current?.id == t.id, disc = discView,
                            onClick = { vm.playList(shownReceived, i, tr("Recibidos")) },
                            onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); songMenu = t },
                        )
                    }
                }

                else -> LazyColumn(state = ui.list("canciones"), contentPadding = PaddingValues(bottom = bottom)) {
                    itemsIndexed(shownSongs, key = { _, t -> t.id }) { i, t ->
                        SongRow(
                            t, playing = current?.id == t.id, disc = discView,
                            onClick = { vm.playList(shownSongs, i, tr("Canciones")) },
                            onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); songMenu = t },
                        )
                    }
                }
            }
        }
    }

    albumMenu?.let { a ->
        val fav = a.id in favs
        ActionSheet(
            a.title, a.artist,
            listOf(
                SheetAction(tr("Reproducir después"), Ico.PlayNext) { vm.playNext(a.tracks) },
                SheetAction(tr("Añadir a la cola"), Ico.QueueAdd) { vm.addToQueue(a.tracks) },
                SheetAction(if (fav) tr("Quitar de favoritos") else tr("Añadir a favoritos"), if (fav) Ico.HeartFull else Ico.Heart) { Engine.toggleFavorite(a.id) },
                SheetAction(tr("Añadir a un disco"), Ico.Disc) { addToDisco = a.tracks.map { it.id } },
                SheetAction(tr("Ocultar de la biblioteca"), Ico.Hide, destructive = true) { Engine.hideAlbum(a.id) },
            ),
            onDismiss = { albumMenu = null }
        )
    }
    songMenu?.let { t ->
        ActionSheet(
            t.title, t.artist,
            listOf(
                SheetAction(tr("Reproducir después"), Ico.PlayNext) { vm.playNext(listOf(t)) },
                SheetAction(tr("Añadir a la cola"), Ico.QueueAdd) { vm.addToQueue(listOf(t)) },
                SheetAction(tr("Añadir a un disco"), Ico.Disc) { addToDisco = listOf(t.id) },
                SheetAction(tr("Ocultar canción"), Ico.Hide, destructive = true) { Engine.hideTrack(t.id) },
            ),
            onDismiss = { songMenu = null }
        )
    }
    addToDisco?.let { ids -> AddToDiscoSheet(ids) { addToDisco = null } }
    if (sorting) SortSheet { sorting = false }
    if (naming) {
        NameDialog(tr("Nuevo disco"), "", tr("Crear"), onDismiss = { naming = false }) { name ->
            val d = Engine.createDisco(name)
            onOpen(Detail.OfDisco(d.id))
            onOpen(Detail.PickSongs(d.id))
        }
    }
}

/** Rocola: un botón ancho sobre la estantería para escuchar todo al azar. */
@Composable
fun RocolaButton(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Tarjeta)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(C.Rojo), contentAlignment = Alignment.Center) {
            Icon(Ico.Shuffle, null, tint = C.SobreRojo, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Ico.Play, null, tint = C.Rojo, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(a: Album, favorite: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLong),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = C.Negro, spotColor = C.Negro)
        ) {
            Cover(a.tracks.first(), Modifier.fillMaxSize())
            if (favorite) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(28.dp).clip(CircleShape).background(C.Marfil),
                    contentAlignment = Alignment.Center
                ) { Icon(Ico.HeartFull, tr("Favorito"), tint = C.Rojo, modifier = Modifier.size(16.dp)) }
            }
        }
        Column {
            Text(a.title, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.artist, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Funda con el vinilo asomando y girando: así se ve la colección en modo discos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SleeveCard(
    cover: Track?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLong: (() -> Unit)?,
    fast: Boolean = false,
    favorite: Boolean = false,
    mine: Boolean = false,
) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLong),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Sleeve(cover, Modifier.fillMaxWidth().aspectRatio(1f), fast, favorite, mine)
        Column {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** La funda y el vinilo, sin textos. */
@Composable
fun Sleeve(cover: Track?, modifier: Modifier, fast: Boolean = false, favorite: Boolean = false, mine: Boolean = false) {
    Box(modifier) {
        MiniVinyl(
            cover,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.86f).aspectRatio(1f)
                .shadow(6.dp, CircleShape, ambientColor = C.Negro, spotColor = C.Negro),
            fast = fast
        )
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight(0.86f).aspectRatio(1f)
                .shadow(10.dp, RoundedCornerShape(4.dp), ambientColor = C.Negro, spotColor = C.Negro)
        ) {
            Cover(cover, Modifier.fillMaxSize(), corner = 4.dp)
            if (mine) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(C.Rojo).padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(tr("MI DISCO"), style = Cuerpo.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = C.SobreRojo, fontWeight = FontWeight.SemiBold)) }
            }
            if (favorite) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(C.Marfil),
                    contentAlignment = Alignment.Center
                ) { Icon(Ico.HeartFull, tr("Favorito"), tint = C.Rojo, modifier = Modifier.size(14.dp)) }
            }
        }
    }
}

@Composable
fun ImportDiscCard(onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(C.Arena),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(C.Negro), contentAlignment = Alignment.Center) {
                Icon(Ico.Download, null, tint = C.SobreRojo, modifier = Modifier.size(24.dp))
            }
        }
        Column {
            Text(tr("Abrir disco"), style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
            Text(tr("Uno que te compartieron"), style = Secundario)
        }
    }
}

@Composable
fun NewDiscoCard(onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 12.dp)
                .clip(RoundedCornerShape(12.dp)).background(C.Arena),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(C.Rojo), contentAlignment = Alignment.Center) {
                Icon(Ico.Plus, null, tint = C.SobreRojo, modifier = Modifier.size(26.dp))
            }
        }
        Column {
            Text(tr("Nuevo disco"), style = Cuerpo.copy(fontWeight = FontWeight.SemiBold, color = C.Rojo))
            Text(tr("Arma tu propio vinilo"), style = Secundario)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(t: Track, playing: Boolean, onClick: () -> Unit, onLong: () -> Unit, disc: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (disc) MiniVinyl(t, Modifier.size(48.dp), fast = playing) else Cover(t, Modifier.size(48.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                t.title, style = Cuerpo.copy(color = if (playing) C.Rojo else C.Carbon, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.source != null) {
                    Box(
                        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(5.dp)).background(C.Arena)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text(t.source, style = Secundario.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold)) }
                }
                Text(t.artist, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(fmtTime(t.durationMs), style = Secundario.copy(fontSize = 12.sp))
    }
}

/** Detalle de un álbum o de un disco propio. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(detail: Detail, vm: PlayerViewModel, onBack: () -> Unit, onOpen: (Detail) -> Unit) {
    val all by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    val favs by Engine.favorites.collectAsState()
    val discos by Engine.discos.collectAsState()
    val current by vm.current.collectAsState()
    val haptic = LocalHapticFeedback.current

    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    val view by Engine.libraryView.collectAsState()
    val playingNow by vm.isPlaying.collectAsState()
    val queueTitle by Engine.queueTitle.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var songMenu by remember { mutableStateOf<Track?>(null) }
    var addToDisco by remember { mutableStateOf<List<Long>?>(null) }

    val disco: Disco? = (detail as? Detail.OfDisco)?.let { d -> discos.firstOrNull { it.id == d.discoId } }
    val album: Album? = (detail as? Detail.OfAlbum)?.let { d ->
        Engine.albums(Engine.visible(all, hAlbums, hTracks).filter { it.albumId == d.albumId }).firstOrNull()
    }
    val tracks: List<Track> = disco?.let { Engine.discoTracks(it) } ?: album?.tracks ?: emptyList()
    val title = disco?.name ?: album?.title ?: ""
    val artist = when {
        album != null -> album.artist
        else -> tracks.map { it.artist }.distinct().let { if (it.size == 1) it.first() else tr("Varios artistas") }
    }
    val total = tracks.sumOf { it.durationMs }
    val year = if ((album?.year ?: 0) > 0) "${album?.year} · " else ""

    if (disco == null && album == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Back, tr("Volver"), onClick = onBack)
            Spacer(Modifier.weight(1f))
            if (album != null) {
                val fav = album.id in favs
                SoftButton(if (fav) Ico.HeartFull else Ico.Heart, if (fav) tr("Quitar de favoritos") else tr("Añadir a favoritos"), tint = C.Rojo) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    Engine.toggleFavorite(album.id)
                }
                Spacer(Modifier.width(10.dp))
            }
            SoftButton(Ico.More, tr("Más opciones")) { menu = true }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (view == "Discos") {
                Sleeve(
                    tracks.firstOrNull(), Modifier.size(156.dp),
                    fast = playingNow && queueTitle == title, mine = disco != null
                )
            } else {
                Box(Modifier.size(132.dp).shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = C.Negro, spotColor = C.Negro)) {
                    Cover(tracks.firstOrNull(), Modifier.fillMaxSize())
                }
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (disco != null) Text(tr("MI DISCO"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, color = C.Rojo, fontWeight = FontWeight.SemiBold))
                Text(title, style = Titulo.copy(fontSize = 28.sp, lineHeight = 32.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(artist, style = Cuerpo.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("%s%s canciones · %s min", year, tracks.size, total / 60000), style = Secundario.copy(fontSize = 12.sp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (tracks.isEmpty()) C.Piedra else C.Rojo)
                    .clickable(enabled = tracks.isNotEmpty()) { vm.playList(tracks, 0, title) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Ico.Play, null, tint = C.SobreRojo, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Poner el disco"), style = Cuerpo.copy(color = C.SobreRojo, fontWeight = FontWeight.Medium))
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.Arena)
                    .clickable(enabled = tracks.isNotEmpty()) { vm.playList(tracks.shuffled(), 0, title) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Ico.Shuffle, null, tint = C.Carbon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Aleatorio"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (disco != null && tracks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                EmptyHint(tr("Disco vacío"), tr("Añade canciones para armar tu vinilo."), tr("Añadir canciones")) {
                    onOpen(Detail.PickSongs(disco.id))
                }
            }
        } else {
            val half = (tracks.size + 1) / 2
            val sideA = tracks.take(half).sumOf { it.durationMs }
            val sideB = tracks.drop(half).sumOf { it.durationMs }
            if (ordering && disco != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Arena).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(tr("Usa las flechas para ordenar tu disco"), style = Secundario.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                    Pill("Listo", selected = true) { ordering = false }
                }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 200.dp)) {
                itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                    if (tracks.size >= 4 && (i == 0 || i == half)) {
                        val side = if (i == 0) sideA else sideB
                        val long = disco != null && side > 22 * 60_000L
                        Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (i == 0) tr("LADO A") else tr("LADO B")) + " · " + fmtTime(side),
                                style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = if (long) C.Rojo else C.Tierra)
                            )
                            if (long) Text(tr("  más largo que un LP (22 min)"), style = Secundario.copy(fontSize = 11.sp, color = C.Rojo))
                        }
                    }
                    val on = current?.id == t.id
                    Row(
                        Modifier.fillMaxWidth().height(56.dp)
                            .combinedClickable(
                                onClick = { vm.playList(tracks, i, title) },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); songMenu = t }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val no = if (disco != null) i + 1 else t.trackNo
                        Text(
                            if (no > 0) "%02d".format(no) else "·",
                            style = Secundario.copy(fontSize = 12.sp, color = if (on) C.Rojo else C.Tierra),
                            modifier = Modifier.width(32.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                style = Cuerpo.copy(color = if (on) C.Rojo else C.Carbon, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (disco != null) Text(t.artist, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (ordering && disco != null) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = i > 0) { Engine.moveInDisco(disco.id, i, i - 1) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Ico.Up, tr("Subir"), tint = if (i > 0) C.Carbon else C.Linea, modifier = Modifier.size(18.dp)) }
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable(enabled = i < tracks.size - 1) { Engine.moveInDisco(disco.id, i, i + 1) },
                                contentAlignment = Alignment.Center
                            ) { Icon(Ico.Down, tr("Bajar"), tint = if (i < tracks.size - 1) C.Carbon else C.Linea, modifier = Modifier.size(18.dp)) }
                        } else {
                            Text(fmtTime(t.durationMs), style = Secundario.copy(fontSize = 12.sp, color = if (on) C.Rojo else C.Tierra))
                        }
                    }
                    HorizontalDivider(color = C.Linea, thickness = 1.dp)
                }
            }
        }
    }

    if (menu) {
        val queueActions = listOf(
            SheetAction(tr("Reproducir después"), Ico.PlayNext) { vm.playNext(tracks) },
            SheetAction(tr("Añadir a la cola"), Ico.QueueAdd) { vm.addToQueue(tracks) },
        )
        val share = SheetAction(tr("Compartir disco"), Ico.Upload) {
            scope.launch {
                val uri = Sharing.exportDisc(ctx, title, tracks)
                if (uri != null) ctx.startActivity(Sharing.shareIntent(uri, title))
                else android.widget.Toast.makeText(ctx, tr("No se pudo preparar el disco"), android.widget.Toast.LENGTH_LONG).show()
            }
        }
        val actions = queueActions + share + if (disco != null) listOf(
            SheetAction(tr("Ordenar canciones"), Ico.ListIco) { ordering = true },
            SheetAction(tr("Añadir canciones"), Ico.Plus) { onOpen(Detail.PickSongs(disco.id)) },
            SheetAction(tr("Cambiar nombre"), Ico.Pencil) { renaming = true },
            SheetAction(tr("Eliminar disco"), Ico.Trash, destructive = true) { Engine.deleteDisco(disco.id) },
        ) else listOf(
            SheetAction(tr("Añadir a un disco"), Ico.Disc) { addToDisco = tracks.map { it.id } },
            SheetAction(tr("Ocultar de la biblioteca"), Ico.Hide, destructive = true) { album?.let { Engine.hideAlbum(it.id) } },
        )
        ActionSheet(title, artist, actions, onDismiss = { menu = false })
    }
    songMenu?.let { t ->
        val queueActions = listOf(
            SheetAction(tr("Reproducir después"), Ico.PlayNext) { vm.playNext(listOf(t)) },
            SheetAction(tr("Añadir a la cola"), Ico.QueueAdd) { vm.addToQueue(listOf(t)) },
        )
        val actions = queueActions + if (disco != null) listOf(
            SheetAction(tr("Quitar de este disco"), Ico.Close, destructive = true) {
                Engine.setDiscoTracks(disco.id, disco.trackIds.filter { it != t.id })
            },
        ) else listOf(
            SheetAction(tr("Añadir a un disco"), Ico.Disc) { addToDisco = listOf(t.id) },
            SheetAction(tr("Ocultar canción"), Ico.Hide, destructive = true) { Engine.hideTrack(t.id) },
        )
        ActionSheet(t.title, t.artist, actions, onDismiss = { songMenu = null })
    }
    addToDisco?.let { ids -> AddToDiscoSheet(ids) { addToDisco = null } }
    if (renaming && disco != null) {
        NameDialog(tr("Cambiar nombre"), disco.name, tr("Guardar"), onDismiss = { renaming = false }) { Engine.renameDisco(disco.id, it) }
    }
}

/** Elegir canciones para un disco propio. */
@Composable
fun PickSongsScreen(discoId: Long, onDone: () -> Unit) {
    val all by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    val discos by Engine.discos.collectAsState()
    val disco = discos.firstOrNull { it.id == discoId }
    val songs = remember(all, hAlbums, hTracks) {
        Engine.visible(all, hAlbums, hTracks).sortedWith(compareBy<Track> { it.artist.lowercase() }.thenBy { it.album.lowercase() }.thenBy { it.trackNo })
    }
    val selected = remember(discoId) { mutableStateListOf<Long>().apply { addAll(disco?.trackIds ?: emptyList()) } }
    var query by remember { mutableStateOf("") }
    val shown = songs.filter { matches(query, it.title, it.artist, it.album) }

    if (disco == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Close, tr("Cancelar"), onClick = onDone)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(disco.name, style = Titulo.copy(fontSize = 22.sp, lineHeight = 26.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr("%s canciones elegidas", selected.size), style = Secundario.copy(fontSize = 12.sp))
            }
            Box(
                Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(C.Rojo)
                    .clickable { Engine.setDiscoTracks(disco.id, selected.toList()); onDone() }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) { Text(tr("Listo"), style = Cuerpo.copy(color = C.SobreRojo, fontWeight = FontWeight.SemiBold)) }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Buscar"), style = Cuerpo.copy(color = C.Piedra)) },
            leadingIcon = { Icon(Ico.Search, null, tint = C.Tierra, modifier = Modifier.size(20.dp)) },
            textStyle = Cuerpo,
            shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.Rojo,
                unfocusedBorderColor = C.Linea,
                cursorColor = C.Rojo,
                focusedContainerColor = C.Tarjeta,
                unfocusedContainerColor = C.Tarjeta,
            ),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            itemsIndexed(shown, key = { _, t -> t.id }) { _, t ->
                val on = t.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable { if (on) selected.remove(t.id) else selected.add(t.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(t, Modifier.size(44.dp), corner = 8.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(t.title, style = Cuerpo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.artist} · ${t.album}", style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(if (on) C.Rojo else C.Arena),
                        contentAlignment = Alignment.Center
                    ) {
                        if (on) Icon(Ico.Check, tr("Elegida"), tint = C.SobreRojo, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** "Volver a escuchar": los discos que sonaron hace poco. */
@Composable
fun RecentRow(albums: List<Album>, disc: Boolean, onOpen: (Album) -> Unit) {
    Column {
        Text(tr("VOLVER A ESCUCHAR"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rowItems(albums, key = { "r" + it.id }) { a ->
                Column(Modifier.width(if (disc) 132.dp else 108.dp).clickable { onOpen(a) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (disc) {
                        Sleeve(a.tracks.first(), Modifier.width(132.dp).aspectRatio(1f))
                    } else {
                        Box(Modifier.size(108.dp).shadow(6.dp, RoundedCornerShape(10.dp), ambientColor = C.Negro, spotColor = C.Negro)) {
                            Cover(a.tracks.first(), Modifier.fillMaxSize(), corner = 10.dp)
                        }
                    }
                    Text(a.title, style = Cuerpo.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(tr("TODOS LOS ÁLBUMES"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
    }
}
