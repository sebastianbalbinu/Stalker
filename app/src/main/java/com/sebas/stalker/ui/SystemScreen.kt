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
import com.sebas.stalker.Engine
import com.sebas.stalker.OutputMode
import com.sebas.stalker.PlayerViewModel
import com.sebas.stalker.R

@Composable
fun SystemScreen(vm: PlayerViewModel) {
    val mode by Engine.outputMode.collectAsState()
    val status by Engine.outputStatus.collectAsState()
    val usb by Engine.usbDevice.collectAsState()
    val theme by Engine.theme.collectAsState()
    val tracks by Engine.tracks.collectAsState()
    val hAlbums by Engine.hiddenAlbums.collectAsState()
    val hTracks by Engine.hiddenTracks.collectAsState()
    var showHidden by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    CurtainSheet {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 190.dp)) {
            Text("Sistema", style = Titulo)
            Spacer(Modifier.height(18.dp))

            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ico.Headphones, null, tint = if (usb != null) C.Rojo else C.Piedra, modifier = Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(usb ?: "Sin DAC USB", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text(status, style = Secundario.copy(fontSize = 12.sp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Salida de audio", style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
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
                            Text(m.description, style = Secundario.copy(fontSize = 12.sp, lineHeight = 16.sp))
                        }
                    }
                    if (i < OutputMode.values().size - 1) HorizontalDivider(color = C.Arena, thickness = 1.dp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Apariencia", style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(10.dp))
            Card {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("Telón", "Zigzag", "Marfil").forEach { name ->
                        val sel = name == theme
                        Column(
                            Modifier.weight(1f).clickable { Engine.setTheme(name) },
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(64.dp)
                                    .border(if (sel) 2.dp else 1.dp, if (sel) C.Rojo else C.Linea, RoundedCornerShape(12.dp))
                                    .padding(if (sel) 3.dp else 0.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(C.Arena)
                            ) {
                                when (name) {
                                    "Telón" -> Image(painterResource(R.drawable.telon), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    "Zigzag" -> Image(painterResource(R.drawable.zigzag), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    else -> Box(Modifier.fillMaxSize().background(C.Marfil))
                                }
                            }
                            Text(name, style = Secundario.copy(fontSize = 12.sp, color = if (sel) C.Rojo else C.Tierra, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Card {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.rescan() }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ico.Refresh, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Biblioteca", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text("${tracks.size} canciones · sin audios de WhatsApp ni grabaciones · toca para escanear", style = Secundario.copy(fontSize = 12.sp))
                    }
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { showHidden = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ico.Hide, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Ocultos", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text("${hAlbums.size} álbumes · ${hTracks.size} canciones", style = Secundario.copy(fontSize = 12.sp))
                    }
                    Icon(Ico.Right, null, tint = C.Tierra, modifier = Modifier.size(16.dp))
                }
                HorizontalDivider(color = C.Arena, thickness = 1.dp)
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ico.Layers, null, tint = C.Carbon, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text("Acerca de Stalker", style = Cuerpo.copy(fontWeight = FontWeight.Medium))
                        Text("Versión $version · Media3 · FLAC / WAV / ALAC / MP3", style = Secundario.copy(fontSize = 12.sp))
                    }
                }
            }
        }
    }
    if (showHidden) HiddenSheet { showHidden = false }
}

