package stalker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.WString
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Cadena de señal que se muestra al usuario. */
data class Chain(
    val srcRate: Int = 0, val srcBits: Int = 0, val srcCh: Int = 0,
    val outRate: Int = 0, val outBits: Int = 0, val outValid: Int = 0,
    val mode: Int = 0,
) {
    val exclusive get() = mode == 1
}

class Player {
    private val audio = Executors.newSingleThreadExecutor { r -> Thread(r, "stalker-audio").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + audio)

    var queue by mutableStateOf<List<Track>>(emptyList()); private set
    var queueTitle by mutableStateOf(""); private set
    var index by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var chain by mutableStateOf(Chain()); private set
    var message by mutableStateOf<String?>(null)
    var volume by mutableFloatStateOf(Prefs.volume); private set
    var exclusive by mutableStateOf(Prefs.exclusive); private set
    var device by mutableIntStateOf(Prefs.device); private set
    /** Sube cada vez que el usuario baja la aguja: dispara la animación del brazo. */
    var drops by mutableIntStateOf(0); private set

    val available get() = Audio.lib != null
    val current: Track? get() = queue.getOrNull(index)

    private var loaded = false
    private var queuedIndex = -1
    private var lastChanges = 0
    private var pausedAt = 0L
    private var suspended = false

    val albumDurationMs get() = queue.sumOf { it.durationMs }
    val albumPositionMs get() = queue.take(index.coerceAtLeast(0)).sumOf { it.durationMs } + positionMs

    val bitPerfect: Boolean
        get() {
            val c = chain
            val t = current ?: return false
            return t.lossless && c.exclusive && c.outRate == c.srcRate && c.outValid >= c.srcBits && volume >= 0.999f
        }

    init {
        Audio.lib?.sa_volume(volume)
        scope.launch { tick() }
    }

    /** Coloca un disco en el plato. Si [play] es falso queda esperando la aguja. */
    fun setQueue(tracks: List<Track>, start: Int = 0, title: String = "", play: Boolean = true, startMs: Long = 0) {
        if (tracks.isEmpty()) return
        queue = tracks
        queueTitle = title
        index = start.coerceIn(0, tracks.size - 1)
        positionMs = startMs
        loaded = false
        Prefs.lastQueue = tracks.map { it.path }
        Prefs.lastIndex = index
        scope.launch { load(index, startMs, play) }
    }

    private fun load(i: Int, startMs: Long, play: Boolean) {
        val l = Audio.lib ?: run { message = "No se encontró el motor de audio"; return }
        val t = queue.getOrNull(i) ?: return
        val mode = l.sa_load(WString(t.path), t.bits, if (exclusive) 1 else 0, device)
        if (mode == 0) {
            message = Audio.error().ifBlank { "No se pudo reproducir ${t.title}" }
            playing = false
            loaded = false
            return
        }
        message = if (exclusive && mode == 2) (Audio.error().ifBlank { "El DAC no aceptó el modo exclusivo" } + " · salida compartida") else null
        loaded = true
        suspended = false
        index = i
        Prefs.lastIndex = i
        val info = IntArray(12)
        l.sa_info(info)
        lastChanges = info[8]
        updateChain(info)
        if (startMs > 0 && t.rate > 0) l.sa_seek(startMs * t.rate / 1000)
        positionMs = startMs
        if (play) {
            l.sa_play()
            playing = true
        } else {
            playing = false
            pausedAt = System.currentTimeMillis()
        }
        queueNext()
    }

    private fun queueNext() {
        val l = Audio.lib ?: return
        queuedIndex = -1
        val n = queue.getOrNull(index + 1) ?: run { l.sa_clear_queue(); return }
        if (l.sa_queue(WString(n.path), n.bits) == 1) queuedIndex = index + 1
    }

    private fun updateChain(info: IntArray) {
        val c = Chain(info[0], info[2], info[1], info[3], info[5], info[6], info[7])
        if (c != chain) chain = c
    }

    fun toggle() { if (playing) pause() else play() }

    fun play() {
        if (queue.isEmpty()) return
        scope.launch {
            val l = Audio.lib ?: return@launch
            if (!loaded) { load(index.coerceAtLeast(0), positionMs, true); drops++; return@launch }
            if (l.sa_play() == 0) { load(index, positionMs, true); return@launch }
            suspended = false
            playing = true
            drops++
            val info = IntArray(12); l.sa_info(info); updateChain(info)
        }
    }

    fun pause() {
        scope.launch {
            Audio.lib?.sa_pause()
            playing = false
            pausedAt = System.currentTimeMillis()
            savePos()
        }
    }

    fun next() {
        if (index + 1 < queue.size) scope.launch { load(index + 1, 0, true) }
    }

    fun prev() {
        if (positionMs > 3000 || index == 0) seek(0) else scope.launch { load(index - 1, 0, true) }
    }

    fun jump(i: Int) { scope.launch { load(i, 0, true); drops++ } }

    fun seek(ms: Long) {
        positionMs = ms.coerceAtLeast(0)
        scope.launch {
            val t = current ?: return@launch
            if (!loaded) { load(index, ms, false); return@launch }
            Audio.lib?.sa_seek(ms.coerceAtLeast(0) * t.rate / 1000)
        }
    }

    fun seekBy(deltaMs: Long) {
        val t = current ?: return
        seek((positionMs + deltaMs).coerceIn(0, t.durationMs - 500))
    }

    /** Posición dentro de todo el disco (todas las pistas de la cola). */
    fun dropNeedle(albumMs: Long, play: Boolean = true) {
        var acc = 0L
        for ((i, t) in queue.withIndex()) {
            if (albumMs < acc + t.durationMs || i == queue.size - 1) {
                val off = (albumMs - acc).coerceIn(0, (t.durationMs - 500).coerceAtLeast(0))
                positionMs = off
                index = i
                scope.launch {
                    load(i, off, play)
                    if (play) drops++
                }
                return
            }
            acc += t.durationMs
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        Prefs.volume = volume
        Audio.lib?.sa_volume(volume)
    }

    fun setExclusive(on: Boolean) {
        exclusive = on
        Prefs.exclusive = on
        reload()
    }

    fun setDevice(i: Int) {
        device = i
        Prefs.device = i
        reload()
    }

    private fun reload() {
        if (!loaded) return
        val was = playing
        scope.launch { Audio.lib?.sa_suspend(); load(index, positionMs, was) }
    }

    fun move(from: Int, to: Int) {
        if (from == index) return
        val q = queue.toMutableList()
        val t = q.removeAt(from)
        q.add(to, t)
        val cur = current
        queue = q
        index = q.indexOf(cur)
        scope.launch { queueNext() }
    }

    fun savePos() {
        Prefs.lastIndex = index
        Prefs.lastPos = positionMs
    }

    fun restore(all: List<Track>) {
        val byPath = all.associateBy { it.path }
        val q = Prefs.lastQueue.mapNotNull { byPath[it] }
        if (q.isEmpty()) return
        val i = Prefs.lastIndex.coerceIn(0, q.size - 1)
        queue = q
        index = i
        positionMs = Prefs.lastPos.coerceIn(0, q[i].durationMs)
        queueTitle = q.first().album
    }

    fun shutdown() {
        savePos()
        try { Audio.lib?.sa_shutdown() } catch (_: Throwable) {}
    }

    private suspend fun tick() {
        val info = IntArray(12)
        var saveCounter = 0
        while (scope.isActive) {
            delay(40)
            val l = Audio.lib ?: continue
            if (!loaded) continue
            l.sa_info(info)
            val t = current ?: continue
            // cambio de pista sin pausa (gapless)
            if (info[8] != lastChanges) {
                lastChanges = info[8]
                if (queuedIndex >= 0) {
                    index = queuedIndex
                    Prefs.lastIndex = index
                }
                queueNext()
            }
            val rate = info[0].takeIf { it > 0 } ?: t.rate
            if (rate > 0) positionMs = l.sa_cursor() * 1000 / rate
            updateChain(info)
            if (info[11] == 1) {
                message = "Se desconectó la salida de audio"
                load(index, positionMs, playing)
                continue
            }
            if (info[9] == 1 && playing) {
                if (index + 1 < queue.size) load(index + 1, 0, true)
                else {
                    playing = false
                    pausedAt = System.currentTimeMillis()
                    positionMs = 0
                    l.sa_seek(0)
                }
            }
            if (!playing && info[10] == 0 && !suspended && pausedAt > 0 &&
                System.currentTimeMillis() - pausedAt > 4000
            ) {
                // en pausa soltamos el DAC para que el resto del PC pueda sonar
                l.sa_suspend()
                suspended = true
            }
            if (++saveCounter % 125 == 0 && playing) savePos()
        }
    }
}

