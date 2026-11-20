package com.sebas.stalker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Idioma de la interfaz. Los textos se escriben en español en el código y [tr] los cambia
 * al inglés cuando corresponde (el diccionario está en Strings.kt).
 */
object I18n {
    /** "Sistema", "Español" o "English". */
    val choice = MutableStateFlow("Sistema")

    /** true si la interfaz está en inglés ahora mismo. */
    @Volatile
    var english = false
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("stalker", Context.MODE_PRIVATE)
        prefs = p
        choice.value = p.getString("language", "Sistema") ?: "Sistema"
        recompute()
    }

    fun set(v: String) {
        choice.value = v
        prefs?.edit()?.putString("language", v)?.apply()
        recompute()
    }

    private fun recompute() {
        english = when (choice.value) {
            "English" -> true
            "Español" -> false
            // Sigue al teléfono: español si el sistema está en español, inglés en cualquier otro idioma.
            else -> Locale.getDefault().language != "es"
        }
    }
}

/** Traduce un texto escrito en español. Los %s se rellenan con [args], como en String.format. */
fun tr(es: String, vararg args: Any?): String {
    val base = if (I18n.english) EN[es] ?: es else es
    return if (args.isEmpty()) base else String.format(base, *args)
}
