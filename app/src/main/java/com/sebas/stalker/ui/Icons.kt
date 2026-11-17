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
    val Plus = build("plus", "M12 5v14M5 12h14" to true)
    val Heart = build("heart", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" to true)
    val HeartFull = build("heartFull", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" to false)
    val Search = build("search", circle(11f, 11f, 6.5f) + " M16 16l4.5 4.5" to true)
    val Close = build("close", "M6 6l12 12M18 6L6 18" to true)
    val ListIco = build("list", "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" to true)
    val More = build("more", circle(6f, 12f, 1.6f) + " " + circle(12f, 12f, 1.6f) + " " + circle(18f, 12f, 1.6f) to false)
    val Check = build("check", "M5 12.5l4.5 4.5L19 7.5" to true)
    val Trash = build("trash", "M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13" to true)
    val Hide = build("hide", "M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 9 6 9 6a15.7 15.7 0 0 1-2.6 3.2M6.3 7.6C4.2 9.1 3 12 3 12s4 6 9 6a8.6 8.6 0 0 0 3.9-.9" to true)
    val Disc = build("disc", circle(12f, 12f, 8.5f) + " " + circle(12f, 12f, 2.5f) to true)
    val Pencil = build("pencil", "M4 20h4L19 9l-4-4L4 16z M13.5 6.5l4 4" to true)
    val Moon = build("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" to true)
    val Lyrics = build("lyrics", "M4 6h16M4 10h16M4 14h10M4 18h7 M17 14v6 M17 14l3-1" to true)
    val Chart = build("chart", "M4 20V10M10 20V4M16 20v-7M21 20H3" to true)
    val Upload = build("upload", "M12 15V4M7.5 8.5L12 4l4.5 4.5M5 15v4h14v-4" to true)
    val Download = build("download", "M12 4v11M7.5 10.5L12 15l4.5-4.5M5 15v4h14v-4" to true)
    val PlayNext = build("playNext", "M4 6h10M4 11h10M4 16h6 M15 14v6l5-3z" to true)
    val QueueAdd = build("queueAdd", "M4 6h12M4 11h12M4 16h7 M18 13v6M15 16h6" to true)
    val Volume = build("volume", "M4 9h4l5-4v14l-5-4H4z M16.5 8.5a5 5 0 0 1 0 7" to true)
    val Bag = build("bag", "M5 8h14l-1.2 12H6.2z M9 8V6.5a3 3 0 0 1 6 0V8" to true)
    val Bluetooth = build("bt", "M7 7l10 10-5 4V3l5 4L7 17" to true)
    val Up = build("up", "M6 15l6-6 6 6" to true)
    val Layers = build("layers", "M12 3l9 5-9 5-9-5z M3 12l9 5 9-5M3 16l9 5 9-5" to true)
}
