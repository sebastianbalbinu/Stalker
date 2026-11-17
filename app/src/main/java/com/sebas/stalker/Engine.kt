package com.sebas.stalker

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.MutableStateFlow

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
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int,
    val tracks: List<Track>,
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
    val currentInfo = MutableStateFlow<AudioInfo?>(null)

    val eqEnabled = MutableStateFlow(false)
    val eqPreset = MutableStateFlow("Plano")
    val eqAvailable = MutableStateFlow(true)

    val theme = MutableStateFlow("Telón")

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
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("stalker", Context.MODE_PRIVATE)
        prefs = p
        outputMode.value = runCatching { OutputMode.valueOf(p.getString("mode", OutputMode.BIT_PERFECT.name)!!) }
            .getOrDefault(OutputMode.BIT_PERFECT)
        eqEnabled.value = p.getBoolean("eqOn", false)
        eqPreset.value = p.getString("eqPreset", "Plano") ?: "Plano"
        theme.value = p.getString("theme", "Telón") ?: "Telón"
    }

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
                    .build()
            )
            .build()
}
