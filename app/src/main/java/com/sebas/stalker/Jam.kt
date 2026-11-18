package com.sebas.stalker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** Conexión WebSocket con OkHttp para la Jam. */
private object OkTransport : JamTransport {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    override fun open(url: String, onOpen: (JamSocket) -> Unit, onText: (String) -> Unit, onClosed: (String?) -> Unit) {
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen(object : JamSocket {
                    override fun send(text: String) { webSocket.send(text) }
                    override fun close() { webSocket.close(1000, null) }
                })
            }
            override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed(t.message)
        })
    }
}

/** La Jam en Android: el núcleo compartido más el estado que observa la interfaz. */
object Jam {
    val core = JamCore(OkTransport, "Android")
    /** Sube cada vez que algo cambia: la interfaz lo observa para redibujarse. */
    val version = MutableStateFlow(0)
    /** Canciones del disco de la Jam que este teléfono no tiene. */
    val missing = MutableStateFlow<List<String>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null

    init {
        core.onChange = { version.value++ }
    }

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.getSharedPreferences("jam", Context.MODE_PRIVATE)
    }

    var savedName: String
        get() = prefs?.getString("name", "") ?: ""
        set(v) { prefs?.edit()?.putString("name", v)?.apply() }
}
