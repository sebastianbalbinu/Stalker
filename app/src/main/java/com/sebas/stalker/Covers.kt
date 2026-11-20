package com.sebas.stalker

import android.content.Context
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Portadas que faltan: se buscan en Cover Art Archive (de MusicBrainz), en alta resolución,
 * y se guardan dentro de la app. Los archivos de música no se tocan.
 */
object Covers {
    private const val RETRY_MS = 7L * 24 * 3_600_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Pair<Context, Track>>(Channel.UNLIMITED)
    private val queued = HashSet<String>()

    /** Sube cada vez que llega una portada nueva: las carátulas en pantalla se recargan. */
    val changes = MutableStateFlow(0)
    val batchStatus = MutableStateFlow("")

    init {
        scope.launch { for ((ctx, t) in queue) runCatching { fetch(ctx, t) }; }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stalker", Context.MODE_PRIVATE)
    fun autoEnabled(ctx: Context) = prefs(ctx).getBoolean("autoCovers", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoCovers", on).apply()

    private fun key(t: Track): String {
        val s = "${(t.albumArtist ?: t.artist).trim().lowercase()}|${t.album.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    fun file(ctx: Context, t: Track) = File(dir(ctx), key(t) + ".jpg")
    private fun none(ctx: Context, t: Track) = File(dir(ctx), key(t) + ".none")

    /** Portada descargada antes, ya reducida al tamaño de pantalla. */
    fun local(ctx: Context, t: Track, px: Int): android.graphics.Bitmap? {
        val f = file(ctx, t)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Pide buscar la portada de este álbum (si está activado y no se buscó hace poco). */
    fun request(ctx: Context, t: Track) {
        if (!autoEnabled(ctx) || t.source != null) return
        val k = key(t)
        synchronized(queued) { if (!queued.add(k)) return }
        if (recentlyMissing(ctx, t)) return
        queue.trySend(ctx.applicationContext to t)
    }

    private fun recentlyMissing(ctx: Context, t: Track): Boolean {
        val n = none(ctx, t)
        return n.exists() && System.currentTimeMillis() - n.lastModified() < RETRY_MS
    }

    /** Busca y guarda la portada. true si la encontró. */
    suspend fun fetch(ctx: Context, t: Track): Boolean {
        if (file(ctx, t).exists()) return true
        val artist = t.albumArtist ?: t.artist
        val group = try { MusicBrainz.releaseGroup(t.album, artist) } catch (e: Exception) { return false }
        val id = group?.optString("id")
        val img = if (id.isNullOrBlank()) null else try {
            MusicBrainz.bytes("https://coverartarchive.org/release-group/$id/front-1200")
                ?: MusicBrainz.bytes("https://coverartarchive.org/release-group/$id/front")
        } catch (e: Exception) {
            return false // sin internet: se intenta otra vez después
        }
        if (img == null || img.size < 2000) {
            none(ctx, t).apply { writeText(""); setLastModified(System.currentTimeMillis()) }
            return false
        }
        file(ctx, t).writeBytes(img)
        none(ctx, t).delete()
        ArtCache.forget(t.albumId)
        changes.value++
        return true
    }

    /** Busca las portadas que faltan en toda la biblioteca. */
    suspend fun fetchAll(ctx: Context, albums: List<Album>) {
        if (batchStatus.value.startsWith(tr("Buscando portadas"))) return
        val pending = albums.filter { a ->
            val t = a.tracks.first()
            t.source == null && !file(ctx, t).exists() && ArtCache.load(ctx, t) == null
        }
        var got = 0
        pending.forEachIndexed { i, a ->
            batchStatus.value = tr("Buscando portadas · %s de %s · %s encontradas", i + 1, pending.size, got)
            if (fetch(ctx, a.tracks.first())) got++
        }
        batchStatus.value = if (pending.isEmpty()) tr("Todos tus discos tienen portada") else tr("Listo · %s portadas nuevas", got)
    }
}
