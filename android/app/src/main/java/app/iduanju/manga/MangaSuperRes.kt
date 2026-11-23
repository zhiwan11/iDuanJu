package app.iduanju.manga

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import coil.size.Size
import coil.transform.Transformation
import kotlin.math.abs
import kotlin.math.roundToInt

/** Below this many rendered pixels per source pixel nothing is being reconstructed; only a light crispening pass runs. */
private const val FULL_STRENGTH_SCALE = 1.5f
private const val BASE_AMOUNT = 0.35f

/** Strength for a page drawn at [displayScale] rendered pixels per source pixel ([baseline] applies at 1:1 and below). */
internal fun mangaRestoreAmount(displayScale: Float, baseline: Float = BASE_AMOUNT): Float {
    val ramp = ((displayScale - 1f) / (FULL_STRENGTH_SCALE - 1f)).coerceIn(0f, 1f)
    return baseline + (1f - baseline) * ramp
}

/**
 * Standard mode: one GPU pass over the fitted image. The sampling radius follows the source pixel
 * ([displayScale] rendered pixels per source pixel), so edges that the fit upscale spread over several
 * screen pixels are actually reached. Luma only; chroma and flat areas are untouched.
 */
@Composable
internal fun Modifier.mangaSuperRes(enabled: Boolean, displayScale: Float = 1f, baseline: Float = BASE_AMOUNT): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < 33) return this
    // Quantised so a resize does not rebuild the effect every frame.
    val radius = (displayScale.coerceIn(1f, 3f) * 8f).roundToInt() / 8f
    val amount = (mangaRestoreAmount(displayScale, baseline) * 20f).roundToInt() / 20f
    if (amount <= 0f) return this
    val effect = remember(radius, amount) {
        runCatching {
            val shader = RuntimeShader(MANGA_RESTORE_SHADER).apply {
                setFloatUniform("radius", radius)
                setFloatUniform("amount", amount)
            }
            RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
        }.onFailure { Log.w("MangaSR", "Edge restoration shader unavailable", it) }.getOrNull()
    } ?: return this
    return graphicsLayer { renderEffect = effect }
}

/**
 * Edge-directed restoration (shock filter + controlled unsharp):
 *  1. pixels on an edge are pulled toward the extreme of the side they belong to — ink gets darker and
 *     thinner, paper next to a stroke gets cleaner — which steepens the soft ramp a bilinear upscale leaves;
 *  2. a source-scale unsharp adds back the lost micro contrast;
 *  3. the result may overshoot the local [min, max] by a small fraction of the local contrast only (no halos).
 * Flat areas and gentle gradients (local contrast < 3%) are left bit-exact.
 */
private const val MANGA_RESTORE_SHADER = """
uniform shader img;
uniform float radius;
uniform float amount;
const float3 LUMA = float3(0.299, 0.587, 0.114);

float lumaAt(float2 pos, float mid, float alpha) {
    float4 c = img.eval(pos);
    return c.a >= alpha - 0.002 && c.a > 0.004 ? dot(c.rgb, LUMA) / c.a : mid;
}

half4 main(float2 pos) {
    float4 base = img.eval(pos);
    if (base.a < 0.004) return half4(base);
    float3 rgb = base.rgb / base.a;
    float y = dot(rgb, LUMA);
    float r = radius;
    float w = lumaAt(pos + float2(-r, 0.0), y, base.a);
    float e = lumaAt(pos + float2(r, 0.0), y, base.a);
    float n = lumaAt(pos + float2(0.0, -r), y, base.a);
    float s = lumaAt(pos + float2(0.0, r), y, base.a);
    float nw = lumaAt(pos + float2(-r, -r), y, base.a);
    float ne = lumaAt(pos + float2(r, -r), y, base.a);
    float sw = lumaAt(pos + float2(-r, r), y, base.a);
    float se = lumaAt(pos + float2(r, r), y, base.a);

    float lo = min(min(min(y, w), min(e, n)), min(min(s, nw), min(min(ne, sw), se)));
    float hi = max(max(max(y, w), max(e, n)), max(max(s, nw), max(max(ne, sw), se)));
    float spread = hi - lo;
    float gate = smoothstep(0.03, 0.18, spread) * amount;
    if (gate <= 0.0) return half4(base);

    float blur = (4.0 * y + 2.0 * (w + e + n + s) + nw + ne + sw + se) / 16.0;
    float lap = blur - y;
    float side = lap > 0.0 ? lo : hi;
    float conf = clamp(abs(lap) / (spread * 0.25 + 0.0001), 0.0, 1.0);
    float shocked = y + (side - y) * 0.6 * conf * gate;
    float want = shocked + 0.9 * (y - blur) * gate;
    float allow = 0.15 * spread;
    want = clamp(want, lo - allow, hi + allow);

    float d = want - y;
    d = clamp(d, -min(rgb.r, min(rgb.g, rgb.b)), 1.0 - max(rgb.r, max(rgb.g, rgb.b)));
    return half4((rgb + d) * base.a, base.a);
}
"""

/**
 * CPU equivalent of [MANGA_RESTORE_SHADER] for Android 12 and below (no runtime shaders). It runs once at
 * source resolution inside the image pipeline, so the page is decoded already restored.
 */
internal class MangaRestoreTransformation(private val amount: Float = 0.7f) : Transformation {
    override val cacheKey = "manga-restore-v2-$amount"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 3 || h < 3) return input
        val src = IntArray(w * h)
        val bitmap = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h) { i -> lumaOf(src[i]) }
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val up = (row - 1).coerceAtLeast(0) * w
            val mid = row * w
            val down = (row + 1).coerceAtMost(h - 1) * w
            for (col in 0 until w) {
                val left = (col - 1).coerceAtLeast(0)
                val right = (col + 1).coerceAtMost(w - 1)
                val c = y[mid + col]
                val wv = y[mid + left]; val ev = y[mid + right]; val nv = y[up + col]; val sv = y[down + col]
                val nwv = y[up + left]; val nev = y[up + right]; val swv = y[down + left]; val sev = y[down + right]
                val lo = minOf(minOf(c, wv, ev), minOf(nv, sv, nwv), minOf(nev, swv, sev))
                val hi = maxOf(maxOf(c, wv, ev), maxOf(nv, sv, nwv), maxOf(nev, swv, sev))
                val range = hi - lo
                val t = ((range - 0.03f) / 0.15f).coerceIn(0f, 1f)
                val gate = t * t * (3f - 2f * t) * amount
                val pixel = src[mid + col]
                if (gate <= 0f) { out[mid + col] = pixel; continue }
                val blur = (4f * c + 2f * (wv + ev + nv + sv) + nwv + nev + swv + sev) / 16f
                val lap = blur - c
                val side = if (lap > 0f) lo else hi
                val conf = (abs(lap) / (range * 0.25f + 1e-4f)).coerceIn(0f, 1f)
                val shocked = c + (side - c) * 0.6f * conf * gate
                val allow = 0.15f * range
                val target = (shocked + 0.9f * (c - blur) * gate).coerceIn(lo - allow, hi + allow)
                val r = (pixel ushr 16 and 255) / 255f
                val g = (pixel ushr 8 and 255) / 255f
                val b = (pixel and 255) / 255f
                val d = (target - c).coerceIn(-minOf(r, g, b), 1f - maxOf(r, g, b))
                out[mid + col] = (pixel and -0x1000000) or
                    (((r + d) * 255f).roundToInt().coerceIn(0, 255) shl 16) or
                    (((g + d) * 255f).roundToInt().coerceIn(0, 255) shl 8) or
                    ((b + d) * 255f).roundToInt().coerceIn(0, 255)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun lumaOf(p: Int) = (0.299f * (p ushr 16 and 255) + 0.587f * (p ushr 8 and 255) + 0.114f * (p and 255)) / 255f

    override fun equals(other: Any?) = other is MangaRestoreTransformation && other.amount == amount
    override fun hashCode() = amount.hashCode()
}
