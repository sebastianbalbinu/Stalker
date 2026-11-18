package stalker

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

object Res {
    val telon: ImageBitmap by lazy { useResource("telon.jpg") { loadImageBitmap(it) } }
    val zigzag: ImageBitmap by lazy { useResource("zigzag.jpg") { loadImageBitmap(it) } }
    val logo: ImageBitmap by lazy { useResource("logo.png") { loadImageBitmap(it) } }
}

/** Shaders de la sala: el piso en zigzag en perspectiva y la luz que respira sobre el telón. */
object Shaders {
    private const val FLOOR = """
uniform float2 res;
uniform float horizon;
uniform float2 pool;
half4 main(float2 p) {
    float y = p.y - horizon;
    if (y <= 0.5) return half4(0.0);
    float depth = res.y * 0.55 / y;
    float u = (p.x - res.x * 0.5) * depth / res.y;
    float v = depth;
    float freq = 2.2;
    float f2 = 1.4;
    float amp = 0.9;
    float s = v * freq + abs(fract(u * f2) - 0.5) * amp;
    float tri = abs(fract(s) - 0.5) * 2.0;
    float aa = (freq * res.y * 0.55 / (y * y) + amp * f2 * depth / res.y) * 1.2 + 0.0001;
    float st = smoothstep(0.5 - aa, 0.5 + aa, tri);
    float3 col = mix(float3(0.05, 0.04, 0.04), float3(0.86, 0.80, 0.71), st);
    float fog = smoothstep(1.8, 9.0, depth);
    col = mix(col, float3(0.07, 0.008, 0.008), fog);
    float2 d = float2(p.x / res.x - pool.x, p.y / res.y - pool.y);
    float light = exp(-(d.x * d.x * 4.0 + d.y * d.y * 10.0));
    col *= 0.32 + 0.78 * light;
    float edge = smoothstep(0.0, 6.0, y);
    return half4(half3(col * edge), half(edge));
}
"""

    private const val LIGHT = """
uniform float2 res;
uniform float t;
uniform float horizon;
half4 main(float2 p) {
    float2 uv = p / res;
    if (p.y > horizon) return half4(0.0);
    float band = sin(uv.x * 34.0 + sin(uv.y * 2.6 + t * 0.32) * 1.7 + t * 0.21) * 0.5 + 0.5;
    float sheen = pow(band, 7.0) * 0.07 * (1.0 - uv.y * 0.5);
    float2 d = (uv - float2(0.40, 0.02)) * float2(1.1, 0.75);
    float spot = exp(-dot(d, d) * 3.2) * 0.16;
    float n = fract(sin(dot(floor(p) + floor(t * 24.0) * 7.31, float2(12.9898, 78.233))) * 43758.5453);
    float grain = (n - 0.5) * 0.035;
    float3 c = float3(1.0, 0.82, 0.68) * (sheen + spot) + grain;
    c = max(c, float3(0.0));
    return half4(half3(c), 1.0);
}
"""

    val floor: RuntimeEffect? by lazy { try { RuntimeEffect.makeForShader(FLOOR) } catch (t: Throwable) { System.err.println(t); null } }
    val light: RuntimeEffect? by lazy { try { RuntimeEffect.makeForShader(LIGHT) } catch (t: Throwable) { System.err.println(t); null } }
}

fun DrawScope.drawCover(img: ImageBitmap, alpha: Float = 1f, topLeft: Offset = Offset.Zero, w: Float = size.width, h: Float = size.height, anchorY: Float = 0.5f) {
    val sr = img.width.toFloat() / img.height
    val dr = w / h
    val (sw, sh) = if (sr > dr) (img.height * dr) to img.height.toFloat() else img.width.toFloat() to (img.width / dr)
    val sx = (img.width - sw) / 2
    val sy = (img.height - sh) * anchorY
    drawImage(
        img,
        srcOffset = IntOffset(sx.toInt(), sy.toInt()),
        srcSize = IntSize(sw.toInt().coerceAtLeast(1), sh.toInt().coerceAtLeast(1)),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}

/**
 * La red room: telón al fondo, piso en zigzag hacia el horizonte, una luz cálida
 * que cae sobre el tocadiscos y grano de película. [horizon] es la fracción de alto.
 */
@Composable
fun RoomBackdrop(modifier: Modifier, horizon: Float = 0.66f, pool: Offset = Offset(0.4f, 0.86f), dim: Float = 0f, animate: Boolean = true) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) t += ((n - last) / 1e9f).coerceAtMost(0.1f)
                last = n
            }
            kotlinx.coroutines.delay(40) // la luz respira lento: no hace falta 60 fps
        }
    }
    val floorFx = Shaders.floor
    val lightFx = Shaders.light
    Canvas(modifier) {
        val hz = size.height * horizon
        drawRect(C.Sombra)
        // telón
        drawCover(Res.telon, topLeft = Offset.Zero, w = size.width, h = hz + 2, anchorY = 0.35f)
        drawRect(Brush.verticalGradient(0f to Color(0x55000000), 0.55f to Color(0x00000000), 1f to Color(0xCC000000), endY = hz))
        // piso
        if (floorFx != null) {
            val b = RuntimeShaderBuilder(floorFx)
            b.uniform("res", size.width, size.height)
            b.uniform("horizon", hz)
            b.uniform("pool", pool.x, pool.y)
            drawRect(ShaderBrush(b.makeShader()))
        } else {
            drawCover(Res.zigzag, topLeft = Offset(0f, hz), w = size.width, h = size.height - hz)
            drawRect(Brush.verticalGradient(listOf(Color(0xEE000000), Color(0x55000000)), startY = hz, endY = size.height), topLeft = Offset(0f, hz))
        }
        // sombra donde el telón toca el piso
        drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xDD000000), Color(0x00000000)), startY = hz - size.height * 0.05f, endY = hz + size.height * 0.04f))
        // luz y grano
        if (lightFx != null) {
            val b = RuntimeShaderBuilder(lightFx)
            b.uniform("res", size.width, size.height)
            b.uniform("t", t)
            b.uniform("horizon", hz)
            drawRect(ShaderBrush(b.makeShader()), blendMode = BlendMode.Screen)
        }
        // viñeta
        drawRect(Brush.radialGradient(
            0f to Color(0x00000000), 0.62f to Color(0x22000000), 1f to Color(0xDD000000),
            center = Offset(size.width * pool.x, size.height * 0.55f), radius = size.maxDimension * 0.75f,
        ))
        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
    }
}
