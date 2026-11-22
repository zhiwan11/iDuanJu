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

/**
 * 漫画超分「标准」: instant (API 33+) single-pass AGSL RenderEffect porting the Anime4K `Deblur_DoG` restore plus an
 * Anime4K edge-directed push, applied in on-screen pixels.
 *
 * Strictly colour-preserving, exactly like Anime4K itself: only a *luma delta* is computed and it is added equally
 * to R, G and B (`rgb + Δ`), so hue, saturation, paper tone and overall brightness of the page are untouched — no
 * levels stretch, no line darkening, no tinting. Flat areas (paper, screen-tone fields, JPEG grain below the noise
 * threshold) get exactly Δ = 0; only edge slopes are steepened. Every result is clamped to the local 3×3 luma range
 * of the original with a small soft margin, so there is no ringing / white halo around lines and text.
 *
 *  1. DoG deblur: Δ = curve(mid − Gauss5×5) where curve is Anime4K's power curve (noise gate, then x^0.6 lift of
 *     soft slopes), so blurry lines and small text tighten visibly while already-sharp edges are left alone.
 *  2. Edge push: on real edges (Sobel-gated) the deblurred value is pulled toward its nearer local extremum, which
 *     thins the soft anti-aliased fringe of ink lines without moving them.
 * The kernel footprint scales with the on-screen magnification of the page (low-res scans shown enlarged have
 * wider blur), never with the layer size.
 */
@Composable
fun Modifier.mangaSuperRes(
    level: Int,
    contentSize: IntSize = IntSize.Zero,
    fillWidth: Boolean = false,
    viewSize: IntSize = IntSize.Zero,
): Modifier {
    if (Build.VERSION.SDK_INT < 33 || level <= 0) return this
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val size = if (measuredSize.width > 0 && measuredSize.height > 0) measuredSize else viewSize
    val scale = superResScale(size, contentSize, fillWidth)
    val effect = remember(size.width > 0 && size.height > 0, scale) {
        if (size.width < 1 || size.height < 1) null
        else runCatching { // AGSL compilation can fail on broken drivers: fall back to the plain page
            val k = ((scale - 1f) / 1.2f).coerceIn(0f, 1f) // 0 = shown ≤ 1:1, 1 = enlarged ≥ 2.2×
            val shader = RuntimeShader(MANGA_RESTORE_SHADER)
            shader.setFloatUniform("uStep", scale.coerceIn(1f, 2.5f))
            shader.setFloatUniform("uDeblur", 1.05f + 0.45f * k)
            shader.setFloatUniform("uPush", 0.38f + 0.22f * k)
            RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
        }.getOrNull()
    }
    return onSizeChanged { measuredSize = it }.graphicsLayer { renderEffect = effect }
}

/** On-screen magnification of a [content]-sized bitmap fitted (or width-filled) into [layout]; 1 when unknown. */
private fun superResScale(layout: IntSize, content: IntSize, fillWidth: Boolean): Float {
    if (layout.width < 1 || layout.height < 1 || content.width < 1 || content.height < 1) return 1f
    val sx = layout.width.toFloat() / content.width
    val sy = layout.height.toFloat() / content.height
    val s = if (fillWidth) sx else minOf(sx, sy)
    return (kotlin.math.round(s * 8f) / 8f) // quantised so tiny size jitter never recompiles the effect
}

/** Anime4K DoG deblur + edge-directed push; adds one luma delta to R, G and B (hue / paper tone preserved). */
private const val MANGA_RESTORE_SHADER = """
uniform shader img;
uniform float uStep;
uniform float uDeblur;
uniform float uPush;

const half3 LUMA = half3(0.299, 0.587, 0.114);
const half NOISE_T = 0.002;
const half BLUR_T = 0.10;
const half CURVE = 0.6;

half lumaAt(float2 p, half centre) {
    half4 c = img.eval(p);
    // transparent letterbox around a fitted page reads as the centre pixel: no edge against "nothing"
    return c.a > 0.004 ? dot(c.rgb, LUMA) / c.a : centre;
}

half4 main(float2 pos) {
    half4 base = img.eval(pos);
    if (base.a < 0.004) return base;
    half mid = dot(base.rgb, LUMA) / base.a;

    half blur = 0.0; half wsum = 0.0;
    half mn = mid; half mx = mid;
    half gx = 0.0; half gy = 0.0;
    for (float j = -2.0; j <= 2.0; j += 1.0) {
        for (float i = -2.0; i <= 2.0; i += 1.0) {
            half l = (i == 0.0 && j == 0.0) ? mid : lumaAt(pos + float2(i, j) * uStep, mid);
            half wgt = half(exp(-0.5 * (i * i + j * j)));
            blur += l * wgt; wsum += wgt;
            if (abs(i) <= 1.0 && abs(j) <= 1.0) {
                mn = min(mn, l); mx = max(mx, l);
                gx += l * half(i * (2.0 - abs(j)));
                gy += l * half(j * (2.0 - abs(i)));
            }
        }
    }
    blur /= wsum;

    // 1. Anime4K Deblur_DoG power curve
    half c = (mid - blur) * half(uDeblur);
    half ac = abs(c);
    half ct = c;
    if (ac > NOISE_T) {
        half t = (ac - NOISE_T) / (BLUR_T - NOISE_T);
        ct = (pow(t, CURVE) * (BLUR_T - NOISE_T) + NOISE_T) * sign(c);
    } else {
        ct = 0.0; // flat paper / tone / grain: untouched
    }
    half margin = min(0.02, 0.08 * (mx - mn));
    half lo = mn - margin; half hi = mx + margin;
    half v = clamp(mid + ct, lo, hi);

    // 2. edge-directed push toward the nearer local extremum on real edges only
    half grad = sqrt(gx * gx + gy * gy) * 0.25;
    half gate = smoothstep(0.05, 0.28, grad);
    half nearest = (v - mn < mx - v) ? mn : mx;
    v = clamp(mix(v, nearest, half(uPush) * gate), lo, hi);

    half delta = v - mid;
    return half4(clamp(base.rgb + delta * base.a, 0.0, base.a), base.a);
}
"""
