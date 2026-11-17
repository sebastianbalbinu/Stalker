package com.sebas.stalker

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var output: AudioOutput
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reapply()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reapply()
    }

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        output = AudioOutput(this)

        // Salida en PCM flotante: conserva 24 bits sin recortar a 16.
        val sink = DefaultAudioSink.Builder(this)
            .setEnableFloatOutput(true)
            .build()
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener, sink)
            )
        }

        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                reapply()
                saveSession()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = saveSession()
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        Engine.track(item.mediaId)?.let { Engine.mediaItem(it) } ?: item
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()

        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))

        // Reanudar donde quedó la última vez (sin empezar a sonar).
        scope.launch {
            Engine.tracks.first { it.isNotEmpty() }
            restoreSession()
        }

        scope.launch {
            combine(Engine.outputMode, Engine.eqEnabled, Engine.eqPreset) { _, _, _ -> }
                .collect { reapply() }
        }
    }

    private fun saveSession() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        Engine.saveSession(ids, player.currentMediaItemIndex, player.currentPosition)
    }

    private fun restoreSession() {
        if (player.mediaItemCount > 0) return
        val (ids, index, pos) = Engine.savedSession() ?: return
        val items = ids.mapNotNull { id -> Engine.track(id)?.let { Engine.mediaItem(it) } }
        if (items.isEmpty()) return
        player.setMediaItems(items, index.coerceIn(0, items.size - 1), pos)
        player.prepare()
    }

    private fun reapply() {
        if (!::player.isInitialized) return
        val track = Engine.track(player.currentMediaItem?.mediaId)
        scope.launch {
            val info = if (track != null) Library.audioInfo(this@PlaybackService, track) else null
            Engine.currentInfo.value = info
            output.apply(Engine.outputMode.value, info)
            applyEq()
        }
    }

    private fun applyEq() {
        val bitPerfect = Engine.outputMode.value == OutputMode.BIT_PERFECT && output.usbDevice() != null
        val want = Engine.eqEnabled.value && !bitPerfect
        try {
            if (equalizer == null && want) {
                equalizer = Equalizer(0, player.audioSessionId)
            }
            val eq = equalizer ?: return
            if (!want) {
                eq.enabled = false
                return
            }
            val gains = Engine.presets[Engine.eqPreset.value] ?: Engine.presets.getValue("Plano")
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands.toInt()) {
                val hz = eq.getCenterFreq(band.toShort()) / 1000
                val idx = Engine.presetFreqs.indices.minByOrNull { kotlin.math.abs(Engine.presetFreqs[it] - hz) } ?: 0
                val mb = (gains[idx] * 100).coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), mb.toShort())
            }
            eq.enabled = true
            Engine.eqAvailable.value = true
        } catch (e: Exception) {
            Engine.eqAvailable.value = false
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveSession()
        scope.cancel()
        try {
            getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        } catch (e: Exception) {
        }
        output.clear()
        equalizer?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
