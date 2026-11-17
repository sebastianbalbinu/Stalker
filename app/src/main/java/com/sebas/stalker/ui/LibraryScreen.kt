package com.sebas.stalker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.Album
import com.sebas.stalker.Engine
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.Track

@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
) {
    val tracks by Engine.tracks.collectAsState()
    val scanned by Engine.scanned.collectAsState()
    val current by vm.current.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Álbumes") }
    val albums = remember(tracks) { Engine.albums(tracks) }
    val songs = remember(tracks) { tracks.sortedBy { it.title.lowercase() } }

    CurtainSheet {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Biblioteca", style = Titulo, modifier = Modifier.weight(1f))
            SoftButton(Ico.Refresh, "Volver a escanear") { vm.rescan() }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Álbumes", "Canciones").forEach { l ->
                Pill(l, selected = tab == l) { tab = l }
            }
        }
        Spacer(Modifier.height(18.dp))

        val bottom = if (current != null) 190.dp else 110.dp
        Box(Modifier.fillMaxSize()) {
            when {
                !hasPermission -> EmptyHint(
                    "Necesito permiso",
                    "Stalker lee la música guardada en tu teléfono.",
                    "Dar permiso", onRequestPermission
                )
                scanned && tracks.isEmpty() -> EmptyHint(
                    "Sin música aún",
                    "Copia tus FLAC a la carpeta Música del teléfono y toca actualizar.",
                    "Actualizar"
                ) { vm.rescan() }
                tab == "Álbumes" -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = bottom),
                ) {
                    items(albums, key = { it.id }) { a -> AlbumCard(a) { onOpenAlbum(a) } }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = bottom)) {
                    itemsIndexed(songs, key = { _, t -> t.id }) { i, t ->
                        SongRow(t, playing = current?.id == t.id) { vm.playList(songs, i) }
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumCard(a: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = C.Carbon, spotColor = C.Carbon)
        ) {
            Cover(a.tracks.first(), Modifier.fillMaxSize())
        }
        Column {
            Text(a.title, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.artist, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SongRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(t, Modifier.size(48.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                t.title, style = Cuerpo.copy(color = if (playing) C.Rojo else C.Carbon, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(t.artist, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(fmtTime(t.durationMs), style = Secundario.copy(fontSize = 12.sp))
    }
}

@Composable
fun AlbumScreen(a: Album, vm: PlayerViewModel, onBack: () -> Unit) {
    val current by vm.current.collectAsState()
    Column(
        Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 12.dp)
    ) {
        SoftButton(Ico.Back, "Volver", onClick = onBack)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.size(132.dp).shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = C.Carbon, spotColor = C.Carbon)) {
                Cover(a.tracks.first(), Modifier.fillMaxSize())
            }
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.title, style = Titulo.copy(fontSize = 28.sp, lineHeight = 32.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(a.artist, style = Cuerpo.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val year = if (a.year > 0) "${a.year} · " else ""
                Text("$year${a.tracks.size} canciones", style = Secundario.copy(fontSize = 12.sp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.Rojo)
                    .clickable { vm.playList(a.tracks, 0) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Ico.Play, null, tint = C.Marfil, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reproducir", style = Cuerpo.copy(color = C.Marfil, fontWeight = FontWeight.Medium))
            }
            Row(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.Arena)
                    .clickable { vm.playList(a.tracks.shuffled(), 0, shuffled = false) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                Icon(Ico.Shuffle, null, tint = C.Carbon, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Aleatorio", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 190.dp)) {
            itemsIndexed(a.tracks, key = { _, t -> t.id }) { i, t ->
                val on = current?.id == t.id
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable { vm.playList(a.tracks, i) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (t.trackNo > 0) "%02d".format(t.trackNo) else "·",
                        style = Secundario.copy(fontSize = 12.sp, color = if (on) C.Rojo else C.Tierra),
                        modifier = Modifier.width(32.dp)
                    )
                    Text(
                        t.title, modifier = Modifier.weight(1f),
                        style = Cuerpo.copy(color = if (on) C.Rojo else C.Carbon, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(fmtTime(t.durationMs), style = Secundario.copy(fontSize = 12.sp, color = if (on) C.Rojo else C.Tierra))
                }
                HorizontalDivider(color = C.Linea, thickness = 1.dp)
            }
        }
    }
}
