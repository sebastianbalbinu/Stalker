package stalker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.math.abs

/** WebSocket del propio Java para la Jam. */
private object JdkTransport : JamTransport {
    private val client: HttpClient by lazy { HttpClient.newHttpClient() }

    override fun open(url: String, onOpen: (JamSocket) -> Unit, onText: (String) -> Unit, onClosed: (String?) -> Unit) {
        val buf = StringBuilder()
        try {
            client.newWebSocketBuilder().buildAsync(URI(url), object : WebSocket.Listener {
                override fun onOpen(ws: WebSocket) {
                    ws.request(1)
                    var chain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
                    val lock = Any()
                    onOpen(object : JamSocket {
                        override fun send(text: String) {
                            synchronized(lock) { chain = chain.handle { _, _ -> null }.thenCompose { ws.sendText(text, true) } }
                        }
                        override fun close() {
                            synchronized(lock) { chain = chain.handle { _, _ -> null }.thenCompose { ws.sendClose(WebSocket.NORMAL_CLOSURE, "") } }
                        }
                    })
                }

                override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    buf.append(data)
                    if (last) {
                        val t = buf.toString()
                        buf.setLength(0)
                        onText(t)
                    }
                    ws.request(1)
                    return null
                }

                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    onClosed(reason)
                    return null
                }

                override fun onError(ws: WebSocket, error: Throwable) = onClosed(error.message)
            }).exceptionally { onClosed(it.message); null }
        } catch (t: Throwable) {
            onClosed(t.message)
        }
    }
}

/** La Jam en Windows: núcleo compartido + estado para la interfaz + sincronía con el reproductor. */
object Jam {
    val core = JamCore(JdkTransport, "Windows")
    var version by mutableIntStateOf(0); private set
    var missing by mutableStateOf<List<String>>(emptyList()); private set
    private val prefs = java.util.prefs.Preferences.userRoot().node("stalker-pc")
    var savedName: String
        get() = prefs.get("jamName", "")
        set(v) = prefs.put("jamName", v)

    private var idx = -1
    private var playing = false
    private var pos = 0L
    private var at = 0L
    private var paths: List<String> = emptyList()

    fun attach(app: AppState) {
        core.onChange = { version++ }
        core.onRemote = { st -> javax.swing.SwingUtilities.invokeLater { apply(app, st, soft = false) } }
        core.onHello = { javax.swing.SwingUtilities.invokeLater { publish(app.player, force = true) } }
    }

    /** Se llama cada medio segundo desde la interfaz. */
    fun tick(app: AppState, n: Int) {
        if (!core.active || !core.live) return
        if (core.host) publish(app.player, force = false)
        else if (n % 4 == 0) core.remote?.let { apply(app, it, soft = true) }
    }

    private fun publish(p: Player, force: Boolean) {
        val q = p.queue
        if (q.isEmpty()) return
        val now = System.currentTimeMillis()
        val expected = pos + if (playing) now - at else 0
        val ps = q.map { it.path }
        val changed = force || p.index != idx || p.playing != playing || ps != paths ||
            abs(p.positionMs - expected) > 1500 || now - at > 4000
        if (!changed) return
        idx = p.index; playing = p.playing; pos = p.positionMs; at = now; paths = ps
        core.publish(JamState(
            p.queueTitle.ifBlank { q.getOrNull(p.index)?.album ?: tr("Disco") },
            q.map { JamTrack(it.title, it.artist, it.album, it.durationMs) },
            p.index, p.positionMs, p.playing,
        ))
    }

    private var lead = 150L
    private var seekAt = 0L
    private var measured = true

    @Suppress("UNUSED_PARAMETER")
    private fun apply(app: AppState, st: JamState, soft: Boolean) {
        val p = app.player
        val mapped = st.tracks.map { r -> jamMatch(r, app.tracks, { it.title }, { it.artist }, { it.durationMs }) }
        missing = st.tracks.filterIndexed { i, _ -> mapped[i] == null }.map { it.title }
        val have = mapped.filterNotNull()
        val cur = mapped.getOrNull(st.index)
        if (cur == null) {
            if (p.playing) p.pause()
            return
        }
        val local = have.indexOf(cur)
        val exp = st.expectedPos(System.currentTimeMillis()).coerceAtLeast(0)
        if (p.queue.map { it.path } != have.map { it.path }) {
            p.setQueue(have, local, st.disc, play = st.playing, startMs = exp + lead)
            seekAt = System.currentTimeMillis(); measured = false
            return
        }
        val now = System.currentTimeMillis()
        if (p.index != local) { p.jumpTo(local, exp + lead, st.playing); seekAt = now; measured = false; return }
        if (st.playing && p.playing && now - seekAt > 3000) {
            val drift = p.positionMs - exp
            if (!measured && seekAt > 0) { lead = (lead - drift).coerceIn(0L, 1500L); measured = true }
            if (abs(drift) > 700) { p.seek(exp + lead); seekAt = now; measured = false }
        }
        if (st.playing && !p.playing) p.play() else if (!st.playing && p.playing) p.pause()
    }
}

/* ------------------------------------------------------------------ interfaz */

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, app: AppState, modifier: Modifier = Modifier, onEnter: () -> Unit = {}) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x14F1DFC4)).border(1.dp, C.Linea, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) T(hint, 12, color = C.Apagado)
        BasicTextField(
            value, onChange, singleLine = true, textStyle = txt(12.sp), cursorBrush = SolidColor(C.Crema),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { app.typing = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onEnter(); true } else false
                },
        )
    }
}

@Composable
fun JamView(app: AppState, modifier: Modifier = Modifier) {
    Jam.version
    if (!Jam.core.active) JamStart(app, modifier) else JamRoom(app, modifier)
}

@Composable
private fun JamStart(app: AppState, modifier: Modifier) {
    var name by remember { mutableStateOf(Jam.savedName) }
    var code by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth()) {
        T(tr("Escuchar juntos"), 15, FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        T(tr("El mismo disco, a la vez, desde otro PC o un celular, con chat. Cada uno escucha su propio FLAC: por internet solo viaja la aguja."),
            11, color = C.Tenue, maxLines = 4)
        Spacer(Modifier.height(14.dp))
        Field(name, { name = it.take(24) }, tr("Tu nombre"), app)
        Spacer(Modifier.height(10.dp))
        Pill("Abrir una Jam con lo que suena", {
            Jam.savedName = name.trim(); Jam.core.create(name)
        }, Modifier.fillMaxWidth(), active = true, ico = Ico.Jam)
        Spacer(Modifier.height(18.dp))
        T(tr("¿Te invitaron?"), 12, FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, tr("Código"), app, Modifier.weight(1f)) {
                if (code.length >= 4) { Jam.savedName = name.trim(); Jam.core.join(code, name) }
            }
            Spacer(Modifier.width(8.dp))
            Pill("Entrar", { if (code.length >= 4) { Jam.savedName = name.trim(); Jam.core.join(code, name) } })
        }
        Spacer(Modifier.height(10.dp))
        T(tr("Cada uno necesita el disco. Desde el celular: «Compartir disco»; en el PC se abre en Ajustes."), 10, color = C.Apagado, maxLines = 3)
    }
}

@Composable
private fun JamRoom(app: AppState, modifier: Modifier) {
    val core = Jam.core
    var text by remember { mutableStateOf("") }
    val msgs = core.messages
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    fun send() { core.chat(text); text = "" }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(tr("JAM"), 9, FontWeight.SemiBold, C.Tenue, spacing = 2f)
                T(core.code ?: "", 26, FontWeight.SemiBold, spacing = 5f)
                T(tr(core.status) + if (core.host) tr(" · tú pones la música") else tr(" · sigues la sala"), 10, color = C.Apagado)
            }
            Pill("Copiar invitación", {
                val s = tr("Escucha conmigo en Stalker: abre la app → Jam → código %s\nGratis: https://sebastianbalbinu.github.io/Stalker/", core.code)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null)
            })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            core.peers.take(6).forEach { p ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x14F1DFC4)).padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (p.host) C.RojoVivo else C.Apagado))
                    Spacer(Modifier.width(5.dp))
                    T(p.name + if (p.id == core.myId) tr(" (tú)") else "", 10)
                }
            }
        }
        if (!core.host && Jam.missing.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x338E0E0E)).padding(8.dp)) {
                T(if (Jam.missing.size == 1) tr("No tienes 1 canción de este disco. Pide que te lo compartan.")
                else tr("No tienes %s canciones de este disco. Pide que te lo compartan.", Jam.missing.size), 10, maxLines = 2)
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(msgs) { m ->
                if (m.system) {
                    T(m.text, 10, color = C.Apagado, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), maxLines = 2)
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (m.mine) C.Rojo else Color(0x1FF1DFC4)).padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        if (!m.mine) T(m.name, 10, FontWeight.SemiBold, C.Oro)
                        androidx.compose.foundation.text.BasicText(m.text, style = txt(12.sp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(text, { text = it.take(500) }, tr("Escribe algo…"), app, Modifier.weight(1f)) { send() }
            Spacer(Modifier.width(6.dp))
            RoundButton(Ico.Next, { send() }, 34.dp, 13.dp, tint = C.Sombra, filled = C.Crema)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).pointerHoverIcon(Hand)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { core.leave() }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { T(if (core.host) tr("Cerrar la Jam") else tr("Salir de la Jam"), 11, color = C.Tenue) }
    }
}
