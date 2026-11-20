package com.sebas.stalker

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Corrección para audífonos con los perfiles abiertos de AutoEq (github.com/jaakkopasanen/AutoEq):
 * mediciones de miles de modelos, llevados a un sonido neutro de referencia.
 * Se aplica con DynamicsProcessing (Android 9+), 32 bandas; nunca en Bit-perfect.
 */
object AutoEq {
    private const val BASE = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private val line = Regex("""^- \[(.+?)]\(\./(.+?)\) by (.+?) on""")
    /** Fuentes de medición, de la más confiable a la menos. */
    private val sourceRank = listOf("oratory1990", "crinacle", "Rtings", "Innerfidelity", "Headphone.com Legacy")

    data class Entry(val name: String, val path: String, val source: String)

    /** Nombre del perfil activo (null si no hay) y si está encendido. */
    val profile = MutableStateFlow<String?>(null)
    val enabled = MutableStateFlow(false)
    /** Curva activa: pares (Hz, dB). */
    val curve = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())

    private var entries: List<Entry>? = null

    val supported get() = Build.VERSION.SDK_INT >= 28

    fun init(ctx: Context) {
        val p = prefs(ctx)
        profile.value = p.getString("autoEqName", null)
        enabled.value = p.getBoolean("autoEqOn", false) && profile.value != null
        curve.value = parse(p.getString("autoEqCurve", "") ?: "")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stalker", Context.MODE_PRIVATE)

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled.value = on && profile.value != null
        prefs(ctx).edit().putBoolean("autoEqOn", enabled.value).apply()
    }

    fun clear(ctx: Context) {
        profile.value = null
        enabled.value = false
        curve.value = emptyList()
        prefs(ctx).edit().remove("autoEqName").remove("autoEqCurve").putBoolean("autoEqOn", false).apply()
    }

    /** Lista de audífonos (se descarga una vez y queda guardada). */
    suspend fun search(ctx: Context, query: String): List<Entry> = withContext(Dispatchers.IO) {
        val all = entries ?: load(ctx).also { entries = it }
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return@withContext emptyList()
        all.filter { e -> words.all { e.name.lowercase().contains(it) } }.take(40)
    }

    private fun load(ctx: Context): List<Entry> {
        val f = File(ctx.filesDir, "autoeq-index.md")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3_600_000) {
            MusicBrainz.http("$BASE/INDEX.md", "text/plain")?.let { f.writeText(it) }
        }
        if (!f.exists()) return emptyList()
        val byName = LinkedHashMap<String, Entry>()
        f.forEachLine { l ->
            val m = line.find(l) ?: return@forEachLine
            val e = Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val old = byName[e.name]
            fun rank(x: Entry) = sourceRank.indexOf(x.source).let { if (it < 0) 99 else it }
            if (old == null || rank(e) < rank(old)) byName[e.name] = e
        }
        return byName.values.sortedBy { it.name.lowercase() }
    }

    /** Descarga y activa el perfil de estos audífonos. */
    suspend fun choose(ctx: Context, e: Entry): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = e.path.substringAfterLast('/')
            val body = MusicBrainz.http("$BASE/${e.path}/$file%20GraphicEQ.txt", "text/plain") ?: return@withContext false
            val pts = parse(body)
            if (pts.size < 10) return@withContext false
            curve.value = pts
            profile.value = e.name
            enabled.value = true
            prefs(ctx).edit().putString("autoEqName", e.name).putString("autoEqCurve", body).putBoolean("autoEqOn", true).apply()
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** "GraphicEQ: 20 -0.2; 21 -0.2; ..." → pares (Hz, dB). */
    fun parse(s: String): List<Pair<Float, Float>> =
        s.substringAfter(':', "").split(';').mapNotNull { p ->
            val parts = p.trim().split(Regex("\\s+"))
            if (parts.size < 2) null else parts[0].toFloatOrNull()?.let { hz -> parts[1].toFloatOrNull()?.let { hz to it } }
        }.sortedBy { it.first }

    /** Ganancia de la curva en una frecuencia (interpolación en escala logarítmica). */
    fun gainAt(hz: Float, pts: List<Pair<Float, Float>> = curve.value): Float {
        if (pts.isEmpty()) return 0f
        if (hz <= pts.first().first) return pts.first().second
        if (hz >= pts.last().first) return pts.last().second
        val i = pts.indexOfFirst { it.first >= hz }
        val (f0, g0) = pts[i - 1]
        val (f1, g1) = pts[i]
        val t = (ln(hz) - ln(f0)) / (ln(f1) - ln(f0))
        return g0 + (g1 - g0) * t
    }

    private const val BANDS = 32

    /** Crea el efecto sobre la sesión de audio, o null si este teléfono no lo permite. */
    fun build(session: Int): DynamicsProcessing? {
        if (!supported || curve.value.isEmpty()) return null
        return try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, false
            ).build()
            val dp = DynamicsProcessing(0, session, cfg)
            apply(dp)
            dp
        } catch (e: Exception) {
            null
        }
    }

    /** Copia la curva a las 32 bandas (espaciadas en octavas, de 20 Hz a 20 kHz). */
    fun apply(dp: DynamicsProcessing) {
        val eq = DynamicsProcessing.Eq(true, true, BANDS)
        var maxGain = 0f
        for (b in 0 until BANDS) {
            val lo = 20f * (1000f).pow(b.toFloat() / BANDS)
            val hi = 20f * (1000f).pow((b + 1).toFloat() / BANDS)
            val center = kotlin.math.sqrt(lo * hi)
            val g = gainAt(center)
            maxGain = max(maxGain, g)
            eq.getBand(b).apply {
                isEnabled = true
                cutoffFrequency = hi
                gain = g
            }
        }
        dp.setPreEqAllChannelsTo(eq)
        // Bajamos la entrada lo que más suba la curva: así nunca satura.
        dp.setInputGainAllChannelsTo(-maxGain)
        dp.enabled = true
    }
}
