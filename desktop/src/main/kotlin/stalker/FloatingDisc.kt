package stalker

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment

/**
 * Al minimizar Stalker queda un vinilo girando que flota sobre todo.
 * Al pasar el mouse aparecen anterior / pausa / siguiente; doble clic vuelve a la sala.
 */
@Composable
fun FloatingDisc(app: AppState, icon: BitmapPainter, onRestore: () -> Unit) {
    val p = app.player
    val pos = remember {
        val b = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val x = Prefs.floatX.takeIf { it >= 0 } ?: (b.x + b.width - 270)
        val y = Prefs.floatY.takeIf { it >= 0 } ?: (b.y + b.height - 330)
        WindowPosition(x.dp, y.dp)
    }
    val st = rememberWindowState(size = DpSize(232.dp, 290.dp), position = pos)
    Window(
        onCloseRequest = onRestore,
        state = st,
        title = "Stalker",
        icon = icon,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        DisposableEffect(Unit) {
            onDispose {
                Prefs.floatX = window.x
                Prefs.floatY = window.y
            }
        }
        val t = p.current
        val art = rememberArt(t?.path)
        val src = remember { MutableInteractionSource() }
        val hovered by src.collectIsHoveredAsState()
        val h = animateFloatAsState(if (hovered) 1f else 0f, tween(220))
        Column(Modifier.fillMaxSize().hoverable(src), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.padding(top = 10.dp).size(212.dp), contentAlignment = Alignment.Center) {
                // sombra bajo el disco
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0x66000000), size.minDimension * 0.47f, center + Offset(0f, 7f))
                    drawCircle(Color(0x33000000), size.minDimension * 0.5f, center + Offset(0f, 10f))
                }
                // se arrastra desde el disco
                WindowDraggableArea(Modifier.size(196.dp).clip(CircleShape)) {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            var last = 0L
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Press) {
                                    val now = System.currentTimeMillis()
                                    if (now - last < 380) onRestore()
                                    last = now
                                }
                            }
                        }
                    }) {
                        MiniVinyl(art, p.playing, Modifier.fillMaxSize())
                    }
                }
                // anillo de progreso alrededor del disco
                Canvas(Modifier.size(206.dp)) {
                    val dur = t?.durationMs ?: 0
                    val f = if (dur > 0) (p.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val sw = 2.5.dp.toPx()
                    drawArc(Color(0x33F1DFC4), 0f, 360f, false, Offset(sw, sw), Size(size.width - sw * 2, size.height - sw * 2), style = Stroke(sw))
                    drawArc(C.RojoVivo, -90f, 360f * f, false, Offset(sw, sw), Size(size.width - sw * 2, size.height - sw * 2), style = Stroke(sw, cap = StrokeCap.Round))
                }
                // controles al pasar el mouse
                if (hovered) {
                    Box(
                        Modifier.size(196.dp).graphicsLayer { alpha = h.value }.clip(CircleShape).background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RoundButton(Ico.Prev, { p.prev() }, 40.dp, 15.dp, hoverBg = Color(0x40F1DFC4))
                            RoundButton(if (p.playing) Ico.Pause else Ico.Play, { p.toggle() }, 52.dp, 19.dp, tint = C.Sombra, filled = C.Crema)
                            RoundButton(Ico.Next, { p.next() }, 40.dp, 15.dp, hoverBg = Color(0x40F1DFC4))
                        }
                        RoundButton(Ico.Expand, onRestore, 30.dp, 12.dp, tint = C.Tenue, modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (t != null) {
                Column(
                    Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xE60E0907))
                        .border(1.dp, Color(0x26F1DFC4), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.foundation.text.BasicText(t.title, style = txt(12.sp, FontWeight.SemiBold).copy(textAlign = TextAlign.Center), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    androidx.compose.foundation.text.BasicText(t.artist, style = txt(10.sp, color = C.Tenue).copy(textAlign = TextAlign.Center), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

