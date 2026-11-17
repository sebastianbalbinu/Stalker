package com.sebas.stalker

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

object Library {

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
        )
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection2 = projection + pathCol
        val selection = buildString {
            append("(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE '%flac%')")
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_PODCAST} = 0")
            if (Build.VERSION.SDK_INT >= 31) append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
        }
        val out = ArrayList<Track>()
        try {
            context.contentResolver.query(collection, projection2, selection, null, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iPath = c.getColumnIndexOrThrow(pathCol)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val path = c.getString(iPath).orEmpty()
                    val mime = c.getString(iMime).orEmpty()
                    val dur = c.getLong(iDur)
                    if (!looksLikeMusic(path, mime, dur)) continue
                    val artist = c.getString(iArtist)
                    out.add(
                        Track(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            title = c.getString(iTitle) ?: "Sin título",
                            artist = if (artist.isNullOrBlank() || artist == "<unknown>") "Artista desconocido" else artist,
                            album = c.getString(iAlbum) ?: "Sin álbum",
                            albumId = c.getLong(iAlbumId),
                            trackNo = c.getInt(iTrack) % 1000,
                            durationMs = dur,
                            mime = mime,
                            year = c.getInt(iYear),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Sin permiso o error del sistema: biblioteca vacía.
        }
        out
    }

    /** Carpetas que casi nunca son música: notas de voz, grabaciones, tonos. */
    private val noMusicFolders = listOf(
        "whatsapp", "telegram", "signal", "messenger", "instagram",
        "recordings", "recorder", "grabacion", "grabación", "voice notes", "voicenotes", "voice recorder",
        "callrecord", "call_rec", "call recordings", "llamadas",
        "ringtones", "notifications", "alarms", "podcasts", "audiobooks", "miui/sound_recorder",
        "android/data", "android/media", "dcim", "movies",
    )

    private fun looksLikeMusic(path: String, mime: String, durationMs: Long): Boolean {
        val p = path.lowercase()
        if (noMusicFolders.any { p.contains(it) }) return false
        val lossless = mime.contains("flac", true) || mime.contains("alac", true) || mime.contains("wav", true)
        if (mime.contains("opus", true) || mime.contains("amr", true) || mime.contains("3gpp", true)) return false
        // Clips muy cortos (menos de 40 s) casi siempre son audios o efectos.
        if (!lossless && durationMs in 1 until 40_000) return false
        return true
    }

    /** Lee formato real: para FLAC, del encabezado STREAMINFO; para otros, con MediaExtractor. */
    suspend fun audioInfo(context: Context, track: Track): AudioInfo? = withContext(Dispatchers.IO) {
        if (track.mime.contains("flac", ignoreCase = true)) {
            readFlac(context, track.uri)?.let { return@withContext it }
        }
        try {
            val ex = MediaExtractor()
            ex.setDataSource(context, track.uri, null)
            val f = ex.getTrackFormat(0)
            val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: track.mime
            ex.release()
            AudioInfo(codecName(mime), sr, 16, ch)
        } catch (e: Exception) {
            null
        }
    }

    private fun codecName(mime: String): String = when {
        mime.contains("flac", true) -> "FLAC"
        mime.contains("alac", true) -> "ALAC"
        mime.contains("wav", true) || mime.contains("raw", true) -> "WAV"
        mime.contains("mpeg", true) -> "MP3"
        mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
        mime.contains("opus", true) -> "Opus"
        mime.contains("vorbis", true) || mime.contains("ogg", true) -> "Vorbis"
        else -> mime.substringAfter('/').uppercase()
    }

    private fun readFully(s: InputStream, n: Int): ByteArray? {
        val b = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = s.read(b, read, n - read)
            if (r < 0) return null
            read += r
        }
        return b
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k <= 0) {
                if (s.read() < 0) return
                left -= 1
            } else {
                left -= k
            }
        }
    }

    private fun readFlac(context: Context, uri: Uri): AudioInfo? = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            var head = readFully(s, 4) ?: return@use null
            if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                val rest = readFully(s, 6) ?: return@use null
                val size = ((rest[2].toInt() and 0x7f) shl 21) or ((rest[3].toInt() and 0x7f) shl 14) or
                    ((rest[4].toInt() and 0x7f) shl 7) or (rest[5].toInt() and 0x7f)
                skipFully(s, size.toLong())
                head = readFully(s, 4) ?: return@use null
            }
            if (String(head, Charsets.US_ASCII) != "fLaC") return@use null
            readFully(s, 4) ?: return@use null // encabezado del bloque STREAMINFO
            val si = readFully(s, 34) ?: return@use null
            val b10 = si[10].toInt() and 0xff
            val b11 = si[11].toInt() and 0xff
            val b12 = si[12].toInt() and 0xff
            val b13 = si[13].toInt() and 0xff
            val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
            val channels = ((b12 shr 1) and 0x7) + 1
            val bits = (((b12 and 0x1) shl 4) or (b13 shr 4)) + 1
            if (sampleRate <= 0) null else AudioInfo("FLAC", sampleRate, bits, channels)
        }
    } catch (e: Exception) {
        null
    }
}

object ArtCache {
    private val cache = LruCache<Long, ImageBitmap>(80)
    private val missing = HashSet<Long>()

    fun peek(albumId: Long): ImageBitmap? = cache.get(albumId)

    suspend fun load(context: Context, track: Track): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(track.albumId)?.let { return@withContext it }
        synchronized(missing) { if (track.albumId in missing) return@withContext null }
        var bmp: Bitmap? = null
        if (Build.VERSION.SDK_INT >= 29) {
            bmp = try {
                context.contentResolver.loadThumbnail(track.uri, Size(640, 640), null)
            } catch (e: Exception) {
                null
            }
        }
        if (bmp == null) bmp = embedded(context, track.uri)
        if (bmp == null) {
            synchronized(missing) { missing.add(track.albumId) }
            null
        } else {
            val img = bmp.asImageBitmap()
            cache.put(track.albumId, img)
            img
        }
    }

    private fun embedded(context: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val data = r.embeddedPicture ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 640) sample *= 2
            BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }
}
