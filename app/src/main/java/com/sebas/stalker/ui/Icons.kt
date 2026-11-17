package com.sebas.stalker.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Íconos de línea propios (24×24), en el estilo del diseño. */
object Ico {
    private fun build(name: String, vararg parts: Pair<String, Boolean>): ImageVector {
        val b = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for ((d, stroke) in parts) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (stroke) {
                b.addPath(
                    pathData = nodes,
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            } else {
                b.addPath(pathData = nodes, fill = SolidColor(Color.Black))
            }
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy} a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    val Play = build("play", "M8 5v14l11-7z" to false)
    val Pause = build("pause", "M6 5h4v14H6z M14 5h4v14h-4z" to false)
    val Prev = build("prev", "M19 6v12l-10-6z M5 6h2.4v12H5z" to false)
    val Next = build("next", "M5 6v12l10-6z M16.6 6H19v12h-2.4z" to false)
    val Shuffle = build("shuffle", "M4 7h3l10 10h3M4 17h3l3-3M14 10l3-3h3M18 5l2 2-2 2M18 15l2 2-2 2" to true)
    val Repeat = build("repeat", "M5 11V9a2 2 0 0 1 2-2h12l-3-3M19 13v2a2 2 0 0 1-2 2H5l3 3" to true)
    val RepeatOne = build(
        "repeatOne",
        "M5 11V9a2 2 0 0 1 2-2h12l-3-3M19 13v2a2 2 0 0 1-2 2H5l3 3" to true,
        "M11.5 10.5l1-0.5v4" to true,
    )
    val Down = build("down", "M6 9l6 6 6-6" to true)
    val Back = build("back", "M15 6l-6 6 6 6" to true)
    val Right = build("right", "M9 6l6 6-6 6" to true)
    val Library = build("library", "M9.5 7h8a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5h-8A2.5 2.5 0 0 1 7 17.5v-8A2.5 2.5 0 0 1 9.5 7z M4 16V6.5A2.5 2.5 0 0 1 6.5 4H16" to true)
    val Sliders = build(
        "sliders",
        "M6 4v16M12 4v16M18 4v16" to true,
        "M4 13h4v3H4z M10 7h4v3h-4z M16 11h4v3h-4z" to false,
    )
    val Gear = build("gear", circle(12f, 12f, 3f) + " M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1" to true)
    val Headphones = build("headphones", "M4 15v-3a8 8 0 0 1 16 0v3 M3 14h4v6H3z M17 14h4v6h-4z" to true)
    val Wave = build("wave", "M3 12h1M7 9v6M10 6v12M13 8v8M16 10v4M19 12h2" to true)
    val Refresh = build("refresh", "M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5" to true)
    val Layers = build("layers", "M12 3l9 5-9 5-9-5z M3 12l9 5 9-5M3 16l9 5 9-5" to true)
}
