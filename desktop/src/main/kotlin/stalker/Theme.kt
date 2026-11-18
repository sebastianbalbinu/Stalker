package stalker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Sombra = Color(0xFF0B0706)
    val Noche = Color(0xFF140C0A)
    val Panel = Color(0xFF1A100D)
    val Crema = Color(0xFFF1DFC4)
    val Tenue = Color(0xFFA99F92)
    val Apagado = Color(0xFF6E655C)
    val Rojo = Color(0xFF8E0E0E)
    val RojoVivo = Color(0xFFC0261E)
    val Oro = Color(0xFFD9B26F)
    val Linea = Color(0x1FF1DFC4)
}

val Inter = FontFamily(
    Font("font/inter_regular.ttf", FontWeight.Normal),
    Font("font/inter_medium.ttf", FontWeight.Medium),
    Font("font/inter_semibold.ttf", FontWeight.SemiBold),
    Font("font/inter_semibold.ttf", FontWeight.Bold),
)

fun txt(size: TextUnit = 13.sp, weight: FontWeight = FontWeight.Normal, color: Color = C.Crema, spacing: TextUnit = 0.sp) =
    TextStyle(fontFamily = Inter, fontSize = size, fontWeight = weight, color = color, letterSpacing = spacing)

enum class Ico { Play, Pause, Next, Prev, Mini, Minimize, Maximize, Restore, Close, Gear, Search, Folder, Shelf, Room, Expand, Heart, HeartOn, Plus, Trash, Speaker, Sun }

/** Iconos dibujados a mano, finos y redondeados. */
@Composable
fun Icon(ico: Ico, color: Color = C.Crema, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(ico, color) }
}

fun DrawScope.drawIco(ico: Ico, color: Color) {
    val w = size.width
    val s = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
        moveTo(pts[0] * w, pts[1] * w)
        var i = 2
        while (i < pts.size) { lineTo(pts[i] * w, pts[i + 1] * w); i += 2 }
        if (close) close()
    }
    when (ico) {
        Ico.Play -> drawPath(Path().apply {
            moveTo(0.30f * w, 0.18f * w); lineTo(0.82f * w, 0.5f * w); lineTo(0.30f * w, 0.82f * w); close()
        }, color)
        Ico.Pause -> {
            drawRoundRect(color, Offset(0.25f * w, 0.18f * w), Size(0.16f * w, 0.64f * w), CornerRadius(0.04f * w))
            drawRoundRect(color, Offset(0.59f * w, 0.18f * w), Size(0.16f * w, 0.64f * w), CornerRadius(0.04f * w))
        }
        Ico.Next -> {
            drawPath(p(0.2f, 0.2f, 0.66f, 0.5f, 0.2f, 0.8f, close = true), color)
            drawRoundRect(color, Offset(0.70f * w, 0.2f * w), Size(0.1f * w, 0.6f * w), CornerRadius(0.03f * w))
        }
        Ico.Prev -> {
            drawPath(p(0.8f, 0.2f, 0.34f, 0.5f, 0.8f, 0.8f, close = true), color)
            drawRoundRect(color, Offset(0.2f * w, 0.2f * w), Size(0.1f * w, 0.6f * w), CornerRadius(0.03f * w))
        }
        Ico.Mini -> {
            drawRoundRect(color, Offset(0.12f * w, 0.2f * w), Size(0.76f * w, 0.6f * w), CornerRadius(0.1f * w), style = s)
            drawRoundRect(color, Offset(0.46f * w, 0.48f * w), Size(0.3f * w, 0.2f * w), CornerRadius(0.05f * w))
        }
        Ico.Minimize -> drawLine(color, Offset(0.25f * w, 0.5f * w), Offset(0.75f * w, 0.5f * w), s.width, StrokeCap.Round)
        Ico.Maximize -> drawRoundRect(color, Offset(0.26f * w, 0.26f * w), Size(0.48f * w, 0.48f * w), CornerRadius(0.08f * w), style = s)
        Ico.Restore -> {
            drawRoundRect(color, Offset(0.22f * w, 0.34f * w), Size(0.42f * w, 0.42f * w), CornerRadius(0.07f * w), style = s)
            drawPath(p(0.38f, 0.26f, 0.78f, 0.26f, 0.78f, 0.62f), color, style = s)
        }
        Ico.Close -> {
            drawLine(color, Offset(0.28f * w, 0.28f * w), Offset(0.72f * w, 0.72f * w), s.width, StrokeCap.Round)
            drawLine(color, Offset(0.72f * w, 0.28f * w), Offset(0.28f * w, 0.72f * w), s.width, StrokeCap.Round)
        }
        Ico.Gear -> {
            drawCircle(color, 0.14f * w, center, style = s)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = Offset(center.x + (0.30f * w * Math.cos(a)).toFloat(), center.y + (0.30f * w * Math.sin(a)).toFloat())
                val e = Offset(center.x + (0.40f * w * Math.cos(a)).toFloat(), center.y + (0.40f * w * Math.sin(a)).toFloat())
                drawLine(color, c, e, s.width * 1.3f, StrokeCap.Round)
            }
            drawCircle(color, 0.29f * w, center, style = s)
        }
        Ico.Search -> {
            drawCircle(color, 0.24f * w, Offset(0.44f * w, 0.44f * w), style = s)
            drawLine(color, Offset(0.62f * w, 0.62f * w), Offset(0.82f * w, 0.82f * w), s.width, StrokeCap.Round)
        }
        Ico.Folder -> drawPath(p(0.12f, 0.28f, 0.4f, 0.28f, 0.48f, 0.36f, 0.88f, 0.36f, 0.88f, 0.78f, 0.12f, 0.78f, close = true), color, style = s)
        Ico.Shelf -> {
            for (i in 0 until 4) drawRoundRect(color, Offset((0.16f + i * 0.17f) * w, 0.2f * w), Size(0.1f * w, 0.6f * w), CornerRadius(0.03f * w))
        }
        Ico.Room -> {
            drawCircle(color, 0.34f * w, center, style = s)
            drawCircle(color, 0.08f * w, center)
        }
        Ico.Expand -> {
            drawPath(p(0.56f, 0.2f, 0.8f, 0.2f, 0.8f, 0.44f), color, style = s)
            drawPath(p(0.44f, 0.8f, 0.2f, 0.8f, 0.2f, 0.56f), color, style = s)
            drawLine(color, Offset(0.8f * w, 0.2f * w), Offset(0.58f * w, 0.42f * w), s.width, StrokeCap.Round)
            drawLine(color, Offset(0.2f * w, 0.8f * w), Offset(0.42f * w, 0.58f * w), s.width, StrokeCap.Round)
        }
        Ico.Heart, Ico.HeartOn -> {
            val path = Path().apply {
                moveTo(0.5f * w, 0.82f * w)
                cubicTo(0.1f * w, 0.55f * w, 0.1f * w, 0.2f * w, 0.32f * w, 0.2f * w)
                cubicTo(0.42f * w, 0.2f * w, 0.48f * w, 0.28f * w, 0.5f * w, 0.33f * w)
                cubicTo(0.52f * w, 0.28f * w, 0.58f * w, 0.2f * w, 0.68f * w, 0.2f * w)
                cubicTo(0.9f * w, 0.2f * w, 0.9f * w, 0.55f * w, 0.5f * w, 0.82f * w)
                close()
            }
            if (ico == Ico.HeartOn) drawPath(path, color) else drawPath(path, color, style = s)
        }
        Ico.Plus -> {
            drawLine(color, Offset(0.5f * w, 0.22f * w), Offset(0.5f * w, 0.78f * w), s.width, StrokeCap.Round)
            drawLine(color, Offset(0.22f * w, 0.5f * w), Offset(0.78f * w, 0.5f * w), s.width, StrokeCap.Round)
        }
        Ico.Trash -> {
            drawLine(color, Offset(0.2f * w, 0.28f * w), Offset(0.8f * w, 0.28f * w), s.width, StrokeCap.Round)
            drawPath(p(0.28f, 0.28f, 0.33f, 0.82f, 0.67f, 0.82f, 0.72f, 0.28f), color, style = s)
            drawLine(color, Offset(0.42f * w, 0.18f * w), Offset(0.58f * w, 0.18f * w), s.width, StrokeCap.Round)
        }
        Ico.Speaker -> {
            drawPath(p(0.14f, 0.4f, 0.3f, 0.4f, 0.5f, 0.22f, 0.5f, 0.78f, 0.3f, 0.6f, 0.14f, 0.6f, close = true), color)
            drawArc(color, -45f, 90f, false, Offset(0.4f * w, 0.3f * w), Size(0.4f * w, 0.4f * w), style = s)
        }
        Ico.Sun -> {
            drawCircle(color, 0.16f * w, center)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(color,
                    Offset(center.x + (0.28f * w * Math.cos(a)).toFloat(), center.y + (0.28f * w * Math.sin(a)).toFloat()),
                    Offset(center.x + (0.38f * w * Math.cos(a)).toFloat(), center.y + (0.38f * w * Math.sin(a)).toFloat()),
                    s.width, StrokeCap.Round)
            }
        }
    }
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)
}

fun fmtLong(ms: Long): String {
    val m = ms / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
