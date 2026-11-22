package com.sebas.stalker

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Ecualizador de 10 bandas hecho dentro de Stalker.
 *
 * Antes se usaba el ecualizador del sistema (android.media.audiofx.Equalizer), pero en algunos
 * teléfonos (Samsung con Bluetooth) choca con el procesamiento propio del teléfono y la música
 * queda sin sonido. Este trabaja sobre las muestras antes de mandarlas a Android, así que suena
 * igual en todos los teléfonos.
 *
 * Cada banda es un filtro "peaking" (fórmulas de Robert Bristow-Johnson). Para que subir bandas no
 * sature, la entrada se baja lo mismo que sube la banda más alta.
 */
@UnstableApi
class SoftEq : BaseAudioProcessor() {
    /** Ganancias en dB por banda (mismo orden que [freqs]); null = apagado. */
    @Volatile var gains: List<Int>? = null

    private val freqs = Engine.presetFreqs
    private val q = 1.41

    private var format = AudioFormat.NOT_SET
    private var applied: List<Int>? = null
    private var dirty = true
    private var active = false
    private var preamp = 1.0
    // Coeficientes por banda: b0, b1, b2, a1, a2.
    private var coefs = Array(0) { DoubleArray(5) }
    // Memoria del filtro por canal y banda: x1, x2, y1, y2.
    private var state = Array(0) { DoubleArray(4) }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET
        }
        format = inputAudioFormat
        dirty = true
        return inputAudioFormat
    }

    private fun rebuild(g: List<Int>?) {
        applied = g
        dirty = false
        active = g != null && g.any { it != 0 }
        if (!active || g == null) return
        val rate = format.sampleRate.toDouble()
        val bands = freqs.indices.filter { g.getOrElse(it) { 0 } != 0 && freqs[it].toDouble() < rate * 0.45 }
        coefs = Array(bands.size) { i ->
            val gain = g[bands[i]].toDouble()
            val a = 10.0.pow(gain / 40)
            val w = 2 * PI * freqs[bands[i]] / rate
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha / a
            doubleArrayOf(
                (1 + alpha * a) / a0, (-2 * cos(w)) / a0, (1 - alpha * a) / a0,
                (-2 * cos(w)) / a0, (1 - alpha / a) / a0,
            )
        }
        state = Array(bands.size * format.channelCount) { DoubleArray(4) }
        preamp = 10.0.pow(-max(0, g.maxOrNull() ?: 0) / 20.0)
    }

    private fun filter(x: Double, ch: Int): Double {
        var v = x * preamp
        for (b in coefs.indices) {
            val c = coefs[b]
            val s = state[ch * coefs.size + b]
            val y = c[0] * v + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3]
            s[1] = s[0]; s[0] = v; s[3] = s[2]; s[2] = y
            v = y
        }
        return v.coerceIn(-1.0, 1.0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val g = gains
        if (dirty || g !== applied) rebuild(g)
        val out = replaceOutputBuffer(size)
        if (!active) {
            out.put(inputBuffer)
        } else {
            val channels = format.channelCount
            var ch = 0
            if (format.encoding == C.ENCODING_PCM_16BIT) {
                while (inputBuffer.remaining() >= 2) {
                    val y = filter(inputBuffer.short / 32768.0, ch)
                    out.putShort((y * 32767).toInt().toShort())
                    ch = (ch + 1) % channels
                }
            } else {
                while (inputBuffer.remaining() >= 4) {
                    out.putFloat(filter(inputBuffer.float.toDouble(), ch).toFloat())
                    ch = (ch + 1) % channels
                }
            }
        }
        out.flip()
    }

    override fun onFlush() {
        for (s in state) s.fill(0.0)
    }

    override fun onReset() {
        format = AudioFormat.NOT_SET
        applied = null
        dirty = true
        active = false
        coefs = Array(0) { DoubleArray(5) }
        state = Array(0) { DoubleArray(4) }
    }
}
