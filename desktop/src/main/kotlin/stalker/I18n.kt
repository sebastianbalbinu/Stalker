package stalker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Idioma de la interfaz. Los textos se escriben en español en el código y [tr] los cambia
 * al inglés cuando corresponde (el diccionario está en Strings.kt).
 */
object I18n {
    /** "Sistema", "Español" o "English". */
    var choice by mutableStateOf(Prefs.language)
        private set

    /** true si la interfaz está en inglés ahora mismo. */
    @Volatile
    var english = compute(Prefs.language)
        private set

    fun set(v: String) {
        Prefs.language = v
        english = compute(v)
        choice = v
    }

    private fun compute(v: String) = when (v) {
        "English" -> true
        "Español" -> false
        // Sigue a Windows: español si el sistema está en español, inglés en cualquier otro idioma.
        else -> Locale.getDefault().language != "es"
    }
}

/** Traduce un texto escrito en español. Los %s se rellenan con [args], como en String.format. */
fun tr(es: String, vararg args: Any?): String {
    val base = if (I18n.english) EN[es] ?: es else es
    return if (args.isEmpty()) base else String.format(base, *args)
}
