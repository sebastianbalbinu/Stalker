package com.sebas.stalker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sebas.stalker.R

/** Paleta de la sala roja. En modo noche cambian el fondo, las tarjetas y el texto. */
object C {
    var dark by mutableStateOf(false)

    val Rojo = Color(0xFF8E0E0E)
    val RojoClaro = Color(0xFFA8191A)
    val RojoOscuro = Color(0xFF6A0A0A)
    /** Texto e íconos sobre rojo o sobre el vinilo. */
    val SobreRojo = Color(0xFFF5EFE5)
    /** Negro del vinilo, sombras y fondos oscuros fijos. */
    val Negro = Color(0xFF1D1B19)
    val Surco = Color(0xFF2A2725)

    val Marfil: Color get() = if (dark) Color(0xFF120F0E) else Color(0xFFF5EFE5)
    val Tarjeta: Color get() = if (dark) Color(0xFF1E1917) else Color(0xFFFBF8F2)
    val Arena: Color get() = if (dark) Color(0xFF2A2320) else Color(0xFFEDE5D8)
    val Linea: Color get() = if (dark) Color(0xFF3A312D) else Color(0xFFDDD5C7)
    val Carbon: Color get() = if (dark) Color(0xFFEDE5D8) else Color(0xFF1D1B19)
    val Piedra: Color get() = if (dark) Color(0xFF6E655A) else Color(0xFFAAA295)
    val Tierra: Color get() = if (dark) Color(0xFFA99F92) else Color(0xFF6E655A)
}

val Playfair = FontFamily(
    Font(R.font.playfair_regular, FontWeight.Normal),
    Font(R.font.playfair_semibold, FontWeight.SemiBold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)
