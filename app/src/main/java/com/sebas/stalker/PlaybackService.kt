package com.sebas.stalker

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
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
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.pow

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var output: AudioOutput
    private var equalizer: Equalizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var gainFactor = 1f
    private var fadeFactor = 1f
    private var countedFor: String? = null
    private var tick = 0

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
                // "Al terminar el disco": si volvió sola a la primera pista, se apaga.
                if (Engine.sleepEndOfDisco.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    player.currentMediaItemIndex == 0
                ) {
                    player.pause()
                    Engine.sleepEndOfDisco.value = false
                }
                // Modo purista: al terminar el Lado A, el disco se detiene para darle vuelta.
                val n = player.mediaItemCount
                if (Engine.purist.value && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && n >= 4 &&
                    player.currentMediaItemIndex == (n + 1) / 2
                ) {
                    player.pause()
                    Engine.flipPending.value = true
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) Engine.flipPending.value = false
                countedFor = null
                resumeLongFile(reason)
                reapply()
                saveSession()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) Engine.sleepEndOfDisco.value = false
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
            .setBitmapLoader(ArtLoader())
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
            combine(Engine.outputMode, Engine.eqEnabled, Engine.eqPreset, Engine.replayGain) { _, _, _, _ -> }
                .collect { reapply() }
        }

        // Reloj de 1 segundo: estadísticas, temporizador y archivos largos.
        scope.launch {
            while (true) {
                delay(1000)
                onTick()
            }
        }
    }

    private fun onTick() {
        tick++
        val item = player.currentMediaItem
        val track = Engine.track(item?.mediaId)

        if (player.isPlaying) {
            Engine.addListened(1000)
            if (tick % 30 == 0) Engine.flushListened()
            // Cuenta como escuchada a los 30 segundos.
            if (track != null && countedFor != item?.mediaId && player.currentPosition > 30_000) {
                countedFor = item?.mediaId
                Engine.countPlay(track)
            }
            // Mezclas y audiolibros: guardar el minuto exacto.
            if (track != null && track.durationMs > LONG_FILE_MS && tick % 5 == 0) {
                Engine.savePosition(track.id, player.currentPosition)
            }
        }

        // Temporizador para dormir, con desvanecido de 20 s.
        val at = Engine.sleepAt.value
        if (at != null) {
            val left = at - System.currentTimeMillis()
            if (left <= 0) {
                player.pause()
                Engine.sleepAt.value = null
                fadeFactor = 1f
            } else {
                fadeFactor = (left / 20_000f).coerceIn(0f, 1f)
            }
            applyVolume()
        } else if (fadeFactor != 1f) {
            fadeFactor = 1f
            applyVolume()
        }
    }

    private fun resumeLongFile(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        val track = Engine.track(player.currentMediaItem?.mediaId) ?: return
        if (track.durationMs <= LONG_FILE_MS) return
        val pos = Engine.positionOf(track.id)
        if (pos > 30_000 && pos < track.durationMs - 30_000) player.seekTo(pos)
    }

    private fun saveSession() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        Engine.saveSession(ids, player.currentMediaItemIndex, player.currentPosition)
        Engine.track(player.currentMediaItem?.mediaId)?.let {
            if (it.durationMs > LONG_FILE_MS) Engine.savePosition(it.id, player.currentPosition)
        }
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
            val tags = if (track != null) Library.flacTags(this@PlaybackService, track) else emptyMap()
            Engine.currentInfo.value = info
            Engine.currentTags.value = tags
            output.apply(Engine.outputMode.value, info)
            applyEq()
            applyReplayGain(tags)
        }
    }

    private fun bitPerfectActive(): Boolean =
        Engine.outputMode.value == OutputMode.BIT_PERFECT && output.usbDevice() != null

    private fun parseGain(v: String?): Float? =
        v?.trim()?.replace(",", ".")?.split(" ")?.firstOrNull()?.toFloatOrNull()

    private fun applyReplayGain(tags: Map<String, String>) {
        val mode = Engine.replayGain.value
        gainFactor = 1f
        when {
            mode == "Apagado" -> Engine.replayGainStatus.value = ""
            bitPerfectActive() -> Engine.replayGainStatus.value = "ReplayGain en pausa: Bit-perfect no toca el volumen"
            else -> {
                val track = parseGain(tags["REPLAYGAIN_TRACK_GAIN"])
                val album = parseGain(tags["REPLAYGAIN_ALBUM_GAIN"])
                val db = if (mode == "Álbum") album ?: track else track ?: album
                if (db == null) {
                    Engine.replayGainStatus.value = "Esta canción no trae ReplayGain"
                } else {
                    gainFactor = 10f.pow(db / 20f).coerceIn(0.05f, 1f)
                    Engine.replayGainStatus.value = "ReplayGain ${if (db > 0) "+" else ""}${"%.1f".format(db)} dB"
                }
            }
        }
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = (gainFactor * fadeFactor).coerceIn(0f, 1f)
    }

    private fun applyEq() {
        val want = Engine.eqEnabled.value && !bitPerfectActive()
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

    /** Carga la carátula para la notificación y la pantalla de bloqueo. */
    private inner class ArtLoader : BitmapLoader {
        private val exec: ListeningExecutorService =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            exec.submit(Callable {
                BitmapFactory.decodeByteArray(data, 0, data.size) ?: throw IllegalArgumentException("Sin imagen")
            })

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            exec.submit(Callable { loadArt(uri) ?: throw IllegalArgumentException("Sin carátula") })
    }

    private fun loadArt(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return contentResolver.loadThumbnail(uri, Size(720, 720), null)
            } catch (e: Exception) {
            }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(this, uri)
            val data = r.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(data, 0, data.size)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
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
        Engine.flushListened()
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

    companion object {
        const val LONG_FILE_MS = 20 * 60 * 1000L
    }
}
