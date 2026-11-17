package com.sebas.stalker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sebas.stalker.Engine
import com.sebas.stalker.OutputMode

@Composable
fun EqScreen() {
    val on by Engine.eqEnabled.collectAsState()
    val preset by Engine.eqPreset.collectAsState()
    val mode by Engine.outputMode.collectAsState()
    val usb by Engine.usbDevice.collectAsState()
    val available by Engine.eqAvailable.collectAsState()
    val gains = Engine.presets[preset] ?: Engine.presets.getValue("Plano")
    val blocked = mode == OutputMode.BIT_PERFECT && usb != null
    val active = on && !blocked

    CurtainSheet {
      Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ecualizador", style = Titulo, modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = { Engine.setEq(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = C.Marfil,
                    checkedTrackColor = C.Rojo,
                    uncheckedThumbColor = C.Marfil,
                    uncheckedTrackColor = C.Piedra,
                    uncheckedBorderColor = C.Piedra,
                )
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                blocked -> "En modo Bit-perfect el ecualizador se apaga para no tocar la señal. Cámbialo a Hi-Res en Sistema para usarlo."
                !available -> "Este teléfono no permite ecualizador en esta salida."
                on -> "Activo · preset $preset"
                else -> "Apagado · la señal pasa sin cambios"
            },
            style = Secundario
        )
        Spacer(Modifier.height(22.dp))

        // Curva de 10 bandas
        Row {
            Column(Modifier.width(30.dp).height(170.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("+12", "+6", "0", "-6", "-12").forEach {
                    Text(it, style = Secundario.copy(fontSize = 10.sp, textAlign = TextAlign.End), modifier = Modifier.fillMaxWidth())
                }
            }
            Canvas(Modifier.weight(1f).height(170.dp).padding(start = 6.dp)) {
                val n = gains.size
                val step = size.width / n
                val h = size.height
                val y0 = h / 2f
                for (i in 0 until n) {
                    val x = step * i + step / 2f
                    drawRoundRect(C.Linea, Offset(x - 1.5f, 0f), Size(3f, h), CornerRadius(2f, 2f))
                    val y = (12 - gains[i]) / 24f * h
                    val color = if (active) C.Rojo else C.Piedra
                    drawRect(color, Offset(x - 1.5f, minOf(y, y0)), Size(3f, kotlin.math.abs(y - y0)))
                    drawCircle(color, 9.dp.toPx(), Offset(x, y))
                    drawCircle(if (active) C.RojoClaro else C.Linea, 5.dp.toPx(), Offset(x, y))
                }
            }
        }
        Row(Modifier.padding(start = 36.dp, top = 8.dp)) {
            listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K").forEach {
                Text(it, style = Secundario.copy(fontSize = 10.sp, textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("Presets", style = Cuerpo.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(10.dp))
        Engine.presets.keys.toList().chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    Pill(name, selected = name == preset, modifier = Modifier.weight(1f)) {
                        Engine.setPreset(name)
                        if (!on) Engine.setEq(true)
                    }
                }
            }
        }
      }
    }
}
