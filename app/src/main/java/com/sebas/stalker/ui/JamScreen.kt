package com.sebas.stalker.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.tr
import com.sebas.stalker.Jam
import com.sebas.stalker.JamMsg

/** Franja de la Jam en el reproductor: invita a abrir una o muestra la sala en vivo. */
@Composable
fun JamBar(onOpen: () -> Unit) {
    Jam.version.collectAsState().value
    val core = Jam.core
    val last = core.messages.lastOrNull { !it.system }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (core.active) C.Rojo.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = if (core.active) 10.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (core.active) Arrangement.Start else Arrangement.Center,
    ) {
        Icon(Ico.Jam, null, tint = C.Rojo, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        if (!core.active) {
            Text(tr("Escuchar juntos · Jam"), style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = C.Rojo))
        } else {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Jam %s · %s en la sala · %s", core.code, core.peers.size.coerceAtLeast(1), tr(core.status)),
                    style = Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = C.Rojo), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    last?.let { "${if (it.mine) tr("Tú") else it.name}: ${it.text}" } ?: tr("Toca para abrir el chat"),
                    style = Secundario.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun JamSheet(onDismiss: () -> Unit) {
    Jam.version.collectAsState().value
    val core = Jam.core
    StalkerSheet(onDismiss) {
        if (!core.active) JamStart() else JamRoom()
    }
}

@Composable
private fun field(value: String, onChange: (String) -> Unit, hint: String, caps: Boolean = false, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        placeholder = { Text(hint, style = Cuerpo.copy(color = C.Piedra)) },
        textStyle = Cuerpo,
        keyboardOptions = KeyboardOptions(
            capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Rojo, unfocusedBorderColor = C.Linea, cursorColor = C.Rojo),
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun JamStart() {
    var name by remember { mutableStateOf(Jam.savedName) }
    var code by remember { mutableStateOf("") }
    Text(tr("Jam"), style = Titulo.copy(fontSize = 28.sp))
    Spacer(Modifier.height(4.dp))
    Text(
        tr("Escuchen el mismo disco a la vez desde distintos lugares, en celular o PC, y conversen mientras suena. ") +
            tr("Cada uno escucha su propio FLAC: la música no viaja por internet, solo la aguja."),
        style = Secundario.copy(fontSize = 13.sp, lineHeight = 18.sp)
    )
    Spacer(Modifier.height(16.dp))
    field(name, { name = it.take(24) }, tr("Tu nombre"), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    Pill("Abrir una Jam con lo que suena", selected = true, modifier = Modifier.fillMaxWidth()) {
        Jam.savedName = name.trim()
        Jam.core.create(name)
    }
    Spacer(Modifier.height(18.dp))
    Text(tr("¿Te invitaron?"), style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        field(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, tr("Código"), caps = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Pill("Entrar", selected = false) {
            if (code.length >= 4) {
                Jam.savedName = name.trim()
                Jam.core.join(code, name)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(tr("Para escuchar juntos, cada uno necesita el disco. Compártelo antes con «Compartir disco»."), style = Secundario.copy(fontSize = 12.sp))
}

@Composable
private fun JamRoom() {
    val core = Jam.core
    val ctx = LocalContext.current
    val missing by Jam.missing.collectAsState()
    var text by remember { mutableStateOf("") }
    val msgs = core.messages
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Jam"), style = Secundario.copy(fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold))
            Text(core.code ?: "", style = Titulo.copy(fontSize = 34.sp, letterSpacing = 6.sp))
            Text(tr(core.status) + if (core.host) tr(" · tú pones la música") else tr(" · sigues la música de la sala"), style = Secundario.copy(fontSize = 12.sp))
        }
        Pill("Invitar", selected = true) {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                Intent.EXTRA_TEXT,
                tr("Escucha conmigo en Stalker 🎧 Abre la app → Jam → código %s\nDescárgala gratis: https://sebastianbalbinu.github.io/Stalker/", core.code)
            )
            ctx.startActivity(Intent.createChooser(i, tr("Invitar a la Jam")))
        }
    }
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(core.peers, key = { it.id }) { p ->
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(C.Tarjeta).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (p.host) C.Rojo else C.Piedra))
                Spacer(Modifier.width(7.dp))
                Text(p.name + if (p.id == core.myId) tr(" (tú)") else "", style = Cuerpo.copy(fontSize = 13.sp))
                Text("  ${p.device}", style = Secundario.copy(fontSize = 11.sp))
            }
        }
    }
    if (!core.host && missing.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            tr(
                if (missing.size == 1) "No tienes 1 canción de este disco (%s). Pide que te compartan el disco."
                else "No tienes %s canciones de este disco (%s). Pide que te compartan el disco.",
                *(if (missing.size == 1) arrayOf<Any>(missing.first()) else arrayOf<Any>(missing.size, missing.take(2).joinToString(", ") + if (missing.size > 2) "…" else ""))
            ),
            style = Secundario.copy(fontSize = 12.sp, color = C.Rojo), modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Rojo.copy(alpha = 0.08f)).padding(10.dp)
        )
    }
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(msgs) { m -> Bubble(m) }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.imePadding(), verticalAlignment = Alignment.CenterVertically) {
        field(text, { text = it.take(500) }, tr("Escribe algo…"), onDone = { Jam.core.chat(text); text = "" }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Pill("Enviar", selected = text.isNotBlank()) { Jam.core.chat(text); text = "" }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        if (core.host) tr("Cerrar la Jam") else tr("Salir de la Jam"),
        style = Cuerpo.copy(color = C.Tierra, fontSize = 14.sp), textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Jam.core.leave() }.padding(10.dp)
    )
}

@Composable
private fun Bubble(m: JamMsg) {
    if (m.system) {
        Text(m.text, style = Secundario.copy(fontSize = 12.sp), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(if (m.mine) C.Rojo else C.Tarjeta)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!m.mine) Text(m.name, style = Secundario.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = C.Rojo))
            Text(m.text, style = Cuerpo.copy(fontSize = 14.sp, color = if (m.mine) C.SobreRojo else C.Carbon))
        }
    }
}
