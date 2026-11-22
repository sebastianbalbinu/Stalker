package com.sebas.stalker

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Salida de audio con PCM flotante solo cuando sirve de verdad.
 *
 * Algunos teléfonos (por ejemplo Samsung Galaxy S24) dañan el sonido cuando reciben audio en
 * formato flotante: se escucha un ruido fuerte encima de la música, en parlante y en audífonos.
 * Por eso el decodificador entrega audio normal (16 bits) siempre, y solo usa flotante cuando hay
 * un DAC USB en modo Hi-Res o Bit-perfect, que es donde los 24 bits se notan.
 *
 * El cambio se aplica cuando el decodificador arranca (al empezar una canción o al reiniciarlo).
 */
@UnstableApi
class SafeAudioSink(sink: AudioSink, private val allowFloat: () -> Boolean) : ForwardingAudioSink(sink) {
    override fun getFormatSupport(format: Format): Int {
        if (format.pcmEncoding == C.ENCODING_PCM_FLOAT && !allowFloat()) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return super.getFormatSupport(format)
    }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
}
