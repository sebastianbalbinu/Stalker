package stalker

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.WString
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.Collections
import java.util.logging.Level
import java.util.logging.Logger
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

data class Track(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNo: Int,
    val disc: Int,
    val durationMs: Long,
    val rate: Int,
    val bits: Int,
    val channels: Int,
    val format: String,
    val size: Long,
    val modified: Long,
    val year: String,
) {
    val lossless get() = format == "FLAC" || format == "WAV"
    val albumKey get() = (albumArtist.ifBlank { artist } + "\u0001" + album).lowercase()
    val quality: String
        get() = when {
            !lossless -> tr("%s · con pérdida", format)
            else -> "$format · ${if (bits > 0) "$bits-bit · " else ""}${khz(rate)}"
        }
}

fun khz(rate: Int): String {
    if (rate <= 0) return ""
    val k = rate / 1000.0
    return if (rate % 1000 == 0) "${rate / 1000} kHz" else String.format("%.1f kHz", k)
}

data class Album(
    val key: String,
    val title: String,
    val artist: String,
    val year: String,
    val tracks: List<Track>,
) {
    val durationMs get() = tracks.sumOf { it.durationMs }
    val cover get() = tracks.first().path
    val lossless get() = tracks.all { it.lossless }
    val hiRes get() = tracks.any { it.bits > 16 || it.rate > 48000 }
    val added get() = tracks.maxOf { it.modified }
}

object Prefs {
    private val p: Preferences = Preferences.userRoot().node("stalker-pc")

    var folders: List<String>
        get() = p.get("folders", "").split('\n').filter { it.isNotBlank() }.ifEmpty { defaultFolders() }
        set(v) { p.put("folders", v.joinToString("\n")) }
    var exclusive: Boolean
        get() = p.getBoolean("exclusive", true)
        set(v) = p.putBoolean("exclusive", v)
    var volume: Float
        get() = p.getFloat("volume", 1f)
        set(v) = p.putFloat("volume", v)
    var device: Int
        get() = p.getInt("device", -1)
        set(v) = p.putInt("device", v)
    var lastQueue: List<String>
        get() = p.get("lastQueue", "").split('\n').filter { it.isNotBlank() }
        set(v) { p.put("lastQueue", v.take(400).joinToString("\n").take(Preferences.MAX_VALUE_LENGTH)) }
    var lastIndex: Int
        get() = p.getInt("lastIndex", 0)
        set(v) = p.putInt("lastIndex", v)
    var lastPos: Long
        get() = p.getLong("lastPos", 0)
        set(v) = p.putLong("lastPos", v)
    var miniX: Int
        get() = p.getInt("miniX", -1)
        set(v) = p.putInt("miniX", v)
    var miniY: Int
        get() = p.getInt("miniY", -1)
        set(v) = p.putInt("miniY", v)
    /** Modo ligero para PCs modestos: menos animación de fondo y menos memoria. */
    private val motionState = androidx.compose.runtime.mutableStateOf(p.getBoolean("motion", true))
    /** Animaciones encendidas. Apagadas: nada se mueve solo y la ventana solo se redibuja cuando algo cambia. */
    var motion: Boolean
        get() = motionState.value
        set(v) { motionState.value = v; p.putBoolean("motion", v) }
    /** Compatibilidad: "ligero" es lo mismo que sin animaciones. */
    var lite: Boolean
        get() = !motionState.value
        set(v) { motion = !v }
    var floatX: Int
        get() = p.getInt("floatX", -1)
        set(v) = p.putInt("floatX", v)
    var floatY: Int
        get() = p.getInt("floatY", -1)
        set(v) = p.putInt("floatY", v)
    var liteAsked: Boolean
        get() = p.getBoolean("liteAsked", false)
        set(v) = p.putBoolean("liteAsked", v)
    var shelfMode: Int
        get() = p.getInt("shelfMode", 0)
        set(v) = p.putInt("shelfMode", v)
    var floatDisc: Boolean
        get() = p.getBoolean("floatDisc", true)
        set(v) = p.putBoolean("floatDisc", v)
    /** Reproducción continua: al terminar un disco, entra el siguiente. */
    var continuous: Boolean
        get() = p.getBoolean("continuous", true)
        set(v) = p.putBoolean("continuous", v)
    /** Qué disco sigue: "Estantería", "Al azar" o "Mismo artista". */
    var nextOrder: String
        get() = p.get("nextOrder", "Estantería")
        set(v) = p.put("nextOrder", v)
    /** Idioma: "Sistema", "Español" o "English". */
    var language: String
        get() = p.get("language", "Sistema")
        set(v) = p.put("language", v)
    var favorites: Set<String>
        get() = p.get("favorites", "").split('\n').filter { it.isNotBlank() }.toSet()
        set(v) { p.put("favorites", v.joinToString("\n").take(Preferences.MAX_VALUE_LENGTH)) }

    fun defaultFolders(): List<String> {
        val home = System.getProperty("user.home")
        return listOf("$home\\Music", "$home\\Downloads", "$home\\OneDrive\\Music", "$home\\OneDrive\\Música")
            .filter { File(it).isDirectory }
            .ifEmpty { listOf("$home\\Music") }
    }

    val dataDir: File by lazy {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
        File(base, "Stalker").apply { mkdirs() }
    }
}

/** Hilo de baja prioridad para trabajos pesados de fondo (formas de onda, colores de lomos): nunca compite con el audio ni con la ventana. */
val LowPriority: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread(r, "stalker-fondo").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
}.asCoroutineDispatcher()

object Library {
    val exts = setOf("flac", "wav", "mp3")

    // referencia fuerte: si no, Java olvida el nivel y jaudiotagger llena el registro
    private val jatLogger: Logger = Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    fun quiet() { jatLogger.level = Level.OFF }

    private val cacheFile get() = File(Prefs.dataDir, "biblioteca.tsv")

    fun loadCache(): List<Track> = try {
        if (!cacheFile.exists()) emptyList()
        else cacheFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 15) null else Track(
                f[0], f[1], f[2], f[3], f[4], f[5].toIntOrNull() ?: 0, f[6].toIntOrNull() ?: 1,
                f[7].toLongOrNull() ?: 0, f[8].toIntOrNull() ?: 0, f[9].toIntOrNull() ?: 0,
                f[10].toIntOrNull() ?: 2, f[11], f[12].toLongOrNull() ?: 0, f[13].toLongOrNull() ?: 0, f[14],
            )
        }.filter { File(it.path).exists() }
    } catch (_: Throwable) { emptyList() }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun saveCache(list: List<Track>) = try {
        cacheFile.writeText(list.joinToString("\n") { t ->
            listOf(t.path, t.title, t.artist, t.album, t.albumArtist, t.trackNo, t.disc, t.durationMs, t.rate, t.bits,
                t.channels, t.format, t.size, t.modified, t.year).joinToString("\t") { clean(it.toString()) }
        }, Charsets.UTF_8)
    } catch (_: Throwable) {}

    /** Recorre las carpetas; reutiliza lo que ya estaba leído si el archivo no cambió. */
    fun scan(folders: List<String>, previous: List<Track>, onProgress: (Int) -> Unit): List<Track> {
        val known = previous.associateBy { it.path }
        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        var count = 0
        for (root in folders) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .onEnter { !it.name.startsWith(".") && !it.name.equals("\$RECYCLE.BIN", true) }
                .filter { it.isFile && it.extension.lowercase() in exts }
                .forEach { f ->
                    val path = f.absolutePath
                    if (!seen.add(path)) return@forEach
                    val k = known[path]
                    val t = if (k != null && k.modified == f.lastModified() && k.size == f.length()) k else read(f)
                    if (t != null && (t.lossless || t.durationMs > 40_000)) out += t
                    count++
                    if (count % 25 == 0) onProgress(count)
                }
        }
        saveCache(out)
        return out
    }

    fun read(f: File): Track? = try {
        val af = AudioFileIO.read(f)
        val h = af.audioHeader
        val tag = af.tag
        fun g(k: FieldKey) = try { tag?.getFirst(k)?.trim().orEmpty() } catch (_: Throwable) { "" }
        val ext = f.extension.lowercase()
        Track(
            path = f.absolutePath,
            title = g(FieldKey.TITLE).ifBlank { f.nameWithoutExtension },
            artist = g(FieldKey.ARTIST).ifBlank { tr("Artista desconocido") },
            album = g(FieldKey.ALBUM).ifBlank { f.parentFile?.name ?: tr("Sin álbum") },
            albumArtist = g(FieldKey.ALBUM_ARTIST),
            trackNo = g(FieldKey.TRACK).substringBefore('/').toIntOrNull() ?: 0,
            disc = g(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: 1,
            durationMs = ((h.preciseTrackLength.takeIf { it > 0 } ?: h.trackLength.toDouble()) * 1000).toLong(),
            rate = h.sampleRateAsNumber,
            bits = if (ext == "mp3") 0 else h.bitsPerSample,
            channels = h.channels.filter { it.isDigit() }.toIntOrNull()
                ?: if (h.channels.contains("Mono", true)) 1 else 2,
            format = when (ext) { "flac" -> "FLAC"; "wav" -> "WAV"; else -> "MP3" },
            size = f.length(),
            modified = f.lastModified(),
            year = g(FieldKey.YEAR).take(4),
        )
    } catch (_: Throwable) { null }

    fun albums(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumKey }.map { (k, list) ->
            val sorted = list.sortedWith(compareBy({ it.disc }, { it.trackNo }, { it.title }))
            val first = sorted.first()
            Album(k, first.album, first.albumArtist.ifBlank { first.artist }, sorted.firstNotNullOfOrNull { it.year.ifBlank { null } } ?: "", sorted)
        }
}

/** Portadas: de la etiqueta o de cover.jpg / folder.jpg. Se reducen y se guardan en memoria. */
object Art {
    private val mem = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 90
    })

    fun cached(path: String): ImageBitmap? = mem[path]
    fun known(path: String) = mem.containsKey(path)

    fun load(path: String, px: Int = 400): ImageBitmap? {
        if (mem.containsKey(path)) return mem[path]
        val img = try { decode(bytes(path), px) } catch (_: Throwable) { null }
        mem[path] = img
        return img
    }

    fun bytes(path: String): ByteArray? {
        try {
            val tag = AudioFileIO.read(File(path)).tag
            val b = tag?.firstArtwork?.binaryData
            if (b != null && b.isNotEmpty()) return b
        } catch (_: Throwable) {}
        val dir = File(path).parentFile ?: return null
        val names = listOf("cover", "folder", "front", "album", "portada")
        val f = dir.listFiles()?.firstOrNull { f ->
            f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") && names.any { f.nameWithoutExtension.equals(it, true) }
        } ?: dir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        return f?.readBytes()
    }

    private fun decode(b: ByteArray?, px: Int): ImageBitmap? {
        if (b == null) return null
        var src = ImageIO.read(ByteArrayInputStream(b)) ?: return null
        // reducción en pasos para que la portada se vea nítida
        while (src.width > px * 2 || src.height > px * 2) src = scale(src, src.width / 2, src.height / 2)
        val s = minOf(px.toDouble() / src.width, px.toDouble() / src.height, 1.0)
        val out = scale(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1))
        return out.toComposeImageBitmap()
    }

    private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val o = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = o.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return o
    }
}

/** Forma de onda de cada pista, para dibujar los surcos con su música real. */
object Peaks {
    const val N = 600
    private val mem = Collections.synchronizedMap(HashMap<String, FloatArray>())
    private val dir by lazy { File(Prefs.dataDir, "ondas").apply { mkdirs() } }

    fun get(path: String): FloatArray? = mem[path]

    fun load(t: Track): FloatArray? {
        mem[t.path]?.let { return it }
        val f = File(dir, Integer.toHexString((t.path + t.modified).hashCode()) + ".bin")
        try {
            if (f.exists()) {
                DataInputStream(f.inputStream().buffered()).use { s ->
                    val a = FloatArray(N) { s.readFloat() }
                    mem[t.path] = a
                    return a
                }
            }
        } catch (_: Throwable) {}
        val l = Audio.lib ?: return null
        val a = FloatArray(N)
        if (l.sa_peaks(WString(t.path), N, a) != 1) return null
        val mx = a.max().coerceAtLeast(0.01f)
        for (i in a.indices) a[i] = (a[i] / mx).coerceIn(0f, 1f)
        try {
            DataOutputStream(f.outputStream().buffered()).use { s -> a.forEach { s.writeFloat(it) } }
        } catch (_: Throwable) {}
        mem[t.path] = a
        return a
    }
}
