package com.sebas.stalker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.tr
import com.sebas.stalker.Engine
import com.sebas.stalker.OutputMode
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.R

@Composable
fun SystemScreen(vm: PlayerViewModel, onExport: () -> Unit, onImport: () -> Unit, onOpenStore: () -> Unit) {
    val mode by Engine.outputMode.collectAsState()
    val status by Engine.outputStatus.collectAsState()
    val usb by Engine.usbDevice.collectAsState()
    val theme by Engine.theme.collectAsState()
    val tracks by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    var showHidden by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val purist by Engine.purist.collectAsState()
    val shared by Engine.includeShared.collectAsState()
    val continuous by Engine.continuous.collectAsState()
    val discPause by Engine.discPause.collectAsState()
    val language by com.sebas.stalker.I18n.choice.collectAsState()
    val nextOrder by Engine.nextOrder.collectAsState()
    val updateStatus by com.sebas.stalker.Updates.status.collectAsState()
    val updateAvail by com.sebas.stalker.Updates.available.collectAsState()
    val night by Engine.nightMode.collectAsState()
    val rg by Engine.replayGain.collectAsState()
    val ctx = LocalContext.current
    var autoCovers by remember { mutableStateOf(com.sebas.stalker.Covers.autoEnabled(ctx)) }
    val coversBatch by com.sebas.stalker.Covers.batchStatus.collectAsState()
    var autoLyrics by remember { mutableStateOf(com.sebas.stalker.Lyrics.autoEnabled(ctx)) }
    val lyricsBatch by com.sebas.stalker.Lyrics.batchStatus.collectAsState()
    var autoUpdate by remember { mutableStateOf(com.sebas.stalker.Updates.autoEnabled(ctx)) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    CurtainSheet {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 190.dp)) {
            ScreenHeader(tr("Sistema"))
            Spacer(Modifier.height(18.dp))

            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ico.Headphones, null, tint = if (usb != null) C.Rojo else C.Piedra, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(usb ?: tr("Sin DAC USB"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(tr(status), style = Secundario.copy(fontSize = 12.sp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("SALIDA DE AUDIO"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                OutputMode.values().forEachIndexed { i, m ->
                    val sel = m == mode
                    Row(
                        Modifier.fillMaxWidth().clickable { Engine.setMode(m) }.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
                                .border(1.6.dp, if (sel) C.Rojo else C.Piedra, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sel) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Rojo))
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(m.label, style = Cuerpo.copy(fontWeight = FontWeight.SemiBold, color = if (sel) C.Rojo else C.Carbon))
                            Text(tr(m.description), style = Secundario.copy(fontSize = 12.sp, lineHeight = 16.sp))
                        }
                    }
                    if (i < OutputMode.values().size - 1) HorizontalDivider(color = C.Arena, thickness = 1.dp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("VOLUMEN PAREJO (REPLAYGAIN)"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            Text(tr("Iguala el volumen entre discos usando las etiquetas del FLAC. No actúa en Bit-perfect."), style = Secundario.copy(fontSize = 12.sp))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Apagado", "Pista", "Álbum").forEach { m ->
                    Pill(m, selected = rg == m, modifier = Modifier.weight(1f)) { Engine.setReplayGain(m) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("APARIENCIA"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Claro", "Noche", "Sistema").forEach { m ->
                    Pill(m, selected = night == m, modifier = Modifier.weight(1f)) { Engine.setNight(m) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("IDIOMA"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sistema", "Español", "English").forEach { m ->
                    Pill(m, selected = language == m, modifier = Modifier.weight(1f)) { com.sebas.stalker.I18n.set(m) }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("REPRODUCCIÓN CONTINUA"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                ToggleRow(Ico.Repeat, tr("Seguir con otro disco"), tr("Cuando termina un disco o una lista, entra el siguiente disco sin cortes"), continuous) {
                    Engine.setContinuous(it)
                }
                if (continuous) {
                    HorizontalDivider(color = C.Arena, thickness = 1.dp)
                    ToggleRow(Ico.Disc, tr("Pausa entre discos"), tr("2 segundos de silencio, como al cambiar un vinilo"), discPause) {
                        Engine.setDiscPause(it)
                    }
                }
            }
            if (continuous) {
                Spacer(Modifier.height(10.dp))
                Text(tr("Qué disco sigue"), style = Secundario.copy(fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Estantería", "Al azar", "Mismo artista").forEach { m ->
                        Pill(m, selected = nextOrder == m, modifier = Modifier.weight(1f)) { Engine.setNextOrder(m) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("PORTADAS"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                ToggleRow(Ico.Disc, tr("Buscar portadas que faltan"), tr("Si un disco no trae carátula, se descarga en alta resolución de Cover Art Archive"), autoCovers) {
                    autoCovers = it
                    com.sebas.stalker.Covers.setAuto(ctx, it)
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                SettingRow(
                    Ico.Download, tr("Buscar portadas de toda la biblioteca"),
                    coversBatch.ifBlank { tr("Una vez, en segundo plano. Quedan guardadas en la app") }
                ) { vm.fetchAllCovers() }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("LETRAS"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                ToggleRow(Ico.Lyrics, tr("Buscar letras solas"), tr("Si una canción no trae letra, se descarga gratis de LRCLIB al escucharla"), autoLyrics) {
                    autoLyrics = it
                    com.sebas.stalker.Lyrics.setAuto(ctx, it)
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                SettingRow(
                    Ico.Download, tr("Descargar letras de toda la biblioteca"),
                    lyricsBatch.ifBlank { tr("Una vez, en segundo plano. Quedan guardadas en la app") }
                ) { vm.fetchAllLyrics() }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("DISCOS"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                ToggleRow(Ico.Disc, tr("Dar vuelta al disco"), tr("Al terminar el Lado A, el disco se detiene para que le des vuelta, como un vinilo de verdad"), purist) {
                    Engine.setPurist(it)
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                ToggleRow(Ico.Download, tr("Música de WhatsApp y Telegram"), tr("Incluye las canciones que te mandan por chat (nunca las notas de voz)"), shared) {
                    Engine.setIncludeShared(it)
                    vm.rescan()
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(tr("ALMACENAMIENTO"), style = Secundario.copy(fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            StorageCard(tracks)

            Spacer(Modifier.height(18.dp))
            Card {
                SettingRow(Ico.Chart, tr("Tus estadísticas"), tr("Discos y canciones más escuchados")) { showStats = true }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                SettingRow(Ico.Upload, tr("Guardar respaldo"), tr("Discos, favoritos, ocultos, estadísticas y ajustes en un archivo")) { onExport() }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                SettingRow(Ico.Download, tr("Restaurar respaldo"), tr("Desde un archivo de Stalker, por ejemplo en otro celular")) { onImport() }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                SettingRow(Ico.Bag, tr("Conseguir música en FLAC"), tr("Por si no tienes algún disco en buena calidad")) { onOpenStore() }
            }

            Spacer(Modifier.height(18.dp))
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.rescan() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ico.Refresh, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tr("Biblioteca"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(tr("%s canciones · sin audios de WhatsApp ni grabaciones · toca para escanear", tracks.size), style = Secundario.copy(fontSize = 12.sp))
                    }
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { showHidden = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ico.Hide, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tr("Ocultos"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(tr("%s álbumes · %s canciones", hAlbums.size, hTracks.size), style = Secundario.copy(fontSize = 12.sp))
                    }
                    Icon(Ico.Right, null, tint = C.Tierra, modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                ToggleRow(Ico.Download, tr("Actualizaciones automáticas"), tr("Las versiones nuevas se descargan e instalan solas cuando no estás escuchando"), autoUpdate) {
                    autoUpdate = it
                    com.sebas.stalker.Updates.setAuto(ctx, it)
                    if (it) vm.checkUpdates()
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().clickable { vm.checkUpdates() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(tr("Acerca de Stalker"), style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(tr("Versión %s · Media3 · FLAC / WAV / ALAC / MP3", version), style = Secundario.copy(fontSize = 12.sp))
                        Text(
                            updateStatus.ifBlank { tr("Toca para buscar actualizaciones") },
                            style = Secundario.copy(fontSize = 12.sp, color = if (updateAvail != null) C.Rojo else C.Tierra)
                        )
                    }
                }
            }
        }
    }
    if (showHidden) HiddenSheet { showHidden = false }
    if (showStats) StatsSheet { showStats = false }
}


@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.Medium))
            Text(sub, style = Secundario.copy(fontSize = 12.sp))
        }
        Icon(Ico.Right, null, tint = C.Tierra, modifier = Modifier.size(16.dp))
    }
}

fun fmtBytes(b: Long): String = when {
    b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
    b >= 1_048_576L -> "%.0f MB".format(b / 1_048_576.0)
    else -> "%.0f KB".format(b / 1024.0)
}

private fun codecOf(mime: String): String = when {
    mime.contains("flac", true) -> "FLAC"
    mime.contains("mpeg", true) -> "MP3"
    mime.contains("mp4", true) || mime.contains("aac", true) || mime.contains("alac", true) -> "AAC / ALAC"
    mime.contains("wav", true) -> "WAV"
    mime.contains("ogg", true) || mime.contains("opus", true) -> "OGG"
    else -> tr("Otros")
}

/** Cuánto pesa tu música, por formato, y cuánto espacio libre queda en el teléfono. */
@Composable
private fun StorageCard(tracks: List<com.sebas.stalker.Track>) {
    val total = remember(tracks) { tracks.sumOf { it.sizeBytes } }
    val byCodec = remember(tracks) {
        tracks.groupBy { codecOf(it.mime) }
            .map { (k, v) -> Triple(k, v.sumOf { it.sizeBytes }, v.size) }
            .sortedByDescending { it.second }
    }
    val (free, capacity) = remember(tracks) {
        try {
            val st = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            Pair(st.availableBytes, st.totalBytes)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }
    val palette = listOf(C.Rojo, C.RojoOscuro, C.Piedra, C.Tierra, C.Linea, C.Arena)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtBytes(total), style = Titulo.copy(fontSize = 30.sp))
                Spacer(Modifier.size(8.dp))
                Text(tr("de música · %s canciones", tracks.size), style = Secundario, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (total > 0) {
                Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.Arena)) {
                    byCodec.forEachIndexed { i, (_, bytes, _) ->
                        val f = (bytes.toFloat() / total).coerceAtLeast(0.001f)
                        Box(Modifier.weight(f).fillMaxSize().background(palette[i % palette.size]))
                    }
                }
                byCodec.forEachIndexed { i, (name, bytes, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(palette[i % palette.size]))
                        Text(name, style = Cuerpo.copy(fontSize = 14.sp), modifier = Modifier.padding(start = 10.dp).weight(1f))
                        Text("${fmtBytes(bytes)} · $n", style = Secundario.copy(fontSize = 12.sp))
                    }
                }
            }
            if (capacity > 0) {
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                val pct = (total.toDouble() / capacity * 100)
                Text(
                    tr("Tu música ocupa %s %% del teléfono · quedan %s libres de %s", "%.1f".format(pct), fmtBytes(free), fmtBytes(capacity)),
                    style = Secundario.copy(fontSize = 12.sp)
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = Cuerpo.copy(fontWeight = FontWeight.Medium))
            Text(sub, style = Secundario.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = C.SobreRojo, checkedTrackColor = C.Rojo,
                uncheckedThumbColor = C.Marfil, uncheckedTrackColor = C.Piedra, uncheckedBorderColor = C.Piedra,
            )
        )
    }
}
