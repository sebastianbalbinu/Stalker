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
import androidx.media3.common.ForwardingPlayer
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

        // PCM flotante (24 bits sin recortar) solo con DAC USB en Hi-Res o Bit-perfect.
        // En parlante, audífonos y Bluetooth va audio normal: algunos teléfonos (Samsung S24)
        // dañan el sonido con flotante. Ver SafeAudioSink.
        floatWanted = wantFloat()
        val sink = SafeAudioSink(
            DefaultAudioSink.Builder(this)
                .setEnableFloatOutput(true)
                .build()
        ) { floatWanted }
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
                // Entró el disco que dejamos esperando al final de la cola.
                enterNextDiscIfNeeded(reason)
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
                // Suena la última canción: el siguiente disco queda listo detrás, sin cortes ni esperas.
                queueNextDiscIfNeeded()
                countedFor = null
                resumeLongFile(reason)
                reapply()
                saveSession()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                val sleeping = Engine.sleepEndOfDisco.value
                Engine.sleepEndOfDisco.value = false
                if (!sleeping) continueWithNextDisc()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = saveSession()
        })

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // El botón "siguiente" sigue disponible en la última canción para pasar al siguiente disco.
        val sessionPlayer = object : ForwardingPlayer(player) {
            private fun isNext(c: Int) = c == Player.COMMAND_SEEK_TO_NEXT || c == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            private fun canContinue() = Engine.continuous.value

            override fun getAvailableCommands(): Player.Commands {
                val base = super.getAvailableCommands()
                return if (canContinue()) base.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()
                else base
            }

            override fun isCommandAvailable(command: Int): Boolean =
                (isNext(command) && canContinue()) || super.isCommandAvailable(command)

            override fun seekToNext() {
                if (!skipToNextDisc()) super.seekToNext()
            }

            override fun seekToNextMediaItem() {
                if (!skipToNextDisc()) super.seekToNextMediaItem()
            }
        }

        session = MediaSession.Builder(this, sessionPlayer)
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
        AutoEq.init(this)
        scope.launch {
            combine(AutoEq.enabled, AutoEq.curve) { _, _ -> }.collect { applyAutoEq() }
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

    /** Reproducción continua: el brazo vuelve, el disco se guarda y entra el siguiente. */
    private fun continueWithNextDisc() {
        val (tracks, title) = upcomingDisc() ?: return
        scope.launch {
            if (Engine.discPause.value) delay(NEXT_DISC_PAUSE_MS) // lo que tarda cambiar un vinilo
            if (player.playbackState != Player.STATE_ENDED) return@launch // alguien eligió otra cosa
            loadDisc(tracks, title)
        }
    }

    /** El disco que quedó esperando detrás de la última canción: su primera pista y su nombre. */
    private var waitingFirstId: String? = null
    private var waitingTitle: String? = null
    private var discChange = 0

    private fun queueNextDiscIfNeeded() {
        val n = player.mediaItemCount
        if (n == 0 || player.currentMediaItemIndex != n - 1) return
        if (!Engine.continuous.value || Engine.sleepEndOfDisco.value || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val (tracks, title) = upcomingDisc() ?: return
        waitingFirstId = tracks.first().id.toString()
        waitingTitle = title
        player.addMediaItems(tracks.map { Engine.mediaItem(it) })
    }

    private fun enterNextDiscIfNeeded(reason: Int) {
        val first = waitingFirstId ?: return
        val idx = player.currentMediaItemIndex
        if (idx <= 0 || player.currentMediaItem?.mediaId != first) {
            // Si la cola se reemplazó (otro disco, la Rocola…), el disco en espera ya no aplica.
            if ((1 until player.mediaItemCount).none { player.getMediaItemAt(it).mediaId == first }) {
                waitingFirstId = null; waitingTitle = null
            }
            return
        }
        waitingFirstId = null
        val title = waitingTitle
        waitingTitle = null
        // El disco anterior vuelve a la estantería: la cola queda solo con el nuevo.
        player.removeMediaItems(0, idx)
        Engine.setQueueTitle(title)
        Engine.flipPending.value = false
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !Engine.sleepEndOfDisco.value && Engine.discPause.value) {
            // Lo que tarda cambiar un vinilo.
            val token = ++discChange
            player.pause()
            scope.launch {
                delay(NEXT_DISC_PAUSE_MS)
                if (token == discChange && !player.isPlaying) player.play()
            }
        }
    }

    /** "Siguiente" en la última canción (pantalla, notificación o audífonos): pasa al siguiente disco. */
    private fun skipToNextDisc(): Boolean {
        if (player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF) return false
        val (tracks, title) = upcomingDisc() ?: return false
        loadDisc(tracks, title)
        return true
    }

    private fun upcomingDisc(): Pair<List<Track>, String>? {
        // En una Jam como invitado manda quien la abrió.
        if (Jam.core.active && !Jam.core.host) return null
        val ids = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }
        val next = Engine.nextDisc(ids, Engine.queueTitle.value) ?: return null
        return if (next.first.isEmpty()) null else next
    }

    private fun loadDisc(tracks: List<Track>, title: String) {
        waitingFirstId = null; waitingTitle = null
        Engine.setQueueTitle(title)
        player.shuffleModeEnabled = false
        player.setMediaItems(tracks.map { Engine.mediaItem(it) }, 0, 0L)
        player.prepare()
        player.play()
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

    /** Se puede usar audio flotante: hay DAC USB y el modo no es Normal. */
    @Volatile private var floatWanted = false

    private fun wantFloat(): Boolean =
        Engine.outputMode.value != OutputMode.NORMAL && output.usbDevice() != null

    /** Si se conectó o quitó el DAC (o cambió el modo), reinicia el decodificador sin perder el punto. */
    private fun updateFloat() {
        val want = wantFloat()
        if (want == floatWanted) return
        floatWanted = want
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        val playing = player.playWhenReady
        player.stop()
        player.prepare()
        player.playWhenReady = playing
    }

    private fun reapply() {
        if (!::player.isInitialized) return
        updateFloat()
        val track = Engine.track(player.currentMediaItem?.mediaId)
        scope.launch {
            val info = if (track != null) Library.audioInfo(this@PlaybackService, track) else null
            val tags = if (track != null) Library.flacTags(this@PlaybackService, track) else emptyMap()
            Engine.currentInfo.value = info
            Engine.currentTags.value = tags
            output.apply(Engine.outputMode.value, info)
            applyEq()
            applyReplayGain(tags)
            // Si la canción no trae letra, se busca sola en LRCLIB (en segundo plano).
            if (track != null) launch(kotlinx.coroutines.Dispatchers.IO) { Lyrics.auto(this@PlaybackService, track, tags) }
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
            bitPerfectActive() -> Engine.replayGainStatus.value = tr("ReplayGain en pausa: Bit-perfect no toca el volumen")
            else -> {
                val track = parseGain(tags["REPLAYGAIN_TRACK_GAIN"])
                val album = parseGain(tags["REPLAYGAIN_ALBUM_GAIN"])
                val db = if (mode == "Álbum") album ?: track else track ?: album
                if (db == null) {
                    Engine.replayGainStatus.value = tr("Esta canción no trae ReplayGain")
                } else {
                    gainFactor = 10f.pow(db / 20f).coerceIn(0.05f, 1f)
                    Engine.replayGainStatus.value = tr("ReplayGain %s%s dB", if (db > 0) "+" else "", "%.1f".format(db))
                }
            }
        }
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = (gainFactor * fadeFactor).coerceIn(0f, 1f)
    }

    private var headphoneEq: android.media.audiofx.DynamicsProcessing? = null

    /** Corrección de audífonos (AutoEq): encima del ecualizador, nunca en Bit-perfect. */
    private fun applyAutoEq() {
        val want = AutoEq.enabled.value && !bitPerfectActive()
        try {
            if (!want) {
                headphoneEq?.enabled = false
                return
            }
            val dp = headphoneEq
            if (dp == null) headphoneEq = AutoEq.build(player.audioSessionId) else AutoEq.apply(dp)
        } catch (e: Exception) {
        }
    }

    private fun applyEq() {
        applyAutoEq()
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
        headphoneEq?.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val LONG_FILE_MS = 20 * 60 * 1000L
        const val NEXT_DISC_PAUSE_MS = 2000L
    }
}
