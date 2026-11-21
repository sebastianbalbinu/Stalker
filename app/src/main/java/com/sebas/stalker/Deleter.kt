package com.sebas.stalker

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Borrar canciones del teléfono. Android siempre pide confirmar con su propia ventana
 * (desde Android 11, una sola vez para todas las canciones elegidas).
 */
object Deleter {
    /** Ventana de confirmación de Android que la pantalla debe mostrar. */
    val request = MutableStateFlow<IntentSender?>(null)
    /** Canciones esperando esa confirmación (para avisar cuántas se borraron). */
    private var pending: List<Track> = emptyList()
    /** Sube cada vez que se borra algo: la biblioteca se vuelve a leer. */
    val done = MutableStateFlow(0)
    val message = MutableStateFlow<String?>(null)

    /** Pide borrar estas canciones. En Android 11+ abre la confirmación del sistema. */
    suspend fun delete(ctx: Context, tracks: List<Track>) = withContext(Dispatchers.IO) {
        val ts = tracks.filter { it.id > 0 }.distinctBy { it.id }
        if (ts.isEmpty()) return@withContext
        pending = ts
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, ts.map { it.uri })
            request.value = pi.intentSender
            return@withContext
        }
        var removed = 0
        for (t in ts) {
            try {
                removed += ctx.contentResolver.delete(t.uri, null, null)
            } catch (e: SecurityException) {
                // Android 10: hay que pedir permiso archivo por archivo.
                if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                    pending = ts.drop(ts.indexOf(t))
                    request.value = e.userAction.actionIntent.intentSender
                    finish(removed)
                    return@withContext
                }
            }
        }
        finish(removed)
    }

    /** La ventana de Android se cerró: [ok] si el usuario aceptó. */
    suspend fun confirmed(ctx: Context, ok: Boolean) {
        request.value = null
        if (!ok) { pending = emptyList(); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val n = pending.size
            forget(pending)
            pending = emptyList()
            finish(n)
        } else {
            // Android 10: ya hay permiso para esta; seguimos con las demás.
            delete(ctx, pending)
        }
    }

    private fun forget(ts: List<Track>) {
        // Quita las canciones de los discos armados y de la cola de favoritos de canciones.
        Engine.forgetTracks(ts.map { it.id }.toSet())
    }

    private fun finish(n: Int) {
        if (n <= 0) return
        done.value++
        message.value = if (n == 1) tr("Canción borrada del teléfono") else tr("%s canciones borradas del teléfono", n)
    }

    // ---------- repetidas ----------

    data class DupGroup(val keep: Track, val extra: List<Track>)

    /**
     * Canciones repetidas: mismo artista, mismo título y casi la misma duración (±3 s).
     * Se queda la de mejor calidad (sin pérdida antes que MP3, y la más pesada).
     */
    fun duplicates(tracks: List<Track>): List<DupGroup> {
        fun norm(s: String) = s.lowercase()
            .replace(Regex("""\s*[(\[].*?(remaster|version|versión|mono|stereo|edit).*?[)\]]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        fun quality(t: Track): Long {
            val lossless = t.mime.contains("flac", true) || t.mime.contains("wav", true) || t.mime.contains("alac", true)
            return (if (lossless) 1L shl 40 else 0L) + t.sizeBytes
        }
        val out = ArrayList<DupGroup>()
        val groups = tracks.filter { it.id > 0 && it.title.isNotBlank() }
            .groupBy { norm(it.artist) + "|" + norm(it.title) }
        for ((_, g) in groups) {
            if (g.size < 2) continue
            // Dentro del mismo nombre, junta por duración parecida (no confundir versiones en vivo).
            val left = g.sortedBy { it.durationMs }.toMutableList()
            while (left.isNotEmpty()) {
                val first = left.removeAt(0)
                val same = mutableListOf(first)
                left.removeAll { t -> (kotlin.math.abs(t.durationMs - first.durationMs) <= 3000).also { if (it) same += t } }
                if (same.size > 1) {
                    val best = same.maxBy { quality(it) }
                    out += DupGroup(best, same.filter { it.id != best.id })
                }
            }
        }
        return out.sortedBy { it.keep.artist.lowercase() + it.keep.title.lowercase() }
    }
}
