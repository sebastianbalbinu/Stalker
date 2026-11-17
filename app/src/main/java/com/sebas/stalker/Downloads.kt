package com.sebas.stalker

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class DownloadInfo(
    val id: Long,
    val title: String,
    val status: Int,
    val done: Long,
    val total: Long,
) {
    val progress: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * Descargas de la tienda: van a Música/Stalker en segundo plano (siguen aunque cambies de pantalla
 * o escuches otra cosa). Los álbumes que llegan en .zip se descomprimen solos.
 */
object Downloads {

    private val audioExt = setOf("flac", "mp3", "m4a", "wav", "aiff", "aif", "ogg", "alac", "ape", "wv", "dsf", "dff")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isAudio(name: String) = ext(name) in audioExt
    fun isZip(name: String) = ext(name) == "zip"

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    /** Devuelve el nombre del archivo, o null si no se pudo empezar. */
    fun enqueue(ctx: Context, url: String, userAgent: String?, contentDisposition: String?, mime: String?): String? = try {
        val name = URLUtil.guessFileName(url, contentDisposition, mime)
        val musical = isAudio(name) || isZip(name)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("Stalker")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (musical) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Stalker/$name")
        else req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        if (!userAgent.isNullOrBlank()) req.addRequestHeader("User-Agent", userAgent)
        val id = dm(ctx).enqueue(req)
        Engine.addDownload(id)
        name
    } catch (e: Exception) {
        null
    }

    fun list(ctx: Context): List<DownloadInfo> {
        val ids = Engine.downloads.value
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<DownloadInfo>()
        try {
            dm(ctx).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iTitle = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    out.add(
                        DownloadInfo(
                            c.getLong(iId), c.getString(iTitle) ?: "Descarga",
                            c.getInt(iStatus), c.getLong(iDone), c.getLong(iTotal)
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out.sortedByDescending { it.id }
    }

    fun cancel(ctx: Context, id: Long) {
        try { dm(ctx).remove(id) } catch (e: Exception) { }
        Engine.removeDownload(id)
    }

    /** Quita de la lista las descargas terminadas (los archivos se quedan). */
    fun clearFinished(ctx: Context) {
        list(ctx).filter { it.status == DownloadManager.STATUS_SUCCESSFUL || it.status == DownloadManager.STATUS_FAILED }
            .filter { it.id !in Engine.extracting.value }
            .forEach { Engine.removeDownload(it.id) }
    }

    private fun clean(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Álbum" }

    private fun mimeOf(name: String) = when (ext(name)) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "alac" -> "audio/mp4"
        "wav" -> "audio/x-wav"
        "aiff", "aif" -> "audio/x-aiff"
        "ogg" -> "audio/ogg"
        else -> "audio/*"
    }

    /** Descomprime un álbum .zip descargado dentro de Música/Stalker/<álbum>. Devuelve cuántas canciones sacó. */
    suspend fun extract(ctx: Context, id: Long, zipTitle: String): Int = withContext(Dispatchers.IO) {
        val folder = clean(zipTitle.removeSuffix(".zip").removeSuffix(".ZIP"))
        var count = 0
        val pfd: ParcelFileDescriptor = dm(ctx).openDownloadedFile(id)
        ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd).buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = clean(entry.name.substringAfterLast('/'))
                if (!isAudio(name) || name.startsWith("._")) continue
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                        put(MediaStore.Audio.Media.MIME_TYPE, mimeOf(name))
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Stalker/$folder")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = ctx.contentResolver.insert(collection, values) ?: continue
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> zip.copyTo(out, 1 shl 16) }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Stalker/$folder")
                    dir.mkdirs()
                    FileOutputStream(File(dir, name)).use { out -> zip.copyTo(out, 1 shl 16) }
                }
                count++
            }
        }
        if (count > 0) {
            // El .zip ya no hace falta.
            try { dm(ctx).remove(id) } catch (e: Exception) { }
        }
        count
    }
}

/** El sistema avisa cuando termina una descarga, aunque la app esté cerrada. */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        Engine.init(context)
        if (id !in Engine.downloads.value) return
        val info = try {
            val dm = context.getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) null
                else Pair(
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "",
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                )
            }
        } catch (e: Exception) {
            null
        } ?: return
        if (info.second == DownloadManager.STATUS_SUCCESSFUL && Downloads.isZip(info.first)) {
            Engine.setExtracting(id, true)
            val work = OneTimeWorkRequestBuilder<ExtractWorker>()
                .setInputData(workDataOf("id" to id, "title" to info.first))
                .build()
            WorkManager.getInstance(context).enqueue(work)
        }
    }
}

class ExtractWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("id", -1L)
        val title = inputData.getString("title") ?: "Álbum.zip"
        if (id < 0) return Result.failure()
        Engine.init(applicationContext)
        return try {
            Downloads.extract(applicationContext, id, title)
            Engine.setExtracting(id, false)
            Engine.removeDownload(id)
            Result.success()
        } catch (e: Exception) {
            Engine.setExtracting(id, false)
            Result.failure()
        }
    }
}
