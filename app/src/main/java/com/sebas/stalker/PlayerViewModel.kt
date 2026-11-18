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
                if (!core.active || !core.live) continue
                if (core.host) publishJam(force = false)
                else if (tick % 4 == 0) core.remote?.let { applyJam(it, soft = true) }
            }
        }
        viewModelScope.launch { Updates.check(app) }
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

    fun next() { controller?.seekToNextMediaItem() }

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
            playList(tracks, 0, "Cola")
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    /** Al final de la cola. */
    fun addToQueue(tracks: List<Track>) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        if (c.mediaItemCount == 0) playList(tracks, 0, "Cola")
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
                Engine.queueTitle.value ?: q.getOrNull(idx)?.album ?: "Disco",
                q.map { JamTrack(it.title, it.artist, it.album, it.durationMs) },
                idx, pos, c.isPlaying,
            )
        )
    }

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
            c.setMediaItems(have.map { Engine.mediaItem(it) }, localIdx, exp)
            c.prepare()
            c.playWhenReady = st.playing
            return
        }
        if (c.currentMediaItemIndex != localIdx) c.seekTo(localIdx, exp)
        else if (kotlin.math.abs(c.currentPosition - exp) > (if (soft) 900 else 450)) c.seekTo(exp)
        if (st.playing && !c.isPlaying) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        } else if (!st.playing && c.isPlaying) c.pause()
    }

    fun removeFromQueue(i: Int) {
        val c = controller ?: return
        if (i in 0 until c.mediaItemCount && i != c.currentMediaItemIndex) c.removeMediaItem(i)
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
