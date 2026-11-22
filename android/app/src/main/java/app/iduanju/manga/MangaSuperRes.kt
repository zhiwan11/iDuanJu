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
 * 漫画超分「标准」: instant API 33+ two-pass AGSL RenderEffect implementing an Anime4K restoration pipeline
 * (`Anime4K_Deblur_DoG` + `Anime4K_Upscale_Original` edge push, followed by `Anime4K_Darken` + paper/ink levels).
 *
 * Why the previous single-pass shader looked like a no-op:
 * 1. Its 3×3 unsharp mask was clamped strictly to local `[mn, mx]`, which clamped stroke cores (`mid == mn`) and paper
 *    borders (`mid == mx`) right back to `mid` — literally zero change at line centers and edges.
 * 2. When pages fit the screen (`scale <= 1`), `uAmount` fell to `0.45` and `uInk` to `0.45` (`3%` levels shift).
 *
 * Now Pass 1 runs Anime4K DoG Deblur (non-linear power curve `0.55` on a 5×5 DoG footprint) + Sobel edge-directed
 * sharpening, and Pass 2 runs Anime4K Line Darken (deepening washed-out ink lines and small text without white halos)
 * plus clean paper/ink levels. Below Android 13 [mangaInkLevels] gives the levels part via colour matrix.
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
    val effect = remember(size, scale) {
        if (size.width < 1 || size.height < 1) null
        else runCatching { // AGSL compilation can fail on broken drivers: fall back to the plain page
            val k = ((scale - 1f) / 0.8f).coerceIn(0f, 1f) // 0 = ≤ 1:1, 1 = enlarged ≥ 1.8×
            val radius = scale.coerceIn(1f, 2.5f)

            val deblur = RuntimeShader(MANGA_DEBLUR_SHADER)
            deblur.setFloatUniform("uRadius", radius)
            deblur.setFloatUniform("uDeblur", 0.95f + 0.35f * k)
            deblur.setFloatUniform("uRefine", 0.24f + 0.16f * k)
            val deblurEffect = RenderEffect.createRuntimeShaderEffect(deblur, "img")

            val darken = RuntimeShader(MANGA_DARKEN_LEVELS_SHADER)
            darken.setFloatUniform("uRadius", radius)
            darken.setFloatUniform("uDarken", 0.85f + 0.30f * k)
            darken.setFloatUniform("uBlack", 0.05f)
            darken.setFloatUniform("uWhite", 0.95f)
            val darkenEffect = RenderEffect.createRuntimeShaderEffect(darken, "img")

            RenderEffect.createChainEffect(darkenEffect, deblurEffect).asComposeRenderEffect()
        }.getOrNull()
    }
    return onSizeChanged { measuredSize = it }.graphicsLayer { renderEffect = effect }
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
    val black = 0.05f; val white = 0.95f
    val gain = 1f / (white - black)
    val off = -black * 255f * gain
    return floatArrayOf(gain, 0f, 0f, 0f, off, 0f, gain, 0f, 0f, off, 0f, 0f, gain, 0f, off, 0f, 0f, 0f, 1f, 0f)
}

/** Pass 1: Anime4K DoG Deblur + Sobel edge-directed refinement (no coordinate clamp). */
private const val MANGA_DEBLUR_SHADER = """
uniform shader img;
uniform float uRadius;
uniform float uDeblur;
uniform float uRefine;

const half3 LUMA = half3(0.299, 0.587, 0.114);

half lumaAt(float2 p, half centre) {
    half4 c = img.eval(p);
    return dot(c.rgb, LUMA) + (1.0 - c.a) * centre;
}

half4 main(float2 pos) {
    half4 base = img.eval(pos);
    if (base.a == 0.0) return base;
    half mid = dot(base.rgb, LUMA) / base.a;

    // 3x3 ring at 1 px: inner Gaussian, local [mn, mx], and Sobel gradient
    half a0 = lumaAt(pos + float2(-1.0, -1.0), mid); half a1 = lumaAt(pos + float2(0.0, -1.0), mid); half a2 = lumaAt(pos + float2(1.0, -1.0), mid);
    half a3 = lumaAt(pos + float2(-1.0,  0.0), mid);                                                 half a5 = lumaAt(pos + float2(1.0,  0.0), mid);
    half a6 = lumaAt(pos + float2(-1.0,  1.0), mid); half a7 = lumaAt(pos + float2(0.0,  1.0), mid); half a8 = lumaAt(pos + float2(1.0,  1.0), mid);

    half mn = min(mid, min(min(min(a0, a1), min(a2, a3)), min(min(a5, a6), min(a7, a8))));
    half mx = max(mid, max(max(max(a0, a1), max(a2, a3)), max(max(a5, a6), max(a7, a8))));
    half blur3 = (a0 + a2 + a6 + a8 + 2.0 * (a1 + a3 + a5 + a7) + 4.0 * mid) / 16.0;

    // Outer cardinal taps scaled to the page's on-screen magnification for wide blur capture
    float r = uRadius * 1.75;
    half o1 = lumaAt(pos + float2(0.0, -r), mid);
    half o3 = lumaAt(pos + float2(-r, 0.0), mid);
    half o5 = lumaAt(pos + float2(r, 0.0), mid);
    half o7 = lumaAt(pos + float2(0.0, r), mid);
    half blurWide = 0.55 * blur3 + 0.45 * (0.25 * (o1 + o3 + o5 + o7));

    // Anime4K DoG Deblur power curve (lifts soft edge slopes, ignores flat-paper JPEG grain)
    half c = (mid - blurWide) * half(uDeblur);
    half ac = abs(c);
    half noiseT = 0.008;
    half blurT = 0.14;
    if (ac > noiseT) {
        half t = clamp((ac - noiseT) / (blurT - noiseT), 0.0, 1.0);
        half boosted = pow(max(t, 0.0001), 0.55) * (blurT - noiseT) + noiseT;
        c = (ac < blurT ? boosted : ac) * sign(c);
    } else {
        c = 0.0;
    }
    half deblurred = clamp(mid + c, mn, mx);

    // Anime4K-style edge-directed push: tighten blurry slopes toward nearer extremum within [mn, mx]
    half gx = (a2 + 2.0 * a5 + a8) - (a0 + 2.0 * a3 + a6);
    half gy = (a6 + 2.0 * a7 + a8) - (a0 + 2.0 * a1 + a2);
    half grad = sqrt(gx * gx + gy * gy) * 0.25;
    half edgeGate = smoothstep(0.14, 0.55, grad);
    half nearest = (deblurred - mn < mx - deblurred) ? mn : mx;
    half target = mix(deblurred, nearest, half(uRefine) * edgeGate);

    half delta = target - mid;
    half3 rgb = (delta < 0.0 && mid > 0.01)
        ? base.rgb * (target / mid)
        : base.rgb + delta * base.a;
    return half4(clamp(rgb, 0.0, base.a), base.a);
}
"""

/** Pass 2: Anime4K Line Darken + clean manga paper/ink levels (color-preserving). */
private const val MANGA_DARKEN_LEVELS_SHADER = """
uniform shader img;
uniform float uRadius;
uniform float uDarken;
uniform float uBlack;
uniform float uWhite;

const half3 LUMA = half3(0.299, 0.587, 0.114);

half lumaAt(float2 p, half centre) {
    half4 c = img.eval(p);
    return dot(c.rgb, LUMA) + (1.0 - c.a) * centre;
}

half4 main(float2 pos) {
    half4 base = img.eval(pos);
    if (base.a == 0.0) return base;
    half mid = dot(base.rgb, LUMA) / base.a;

    // Surrounding neighborhood luma (excluding center so thin 1-2 px strokes pop strongly)
    half a0 = lumaAt(pos + float2(-1.0, -1.0), mid); half a1 = lumaAt(pos + float2(0.0, -1.0), mid); half a2 = lumaAt(pos + float2(1.0, -1.0), mid);
    half a3 = lumaAt(pos + float2(-1.0,  0.0), mid);                                                 half a5 = lumaAt(pos + float2(1.0,  0.0), mid);
    half a6 = lumaAt(pos + float2(-1.0,  1.0), mid); half a7 = lumaAt(pos + float2(0.0,  1.0), mid); half a8 = lumaAt(pos + float2(1.0,  1.0), mid);
    half ring1 = (a0 + a2 + a6 + a8 + 2.0 * (a1 + a3 + a5 + a7)) / 12.0;

    float r = max(2.0, uRadius * 1.8);
    half o1 = lumaAt(pos + float2(0.0, -r), mid);
    half o3 = lumaAt(pos + float2(-r, 0.0), mid);
    half o5 = lumaAt(pos + float2(r, 0.0), mid);
    half o7 = lumaAt(pos + float2(0.0, r), mid);
    half surround = 0.65 * ring1 + 0.35 * (0.25 * (o1 + o3 + o5 + o7));

    // Anime4K Line Darken: darken only pixels noticeably darker than local paper/surround (no white halos)
    half neg = max(surround - mid, 0.0);
    half lineGate = smoothstep(0.025, 0.11, neg);
    half darkened = clamp(mid - neg * half(uDarken) * lineGate, 0.0, 1.0);

    // Ink / paper levels: clean speech-bubble paper and deep ink blacks
    half leveled = clamp((darkened - half(uBlack)) / half(uWhite - uBlack), 0.0, 1.0);

    half delta = leveled - mid;
    half3 rgb = (delta < 0.0 && mid > 0.01)
        ? base.rgb * (leveled / mid)
        : base.rgb + delta * base.a;
    return half4(clamp(rgb, 0.0, base.a), base.a);
}
"""
