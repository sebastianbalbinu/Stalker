package stalker

import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/** Discos compartidos desde Stalker en el celular (.stalker.zip con los FLAC originales). */
object Discs {
    private fun clean(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.').ifBlank { "Disco" }

    /** Extrae el disco en Música\Stalker\<nombre> y devuelve esa carpeta. */
    fun import(file: File): File? {
        ZipFile(file).use { zip ->
            val manifest = zip.getEntry("stalker.json")?.let { e -> zip.getInputStream(e).use { JSONObject(it.readBytes().decodeToString()) } }
            val name = clean(manifest?.optString("name")?.ifBlank { null } ?: file.name.removeSuffix(".zip").removeSuffix(".stalker"))
            val music = File(System.getProperty("user.home"), "Music")
            var dest = File(File(music, "Stalker"), name)
            var n = 2
            while (dest.exists() && (dest.listFiles()?.isNotEmpty() == true)) dest = File(File(music, "Stalker"), "$name ($n)").also { n++ }
            dest.mkdirs()
            val base = dest.canonicalPath + File.separator
            var count = 0
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val out = File(dest, File(e.name).name)
                if (!out.canonicalPath.startsWith(base)) continue // nada fuera de la carpeta del disco
                zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
                if (out.extension.lowercase() in Library.exts) count++
            }
            return if (count > 0) dest else null
        }
    }
}
