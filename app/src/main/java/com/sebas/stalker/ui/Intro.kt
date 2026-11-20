package com.sebas.stalker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.tr
import kotlin.math.sin

private val Sala = Color(0xFF0D0A09)
private val Vino = Color(0xFF3A0B07)
private val RojoTelon = Color(0xFF7A1410)
private val Crema = Color(0xFFEFE4D2)

/**
 * Pantalla de carga: la sala a oscuras. Un telón que apenas se ve, un vinilo girando
 * con su reflejo quieto y el logo. Siempre oscura, sin importar el tema.
 */
@Composable
fun IntroScreen(message: String?) {
    val t = rememberInfiniteTransition(label = "intro")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "giro")
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "luz")

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Sala, 0.55f to Sala, 1f to Vino.copy(alpha = 0.55f))
        )
    ) {
        // El telón en la penumbra: pliegues verticales muy tenues.
        Canvas(Modifier.fillMaxSize()) {
            val folds = 14
            val w = size.width / folds
            for (i in 0 until folds) {
                val x = i * w
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, RojoTelon.copy(alpha = 0.10f * glow), Color.Transparent),
                        startX = x, endX = x + w
                    ),
                    topLeft = Offset(x, 0f),
                    size = androidx.compose.ui.geometry.Size(w, size.height * 0.62f)
                )
            }
            // Luz de foco desde arriba, sobre el vinilo.
            drawCircle(
                Brush.radialGradient(
                    listOf(Crema.copy(alpha = 0.07f * glow), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.42f), radius = size.width * 0.7f
                ),
                radius = size.width * 0.7f, center = Offset(size.width / 2, size.height * 0.42f)
            )
        }

        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(168.dp)) {
                val r = size.minDimension / 2
                val c = center
                // Disco
                drawCircle(Color(0xFF070606), r, c)
                for (i in 0 until 22) {
                    drawCircle(
                        Color.White.copy(alpha = if (i % 3 == 0) 0.05f else 0.025f), r * (0.42f + i * 0.026f), c,
                        style = Stroke(width = 0.8f)
                    )
                }
                // Etiqueta roja que gira, con una marca para que se note el giro.
                rotate(angle, c) {
                    drawCircle(RojoTelon, r * 0.34f, c)
                    drawCircle(Color(0xFF9C1C15), r * 0.34f, c, style = Stroke(width = r * 0.02f))
                    drawCircle(Crema.copy(alpha = 0.85f), r * 0.035f, Offset(c.x, c.y - r * 0.22f))
                    drawArc(
                        Crema.copy(alpha = 0.35f), -100f, 20f, false,
                        topLeft = Offset(c.x - r * 0.27f, c.y - r * 0.27f),
                        size = androidx.compose.ui.geometry.Size(r * 0.54f, r * 0.54f),
                        style = Stroke(width = r * 0.012f)
                    )
                }
                drawCircle(Color(0xFFCFC6B8), r * 0.035f, c)
                // Reflejo quieto, como la luz sobre un vinilo real.
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), c),
                    200f, 50f, true, topLeft = Offset(c.x - r, c.y - r), size = size
                )
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent), c),
                    20f, 50f, true, topLeft = Offset(c.x - r, c.y - r), size = size
                )
            }
            Spacer(Modifier.height(34.dp))
            Bars(glow)
            Spacer(Modifier.height(10.dp))
            Text(
                "STALKER",
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 18.sp, letterSpacing = 9.sp, color = Crema)
            )
        }

        Text(
            message ?: tr("Sin pérdida. Sin prisa."),
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp, letterSpacing = 1.sp, color = Crema.copy(alpha = 0.45f)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp)
        )
    }
}

/** Las cinco barras del logo, respirando. */
@Composable
private fun Bars(glow: Float) {
    val t = rememberInfiniteTransition(label = "barras")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "fase")
    Canvas(Modifier.size(width = 34.dp, height = 22.dp)) {
        val base = listOf(0.35f, 0.65f, 1f, 0.65f, 0.35f)
        val gap = size.width / (base.size - 1)
        base.forEachIndexed { i, h ->
            val k = (h * (0.75f + 0.25f * sin(phase + i * 0.9f))).coerceIn(0.2f, 1f)
            val bh = size.height * k
            val x = gap * i
            drawLine(
                if (i == 2) Color(0xFFD23A2E).copy(alpha = glow) else Crema.copy(alpha = 0.9f),
                Offset(x, (size.height - bh) / 2f), Offset(x, (size.height + bh) / 2f),
                strokeWidth = 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}
