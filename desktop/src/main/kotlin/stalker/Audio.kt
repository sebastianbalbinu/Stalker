package stalker

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.WString
import java.io.File

/** Puente al motor nativo stalker_audio.dll (WASAPI exclusivo + miniaudio). */
interface SA : Library {
    fun sa_init(): Int
    fun sa_load(path: WString, bits: Int, exclusive: Int, dev: Int): Int
    fun sa_queue(path: WString, bits: Int): Int
    fun sa_clear_queue()
    fun sa_play(): Int
    fun sa_pause()
    fun sa_suspend()
    fun sa_seek(frame: Long)
    fun sa_cursor(): Long
    fun sa_length(): Long
    fun sa_volume(v: Float)
    fun sa_info(info: IntArray)
    fun sa_error(buf: ByteArray, len: Int)
    fun sa_device_count(): Int
    fun sa_device_name(index: Int, buf: ByteArray, len: Int): Int
    fun sa_peaks(path: WString, count: Int, out: FloatArray): Int
    fun sa_shutdown()
}

object Audio {
    val lib: SA? by lazy {
        try {
            val dirs = listOfNotNull(
                System.getProperty("compose.application.resources.dir"),
                File("resources/windows").absolutePath,
                File("desktop/resources/windows").absolutePath,
            )
            val old = System.getProperty("jna.library.path")
            System.setProperty("jna.library.path", (dirs + listOfNotNull(old)).joinToString(File.pathSeparator))
            val l = Native.load("stalker_audio", SA::class.java)
            if (l.sa_init() == 1) l else null
        } catch (t: Throwable) {
            System.err.println("Motor de audio no disponible: $t")
            null
        }
    }

    fun error(): String {
        val b = ByteArray(256)
        lib?.sa_error(b, b.size)
        return cString(b)
    }

    fun devices(): List<Pair<String, Boolean>> {
        val l = lib ?: return emptyList()
        val n = l.sa_device_count()
        return (0 until n).map { i ->
            val b = ByteArray(512)
            val r = l.sa_device_name(i, b, b.size)
            cString(b) to (r == 2)
        }
    }

    fun cString(b: ByteArray): String {
        val n = b.indexOf(0).let { if (it < 0) b.size else it }
        return String(b, 0, n, Charsets.UTF_8)
    }
}
