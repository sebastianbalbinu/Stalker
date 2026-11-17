package com.sebas.stalker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.sebas.stalker.R

object C {
    val Rojo = Color(0xFF8E0E0E)
    val RojoClaro = Color(0xFFA8191A)
    val RojoOscuro = Color(0xFF6A0A0A)
    val Marfil = Color(0xFFF5EFE5)
    val Tarjeta = Color(0xFFFBF8F2)
    val Arena = Color(0xFFEDE5D8)
    val Linea = Color(0xFFDDD5C7)
    val Carbon = Color(0xFF1D1B19)
    val Surco = Color(0xFF2A2725)
    val Piedra = Color(0xFFAAA295)
    val Tierra = Color(0xFF6E655A)
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
