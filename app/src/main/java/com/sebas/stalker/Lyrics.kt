package com.sebas.stalker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Letras desde LRCLIB (lrclib.net): gratis, abiertas y casi siempre sincronizadas.
 * Se guardan dentro de la app (no se tocan tus FLAC), por canción.
 */
object Lyrics {
    private const val API = "https://lrclib.net/api"
    private const val AGENT = "Stalker (https://github.com/sebastianbalbinu/Stalker)"
    /** Si una canción no tenía letra, se vuelve a intentar después de una semana. */
    private const val RETRY_MS = 7L * 24 * 3_600_000

    /** Sube cada vez que se guarda o se borra una letra, para que la pantalla se refresque. */
    val changes = MutableStateFlow(0)
    /** Progreso de "descargar letras de toda la biblioteca" (vacío si no está corriendo). */
    val batchStatus = MutableStateFlow("")
    private val batchLock = Mutex()

    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("autoLyrics", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoLyrics", on).apply()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stalker", Context.MODE_PRIVATE)

    /** La letra que ya trae el archivo, si tiene. */
    fun embedded(tags: Map<String, String>): String? =
        (tags["LYRICS"] ?: tags["SYNCEDLYRICS"] ?: tags["UNSYNCEDLYRICS"] ?: tags["UNSYNCED LYRICS"])?.takeIf { it.isNotBlank() }

    /** Clave estable por canción (sobrevive a que Android renumere los archivos). */
    private fun key(t: Track): String {
        val s = "${t.artist.trim().lowercase()}|${t.title.trim().lowercase()}|${t.album.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun file(ctx: Context, t: Track) = File(dir(ctx), key(t) + ".lrc")
    private fun none(ctx: Context, t: Track) = File(dir(ctx), key(t) + ".none")

    /** Letra guardada en la app para esta canción. */
    fun local(ctx: Context, t: Track): String? = file(ctx, t).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun remove(ctx: Context, t: Track) {
        file(ctx, t).delete()
        none(ctx, t).delete()
        changes.value++
    }

    /** Ya se buscó hace poco y no había. */
    private fun recentlyMissing(ctx: Context, t: Track): Boolean {
        val n = none(ctx, t)
        return n.exists() && System.currentTimeMillis() - n.lastModified() < RETRY_MS
    }

    /**
     * Busca y guarda la letra. Devuelve true si la encontró.
     * [force] ignora que hace poco no se encontró.
     */
    suspend fun fetch(ctx: Context, t: Track, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (local(ctx, t) != null) return@withContext true
        if (!force && recentlyMissing(ctx, t)) return@withContext false
        val found = try { exact(t) ?: search(t) } catch (e: Exception) { return@withContext false } // sin internet: no se marca
        if (found == null) {
            none(ctx, t).writeText("")
            none(ctx, t).setLastModified(System.currentTimeMillis())
            changes.value++
            return@withContext false
        }
        file(ctx, t).writeText(found)
        none(ctx, t).delete()
        changes.value++
        true
    }

    /** Al empezar una canción sin letra, la busca sola (si está activado). */
    suspend fun auto(ctx: Context, t: Track, tags: Map<String, String>) {
        if (!autoEnabled(ctx) || embedded(tags) != null || t.source != null) return
        fetch(ctx, t)
    }

    /** Descarga las letras de toda la biblioteca, una canción tras otra, sin saturar el servidor. */
    suspend fun fetchAll(ctx: Context, tracks: List<Track>) {
        if (!batchLock.tryLock()) return
        try {
            val pending = tracks.filter { it.source == null && local(ctx, it) == null && !recentlyMissing(ctx, it) }
            var got = 0
            pending.forEachIndexed { i, t ->
                batchStatus.value = tr("Buscando letras · %s de %s · %s encontradas", i + 1, pending.size, got)
                if (fetch(ctx, t)) got++
                delay(250)
            }
            batchStatus.value = if (pending.isEmpty()) tr("Ya tienes todas las letras que hay disponibles")
            else tr("Listo · %s letras nuevas", got)
        } finally {
            batchLock.unlock()
        }
    }

    // ---------- LRCLIB ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", AGENT)
        return try {
            when (c.responseCode) {
                200 -> c.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IllegalStateException("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun pick(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return "[00:00.00] ♪ (instrumental)"
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return synced ?: plain
    }

    /** Búsqueda exacta: artista, canción, álbum y duración (±2 s). */
    private fun exact(t: Track): String? {
        val secs = t.durationMs / 1000
        val url = "$API/get?artist_name=${enc(t.artist)}&track_name=${enc(t.title)}&album_name=${enc(t.album)}&duration=$secs"
        return get(url)?.let { pick(JSONObject(it)) }
    }

    /** Si no hay coincidencia exacta: busca por artista y canción y elige la de duración más parecida. */
    private fun search(t: Track): String? {
        val body = get("$API/search?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}") ?: return null
        val arr = JSONArray(body)
        val secs = t.durationMs / 1000.0
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val d = o.optDouble("duration", -1.0)
            val diff = if (d > 0 && secs > 0) abs(d - secs) else 0.0
            if (diff > 6) continue
            val score = diff - (if (o.optString("syncedLyrics").let { it.isNotBlank() && it != "null" }) 3 else 0)
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { pick(it) }
    }
}
