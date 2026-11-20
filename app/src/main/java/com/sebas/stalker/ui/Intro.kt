package com.sebas.stalker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/** La curva suave de Apple: arranca decidida y se posa sin frenazo. */
val AppleEase = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * Pantalla de carga: solo un vinilo girando sobre negro puro. El arranque de Android también es
 * negro y sin ícono, así que no hay saltos entre una pantalla y otra.
 * [leaving] va de 0 a 1 mientras se despide (se desvanece y se acerca apenas).
 */
@Composable
fun IntroScreen(leaving: () -> Float) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(520, easing = AppleEase)) }
    val t = rememberInfiniteTransition(label = "intro")
    // 33⅓ rpm: una vuelta cada 1,8 s.
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "giro")

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - leaving() }
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier.size(148.dp).graphicsLayer {
                val l = leaving()
                alpha = appear.value * (1f - l)
                val s = 0.94f + 0.06f * appear.value + 0.08f * l
                scaleX = s; scaleY = s
            }
        ) {
            val r = size.minDimension / 2
            val c = center
            drawCircle(Color(0xFF0B0A0A), r, c)
            // Surcos
            for (i in 0 until 20) {
                drawCircle(
                    Color.White.copy(alpha = if (i % 4 == 0) 0.06f else 0.03f), r * (0.42f + i * 0.028f), c,
                    style = Stroke(width = 0.8f)
                )
            }
            drawCircle(Color.White.copy(alpha = 0.08f), r - 0.5f, c, style = Stroke(width = 1f))
            // Etiqueta roja: gira y tiene una marca para que se note.
            rotate(angle, c) {
                drawCircle(Color(0xFF7A1410), r * 0.33f, c)
                drawCircle(Color(0xFF8E1A13), r * 0.33f, c, style = Stroke(width = r * 0.015f))
                drawCircle(Color(0xFFEFE4D2).copy(alpha = 0.85f), r * 0.03f, Offset(c.x, c.y - r * 0.21f))
            }
            drawCircle(Color(0xFFCFC6B8), r * 0.03f, c)
            // Reflejo de luz quieto, como sobre un vinilo real.
            for ((start, a) in listOf(205f to 0.11f, 25f to 0.07f)) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = a), Color.Transparent), c),
                    start, 46f, true, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r)
                )
            }
        }
    }
}
