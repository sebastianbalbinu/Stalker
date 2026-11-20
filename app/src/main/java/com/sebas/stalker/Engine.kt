package com.sebas.stalker

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val trackNo: Int,
    val durationMs: Long,
    val mime: String,
    val year: Int,
    val sizeBytes: Long = 0L,
    /** "WhatsApp" o "Telegram" si llegó compartida por un chat. */
    val source: String? = null,
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
)

/** Calidad de un álbum: bits, frecuencia y códec de su primera pista. */
data class AlbumQuality(val bits: Int, val rate: Int, val codec: String) {
    val lossless get() = codec == "FLAC" || codec == "ALAC" || codec == "WAV"
    val hiRes get() = lossless && (bits > 16 || rate > 48000)
    /** Mayor es mejor: sin pérdida antes que con pérdida, luego bits y frecuencia. */
    val score: Long get() = (if (lossless) 1_000_000_000L else 0L) + bits * 1_000_000L + rate
}

/** Disco armado por el usuario. */
data class Disco(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
)

data class AudioInfo(
    val codec: String,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
) {
    fun label(): String {
        val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else String.format("%.1f", sampleRate / 1000.0)
        return "$codec · $bits bit / $khz kHz"
    }
}

enum class OutputMode(val label: String, val description: String) {
    BIT_PERFECT("Bit-perfect", "El audio llega al DAC sin tocar. El volumen lo maneja el audífono y el ecualizador se apaga."),
    HI_RES("Hi-Res", "El DAC cambia a la frecuencia de cada canción, sin remuestreo. Permite ecualizador."),
    NORMAL("Normal", "Pasa por el mezclador de Android. Máxima compatibilidad."),
}

/** Estado compartido entre la interfaz y el servicio de reproducción (mismo proceso). */
object Engine {
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val scanned = MutableStateFlow(false)
    private var byId: Map<String, Track> = emptyMap()

    val outputMode = MutableStateFlow(OutputMode.BIT_PERFECT)
    val outputStatus = MutableStateFlow("Sin reproducir")
    val usbDevice = MutableStateFlow<String?>(null)
    /** Por dónde sale el audio: "usb", "bluetooth", "cable" o "parlante". */
    val route = MutableStateFlow("parlante")
    val routeName = MutableStateFlow<String?>(null)
    val currentInfo = MutableStateFlow<AudioInfo?>(null)

    val eqEnabled = MutableStateFlow(false)
    val eqPreset = MutableStateFlow("Plano")
    val eqAvailable = MutableStateFlow(true)

    val theme = MutableStateFlow("Telón")

    val favorites = MutableStateFlow<Set<Long>>(emptySet())      // álbumes favoritos
    val hiddenAlbums = MutableStateFlow<Set<Long>>(emptySet())
    val hiddenTracks = MutableStateFlow<Set<Long>>(emptySet())
    val discos = MutableStateFlow<List<Disco>>(emptyList())

    /** Nombre del disco/álbum que está sonando. */
    val queueTitle = MutableStateFlow<String?>(null)

    /** "Claro", "Noche" o "Sistema". */
    val nightMode = MutableStateFlow("Sistema")
    /** "Apagado", "Pista" o "Álbum". */
    val replayGain = MutableStateFlow("Apagado")
    val replayGainStatus = MutableStateFlow("")

    /** Etiquetas del FLAC que suena (letra, ReplayGain…), en mayúsculas. */
    val currentTags = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Temporizador para dormir: hora de apagado (ms) o "al terminar el disco". */
    val sleepAt = MutableStateFlow<Long?>(null)
    val sleepEndOfDisco = MutableStateFlow(false)

    // Estadísticas
    val playCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val albumLastPlayed = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val listenedMs = MutableStateFlow(0L)

    /** "Discos" (fundas con vinilo) o "Portadas". */
    val libraryView = MutableStateFlow("Discos")
    /** Discos por fila en la estantería (2 a 5). */
    val libraryColumns = MutableStateFlow(3)
    /** Orden de la estantería: "Álbum", "Artista", "Año", "Calidad", "Recientes" o "Más escuchados". */
    val librarySort = MutableStateFlow("Álbum")
    /** Invierte el orden natural del criterio elegido. */
    val sortReversed = MutableStateFlow(false)
    /** Calidad de cada álbum (de su primera pista), leída una vez del archivo y guardada. */
    val albumQuality = MutableStateFlow<Map<Long, AlbumQuality>>(emptyMap())
    /** Incluir la música recibida por WhatsApp y Telegram (nunca las notas de voz). */
    val includeShared = MutableStateFlow(true)
    /** Modo purista: al terminar el Lado A, el disco se detiene para darle vuelta. */
    val purist = MutableStateFlow(false)
    /** El Lado A terminó y espera que le den vuelta. */
    val flipPending = MutableStateFlow(false)
    /** Reproducción continua: al terminar un disco, entra el siguiente. */
    val continuous = MutableStateFlow(true)
    /** Qué disco sigue: "Estantería", "Al azar" o "Mismo artista". */
    val nextOrder = MutableStateFlow("Estantería")
    /** Discos que ya sonaron en esta cadena continua (para no repetir hasta pasar por todos). */
    private val chainPlayed = mutableSetOf<String>()

    /** Descargas hechas desde la tienda (ids del DownloadManager). */
    val downloads = MutableStateFlow<Set<Long>>(emptySet())
    /** Descargas .zip que se están descomprimiendo ahora. */
    val extracting = MutableStateFlow<Set<Long>>(emptySet())
    /** Última página abierta en la tienda. */
    val lastStoreUrl = MutableStateFlow<String?>(null)

    /** Posición guardada de archivos largos (mezclas, audiolibros). */
    private var positions: MutableMap<Long, Long> = mutableMapOf()

    private var prefs: SharedPreferences? = null

    val presets: Map<String, List<Int>> = linkedMapOf(
        "Plano" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        "Estudio" to listOf(2, 1, 0, 0, -1, 0, 1, 2, 2, 1),
        "Jazz" to listOf(3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
        "Rock" to listOf(5, 4, 2, 0, -1, -1, 1, 3, 4, 5),
        "Cinemático" to listOf(6, 5, 3, 1, -1, -2, 0, 2, 4, 5),
        "Vocal" to listOf(-2, -1, 0, 2, 4, 4, 3, 1, 0, -1),
    )
    val presetFreqs = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    fun init(context: Context) {
        I18n.init(context)
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("stalker", Context.MODE_PRIVATE)
        prefs = p
        outputMode.value = runCatching { OutputMode.valueOf(p.getString("mode", OutputMode.BIT_PERFECT.name)!!) }
            .getOrDefault(OutputMode.BIT_PERFECT)
        eqEnabled.value = p.getBoolean("eqOn", false)
        eqPreset.value = p.getString("eqPreset", "Plano") ?: "Plano"
        theme.value = p.getString("theme", "Telón") ?: "Telón"
        favorites.value = readIds(p.getString("favorites", ""))
        hiddenAlbums.value = readIds(p.getString("hiddenAlbums", ""))
        hiddenTracks.value = readIds(p.getString("hiddenTracks", ""))
        discos.value = readDiscos(p.getString("discos", "[]") ?: "[]")
        queueTitle.value = p.getString("queueTitle", null)
        nightMode.value = p.getString("night", "Sistema") ?: "Sistema"
        replayGain.value = p.getString("replayGain", "Apagado") ?: "Apagado"
        playCounts.value = readLongMap(p.getString("playCounts", "{}")).mapValues { it.value.toInt() }
        albumLastPlayed.value = readLongMap(p.getString("albumLast", "{}"))
        positions = readLongMap(p.getString("positions", "{}")).toMutableMap()
        listenedMs.value = p.getLong("listenedMs", 0L)
        downloads.value = readIds(p.getString("downloads", ""))
        libraryView.value = p.getString("libraryView", "Discos") ?: "Discos"
        includeShared.value = p.getBoolean("includeShared", true)
        purist.value = p.getBoolean("purist", false)
        continuous.value = p.getBoolean("continuous", true)
        libraryColumns.value = p.getInt("libraryColumns", 3).coerceIn(2, 5)
        librarySort.value = p.getString("librarySort", "Álbum") ?: "Álbum"
        sortReversed.value = p.getBoolean("sortReversed", false)
        albumQuality.value = readQuality(p.getString("albumQuality", "{}"))
        nextOrder.value = p.getString("nextOrder", "Estantería") ?: "Estantería"
        lastStoreUrl.value = p.getString("storeUrl", null)
    }

    @Synchronized
    fun addDownload(id: Long) {
        downloads.value = downloads.value + id
        writeIds("downloads", downloads.value)
    }

    @Synchronized
    fun removeDownload(id: Long) {
        downloads.value = downloads.value - id
        writeIds("downloads", downloads.value)
    }

    @Synchronized
    fun setExtracting(id: Long, on: Boolean) {
        extracting.value = if (on) extracting.value + id else extracting.value - id
    }

    fun setLibraryView(v: String) {
        libraryView.value = v
        prefs?.edit()?.putString("libraryView", v)?.apply()
    }

    fun setIncludeShared(on: Boolean) {
        includeShared.value = on
        prefs?.edit()?.putBoolean("includeShared", on)?.apply()
    }

    fun setPurist(on: Boolean) {
        purist.value = on
        prefs?.edit()?.putBoolean("purist", on)?.apply()
    }

    fun setLibraryColumns(n: Int) {
        val v = n.coerceIn(2, 5)
        libraryColumns.value = v
        prefs?.edit()?.putInt("libraryColumns", v)?.apply()
    }

    fun setLibrarySort(v: String) {
        librarySort.value = v
        prefs?.edit()?.putString("librarySort", v)?.apply()
    }

    fun setSortReversed(on: Boolean) {
        sortReversed.value = on
        prefs?.edit()?.putBoolean("sortReversed", on)?.apply()
    }

    @Synchronized
    fun putQuality(found: Map<Long, AlbumQuality>) {
        if (found.isEmpty()) return
        val m = albumQuality.value + found
        albumQuality.value = m
        val o = JSONObject()
        m.forEach { (k, q) -> o.put(k.toString(), "${q.bits},${q.rate},${q.codec}") }
        prefs?.edit()?.putString("albumQuality", o.toString())?.apply()
    }

    private fun readQuality(s: String?): Map<Long, AlbumQuality> = try {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().mapNotNull { k ->
            val parts = o.getString(k).split(',')
            val id = k.toLongOrNull() ?: return@mapNotNull null
            if (parts.size < 3) null else id to AlbumQuality(parts[0].toInt(), parts[1].toInt(), parts[2])
        }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun setContinuous(on: Boolean) {
        continuous.value = on
        prefs?.edit()?.putBoolean("continuous", on)?.apply()
    }

    fun setNextOrder(v: String) {
        nextOrder.value = v
        chainPlayed.clear()
        prefs?.edit()?.putString("nextOrder", v)?.apply()
    }

    // ---------- Reproducción continua y Rocola ----------

    const val ROCOLA = "Rocola"
    const val ROCOLA_FAVS = "Rocola · Favoritos"

    /** Nombre para mostrar de la cola (la Rocola se traduce; los discos y álbumes no). */
    fun displayTitle(t: String): String = if (t == ROCOLA || t == ROCOLA_FAVS) tr(t) else t

    /** Todas las canciones visibles mezcladas: sin repetir y sin dos seguidas del mismo disco. */
    fun rocola(onlyFavorites: Boolean): List<Track> {
        var pool = visible(tracks.value, hiddenAlbums.value, hiddenTracks.value)
        if (onlyFavorites) pool = pool.filter { it.albumId in favorites.value }
        val list = pool.shuffled().toMutableList()
        for (i in 1 until list.size) {
            if (list[i].albumId != list[i - 1].albumId) continue
            val j = (i + 1 until list.size).firstOrNull { list[it].albumId != list[i - 1].albumId } ?: break
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
        return list
    }

    /**
     * Lo que entra cuando termina la cola: el siguiente álbum (o el siguiente de "Mis discos"),
     * según el orden elegido. Devuelve null si no corresponde seguir.
     */
    fun nextDisc(queueIds: List<Long>, title: String?): Pair<List<Track>, String>? {
        if (!continuous.value) return null
        if (title == ROCOLA || title == ROCOLA_FAVS) {
            val again = rocola(title == ROCOLA_FAVS)
            return if (again.isEmpty()) null else again to title
        }
        val queue = queueIds.mapNotNull { byId[it.toString()] }
        if (queue.isEmpty()) return null

        // Uno de "Mis discos": sigue el siguiente disco armado.
        val mine = discos.value.filter { discoTracks(it).isNotEmpty() }
        mine.firstOrNull { it.name == title }?.let { cur ->
            val keys = mine.map { "d" + it.id }
            val artists = mine.associate { d -> "d" + d.id to mainArtist(discoTracks(d)) }
            val next = pickNext(keys, "d" + cur.id, artists) ?: return null
            val d = mine.first { "d" + it.id == next }
            return discoTracks(d).filter { it.id !in hiddenTracks.value } to d.name
        }

        // Un álbum completo (o parte de él): sigue el siguiente álbum de la estantería.
        val albumIds = queue.map { it.albumId }.distinct()
        if (albumIds.size != 1) return null
        val shelf = albums(visible(tracks.value, hiddenAlbums.value, hiddenTracks.value))
        if (shelf.isEmpty()) return null
        val keys = shelf.map { "a" + it.id }
        val artists = shelf.associate { "a" + it.id to it.artist.lowercase() }
        val currentArtist = queue.first().artist.lowercase()
        val next = pickNext(keys, "a" + albumIds.first(), artists, currentArtist) ?: return null
        val a = shelf.first { "a" + it.id == next }
        return a.tracks to a.title
    }

    private fun mainArtist(ts: List<Track>): String =
        ts.groupingBy { it.artist.lowercase() }.eachCount().maxByOrNull { it.value }?.key ?: ""

    private fun pickNext(keys: List<String>, current: String, artists: Map<String, String>, fallbackArtist: String? = null): String? {
        if (keys.isEmpty()) return null
        if (keys.size == 1 && keys[0] == current) return null
        chainPlayed.add(current)
        if (keys.all { it in chainPlayed }) { chainPlayed.clear(); chainPlayed.add(current) }
        val at = keys.indexOf(current)
        // En orden de estantería, empezando justo después del actual y dando la vuelta.
        val ring = if (at < 0) keys else keys.drop(at + 1) + keys.take(at)
        val fresh = ring.filter { it !in chainPlayed }
        val pick = when (nextOrder.value) {
            "Al azar" -> fresh.randomOrNull()
            "Mismo artista" -> {
                val artist = artists[current] ?: fallbackArtist
                fresh.firstOrNull { artists[it] == artist } ?: fresh.firstOrNull()
            }
            else -> ring.firstOrNull()
        } ?: ring.firstOrNull()
        pick?.let { chainPlayed.add(it) }
        return pick
    }

    fun moveInDisco(id: Long, from: Int, to: Int) {
        discos.value = discos.value.map { d ->
            if (d.id != id || from !in d.trackIds.indices || to !in d.trackIds.indices) d
            else d.copy(trackIds = d.trackIds.toMutableList().apply { add(to, removeAt(from)) })
        }
        saveDiscos()
    }

    fun setStoreUrl(url: String?) {
        lastStoreUrl.value = url
        prefs?.edit()?.putString("storeUrl", url)?.apply()
    }

    private fun readLongMap(s: String?): Map<Long, Long> = try {
        val o = JSONObject(s ?: "{}")
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.getLong(k) } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeLongMap(key: String, m: Map<Long, Number>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k.toString(), v.toLong()) }
        prefs?.edit()?.putString(key, o.toString())?.apply()
    }

    fun setNight(mode: String) {
        nightMode.value = mode
        prefs?.edit()?.putString("night", mode)?.apply()
    }

    fun setReplayGain(mode: String) {
        replayGain.value = mode
        prefs?.edit()?.putString("replayGain", mode)?.apply()
    }

    // ---------- Estadísticas ----------

    fun countPlay(t: Track) {
        val m = playCounts.value.toMutableMap()
        m[t.id] = (m[t.id] ?: 0) + 1
        playCounts.value = m
        writeLongMap("playCounts", m)
        val a = albumLastPlayed.value.toMutableMap()
        a[t.albumId] = System.currentTimeMillis()
        albumLastPlayed.value = a
        writeLongMap("albumLast", a)
    }

    fun addListened(ms: Long) {
        listenedMs.value += ms
    }

    fun flushListened() {
        prefs?.edit()?.putLong("listenedMs", listenedMs.value)?.apply()
    }

    // ---------- Archivos largos ----------

    fun savePosition(trackId: Long, pos: Long) {
        positions[trackId] = pos
        writeLongMap("positions", positions)
    }

    fun positionOf(trackId: Long): Long = positions[trackId] ?: 0L

    // ---------- Respaldo ----------

    private fun trackKey(t: Track) = JSONObject().apply {
        put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
    }

    private fun findTrack(o: JSONObject): Track? {
        byId[o.optLong("id").toString()]?.let {
            if (it.title == o.optString("title")) return it
        }
        return tracks.value.firstOrNull {
            it.title == o.optString("title") && it.artist == o.optString("artist") && it.album == o.optString("album")
        }
    }

    fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "Stalker")
        root.put("version", 1)
        root.put("discos", JSONArray().apply {
            discos.value.forEach { d ->
                put(JSONObject().apply {
                    put("name", d.name)
                    put("tracks", JSONArray().apply { discoTracks(d).forEach { put(trackKey(it)) } })
                })
            }
        })
        fun albumKeys(ids: Set<Long>) = JSONArray().apply {
            albums(tracks.value).filter { it.id in ids }.forEach { a ->
                put(JSONObject().apply { put("album", a.title); put("artist", a.artist) })
            }
        }
        root.put("favorites", albumKeys(favorites.value))
        root.put("hiddenAlbums", albumKeys(hiddenAlbums.value))
        root.put("hiddenTracks", JSONArray().apply {
            tracks.value.filter { it.id in hiddenTracks.value }.forEach { put(trackKey(it)) }
        })
        root.put("playCounts", JSONArray().apply {
            playCounts.value.forEach { (id, n) ->
                byId[id.toString()]?.let { t -> put(trackKey(t).apply { put("count", n) }) }
            }
        })
        root.put("listenedMs", listenedMs.value)
        root.put("settings", JSONObject().apply {
            put("mode", outputMode.value.name); put("eqOn", eqEnabled.value); put("eqPreset", eqPreset.value)
            put("theme", theme.value); put("night", nightMode.value); put("replayGain", replayGain.value)
        })
        return root.toString(2)
    }

    /** Devuelve un resumen de lo restaurado. */
    fun importJson(text: String): String {
        val root = JSONObject(text)
        val all = albums(tracks.value)
        fun albumIds(arr: JSONArray?): Set<Long> {
            if (arr == null) return emptySet()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                all.firstOrNull { it.title == o.optString("album") && it.artist == o.optString("artist") }?.id
            }.toSet()
        }
        var discosOk = 0
        root.optJSONArray("discos")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = o.optJSONArray("tracks") ?: JSONArray()
                val ids = (0 until ts.length()).mapNotNull { findTrack(ts.getJSONObject(it))?.id }
                val name = o.optString("name", "Disco")
                if (discos.value.none { it.name == name }) {
                    createDisco(name, ids)
                    discosOk++
                }
            }
        }
        val fav = albumIds(root.optJSONArray("favorites"))
        favorites.value = favorites.value + fav
        writeIds("favorites", favorites.value)
        hiddenAlbums.value = hiddenAlbums.value + albumIds(root.optJSONArray("hiddenAlbums"))
        writeIds("hiddenAlbums", hiddenAlbums.value)
        root.optJSONArray("hiddenTracks")?.let { arr ->
            val ids = (0 until arr.length()).mapNotNull { findTrack(arr.getJSONObject(it))?.id }
            hiddenTracks.value = hiddenTracks.value + ids
            writeIds("hiddenTracks", hiddenTracks.value)
        }
        root.optJSONArray("playCounts")?.let { arr ->
            val m = playCounts.value.toMutableMap()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = findTrack(o) ?: continue
                m[t.id] = maxOf(m[t.id] ?: 0, o.optInt("count"))
            }
            playCounts.value = m
            writeLongMap("playCounts", m)
        }
        if (root.has("listenedMs")) {
            listenedMs.value = maxOf(listenedMs.value, root.optLong("listenedMs"))
            flushListened()
        }
        root.optJSONObject("settings")?.let { st ->
            runCatching { setMode(OutputMode.valueOf(st.optString("mode", outputMode.value.name))) }
            setEq(st.optBoolean("eqOn", eqEnabled.value))
            setPreset(st.optString("eqPreset", eqPreset.value))
            setTheme(st.optString("theme", theme.value))
            setNight(st.optString("night", nightMode.value))
            setReplayGain(st.optString("replayGain", replayGain.value))
        }
        return tr("Restaurado: %s discos, %s favoritos", discosOk, fav.size)
    }

    private fun readIds(s: String?): Set<Long> =
        s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    private fun writeIds(key: String, ids: Collection<Long>) {
        prefs?.edit()?.putString(key, ids.joinToString(","))?.apply()
    }

    private fun readDiscos(s: String): List<Disco> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ids = o.optJSONArray("tracks") ?: JSONArray()
            Disco(o.getLong("id"), o.getString("name"), (0 until ids.length()).map { ids.getLong(it) })
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveDiscos() {
        val arr = JSONArray()
        discos.value.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("tracks", JSONArray().apply { d.trackIds.forEach { put(it) } })
            })
        }
        prefs?.edit()?.putString("discos", arr.toString())?.apply()
    }

    // ---------- Ajustes ----------

    fun setMode(m: OutputMode) {
        outputMode.value = m
        prefs?.edit()?.putString("mode", m.name)?.apply()
    }

    fun setEq(on: Boolean) {
        eqEnabled.value = on
        prefs?.edit()?.putBoolean("eqOn", on)?.apply()
    }

    fun setPreset(name: String) {
        eqPreset.value = name
        prefs?.edit()?.putString("eqPreset", name)?.apply()
    }

    fun setTheme(name: String) {
        theme.value = name
        prefs?.edit()?.putString("theme", name)?.apply()
    }

    fun setQueueTitle(t: String?) {
        queueTitle.value = t
        prefs?.edit()?.putString("queueTitle", t)?.apply()
    }

    // ---------- Favoritos y ocultos ----------

    fun toggleFavorite(albumId: Long) {
        val s = favorites.value.toMutableSet()
        if (!s.add(albumId)) s.remove(albumId)
        favorites.value = s
        writeIds("favorites", s)
    }

    fun hideAlbum(albumId: Long, hide: Boolean = true) {
        val s = hiddenAlbums.value.toMutableSet()
        if (hide) s.add(albumId) else s.remove(albumId)
        hiddenAlbums.value = s
        writeIds("hiddenAlbums", s)
    }

    fun hideTrack(trackId: Long, hide: Boolean = true) {
        val s = hiddenTracks.value.toMutableSet()
        if (hide) s.add(trackId) else s.remove(trackId)
        hiddenTracks.value = s
        writeIds("hiddenTracks", s)
    }

    fun visible(all: List<Track>, hAlbums: Set<Long>, hTracks: Set<Long>): List<Track> =
        all.filter { it.albumId !in hAlbums && it.id !in hTracks }

    // ---------- Mis discos ----------

    fun createDisco(name: String, firstTracks: List<Long> = emptyList()): Disco {
        val d = Disco(System.currentTimeMillis(), name.ifBlank { tr("Disco sin nombre") }, firstTracks.distinct())
        discos.value = discos.value + d
        saveDiscos()
        return d
    }

    fun renameDisco(id: Long, name: String) {
        discos.value = discos.value.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }) else it }
        saveDiscos()
    }

    fun deleteDisco(id: Long) {
        discos.value = discos.value.filter { it.id != id }
        saveDiscos()
    }

    fun setDiscoTracks(id: Long, ids: List<Long>) {
        discos.value = discos.value.map { if (it.id == id) it.copy(trackIds = ids.distinct()) else it }
        saveDiscos()
    }

    fun addToDisco(id: Long, ids: List<Long>) {
        discos.value = discos.value.map { if (it.id == id) it.copy(trackIds = (it.trackIds + ids).distinct()) else it }
        saveDiscos()
    }

    fun discoTracks(d: Disco): List<Track> = d.trackIds.mapNotNull { byId[it.toString()] }

    // ---------- Biblioteca ----------

    fun setTracks(list: List<Track>) {
        byId = list.associateBy { it.id.toString() }
        tracks.value = list
        scanned.value = true
    }

    fun track(id: String?): Track? = if (id == null) null else byId[id]

    fun albums(list: List<Track>): List<Album> =
        list.groupBy { it.albumId }
            .map { (id, ts) ->
                val sorted = ts.sortedWith(compareBy<Track> { it.trackNo }.thenBy { it.title.lowercase() })
                val artist = sorted.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key ?: ""
                Album(id, sorted.first().album, artist, sorted.maxOf { it.year }, sorted)
            }
            .sortedBy { it.title.lowercase() }

    fun mediaItem(t: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.id.toString())
            .setUri(t.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album)
                    .setArtworkUri(t.uri) // la carátula la saca nuestro cargador de imágenes
                    .build()
            )
            .build()

    // ---------- Reanudar donde quedó ----------

    fun saveSession(ids: List<String>, index: Int, positionMs: Long) {
        prefs?.edit()
            ?.putString("queue", ids.joinToString(","))
            ?.putInt("queueIndex", index)
            ?.putLong("queuePos", positionMs)
            ?.apply()
    }

    fun savedSession(): Triple<List<String>, Int, Long>? {
        val p = prefs ?: return null
        val ids = p.getString("queue", "").orEmpty().split(',').filter { it.isNotBlank() }
        if (ids.isEmpty()) return null
        return Triple(ids, p.getInt("queueIndex", 0), p.getLong("queuePos", 0L))
    }
}
