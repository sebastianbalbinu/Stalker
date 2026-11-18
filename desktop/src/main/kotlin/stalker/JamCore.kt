package stalker

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/*
 * Jam de Stalker: escuchar el mismo disco a la vez desde varios aparatos y chatear.
 * Este archivo es idéntico en Android y en Windows (solo cambia el paquete).
 *
 * Usa los canales en vivo de Supabase Realtime (protocolo Phoenix sobre WebSocket):
 *  - "estado": quien abrió la Jam cuenta qué disco suena, en qué canción, en qué punto y si está sonando.
 *  - "chat": mensajes.
 *  - presencia: quién está en la sala.
 * La música nunca viaja por la red: cada uno escucha su propio FLAC.
 */

object JamConfig {
    const val PROJECT = "niboefhdtutehnzjwxbj"
    // Llave pública de Supabase: está hecha para ir dentro de las apps.
    const val KEY = "sb_publishable_WQjxDBl5_kC4cU9Udanqzg_WvOybEdh"
    const val URL = "wss://$PROJECT.supabase.co/realtime/v1/websocket?apikey=$KEY&vsn=1.0.0"
}

data class JamTrack(val title: String, val artist: String, val album: String, val durMs: Long)

data class JamState(
    val disc: String,
    val tracks: List<JamTrack>,
    val index: Int,
    val posMs: Long,
    val playing: Boolean,
    /** Momento (reloj local de quien recibe) en que llegó el estado. */
    val receivedAt: Long = 0,
) {
    /** Dónde debería ir la aguja ahora mismo, compensando lo que tardó el mensaje. */
    fun expectedPos(now: Long): Long = if (playing) posMs + (now - receivedAt) + 120 else posMs
}

data class JamMsg(val id: String, val name: String, val text: String, val ts: Long, val mine: Boolean, val system: Boolean = false)
data class JamPeer(val id: String, val name: String, val device: String, val host: Boolean)

interface JamSocket {
    fun send(text: String)
    fun close()
}

interface JamTransport {
    fun open(url: String, onOpen: (JamSocket) -> Unit, onText: (String) -> Unit, onClosed: (String?) -> Unit)
}

/** Para comparar canciones entre aparatos aunque los archivos se llamen distinto. */
fun jamNorm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

fun <T> jamMatch(remote: JamTrack, local: List<T>, title: (T) -> String, artist: (T) -> String, dur: (T) -> Long): T? {
    val t = jamNorm(remote.title)
    val a = jamNorm(remote.artist)
    return local.firstOrNull { jamNorm(title(it)) == t && jamNorm(artist(it)) == a && kotlin.math.abs(dur(it) - remote.durMs) < 4000 }
        ?: local.firstOrNull { jamNorm(title(it)) == t && kotlin.math.abs(dur(it) - remote.durMs) < 2500 }
}

class JamCore(private val transport: JamTransport, private val device: String) {
    /** Se llama (desde cualquier hilo) cada vez que algo cambia, para refrescar la pantalla. */
    var onChange: () -> Unit = {}
    /** Para invitados: llegó un estado nuevo de quien abrió la Jam. */
    var onRemote: (JamState) -> Unit = {}
    /** Para quien abrió la Jam: alguien entró y necesita el estado ya. */
    var onHello: () -> Unit = {}

    val myId: String = UUID.randomUUID().toString().take(8)
    @Volatile var code: String? = null; private set
    @Volatile var host = false; private set
    @Volatile var name = ""; private set
    @Volatile var status = ""; private set
    @Volatile var live = false; private set
    @Volatile var ended = false; private set
    @Volatile var remote: JamState? = null; private set
    @Volatile var peers: List<JamPeer> = emptyList(); private set
    private val msgs = ArrayList<JamMsg>()
    val messages: List<JamMsg> get() = synchronized(msgs) { msgs.toList() }
    val active get() = code != null

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stalker-jam").apply { isDaemon = true } }
    private var socket: JamSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var ref = 1
    private var joinRef = "1"
    private var attempts = 0
    private var generation = 0
    private val presence = LinkedHashMap<String, JamPeer>()

    private val topic get() = "realtime:stalker-jam-$code"

    fun create(name: String) = exec.execute {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        start(name, (1..4).map { alphabet.random() }.joinToString(""), true)
    }

    fun join(code: String, name: String) = exec.execute {
        start(name, code.uppercase().filter { it.isLetterOrDigit() }.take(6), false)
    }

    private fun start(n: String, c: String, asHost: Boolean) {
        stopInternal(sendEnd = false)
        reconnecting = false
        name = n.trim().ifBlank { "Alguien" }.take(24)
        code = c
        host = asHost
        ended = false
        remote = null
        synchronized(msgs) { msgs.clear() }
        system(if (asHost) "Abriste la Jam $c. Comparte el código para que entren." else "Entrando a la Jam $c…")
        attempts = 0
        connect()
    }

    fun leave() = exec.execute { stopInternal(sendEnd = host) ; code = null; onChange() }

    fun chat(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty() || !active) return
        val m = JamMsg(myId, name, t, System.currentTimeMillis(), true)
        synchronized(msgs) { msgs += m; trim() }
        onChange()
        exec.execute {
            broadcast("chat", JSONObject().put("id", myId).put("name", name).put("text", t).put("ts", m.ts))
        }
    }

    /** Solo quien abrió la Jam: cuenta a todos qué está sonando. */
    fun publish(st: JamState) {
        if (!host || !live) return
        exec.execute {
            val tracks = JSONArray()
            st.tracks.forEach { tracks.put(JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album).put("d", it.durMs)) }
            broadcast("estado", JSONObject().put("disc", st.disc).put("tracks", tracks).put("i", st.index)
                .put("p", st.posMs).put("on", st.playing))
        }
    }

    // ---------------------------------------------------------------- conexión

    private fun connect() {
        val gen = ++generation
        status = if (attempts == 0) "Conectando…" else "Reconectando…"
        live = false
        onChange()
        transport.open(JamConfig.URL,
            onOpen = { s -> exec.execute { if (gen == generation) { socket = s; joinChannel() } else s.close() } },
            onText = { t -> exec.execute { if (gen == generation) handle(t) } },
            onClosed = { reason -> exec.execute { if (gen == generation) closed(reason) } },
        )
    }

    private var reconnecting = false

    private fun closed(@Suppress("UNUSED_PARAMETER") reason: String?) {
        if (reconnecting) return
        heartbeat?.cancel(false)
        socket = null
        live = false
        if (code == null || ended) return
        attempts++
        status = "Sin conexión · reintentando"
        onChange()
        val wait = minOf(30L, 1L shl minOf(attempts, 5))
        reconnecting = true
        exec.schedule({ reconnecting = false; if (code != null && socket == null && !ended) connect() }, wait, TimeUnit.SECONDS)
    }

    private fun send(event: String, payload: JSONObject, t: String = topic) {
        val s = socket ?: return
        val msg = JSONObject().put("topic", t).put("event", event).put("payload", payload).put("ref", (ref++).toString())
        if (t != "phoenix") msg.put("join_ref", joinRef)
        try { s.send(msg.toString()) } catch (_: Throwable) {}
    }

    private fun broadcast(event: String, payload: JSONObject) {
        send("broadcast", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload))
    }

    private fun joinChannel() {
        joinRef = (ref).toString()
        val cfg = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", myId))
        send("phx_join", JSONObject().put("config", cfg))
        heartbeat?.cancel(false)
        heartbeat = exec.scheduleAtFixedRate({ send("heartbeat", JSONObject(), "phoenix") }, 25, 25, TimeUnit.SECONDS)
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (_: Throwable) { return }
        val event = m.optString("event")
        val payload = m.optJSONObject("payload") ?: JSONObject()
        when (event) {
            "phx_reply" -> {
                if (m.optString("ref") == joinRef) {
                    if (payload.optString("status") == "ok") {
                        live = true
                        attempts = 0
                        status = "En vivo"
                        send("presence", JSONObject().put("type", "presence").put("event", "track").put("payload",
                            JSONObject().put("id", myId).put("name", name).put("device", device).put("host", host)))
                        broadcast("hola", JSONObject().put("id", myId).put("name", name))
                        if (host) onHello()
                        onChange()
                    } else {
                        status = "No se pudo entrar a la sala"
                        onChange()
                    }
                }
            }
            "phx_error", "phx_close" -> closed(event)
            "presence_state" -> {
                presence.clear()
                readPresence(payload) { id, p -> presence[id] = p }
                updatePeers()
            }
            "presence_diff" -> {
                val joins = payload.optJSONObject("joins") ?: JSONObject()
                val leaves = payload.optJSONObject("leaves") ?: JSONObject()
                readPresence(leaves) { id, p ->
                    if (presence.remove(id) != null && id != myId) system("${p.name} salió")
                }
                readPresence(joins) { id, p ->
                    if (!presence.containsKey(id) && id != myId) system("${p.name} entró desde ${p.device}")
                    presence[id] = p
                }
                updatePeers()
            }
            "broadcast" -> {
                val ev = payload.optString("event")
                val p = payload.optJSONObject("payload") ?: JSONObject()
                when (ev) {
                    "estado" -> if (!host) {
                        val arr = p.optJSONArray("tracks") ?: JSONArray()
                        val tracks = (0 until arr.length()).map { i ->
                            val o = arr.getJSONObject(i)
                            JamTrack(o.optString("t"), o.optString("a"), o.optString("al"), o.optLong("d"))
                        }
                        val st = JamState(p.optString("disc"), tracks, p.optInt("i"), p.optLong("p"), p.optBoolean("on"), System.currentTimeMillis())
                        remote = st
                        onRemote(st)
                        onChange()
                    }
                    "chat" -> {
                        val msg = JamMsg(p.optString("id"), p.optString("name"), p.optString("text"), p.optLong("ts"), false)
                        synchronized(msgs) { msgs += msg; trim() }
                        onChange()
                    }
                    "hola" -> if (host) onHello()
                    "fin" -> if (!host) {
                        ended = true
                        status = "La Jam terminó"
                        system("${p.optString("name")} cerró la Jam")
                        heartbeat?.cancel(false)
                        socket?.close()
                        socket = null
                        live = false
                        onChange()
                    }
                }
            }
        }
    }

    private fun readPresence(obj: JSONObject, f: (String, JamPeer) -> Unit) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val metas = obj.optJSONObject(k)?.optJSONArray("metas") ?: continue
            if (metas.length() == 0) continue
            val o = metas.getJSONObject(0)
            f(k, JamPeer(k, o.optString("name", "Alguien"), o.optString("device", ""), o.optBoolean("host")))
        }
    }

    private fun updatePeers() {
        peers = presence.values.sortedWith(compareBy({ !it.host }, { it.name.lowercase() }))
        onChange()
    }

    private fun system(text: String) {
        synchronized(msgs) { msgs += JamMsg("", "", text, System.currentTimeMillis(), false, system = true); trim() }
        onChange()
    }

    private fun trim() { while (msgs.size > 300) msgs.removeAt(0) }

    private fun stopInternal(sendEnd: Boolean) {
        if (sendEnd && live) broadcast("fin", JSONObject().put("name", name))
        if (socket != null) send("phx_leave", JSONObject())
        generation++
        heartbeat?.cancel(false)
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        live = false
        status = ""
        presence.clear()
        peers = emptyList()
        remote = null
    }
}
