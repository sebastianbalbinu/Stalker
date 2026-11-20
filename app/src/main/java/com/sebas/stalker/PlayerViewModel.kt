package com.sebas.stalker

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Job
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null

    val isPlaying = MutableStateFlow(false)
    val current = MutableStateFlow<Track?>(null)
    val positionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val shuffle = MutableStateFlow(false)
    val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val queue = MutableStateFlow<List<Track>>(emptyList())
    val index = MutableStateFlow(0)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    /** Importación automática: cuando Android registra música nueva o borrada, la biblioteca se actualiza sola. */
    private var rescanJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!Engine.scanned.value) return
            rescanJob?.cancel()
            rescanJob = viewModelScope.launch {
                delay(2500)
                rescan()
            }
        }
    }

    init {
        Engine.init(app)
        Jam.init(app)
        Jam.core.onRemote = { st -> viewModelScope.launch { applyJam(st, soft = false) } }
        Jam.core.onHello = { viewModelScope.launch { publishJam(force = true) } }
        // Jam: quien la abrió cuenta cada cambio; los invitados corrigen la deriva
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(500)
                tick++
                val core = Jam.core
                if (!core.active || !core.live || core.host) controller?.let { if (jamRate != 1f) jamSpeed(it, 1f) }
                if (!core.active || !core.live) continue
                if (core.host) publishJam(force = false)
                else if (tick % 2 == 0) core.remote?.let { applyJam(it, soft = true) }
            }
        }
        viewModelScope.launch { Updates.check(app) }
        Updates.schedule(app)
        // Actualización automática: cuando la app queda en segundo plano y no suena nada, se instala sola.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(Updates.ready, Updates.foreground, isPlaying) { r, fg, pl -> Triple(r, fg, pl) }
                .collectLatest { (r, fg, pl) ->
                    if (r == null || fg || pl || !Updates.autoEnabled(app) || !Updates.silentPossible(app)) return@collectLatest
                    delay(5000)
                    if (!Updates.musicActive(app)) Updates.install(app)
                }
        }
        try {
            app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (e: Exception) {
        }
        viewModelScope.launch {
            try {
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                val c = MediaController.Builder(app, token).buildAsync().await()
                controller = c
                c.addListener(listener)
                sync(c)
            } catch (e: Exception) {
            }
        }
        viewModelScope.launch {
            while (true) {
                controller?.let {
                    positionMs.value = it.currentPosition
                    val d = it.duration
                    durationMs.value = if (d > 0) d else (current.value?.durationMs ?: 0L)
                }
                delay(500)
            }
        }
    }

    fun rescan() {
        viewModelScope.launch {
            Engine.setTracks(Library.scan(getApplication()))
            controller?.let { sync(it) }
            scanQuality()
        }
    }

    /** Lee una vez la calidad real de cada álbum nuevo (encabezado del FLAC), para ordenar por calidad. */
    private var qualityJob: Job? = null
    private fun scanQuality() {
        if (qualityJob?.isActive == true) return
        qualityJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val known = Engine.albumQuality.value
            val pending = Engine.albums(Engine.tracks.value).filter { it.id !in known }
            val found = HashMap<Long, AlbumQuality>()
            for (a in pending) {
                val info = Library.audioInfo(getApplication(), a.tracks.first()) ?: continue
                found[a.id] = AlbumQuality(info.bits, info.sampleRate, info.codec)
                if (found.size >= 25) { Engine.putQuality(found.toMap()); found.clear() }
            }
            Engine.putQuality(found)
        }
    }

    private fun sync(p: Player) {
        isPlaying.value = p.isPlaying
        current.value = Engine.track(p.currentMediaItem?.mediaId)
        shuffle.value = p.shuffleModeEnabled
        repeat.value = p.repeatMode
        queue.value = (0 until p.mediaItemCount).mapNotNull { Engine.track(p.getMediaItemAt(it).mediaId) }
        index.value = p.currentMediaItemIndex
    }

    /** Espera (hasta unos segundos) a que el reproductor esté listo; útil al abrir la app desde otra. */
    suspend fun awaitReady() {
        repeat(40) { if (controller != null) return; kotlinx.coroutines.delay(150) }
    }

    fun playList(tracks: List<Track>, index: Int, title: String? = null, shuffled: Boolean = false) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        Engine.setQueueTitle(title)
        val items: List<MediaItem> = tracks.map { Engine.mediaItem(it) }
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(items, if (shuffled) 0 else index, 0L)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun next() {
        val c = controller ?: return
        // En la última canción, "siguiente" pasa al siguiente disco (si la reproducción continua está activa).
        if (!c.hasNextMediaItem() && c.repeatMode == Player.REPEAT_MODE_OFF && !(Jam.core.active && !Jam.core.host)) {
            val ids = queue.value.map { it.id }
            Engine.nextDisc(ids, Engine.queueTitle.value)?.let { (tracks, title) ->
                if (tracks.isNotEmpty()) { playList(tracks, 0, title); return }
            }
        }
        c.seekToNextMediaItem()
    }

    /** Rocola: toda la biblioteca (o solo los favoritos) al azar. */
    fun rocola(onlyFavorites: Boolean) {
        val list = Engine.rocola(onlyFavorites)
        if (list.isEmpty()) return
        playList(list, 0, if (onlyFavorites) Engine.ROCOLA_FAVS else Engine.ROCOLA)
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seek(ms: Long) { controller?.seekTo(ms) }

    /** Suena justo después de la canción actual. */
    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { Engine.mediaItem(it) }
        if (c.mediaItemCount == 0) {
            playList(tracks, 0, tr("Cola"))
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    /** Al final de la cola. */
    fun addToQueue(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) playList(tracks, 0, tr("Cola"))
        else c.addMediaItems(tracks.map { Engine.mediaItem(it) })
    }

    /** Cambia el orden de la cola sin cortar lo que suena. */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (from == to || from !in 0 until n || to !in 0 until n) return
        c.moveMediaItem(from, to)
    }

    // ------------------------------------------------------------ Jam

    private var jamIdx = -1
    private var jamPlaying = false
    private var jamPos = 0L
    private var jamAt = 0L
    private var jamQueue: List<Long> = emptyList()

    private fun publishJam(force: Boolean) {
        val c = controller ?: return
        val q = queue.value
        if (q.isEmpty()) return
        val now = System.currentTimeMillis()
        val pos = c.currentPosition
        val idx = c.currentMediaItemIndex
        val ids = q.map { it.id }
        val expected = jamPos + if (jamPlaying) now - jamAt else 0
        val changed = force || idx != jamIdx || c.isPlaying != jamPlaying || ids != jamQueue ||
            kotlin.math.abs(pos - expected) > 1500 || now - jamAt > 4000
        if (!changed) return
        jamIdx = idx; jamPlaying = c.isPlaying; jamPos = pos; jamAt = now; jamQueue = ids
        Jam.core.publish(
            JamState(
                Engine.queueTitle.value?.let { Engine.displayTitle(it) } ?: q.getOrNull(idx)?.album ?: tr("Disco"),
                q.map { JamTrack(it.title, it.artist, it.album, it.durationMs) },
                idx, pos, c.isPlaying,
            )
        )
    }

    private var jamLead = 350L
    private var jamSeekAt = 0L
    private var jamMeasured = true
    private var jamRate = 1f

    private fun jamSpeed(c: MediaController, r: Float) {
        if (kotlin.math.abs(r - jamRate) < 0.004f) return
        jamRate = r
        c.setPlaybackSpeed(r)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyJam(st: JamState, soft: Boolean) {
        val c = controller ?: return
        val lib = Engine.tracks.value
        val mapped = st.tracks.map { r -> jamMatch(r, lib, { it.title }, { it.artist }, { it.durationMs }) }
        Jam.missing.value = st.tracks.filterIndexed { i, _ -> mapped[i] == null }.map { it.title }
        val have = mapped.filterNotNull()
        val cur = mapped.getOrNull(st.index)
        if (cur == null) {
            if (c.isPlaying) c.pause()
            return
        }
        val localIdx = have.indexOf(cur)
        val exp = st.expectedPos(System.currentTimeMillis()).coerceAtLeast(0)
        if (queue.value.map { it.id } != have.map { it.id }) {
            Engine.setQueueTitle(st.disc)
            c.shuffleModeEnabled = false
            c.setMediaItems(have.map { Engine.mediaItem(it) }, localIdx, exp + jamLead)
            jamSeekAt = System.currentTimeMillis(); jamMeasured = false
            c.prepare()
            c.playWhenReady = st.playing
            return
        }
        val now = System.currentTimeMillis()
        if (c.currentMediaItemIndex != localIdx) {
            c.seekTo(localIdx, exp + jamLead)
            jamSeekAt = now; jamSpeed(c, 1f)
        } else if (st.playing && c.isPlaying && now - jamSeekAt > 3000) {
            val drift = c.currentPosition - exp
            if (!jamMeasured && jamSeekAt > 0) {
                // aprende cuánto tarda este teléfono en arrancar después de mover la aguja
                jamLead = (jamLead - drift).coerceIn(0L, 1500L)
                jamMeasured = true
            }
            when {
                kotlin.math.abs(drift) > 2000 -> {
                    c.seekTo(exp + jamLead); jamSeekAt = now; jamMeasured = false; jamSpeed(c, 1f)
                }
                // deriva pequeña: se corrige acelerando o frenando apenas, sin cortes
                kotlin.math.abs(drift) > 120 -> jamSpeed(c, (1f - drift / 5000f).coerceIn(0.97f, 1.03f))
                kotlin.math.abs(drift) < 50 -> jamSpeed(c, 1f)
            }
        }
        if (st.playing && !c.isPlaying && c.playbackState != Player.STATE_BUFFERING) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        } else if (!st.playing && c.isPlaying) c.pause()
    }

    fun removeFromQueue(i: Int) {
        val c = controller ?: return
        if (i in 0 until c.mediaItemCount && i != c.currentMediaItemIndex) c.removeMediaItem(i)
    }

    /** Descarga de LRCLIB las letras que falten en toda la biblioteca. */
    fun fetchAllLyrics() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Lyrics.fetchAll(getApplication(), Engine.visible(Engine.tracks.value, Engine.hiddenAlbums.value, Engine.hiddenTracks.value))
        }
    }

    /** Busca las portadas que faltan en toda la biblioteca. */
    fun fetchAllCovers() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            Covers.fetchAll(getApplication(), Engine.albums(Engine.visible(Engine.tracks.value, Engine.hiddenAlbums.value, Engine.hiddenTracks.value)))
        }
    }

    fun checkUpdatesQuiet() {
        viewModelScope.launch { Updates.check(getApplication()) }
    }

    fun checkUpdates() {
        viewModelScope.launch { Updates.check(getApplication(), force = true) }
    }

    /** Soltar la aguja en una canción y un punto de ella. */
    fun dropNeedle(i: Int, ms: Long) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        c.seekTo(i, ms)
        c.play()
    }

    fun jumpTo(i: Int) {
        val c = controller ?: return
        c.seekTo(i, 0L)
        c.play()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onCleared() {
        try { getApplication<Application>().contentResolver.unregisterContentObserver(observer) } catch (e: Exception) { }
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
