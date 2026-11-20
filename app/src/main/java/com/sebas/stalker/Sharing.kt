package com.sebas.stalker

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Compartir un disco: un .stalker.zip con los FLAC originales (sin recomprimir), el orden de las
 * canciones, la portada y una lista .m3u para otros reproductores. Quien lo abre con Stalker
 * recibe el mismo disco en su biblioteca.
 */
object Sharing {

    /** Mensaje mientras se arma o se abre un disco; null cuando no hay nada en curso. */
    val working = MutableStateFlow<String?>(null)

    private fun clean(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Disco" }

    private fun extFor(t: Track): String = when {
        t.mime.contains("flac", true) -> "flac"
        t.mime.contains("mpeg", true) -> "mp3"
        t.mime.contains("mp4", true) || t.mime.contains("aac", true) -> "m4a"
        t.mime.contains("wav", true) -> "wav"
        t.mime.contains("ogg", true) -> "ogg"
        else -> "audio"
    }

    suspend fun exportDisc(ctx: Context, name: String, tracks: List<Track>): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "compartir").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, clean(name) + ".stalker.zip")
            val files = tracks.mapIndexed { i, t -> "%02d - %s.%s".format(i + 1, clean(t.title), extFor(t)) }
            ZipOutputStream(FileOutputStream(file).buffered(1 shl 16)).use { zip ->
                // El FLAC ya viene comprimido sin pérdida: se guarda tal cual.
                zip.setLevel(Deflater.NO_COMPRESSION)

                val manifest = JSONObject().apply {
                    put("app", "Stalker")
                    put("version", 1)
                    put("name", name)
                    put("tracks", JSONArray().apply {
                        tracks.forEachIndexed { i, t ->
                            put(JSONObject().apply {
                                put("file", files[i]); put("title", t.title); put("artist", t.artist); put("album", t.album)
                            })
                        }
                    })
                }
                zip.putNextEntry(ZipEntry("stalker.json"))
                zip.write(manifest.toString(2).toByteArray())
                zip.closeEntry()

                val m3u = StringBuilder("#EXTM3U\n#PLAYLIST:$name\n")
                tracks.forEachIndexed { i, t ->
                    m3u.append("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}\n${files[i]}\n")
                }
                zip.putNextEntry(ZipEntry(clean(name) + ".m3u"))
                zip.write(m3u.toString().toByteArray())
                zip.closeEntry()

                tracks.firstOrNull()?.let { first ->
                    try {
                        val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 29)
                            ctx.contentResolver.loadThumbnail(first.uri, Size(1000, 1000), null) else null
                        if (bmp != null) {
                            zip.putNextEntry(ZipEntry("cover.jpg"))
                            bmp.compress(Bitmap.CompressFormat.JPEG, 92, zip)
                            zip.closeEntry()
                        }
                    } catch (e: Exception) {
                    }
                }

                tracks.forEachIndexed { i, t ->
                    working.value = tr("Preparando el disco · %s de %s", i + 1, tracks.size)
                    ctx.contentResolver.openInputStream(t.uri)?.use { input ->
                        zip.putNextEntry(ZipEntry(files[i]))
                        input.copyTo(zip, 1 shl 16)
                        zip.closeEntry()
                    }
                }
            }
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        } catch (e: Exception) {
            null
        } finally {
            working.value = null
        }
    }

    fun shareIntent(uri: Uri, name: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, tr("Disco: %s", name))
                .putExtra(Intent.EXTRA_TEXT, tr("Te comparto el disco «%s». Ábrelo con Stalker para tenerlo en tu biblioteca.", name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            tr("Compartir disco")
        )

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Qué es lo que nos mandaron: un disco (zip), una canción o nada que sirva. */
    enum class Kind { DISC, SONG, OTHER }

    fun kindOf(ctx: Context, uri: Uri, declared: String?): Kind {
        val name = displayName(ctx, uri).orEmpty()
        val mime = (declared ?: ctx.contentResolver.getType(uri)).orEmpty().lowercase()
        if (Downloads.isAudio(name) || mime.startsWith("audio/") || mime.contains("flac")) return Kind.SONG
        // Por nombre o, si viene como archivo genérico, mirando si empieza como un zip ("PK").
        if (Downloads.isZip(name) || mime.contains("zip")) return Kind.DISC
        return try {
            val head = ByteArray(4)
            ctx.contentResolver.openInputStream(uri)?.use { it.read(head) }
            when {
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Kind.DISC
                head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> Kind.SONG
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> Kind.SONG
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> Kind.SONG
                else -> Kind.OTHER
            }
        } catch (e: Exception) {
            Kind.OTHER
        }
    }

    /**
     * Canciones sueltas abiertas con Stalker: se guardan en Música/Stalker/Recibidos (una sola vez)
     * y se devuelven sus ids para ponerlas a sonar.
     */
    suspend fun importSongs(ctx: Context, uris: List<Uri>): List<Long> = withContext(Dispatchers.IO) {
        val out = ArrayList<Long>()
        for ((i, uri) in uris.withIndex()) {
            working.value = if (uris.size > 1) tr("Guardando canciones · %s", i + 1) else tr("Abriendo la canción…")
            try {
                var name = clean(displayName(ctx, uri) ?: "Stalker ${System.currentTimeMillis()}.flac")
                if (!Downloads.isAudio(name)) {
                    val m = ctx.contentResolver.getType(uri).orEmpty()
                    name += when {
                        m.contains("flac") -> ".flac"
                        m.contains("mpeg") -> ".mp3"
                        m.contains("wav") -> ".wav"
                        m.contains("mp4") || m.contains("m4a") -> ".m4a"
                        else -> ".flac"
                    }
                }
                val existing = findReceived(ctx, name)
                if (existing != null) { out += existing; continue }
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    insertAudio(ctx, "", name, input, base = "Recibidos")?.let { out += it }
                }
            } catch (e: Exception) {
            }
        }
        working.value = null
        out
    }

    /** Si esa canción ya se guardó antes en Recibidos, su id. */
    private fun findReceived(ctx: Context, name: String): Long? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "${Environment.DIRECTORY_MUSIC}/Stalker/Recibidos%"), null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    private fun insertAudio(ctx: Context, folder: String, name: String, input: InputStream, base: String = "Discos"): Long? {
        val sub = if (folder.isBlank()) base else "$base/$folder"
        val mime = when (Downloads.ext(name)) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/x-wav"
            "ogg" -> "audio/ogg"
            else -> "audio/*"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Stalker/$sub")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
            ) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 1 shl 16) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            ctx.contentResolver.update(uri, values, null, null)
            return ContentUris.parseId(uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Stalker/$sub")
            dir.mkdirs()
            FileOutputStream(File(dir, name)).use { out -> input.copyTo(out, 1 shl 16) }
            return null
        }
    }

    /** Abre un disco compartido (o cualquier .zip con música) y lo agrega como "Mi disco". */
    suspend fun importDisc(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val zipName = displayName(ctx, uri)?.removeSuffix(".zip")?.removeSuffix(".stalker") ?: tr("Disco compartido")
        var discName = zipName
        var order: List<String> = emptyList()
        val ids = HashMap<String, Long>()
        var count = 0
        try {
            working.value = tr("Abriendo el disco…")
            val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext tr("No se pudo abrir el archivo")
            ZipInputStream(input.buffered(1 shl 16)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val base = entry.name.substringAfterLast('/')
                    if (base == "stalker.json") {
                        val o = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        discName = o.optString("name", discName)
                        val arr = o.optJSONArray("tracks") ?: JSONArray()
                        order = (0 until arr.length()).map { arr.getJSONObject(it).optString("file") }
                        continue
                    }
                    if (!Downloads.isAudio(base) || base.startsWith("._")) continue
                    count++
                    working.value = tr("Guardando canciones · %s", count)
                    insertAudio(ctx, clean(discName), clean(base), zip)?.let { ids[base] = it }
                }
            }
            if (count == 0) return@withContext tr("Ese archivo no trae canciones")
            val ordered = if (order.isNotEmpty()) order.mapNotNull { ids[it] } + ids.filterKeys { it !in order }.values
            else ids.entries.sortedBy { it.key }.map { it.value }
            if (ordered.isNotEmpty()) Engine.createDisco(discName, ordered)
            tr("Disco «%s» agregado con %s canciones", discName, count)
        } catch (e: Exception) {
            if (count > 0) tr("Se guardaron %s canciones, pero el archivo venía incompleto", count) else tr("Ese archivo no es un disco válido")
        } finally {
            working.value = null
        }
    }
}
