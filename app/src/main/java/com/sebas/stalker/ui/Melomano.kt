package com.sebas.stalker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.Album
import com.sebas.stalker.AutoEq
import com.sebas.stalker.Engine
import com.sebas.stalker.Liner
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.Spectrum
import com.sebas.stalker.Track
import com.sebas.stalker.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ------------------------------------------------------------ calidad real */

/** Color del veredicto: verde tenue si es real, ámbar si dudoso, rojo si es falso. */
private fun verdictColor(v: Spectrum.Verdict) = when (v) {
    Spectrum.Verdict.REAL -> Color(0xFF3F8F5A)
    Spectrum.Verdict.UNCLEAR -> Color(0xFFB7862B)
    else -> Color(0xFFB3261E)
}

/** Etiqueta corta para la cabecera del álbum (si ya se analizó). */
@Composable
fun QualityChip(track: Track, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val progress by Spectrum.progress.collectAsState()
    val res = remember(track.id, progress) { Spectrum.cached(ctx, track) }
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(C.Arena).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(res?.let { verdictColor(it.verdict) } ?: C.Piedra))
        Spacer(Modifier.width(8.dp))
        Text(res?.title ?: tr("Calidad real"), style = Secundario.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = C.Carbon))
    }
}

@Composable
fun AnalysisSheet(track: Track, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val progress by Spectrum.progress.collectAsState()
    var failed by remember { mutableStateOf(false) }
    val result by produceState<Spectrum.Result?>(Spectrum.cached(ctx, track), track.id) {
        if (value == null) {
            value = Spectrum.analyze(ctx, track)
            failed = value == null
        }
    }
    StalkerSheet(onDismiss) {
        Text(tr("Calidad real"), style = Titulo.copy(fontSize = 24.sp))
        Text(track.title + " · " + track.album, style = Secundario, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        val r = result
        if (r == null) {
            Text(
                if (failed) tr("No se pudo analizar este archivo.") else tr("Escuchando el espectro de la canción… %s %%", ((progress ?: 0f) * 100).toInt()),
                style = Cuerpo.copy(color = C.Tierra)
            )
            Spacer(Modifier.height(30.dp))
            return@StalkerSheet
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(verdictColor(r.verdict)))
            Spacer(Modifier.width(10.dp))
            Text(r.title, style = Titulo.copy(fontSize = 20.sp, color = verdictColor(r.verdict)))
        }
        Spacer(Modifier.height(6.dp))
        Text(r.detail, style = Cuerpo.copy(color = C.Tierra, lineHeight = 21.sp))
        Spacer(Modifier.height(16.dp))
        Spectrogram(r, Modifier.fillMaxWidth().height(190.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Espectrograma: de abajo (graves) hacia arriba (agudos), en tres momentos de la canción. Lo brillante es música; un techo recto y oscuro delata un MP3."),
            style = Secundario.copy(fontSize = 12.sp, lineHeight = 17.sp)
        )
        Spacer(Modifier.height(20.dp))
    }
}

/** El espectrograma en tonos de fuego sobre negro, con marcas de frecuencia. */
@Composable
private fun Spectrogram(r: Spectrum.Result, modifier: Modifier) {
    val nyq = r.sampleRate / 2f
    val palette = remember {
        val stops = listOf(Color(0xFF000000), Color(0xFF2A0610), Color(0xFF6E0F1F), Color(0xFFB3261E), Color(0xFFE8792B), Color(0xFFF6D365), Color(0xFFFFFBEA))
        Array(256) { i ->
            val p = i / 255f * (stops.size - 1)
            val k = p.toInt().coerceAtMost(stops.size - 2)
            lerp(stops[k], stops[k + 1], p - k)
        }
    }
    Row(modifier) {
        Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) {
                val cw = size.width / Spectrum.COLS
                val rh = size.height / Spectrum.ROWS
                for (c in 0 until Spectrum.COLS) for (row in 0 until Spectrum.ROWS) {
                    val v = r.image[c * Spectrum.ROWS + row].toInt() and 0xFF
                    if (v < 6) continue
                    drawRect(palette[v], Offset(c * cw, size.height - (row + 1) * rh), Size(cw + 0.6f, rh + 0.6f))
                }
                // Línea del corte detectado.
                if (r.verdict != Spectrum.Verdict.REAL) {
                    val y = size.height * (1 - r.cutoffHz / nyq)
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f,
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                }
            }
        }
        Column(Modifier.width(42.dp).fillMaxSize().padding(start = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(nyq, nyq * 0.75f, nyq / 2, nyq / 4, 0f).forEach {
                Text(if (it == 0f) "0" else String.format("%.0fk", it / 1000), style = Secundario.copy(fontSize = 10.sp))
            }
        }
    }
}

/* ------------------------------------------------------------ librito del disco */

@Composable
fun AlbumInfoSheet(album: Album, onDismiss: () -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    val info by produceState(Liner.cachedAlbum(ctx, album), album.id) {
        if (value == null) value = Liner.album(ctx, album)
        loading = false
    }
    StalkerSheet(onDismiss) {
        LazyColumn(Modifier.heightIn(max = 620.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(tr("SOBRE EL DISCO"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = C.Rojo))
                Spacer(Modifier.height(4.dp))
                Text(album.title, style = Titulo.copy(fontSize = 26.sp, lineHeight = 30.sp))
                Text(album.artist, style = Cuerpo.copy(color = C.Rojo), modifier = Modifier.clickable { onArtist(album.artist) }.padding(vertical = 2.dp))
                Spacer(Modifier.height(14.dp))
            }
            val i = info
            if (i == null) {
                item {
                    Text(
                        if (loading) tr("Buscando el librito del disco en MusicBrainz y Wikipedia…")
                        else tr("No encontramos este disco en MusicBrainz. Puede ser un lanzamiento independiente o tener otro nombre ahí."),
                        style = Cuerpo.copy(color = C.Tierra, lineHeight = 21.sp)
                    )
                }
                return@LazyColumn
            }
            item {
                Card {
                    listOf(
                        tr("Publicado") to i.date,
                        tr("Sello") to i.label,
                        tr("País") to i.country,
                        tr("Formato original") to i.format,
                    ).filter { it.second.isNotBlank() }.forEachIndexed { n, (k, v) ->
                        if (n > 0) HorizontalDivider(color = C.Arena, thickness = 1.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(k, style = Secundario, modifier = Modifier.width(120.dp))
                            Text(v, style = Cuerpo, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            if (i.summary.isNotBlank()) item {
                Spacer(Modifier.height(18.dp))
                Text(i.summary, style = Cuerpo.copy(lineHeight = 23.sp, color = C.Carbon))
                Text(
                    tr("Wikipedia"), style = Secundario.copy(fontSize = 12.sp, color = C.Rojo),
                    modifier = Modifier.clickable { open(ctx, i.wikiUrl) }.padding(vertical = 8.dp)
                )
            }
            if (i.credits.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(14.dp))
                    Text(tr("CRÉDITOS"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(8.dp))
                }
                items(i.credits) { (role, names) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(role, style = Secundario, modifier = Modifier.width(120.dp))
                        Text(names.joinToString(", "), style = Cuerpo.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                    }
                }
            }
            item {
                Spacer(Modifier.height(14.dp))
                Text(
                    tr("Datos de MusicBrainz"), style = Secundario.copy(fontSize = 12.sp, color = C.Piedra),
                    modifier = Modifier.clickable { open(ctx, i.mbUrl) }.padding(vertical = 6.dp)
                )
            }
        }
    }
}

private fun open(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
}

/* ------------------------------------------------------------ página de artista */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistScreen(name: String, vm: PlayerViewModel, onBack: () -> Unit, onOpen: (Detail) -> Unit) {
    val ctx = LocalContext.current
    val all by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    val view by Engine.libraryView.collectAsState()
    val albums = remember(all, hAlbums, hTracks, name) {
        Engine.albums(Engine.visible(all, hAlbums, hTracks))
            .filter { a -> a.artist.equals(name, true) || a.tracks.any { it.artist.equals(name, true) } }
            .sortedWith(compareBy<Album> { if (it.year > 0) it.year else Int.MAX_VALUE }.thenBy { it.title.lowercase() })
    }
    var loading by remember { mutableStateOf(true) }
    val info by produceState<com.sebas.stalker.ArtistInfo?>(null, name) {
        value = Liner.artist(ctx, name)
        loading = false
    }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.Marfil).statusBarsPadding().padding(start = 22.dp, end = 22.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftButton(Ico.Back, tr("Volver"), onClick = onBack)
            Spacer(Modifier.weight(1f))
            SoftButton(Ico.Shuffle, tr("Aleatorio")) {
                val ts = albums.flatMap { it.tracks }.shuffled()
                if (ts.isNotEmpty()) vm.playList(ts, 0, name)
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 200.dp)) {
            item {
                Text(tr("ARTISTA"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = C.Rojo))
                Text(name, style = Titulo.copy(fontSize = 32.sp, lineHeight = 36.sp))
                val i = info
                val facts = listOfNotNull(i?.kind?.takeIf { it.isNotBlank() }, i?.area?.takeIf { it.isNotBlank() }, i?.years?.takeIf { it.isNotBlank() })
                Text(
                    (facts + tr("%s discos en tu estantería", albums.size)).joinToString(" · "),
                    style = Secundario.copy(fontSize = 13.sp)
                )
                Spacer(Modifier.height(12.dp))
                when {
                    i != null && i.summary.isNotBlank() -> {
                        Text(
                            i.summary, style = Cuerpo.copy(lineHeight = 22.sp),
                            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { expanded = !expanded }
                        )
                        Text(
                            tr("Wikipedia"), style = Secundario.copy(fontSize = 12.sp, color = C.Rojo),
                            modifier = Modifier.clickable { open(ctx, i.wikiUrl) }.padding(vertical = 6.dp)
                        )
                    }
                    loading -> Text(tr("Buscando su historia…"), style = Secundario)
                }
                Spacer(Modifier.height(10.dp))
            }
            item {
                WoodCabinet(Modifier.fillMaxWidth()) {
                    Column {
                        ShelfRow(
                            tr("EN TU ESTANTERÍA"), albums, view == "Discos", 132.dp, null,
                            onOpen = { onOpen(Detail.OfAlbum(it.id)) }, onLong = { }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------ corrección de audífonos */

@Composable
fun HeadphoneSection(blocked: Boolean) {
    val ctx = LocalContext.current
    val profile by AutoEq.profile.collectAsState()
    val on by AutoEq.enabled.collectAsState()
    val curve by AutoEq.curve.collectAsState()
    var picking by remember { mutableStateOf(false) }
    Text(tr("CORRECCIÓN PARA TUS AUDÍFONOS"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(6.dp))
    Text(
        when {
            !AutoEq.supported -> tr("Necesita Android 9 o superior.")
            blocked -> tr("En Bit-perfect no se aplica: cualquier corrección toca la señal. Cámbialo a Hi-Res en Sistema.")
            else -> tr("Perfiles abiertos de AutoEq: llevan tus audífonos a un sonido neutro de estudio, medido en laboratorio.")
        },
        style = Secundario.copy(fontSize = 12.sp, lineHeight = 17.sp)
    )
    Spacer(Modifier.height(10.dp))
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = AutoEq.supported) { picking = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Ico.Headphones, null, tint = C.Rojo, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(profile ?: tr("Elegir mis audífonos"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                Text(if (profile != null) tr("Toca para cambiar de modelo") else tr("Busca tu modelo entre miles"), style = Secundario.copy(fontSize = 12.sp))
            }
            if (profile != null) Switch(
                checked = on, onCheckedChange = { AutoEq.setEnabled(ctx, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = C.SobreRojo, checkedTrackColor = C.Rojo, uncheckedThumbColor = C.SobreRojo, uncheckedTrackColor = C.Piedra, uncheckedBorderColor = C.Piedra)
            )
        }
        if (profile != null && curve.isNotEmpty()) {
            HorizontalDivider(color = C.Arena, thickness = 1.dp)
            CorrectionCurve(curve, on && !blocked, Modifier.fillMaxWidth().height(90.dp).padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
    if (picking) HeadphonePicker { picking = false }
}

@Composable
private fun CorrectionCurve(curve: List<Pair<Float, Float>>, active: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val minF = kotlin.math.ln(20f)
        val maxF = kotlin.math.ln(20000f)
        val range = 12f
        drawLine(C.Linea, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        var prev: Offset? = null
        for (k in 0..120) {
            val f = kotlin.math.exp(minF + (maxF - minF) * k / 120f)
            val g = AutoEq.gainAt(f, curve).coerceIn(-range, range)
            val p = Offset(size.width * k / 120f, size.height / 2 - g / range * size.height / 2)
            prev?.let { drawLine(if (active) C.Rojo else C.Piedra, it, p, 2.5f) }
            prev = p
        }
    }
}

@Composable
private fun HeadphonePicker(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val results by produceState(emptyList<AutoEq.Entry>(), query) {
        delay(250)
        value = try { AutoEq.search(ctx, query) } catch (e: Exception) { emptyList() }
    }
    StalkerSheet(onDismiss) {
        Text(tr("Tus audífonos"), style = Titulo.copy(fontSize = 24.sp))
        Text(tr("Escribe la marca y el modelo, por ejemplo JBL Tune"), style = Secundario)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Marca y modelo"), style = Cuerpo.copy(color = C.Piedra)) },
            leadingIcon = { Icon(Ico.Search, null, tint = C.Tierra, modifier = Modifier.size(20.dp)) },
            textStyle = Cuerpo, shape = RoundedCornerShape(22.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Rojo, unfocusedBorderColor = C.Linea, cursorColor = C.Rojo, focusedContainerColor = C.Tarjeta, unfocusedContainerColor = C.Tarjeta),
        )
        Spacer(Modifier.height(8.dp))
        if (busy) Text(tr("Descargando el perfil…"), style = Secundario)
        if (error) Text(tr("No se pudo descargar ese perfil. ¿Hay internet?"), style = Secundario.copy(color = C.Rojo))
        if (query.isNotBlank() && results.isEmpty() && !busy) {
            Text(tr("Sin resultados. Si tu modelo no está, prueba con uno de la misma familia (por ejemplo, el inalámbrico del mismo modelo)."), style = Secundario.copy(fontSize = 12.sp), modifier = Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(results, key = { it.name }) { e ->
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        busy = true; error = false
                        scope.launch {
                            val ok = AutoEq.choose(ctx, e)
                            busy = false
                            if (ok) onDismiss() else error = true
                        }
                    }.padding(vertical = 12.dp)
                ) {
                    Text(e.name, style = Cuerpo)
                    Text(tr("Medido por %s", e.source), style = Secundario.copy(fontSize = 12.sp))
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
