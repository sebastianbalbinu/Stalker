package com.sebas.stalker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.Engine
import com.sebas.stalker.Track

data class SheetAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StalkerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = C.Marfil,
        contentColor = C.Carbon,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 22.dp, end = 22.dp, bottom = 16.dp)) {
            content()
        }
    }
}

/** Menú de acciones (pulsación larga). */
@Composable
fun ActionSheet(title: String, subtitle: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    StalkerSheet(onDismiss) {
        Text(title, style = Titulo.copy(fontSize = 24.sp, lineHeight = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = Secundario)
        Spacer(Modifier.height(14.dp))
        Card {
            actions.forEachIndexed { i, a ->
                Row(
                    Modifier.fillMaxWidth().clickable { a.onClick(); onDismiss() }.padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(a.icon, null, tint = if (a.destructive) C.Rojo else C.Carbon, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, style = Cuerpo.copy(color = if (a.destructive) C.Rojo else C.Carbon, fontWeight = FontWeight.Medium))
                }
                if (i < actions.size - 1) HorizontalDivider(color = C.Arena, thickness = 1.dp)
            }
        }
    }
}

/** Diálogo para nombrar un disco. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Marfil,
        title = { Text(title, style = Titulo.copy(fontSize = 24.sp)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Nombre del disco", style = Cuerpo.copy(color = C.Piedra)) },
                textStyle = Cuerpo,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Rojo,
                    unfocusedBorderColor = C.Linea,
                    cursorColor = C.Rojo,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text(confirm, style = Cuerpo.copy(color = C.Rojo, fontWeight = FontWeight.SemiBold))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", style = Cuerpo.copy(color = C.Tierra)) }
        },
    )
}

/** Elegir a qué disco añadir canciones, o crear uno nuevo. */
@Composable
fun AddToDiscoSheet(trackIds: List<Long>, onDismiss: () -> Unit) {
    val discos by Engine.discos.collectAsState()
    var naming by remember { mutableStateOf(false) }
    StalkerSheet(onDismiss) {
        Text("Añadir a un disco", style = Titulo.copy(fontSize = 24.sp))
        Text(if (trackIds.size == 1) "1 canción" else "${trackIds.size} canciones", style = Secundario)
        Spacer(Modifier.height(14.dp))
        Card {
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(C.Rojo), contentAlignment = Alignment.Center) {
                    Icon(Ico.Plus, null, tint = C.SobreRojo, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("Nuevo disco", style = Cuerpo.copy(fontWeight = FontWeight.SemiBold, color = C.Rojo))
            }
            discos.forEach { d ->
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { Engine.addToDisco(d.id, trackIds); onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Cover(Engine.discoTracks(d).firstOrNull(), Modifier.size(40.dp), corner = 8.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(d.name, style = Cuerpo.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.trackIds.size} canciones", style = Secundario.copy(fontSize = 12.sp))
                    }
                }
            }
        }
    }
    if (naming) {
        NameDialog("Nuevo disco", "", "Crear", onDismiss = { naming = false }) { name ->
            Engine.createDisco(name, trackIds)
            onDismiss()
        }
    }
}

/** Lista de pistas del disco que suena, separada en Lado A y Lado B. */
@Composable
fun QueueSheet(title: String?, queue: List<Track>, index: Int, onPick: (Int) -> Unit, onRemove: (Int) -> Unit, onDismiss: () -> Unit) {
    val half = (queue.size + 1) / 2
    StalkerSheet(onDismiss) {
        Text(title ?: "En el tocadiscos", style = Titulo.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${queue.size} pistas", style = Secundario)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(queue) { i, t ->
                if (i == 0 || i == half) {
                    Text(
                        if (i == 0) "LADO A" else "LADO B",
                        style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
                    )
                }
                val on = i == index
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (on) C.Arena else Color.Transparent)
                        .clickable { onPick(i); onDismiss() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "%02d".format(i + 1), modifier = Modifier.width(30.dp),
                        style = Secundario.copy(fontSize = 12.sp, color = if (on) C.Rojo else C.Tierra)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = Cuerpo.copy(color = if (on) C.Rojo else C.Carbon, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        )
                        Text(t.artist, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(fmtTime(t.durationMs), style = Secundario.copy(fontSize = 12.sp))
                    if (!on) {
                        Box(
                            Modifier.padding(start = 6.dp).size(36.dp).clip(CircleShape).clickable { onRemove(i) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Ico.Close, "Quitar de la cola", tint = C.Tierra, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

/** Lo que el usuario ocultó, para poder devolverlo. */
@Composable
fun HiddenSheet(onDismiss: () -> Unit) {
    val all by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    val albums = remember(all, hAlbums) { Engine.albums(all.filter { it.albumId in hAlbums }) }
    val tracks = remember(all, hTracks) { all.filter { it.id in hTracks } }
    StalkerSheet(onDismiss) {
        Text("Ocultos", style = Titulo.copy(fontSize = 24.sp))
        Text("Toca Mostrar para devolverlos a la biblioteca", style = Secundario)
        Spacer(Modifier.height(12.dp))
        if (albums.isEmpty() && tracks.isEmpty()) {
            Text("No has ocultado nada.", style = Cuerpo.copy(color = C.Tierra), modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(albums, key = { "a" + it.id }) { a ->
                HiddenRow(a.title, "Álbum · ${a.artist}") { Engine.hideAlbum(a.id, false) }
            }
            items(tracks, key = { "t" + it.id }) { t ->
                HiddenRow(t.title, "Canción · ${t.artist}") { Engine.hideTrack(t.id, false) }
            }
        }
    }
}

@Composable
private fun HiddenRow(title: String, sub: String, onShow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Pill("Mostrar", selected = false, onClick = onShow)
    }
}


// ---------- Temporizador para dormir ----------

@Composable
fun TimerSheet(onDismiss: () -> Unit) {
    val at by Engine.sleepAt.collectAsState()
    val endDisco by Engine.sleepEndOfDisco.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    StalkerSheet(onDismiss) {
        Text("Temporizador", style = Titulo.copy(fontSize = 24.sp))
        val left = at?.let { ((it - now) / 1000).coerceAtLeast(0) }
        Text(
            when {
                left != null -> "La música se apaga en ${left / 60}:${"%02d".format(left % 60)}, con un desvanecido suave"
                endDisco -> "La música se apaga al terminar el disco"
                else -> "Apaga la música sola, ideal para dormir"
            },
            style = Secundario
        )
        Spacer(Modifier.height(14.dp))
        listOf(15, 30, 45, 60, 90).chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    Pill("$m min", selected = false, modifier = Modifier.weight(1f)) {
                        Engine.sleepEndOfDisco.value = false
                        Engine.sleepAt.value = System.currentTimeMillis() + m * 60_000L
                        onDismiss()
                    }
                }
                if (row.size < 3) Spacer(Modifier.weight((3 - row.size).toFloat()))
            }
        }
        Pill("Al terminar el disco", selected = endDisco, modifier = Modifier.fillMaxWidth()) {
            Engine.sleepAt.value = null
            Engine.sleepEndOfDisco.value = true
            onDismiss()
        }
        if (at != null || endDisco) {
            Spacer(Modifier.height(8.dp))
            Pill("Apagar temporizador", selected = true, modifier = Modifier.fillMaxWidth()) {
                Engine.sleepAt.value = null
                Engine.sleepEndOfDisco.value = false
                onDismiss()
            }
        }
    }
}

// ---------- Letra ----------

data class LyricLine(val ms: Long, val text: String)

private val stamp = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

/** Devuelve las líneas y si vienen sincronizadas (formato LRC). */
fun parseLyrics(raw: String): Pair<List<LyricLine>, Boolean> {
    val synced = ArrayList<LyricLine>()
    val plain = ArrayList<LyricLine>()
    raw.replace("\r", "").split("\n").forEach { line ->
        val stamps = stamp.findAll(line).toList()
        val text = line.replace(stamp, "").trim()
        if (stamps.isEmpty()) {
            if (!line.trim().matches(Regex("""\[[a-zA-Z]+:.*]"""))) plain.add(LyricLine(0, line.trim()))
        } else {
            stamps.forEach { m ->
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLong() * 100
                    2 -> frac.toLong() * 10
                    else -> frac.take(3).toLong()
                }
                synced.add(LyricLine(min * 60_000 + sec * 1000 + ms, text))
            }
        }
    }
    return if (synced.isNotEmpty()) Pair(synced.sortedBy { it.ms }, true) else Pair(plain, false)
}

@Composable
fun LyricsSheet(tags: Map<String, String>, posMs: Long, title: String?, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val raw = tags["LYRICS"] ?: tags["SYNCEDLYRICS"] ?: tags["UNSYNCEDLYRICS"] ?: tags["UNSYNCED LYRICS"]
    val parsed = remember(raw) { raw?.let { parseLyrics(it) } }
    val listState = rememberLazyListState()
    val lines = parsed?.first ?: emptyList()
    val synced = parsed?.second == true
    val current = if (synced) lines.indexOfLast { it.ms <= posMs + 300 } else -1
    LaunchedEffect(current) {
        if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    StalkerSheet(onDismiss) {
        Text(title ?: "Letra", style = Titulo.copy(fontSize = 24.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (synced) "Sincronizada · toca una línea para ir ahí" else "Letra", style = Secundario)
        Spacer(Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(
                "Este archivo no trae letra. Puedes incrustarla en tus FLAC con MusicBrainz Picard o Mp3tag (campo LYRICS, en formato LRC si la quieres sincronizada).",
                style = Cuerpo.copy(color = C.Tierra, lineHeight = 22.sp),
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            LazyColumn(Modifier.heightIn(max = 560.dp), state = listState, contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(lines) { i, l ->
                    val on = i == current
                    Text(
                        l.text.ifBlank { "♪" },
                        style = Titulo.copy(
                            fontSize = if (synced) 22.sp else 18.sp,
                            lineHeight = if (synced) 30.sp else 26.sp,
                            color = when {
                                !synced -> C.Carbon
                                on -> C.Rojo
                                i < current -> C.Piedra
                                else -> C.Carbon
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .then(if (synced) Modifier.clickable { onSeek(l.ms) } else Modifier)
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------- Estadísticas ----------

@Composable
fun StatsSheet(onDismiss: () -> Unit) {
    val all by Engine.tracks.collectAsState()
    val counts by Engine.playCounts.collectAsState()
    val listened by Engine.listenedMs.collectAsState()
    val byId = remember(all) { all.associateBy { it.id } }
    val topTracks = remember(counts, byId) {
        counts.entries.sortedByDescending { it.value }.mapNotNull { e -> byId[e.key]?.let { it to e.value } }.take(5)
    }
    val topAlbums = remember(counts, byId) {
        counts.entries.mapNotNull { e -> byId[e.key]?.let { it to e.value } }
            .groupBy { it.first.albumId }
            .map { (_, list) -> list.first().first to list.sumOf { it.second } }
            .sortedByDescending { it.second }.take(5)
    }
    StalkerSheet(onDismiss) {
        Text("Tus estadísticas", style = Titulo.copy(fontSize = 24.sp))
        val h = listened / 3_600_000
        val m = (listened / 60_000) % 60
        Text("Has escuchado ${h} h ${m} min en Stalker", style = Secundario)
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            item {
                Text("DISCOS MÁS ESCUCHADOS", style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
            }
            if (topAlbums.isEmpty()) item { Text("Aún no hay datos. Escucha un rato y vuelve.", style = Cuerpo.copy(color = C.Tierra)) }
            itemsIndexed(topAlbums) { i, (t, n) ->
                StatRow(i + 1, t, t.album, t.artist, n)
            }
            item {
                Spacer(Modifier.height(16.dp))
                Text("CANCIONES MÁS ESCUCHADAS", style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(topTracks) { i, (t, n) ->
                StatRow(i + 1, t, t.title, t.artist, n)
            }
        }
    }
}

@Composable
private fun StatRow(pos: Int, t: Track, title: String, sub: String, n: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$pos", style = Titulo.copy(fontSize = 20.sp, color = C.Rojo), modifier = Modifier.width(28.dp))
        Cover(t, Modifier.size(44.dp), corner = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (n == 1) "1 vez" else "$n veces", style = Secundario.copy(fontSize = 12.sp))
    }
}
