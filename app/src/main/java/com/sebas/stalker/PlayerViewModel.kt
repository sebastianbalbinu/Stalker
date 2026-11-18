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
