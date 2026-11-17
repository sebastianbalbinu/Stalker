package com.sebas.stalker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Configura la salida USB con la API oficial de Android 14+ (AudioMixerAttributes):
 * - Bit-perfect: el mezclador no toca la señal.
 * - Hi-Res: el mezclador trabaja a la frecuencia original de la canción.
 * Si el teléfono no ofrece bit-perfect, cae a Hi-Res y lo informa.
 */
class AudioOutput(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)

    fun usbDevice(): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }

    fun apply(mode: OutputMode, info: AudioInfo?) {
        val dev = usbDevice()
        Engine.usbDevice.value = dev?.productName?.toString()
        if (dev == null) {
            Engine.outputStatus.value = "Sin DAC USB · salida del teléfono"
            return
        }
        if (Build.VERSION.SDK_INT < 34) {
            Engine.outputStatus.value = "USB conectado · Hi-Res requiere Android 14"
            return
        }
        try {
            applyApi34(dev, mode, info)
        } catch (e: Exception) {
            Engine.outputStatus.value = "USB conectado · no se pudo configurar (${e.javaClass.simpleName})"
        }
    }

    fun clear() {
        if (Build.VERSION.SDK_INT < 34) return
        val dev = usbDevice() ?: return
        try {
            am.clearPreferredMixerAttributes(mediaAttributes(), dev)
        } catch (e: Exception) {
        }
    }

    private fun mediaAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    @RequiresApi(34)
    private fun applyApi34(dev: AudioDeviceInfo, mode: OutputMode, info: AudioInfo?) {
        val attrs = mediaAttributes()
        if (mode == OutputMode.NORMAL) {
            am.clearPreferredMixerAttributes(attrs, dev)
            Engine.outputStatus.value = "Normal · mezclador de Android"
            return
        }
        if (info == null) {
            Engine.outputStatus.value = "USB listo · esperando canción"
            return
        }
        val supported = am.getSupportedMixerAttributes(dev)
        val sameRate = supported.filter { it.format.sampleRate == info.sampleRate }

        if (mode == OutputMode.BIT_PERFECT) {
            val bit = sameRate.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            // El reproductor entrega PCM flotante (sin pérdida para 16 y 24 bits).
            val pick = bit.firstOrNull { it.format.encoding == AudioFormat.ENCODING_PCM_FLOAT }
                ?: bit.firstOrNull()
            if (pick != null && am.setPreferredMixerAttributes(attrs, dev, pick)) {
                Engine.outputStatus.value = "Bit-perfect · ${khz(info.sampleRate)} kHz al DAC"
                return
            }
        }

        val hi = sameRate.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT }
        val pickHi = hi.maxByOrNull { encodingRank(it.format.encoding) }
        if (pickHi != null && am.setPreferredMixerAttributes(attrs, dev, pickHi)) {
            Engine.outputStatus.value = if (mode == OutputMode.BIT_PERFECT) {
                "Tu teléfono no ofrece bit-perfect · Hi-Res ${khz(info.sampleRate)} kHz sin remuestreo"
            } else {
                "Hi-Res · ${khz(info.sampleRate)} kHz sin remuestreo"
            }
        } else {
            am.clearPreferredMixerAttributes(attrs, dev)
            Engine.outputStatus.value = "El DAC no acepta ${khz(info.sampleRate)} kHz · salida normal"
        }
    }

    private fun khz(sr: Int): String =
        if (sr % 1000 == 0) "${sr / 1000}" else String.format("%.1f", sr / 1000.0)

    private fun encodingRank(e: Int): Int = when (e) {
        AudioFormat.ENCODING_PCM_FLOAT -> 5
        AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_16BIT -> 1
        else -> 0
    }
}
