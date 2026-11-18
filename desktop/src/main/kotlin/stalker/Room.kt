package stalker

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
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
    val woodBack: ImageBitmap by lazy { useResource("madera_fondo.jpg") { loadImageBitmap(it) } }
    val plank: ImageBitmap by lazy { useResource("madera_repisa.jpg") { loadImageBitmap(it) } }
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
 *
 * Todo se pinta UNA vez en una imagen (se rehace solo si cambia el tamaño). Lo único que
 * se mueve es un brillo suave sobre el telón, y solo mientras suena la música: así la
 * ventana queda quieta y el PC descansa cuando no hace falta animar.
 */
@Composable
fun RoomBackdrop(modifier: Modifier, horizon: Float = 0.66f, pool: Offset = Offset(0.4f, 0.86f), dim: Float = 0f, animate: Boolean = true) {
    Box(modifier) {
        Spacer(Modifier.fillMaxSize().drawWithCache {
            val img = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(img), size) {
                drawRoomStatic(horizon, pool, dim)
            }
            onDrawBehind { drawImage(img) }
        })
        if (animate && !Prefs.lite) CurtainSheen(Modifier.fillMaxSize(), horizon)
    }
}

/** Brillo que recorre los pliegues del telón: una imagen pintada una vez que solo se desliza. */
@Composable
private fun CurtainSheen(modifier: Modifier, horizon: Float) {
    val t = rememberInfiniteTransition()
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Reverse))
    Box(modifier.graphicsLayer { clip = true }) {
        Spacer(Modifier.fillMaxSize().graphicsLayer {
            translationX = (x - 0.5f) * size.width * 0.18f
            alpha = 0.9f
        }.drawWithCache {
            val hz = size.height * horizon
            val bands = listOf(0.12f, 0.31f, 0.47f, 0.66f, 0.83f)
            onDrawBehind {
                for ((i, b) in bands.withIndex()) {
                    val cx = size.width * b
                    val bw = size.width * (0.05f + (i % 2) * 0.03f)
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color(0x00FFD9B8), Color(0x16FFD9B8), Color(0x00FFD9B8)),
                            startX = cx - bw, endX = cx + bw,
                        ),
                        topLeft = Offset(cx - bw, 0f), size = Size(bw * 2, hz),
                        blendMode = BlendMode.Screen,
                    )
                }
            }
        })
    }
}

private fun DrawScope.drawRoomStatic(horizon: Float, pool: Offset, dim: Float) {
    val floorFx = Shaders.floor
    val lightFx = Shaders.light
    val hz = size.height * horizon
    drawRect(C.Sombra)
    drawCover(Res.telon, topLeft = Offset.Zero, w = size.width, h = hz + 2, anchorY = 0.35f)
    drawRect(Brush.verticalGradient(0f to Color(0x55000000), 0.55f to Color(0x00000000), 1f to Color(0xCC000000), endY = hz))
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
    drawRect(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xDD000000), Color(0x00000000)), startY = hz - size.height * 0.05f, endY = hz + size.height * 0.04f))
    if (lightFx != null) {
        val b = RuntimeShaderBuilder(lightFx)
        b.uniform("res", size.width, size.height)
        b.uniform("t", 7.0f)
        b.uniform("horizon", hz)
        drawRect(ShaderBrush(b.makeShader()), blendMode = BlendMode.Screen)
    }
    drawRect(Brush.radialGradient(
        0f to Color(0x00000000), 0.62f to Color(0x22000000), 1f to Color(0xDD000000),
        center = Offset(size.width * pool.x, size.height * 0.55f), radius = size.maxDimension * 0.75f,
    ))
    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
}
