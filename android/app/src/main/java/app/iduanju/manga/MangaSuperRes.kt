package app.iduanju.manga

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 漫画超分「标准」: instant API 33+ AGSL pass, applied as a RenderEffect on the page (zero latency, no bitmaps).
 *
 * Why the previous pass looked like a no-op: its contrast-adaptive gain bottomed out exactly on black-ink-on-white
 * edges (headroom = 0 → 0.65×), a hard min/max clamp left ≤ 3 % overshoot, and a fixed 1 px kernel ran in *screen*
 * pixels — so on a low-res page drawn 1.5–2× enlarged (blur spread over 2–3 px) it touched nothing visible.
 *
 * Now the kernel radius and strength follow the real magnification of the page ([contentSize] → layout size): a
 * two-scale unsharp mask (1 px + R px rings) gated to real edges (flat paper / JPEG noise untouched) and clamped to
 * the local min/max (zero overshoot ⇒ zero halo), then ink / paper levels that deepen grey ink and clean the grey
 * paper of speech bubbles. Below Android 13 [mangaInkLevels] gives the levels part as a colour matrix.
 */
@Composable
fun Modifier.mangaSuperRes(level: Int, contentSize: IntSize = IntSize.Zero, fillWidth: Boolean = false): Modifier {
    if (Build.VERSION.SDK_INT < 33 || level <= 0) return this
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = superResScale(size, contentSize, fillWidth)
    val effect = remember(size, scale) {
        if (size.width < 1 || size.height < 1) null
        else runCatching { // AGSL compilation can fail on broken drivers: fall back to the plain page
            val k = ((scale - 1f) / 0.8f).coerceIn(0f, 1f) // 0 = page shown at ≤ 1:1, 1 = enlarged ≥ 1.8×
            val shader = RuntimeShader(MANGA_SR_SHADER)
            shader.setFloatUniform("uSize", size.width.toFloat(), size.height.toFloat())
            shader.setFloatUniform("uRadius", scale.roundToInt().coerceIn(1, 3).toFloat())
            shader.setFloatUniform("uAmount", 0.45f + 0.4f * k)
            shader.setFloatUniform("uInk", 0.45f + 0.25f * k)
            RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
        }.getOrNull()
    }
    return onSizeChanged { size = it }.graphicsLayer { renderEffect = effect }
}

/** On-screen magnification of a [content]-sized bitmap fitted (or width-filled) into [layout]; 1 when unknown. */
private fun superResScale(layout: IntSize, content: IntSize, fillWidth: Boolean): Float {
    if (layout.width < 1 || layout.height < 1 || content.width < 1 || content.height < 1) return 1f
    val sx = layout.width.toFloat() / content.width
    val sy = layout.height.toFloat() / content.height
    return if (fillWidth) sx else minOf(sx, sy)
}

/** Ink / paper levels for the 标准 fallback below Android 13 (the AGSL pass applies its own). */
fun mangaInkLevels(): FloatArray {
    val black = 0.04f; val white = 0.96f
    val gain = 1f / (white - black)
    val off = -black * 255f * gain
    return floatArrayOf(gain, 0f, 0f, 0f, off, 0f, gain, 0f, 0f, off, 0f, 0f, gain, 0f, off, 0f, 0f, 0f, 1f, 0f)
}

private const val MANGA_SR_SHADER = """
uniform shader img;
uniform float2 uSize;
uniform float uRadius;
uniform float uAmount;
uniform float uInk;

const half3 LUMA = half3(0.2126, 0.7152, 0.0722);

float2 lo() { return float2(0.5); }
float2 hi() { return uSize - 0.5; }

// Luma of a neighbour; transparent letterbox pixels read as the centre so page edges never get a rim.
half lumaAt(float2 p, half centre) {
    half4 c = img.eval(clamp(p, lo(), hi()));
    return dot(c.rgb, LUMA) + (1.0 - c.a) * centre;
}

half4 main(float2 pos) {
    float2 p = clamp(pos, lo(), hi());
    half4 base = img.eval(p);
    if (base.a == 0.0) return base;
    half mid = dot(base.rgb, LUMA) / base.a;

    // 1 px ring: 3x3 gaussian + local range
    half a0 = lumaAt(p + float2(-1.0, -1.0), mid); half a1 = lumaAt(p + float2(0.0, -1.0), mid); half a2 = lumaAt(p + float2(1.0, -1.0), mid);
    half a3 = lumaAt(p + float2(-1.0, 0.0), mid);                                            half a5 = lumaAt(p + float2(1.0, 0.0), mid);
    half a6 = lumaAt(p + float2(-1.0, 1.0), mid);  half a7 = lumaAt(p + float2(0.0, 1.0), mid);  half a8 = lumaAt(p + float2(1.0, 1.0), mid);
    half blur = (a0 + a2 + a6 + a8 + 2.0 * (a1 + a3 + a5 + a7) + 4.0 * mid) / 16.0;
    half mn = min(mid, min(min(min(a0, a1), min(a2, a3)), min(min(a5, a6), min(a7, a8))));
    half mx = max(mid, max(max(max(a0, a1), max(a2, a3)), max(max(a5, a6), max(a7, a8))));

    // R px ring for pages drawn enlarged (their edges are spread over R screen pixels)
    if (uRadius > 1.5) {
        float r = uRadius;
        half b0 = lumaAt(p + float2(-r, -r), mid); half b1 = lumaAt(p + float2(0.0, -r), mid); half b2 = lumaAt(p + float2(r, -r), mid);
        half b3 = lumaAt(p + float2(-r, 0.0), mid);                                           half b5 = lumaAt(p + float2(r, 0.0), mid);
        half b6 = lumaAt(p + float2(-r, r), mid);  half b7 = lumaAt(p + float2(0.0, r), mid);  half b8 = lumaAt(p + float2(r, r), mid);
        half blurR = (b0 + b2 + b6 + b8 + 2.0 * (b1 + b3 + b5 + b7) + 4.0 * mid) / 16.0;
        blur = 0.5 * blur + 0.5 * blurR;
        mn = min(mn, min(min(min(b0, b1), min(b2, b3)), min(min(b5, b6), min(b7, b8))));
        mx = max(mx, max(max(max(b0, b1), max(b2, b3)), max(max(b5, b6), max(b7, b8))));
    }

    // edge-gated unsharp mask, clamped to the local range: crisp lines, no halo, flat paper / JPEG noise untouched
    half span = mx - mn;
    half sharp = clamp(mid + (mid - blur) * half(uAmount) * 2.0 * smoothstep(0.025, 0.1, span), mn, mx);

    // ink / paper levels: deeper ink, cleaner speech-bubble paper
    half black = 0.07 * half(uInk);
    half white = 1.0 - black;
    half leveled = clamp((sharp - black) / (white - black), 0.0, 1.0);

    half delta = leveled - mid;
    return half4(clamp(base.rgb + delta * base.a, 0.0, base.a), base.a);
}
"""
