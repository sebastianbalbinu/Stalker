package com.sebas.stalker

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detector de FLAC falso: decodifica unos segundos de la canción, mira hasta qué frecuencia
 * llega la música y dibuja su espectrograma. Un MP3 convertido a FLAC tiene un "corte"
 * brusco (16 kHz en 128 kbps, 19–20 kHz en 320 kbps); un FLAC real llega hasta ~22 kHz.
 */
object Spectrum {
    const val ROWS = 128
    const val COLS = 160
    private const val N = 4096

    enum class Verdict { REAL, LOSSY, UPSAMPLED, UNCLEAR }

    data class Result(
        val verdict: Verdict,
        val cutoffHz: Int,
        val sampleRate: Int,
        /** COLS × ROWS valores 0..255 (columna por columna, fila 0 = graves). */
        val image: ByteArray,
    ) {
        val title: String
            get() = when (verdict) {
                Verdict.REAL -> if (sampleRate > 48000) tr("Hi-Res real") else tr("Sin pérdida real")
                Verdict.LOSSY -> tr("Parece un MP3 convertido")
                Verdict.UPSAMPLED -> tr("Hi-Res inflado")
                Verdict.UNCLEAR -> tr("Pocos agudos")
            }
        val detail: String
            get() {
                val k = String.format("%.1f", cutoffHz / 1000.0)
                return when (verdict) {
                    Verdict.REAL -> tr("La música llega hasta %s kHz, como debe ser en un archivo sin pérdida.", k)
                    Verdict.LOSSY -> tr("Hay un corte brusco en %s kHz: %s. Suena bien, pero no es calidad de CD.", k, lossyGuess(cutoffHz))
                    Verdict.UPSAMPLED -> tr("Dice ser Hi-Res, pero nada pasa de %s kHz: en realidad es calidad de CD (o menos) estirada.", k)
                    Verdict.UNCLEAR -> tr("La música llega hasta %s kHz y baja suave, sin corte brusco. Puede ser una grabación antigua o masterizada así; no parece MP3.", k)
                }
            }
    }

    private fun lossyGuess(hz: Int): String = when {
        hz < 15500 -> tr("típico de un MP3 de baja calidad (96–128 kbps)")
        hz < 17500 -> tr("típico de un MP3 de 128–192 kbps")
        hz < 19300 -> tr("típico de un MP3 o AAC de 256 kbps")
        else -> tr("típico de un MP3 de 320 kbps")
    }

    val progress = MutableStateFlow<Float?>(null)

    // ---------- caché por álbum ----------

    private fun key(t: Track): String {
        val s = "${(t.albumArtist ?: t.artist).trim().lowercase()}|${t.album.trim().lowercase()}|${t.title.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "spectrum").apply { mkdirs() }

    fun cached(ctx: Context, t: Track): Result? = try {
        val meta = File(dir(ctx), key(t) + ".json")
        val img = File(dir(ctx), key(t) + ".bin")
        if (!meta.exists() || !img.exists()) null else {
            val o = JSONObject(meta.readText())
            Result(Verdict.valueOf(o.getString("v")), o.getInt("c"), o.getInt("sr"), img.readBytes())
        }
    } catch (e: Exception) {
        null
    }

    private fun save(ctx: Context, t: Track, r: Result) {
        File(dir(ctx), key(t) + ".bin").writeBytes(r.image)
        File(dir(ctx), key(t) + ".json").writeText(
            JSONObject().put("v", r.verdict.name).put("c", r.cutoffHz).put("sr", r.sampleRate).toString()
        )
    }

    // ---------- análisis ----------

    suspend fun analyze(ctx: Context, t: Track): Result? = withContext(Dispatchers.Default) {
        cached(ctx, t)?.let { return@withContext it }
        progress.value = 0f
        try {
            val (samples, rate) = decode(ctx, t) ?: return@withContext null
            val r = compute(samples, rate)
            save(ctx, t, r)
            r
        } catch (e: Exception) {
            null
        } finally {
            progress.value = null
        }
    }

    /** Decodifica ~2,5 s en tres partes de la canción y devuelve mono en flotantes. */
    private fun decode(ctx: Context, t: Track): Pair<FloatArray, Int>? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, t.uri, null)
            val idx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else t.durationMs * 1000
            val perPart = (rate * 2.5).toInt()
            val out = FloatArray(perPart * 3)
            var filled = 0
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            } catch (e: Exception) {
            }
            codec.configure(fmt, null, null, 0)
            codec.start()
            try {
                val info = MediaCodec.BufferInfo()
                for ((part, frac) in listOf(0.22, 0.48, 0.74).withIndex()) {
                    ex.seekTo((durUs * frac).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    var got = 0
                    var inputDone = false
                    var guard = 0
                    while (got < perPart && guard++ < 4000) {
                        if (!inputDone) {
                            val ib = codec.dequeueInputBuffer(5000)
                            if (ib >= 0) {
                                val buf = codec.getInputBuffer(ib)!!
                                val n = ex.readSampleData(buf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    codec.queueInputBuffer(ib, 0, n, ex.sampleTime, 0)
                                    ex.advance()
                                }
                            }
                        }
                        val ob = codec.dequeueOutputBuffer(info, 5000)
                        if (ob >= 0) {
                            val buf = codec.getOutputBuffer(ob)!!.order(ByteOrder.nativeOrder())
                            val of = codec.outputFormat
                            val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            when (enc) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = buf.asFloatBuffer()
                                    while (fb.remaining() >= ch && got < perPart) {
                                        var m = 0f
                                        repeat(ch) { m += fb.get() }
                                        out[part * perPart + got++] = m / ch
                                    }
                                }
                                else -> {
                                    val sb = buf.asShortBuffer()
                                    while (sb.remaining() >= ch && got < perPart) {
                                        var m = 0f
                                        repeat(ch) { m += sb.get() / 32768f }
                                        out[part * perPart + got++] = m / ch
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(ob, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    filled += got
                    progress.value = (part + 1) / 3f * 0.8f
                }
            } finally {
                codec.stop()
                codec.release()
            }
            if (filled < N * 4) return null
            return out to rate
        } finally {
            ex.release()
        }
    }

    private fun compute(x: FloatArray, rate: Int): Result {
        val hop = N / 2
        val frames = ((x.size - N) / hop).coerceAtLeast(1)
        val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
        val bins = N / 2
        val avg = DoubleArray(bins)
        val colsDb = Array(COLS) { FloatArray(ROWS) { -160f } }
        val re = FloatArray(N)
        val im = FloatArray(N)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until N) { re[i] = x[off + i] * win[i]; im[i] = 0f }
            fft(re, im)
            val col = (f.toLong() * COLS / frames).toInt().coerceIn(0, COLS - 1)
            for (b in 1 until bins) {
                val p = re[b] * re[b] + im[b] * im[b]
                avg[b] += p
                val db = (10 * log10(p + 1e-20)).toFloat()
                val row = (b.toLong() * ROWS / bins).toInt().coerceIn(0, ROWS - 1)
                if (db > colsDb[col][row]) colsDb[col][row] = db
            }
        }
        // Espectro promedio en dB, suavizado (ventana de ~300 Hz).
        val db = DoubleArray(bins) { 10 * log10(avg[it.coerceAtLeast(1)] / frames + 1e-20) }
        val hzPerBin = rate.toDouble() / N
        val w = max(1, (150 / hzPerBin).toInt())
        val smooth = DoubleArray(bins) { i ->
            var s = 0.0; var n = 0
            for (j in max(1, i - w)..minOf(bins - 1, i + w)) { s += db[j]; n++ }
            s / n
        }
        fun mean(a: Int, b: Int): Double { var s = 0.0; for (i in a until b) s += smooth[i]; return s / (b - a).coerceAtLeast(1) }
        // Buscamos el "acantilado": dónde cae más el nivel entre el kHz de antes y el de después.
        val near = (200 / hzPerBin).toInt()
        val far = (1000 / hzPerBin).toInt()
        val lo = ((if (rate > 48000) 15000 else 11000) / hzPerBin).toInt()
        var drop = 0.0
        var at = bins - 1
        for (i in lo until bins - far) {
            val d = mean(i - far, i - near) - mean(i + near, i + far)
            if (d > drop) { drop = d; at = i }
        }
        val mid = smooth.copyOfRange((1000 / hzPerBin).toInt(), (6000 / hzPerBin).toInt()).sorted().let { it[it.size / 2] }
        val treble = mean((17000 / hzPerBin).toInt(), (19500 / hzPerBin).toInt()) - mid
        val wall = drop > (if (rate > 48000) 18.0 else 20.0)
        val verdict = when {
            rate > 48000 && wall && at * hzPerBin < 24500 -> Verdict.UPSAMPLED
            rate > 48000 -> Verdict.REAL
            wall && at * hzPerBin < 20800 -> Verdict.LOSSY
            treble < -60 -> Verdict.UNCLEAR
            else -> Verdict.REAL
        }
        val cutoff = if (verdict == Verdict.REAL) rate / 2 else (at * hzPerBin).toInt()

        // Imagen: dB relativos al máximo, de -100 dB (0) a 0 dB (255).
        var top = -200f
        for (c in colsDb) for (v in c) if (v > top) top = v
        val img = ByteArray(COLS * ROWS)
        for (c in 0 until COLS) for (r in 0 until ROWS) {
            val v = ((colsDb[c][r] - (top - 100f)) / 100f).coerceIn(0f, 1f)
            img[c * ROWS + r] = (v * 255).toInt().toByte()
        }
        progress.value = 1f
        return Result(verdict, cutoff, rate, img)
    }

    /** FFT radix-2 en el lugar. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    @Suppress("unused")
    private fun rms(x: FloatArray) = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size)
}
