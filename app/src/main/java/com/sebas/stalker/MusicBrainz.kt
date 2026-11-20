package com.sebas.stalker

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente mínimo de MusicBrainz (y Wikipedia), gratis y sin cuenta.
 * MusicBrainz pide como máximo una consulta por segundo y un User-Agent que diga quiénes somos.
 */
object MusicBrainz {
    const val AGENT = "Stalker/1.4 ( https://github.com/sebastianbalbinu/Stalker )"
    private const val WS = "https://musicbrainz.org/ws/2"
    private val lock = Mutex()
    private var last = 0L

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** GET con User-Agent; devuelve null en 404 y lanza en otros errores (sin internet, etc.). */
    fun http(url: String, accept: String = "application/json"): String? {
        var u = URL(url)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 9000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", accept)
            try {
                val code = c.responseCode
                if (code in 300..399 && hops++ < 6) {
                    u = URL(u, c.getHeaderField("Location"))
                    continue
                }
                return when (code) {
                    200 -> c.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IllegalStateException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Descarga binaria (portadas), siguiendo redirecciones. */
    fun bytes(url: String): ByteArray? {
        var u = URL(url)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 9000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            try {
                val code = c.responseCode
                if (code in 300..399 && hops++ < 6) {
                    u = URL(u, c.getHeaderField("Location"))
                    continue
                }
                return when (code) {
                    200 -> c.inputStream.use { it.readBytes() }
                    404 -> null
                    else -> throw IllegalStateException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    /** Consulta a MusicBrainz respetando su límite de una por segundo. */
    suspend fun ws(path: String): JSONObject? = lock.withLock {
        var attempt = 0
        while (true) {
            val wait = 1100 - (System.currentTimeMillis() - last)
            if (wait > 0) delay(wait)
            try {
                return@withLock http("$WS/$path${if ('?' in path) "&" else "?"}fmt=json")?.let { JSONObject(it) }
            } catch (e: IllegalStateException) {
                // 503: MusicBrainz está ocupado; se espera un poco y se reintenta (hasta 3 veces).
                if (e.message != "HTTP 503" || attempt++ >= 2) throw e
                delay(2500L * attempt)
            } finally {
                last = System.currentTimeMillis()
            }
        }
        null
    }

    private fun clean(s: String) = s.replace("\"", " ").replace("\\", " ").trim()

    /** El "grupo de lanzamiento" (el álbum en abstracto) que mejor coincide con título y artista. */
    suspend fun releaseGroup(album: String, artist: String): JSONObject? {
        val various = artist.equals(tr("Varios artistas"), true) || artist.equals("Various Artists", true)
        val q = buildString {
            append("releasegroup:\"").append(clean(album)).append('"')
            if (!various && artist.isNotBlank()) append(" AND artist:\"").append(clean(artist)).append('"')
        }
        val res = ws("release-group/?query=${enc(q)}&limit=5") ?: return null
        val arr = res.optJSONArray("release-groups") ?: return null
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optInt("score") >= 90) return g
        }
        return null
    }
}
