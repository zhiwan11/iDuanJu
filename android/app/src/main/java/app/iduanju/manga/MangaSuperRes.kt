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

/** From this many rendered pixels per source pixel on, the full strength applies. */
private const val FULL_STRENGTH_SCALE = 1.5f
/**
 * Strength at 1:1 and below. Online pages are often soft, re-compressed JPEGs served at screen width, so even
 * unenlarged pages get a clearly visible ink/text reconstruction rather than a token crispening.
 */
private const val BASE_AMOUNT = 0.85f

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
 * Two-scale edge-directed restoration (shock filter + band-limited unsharp), luma only:
 *  - a fine ring at [radius] and a coarse ring at 2 × [radius] (one and two source pixels at 1:1) span the
 *    2–4 px ramp a soft, compressed page has around every stroke, so its edges are actually reached;
 *  - pixels on an edge are pulled toward the extreme of the side they belong to (ink darker and thinner,
 *    paper next to a stroke cleaner), then fine and coarse unsharp add back the lost micro contrast;
 *  - anti-ringing: the result stays inside the local [min, max] except for a little extra ink depth; it may
 *    brighten above the local maximum by 2 % of the local contrast only, so no white halos appear;
 *  - flat areas, paper texture and compression noise (local contrast < 3.5 %) are left bit-exact, and the fine
 *    band is gated separately so blocky JPEG noise in mid-contrast areas is not amplified.
 * The luma delta is applied equally to R, G and B and clamped to the gamut, so hues never shift.
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
    float a = base.a;
    float r = radius;
    float q = radius * 2.0;
    float w = lumaAt(pos + float2(-r, 0.0), y, a);
    float e = lumaAt(pos + float2(r, 0.0), y, a);
    float n = lumaAt(pos + float2(0.0, -r), y, a);
    float s = lumaAt(pos + float2(0.0, r), y, a);
    float nw = lumaAt(pos + float2(-r, -r), y, a);
    float ne = lumaAt(pos + float2(r, -r), y, a);
    float sw = lumaAt(pos + float2(-r, r), y, a);
    float se = lumaAt(pos + float2(r, r), y, a);
    float w2 = lumaAt(pos + float2(-q, 0.0), y, a);
    float e2 = lumaAt(pos + float2(q, 0.0), y, a);
    float n2 = lumaAt(pos + float2(0.0, -q), y, a);
    float s2 = lumaAt(pos + float2(0.0, q), y, a);
    float nw2 = lumaAt(pos + float2(-q, -q), y, a);
    float ne2 = lumaAt(pos + float2(q, -q), y, a);
    float sw2 = lumaAt(pos + float2(-q, q), y, a);
    float se2 = lumaAt(pos + float2(q, q), y, a);

    float lo1 = min(min(min(y, w), min(e, n)), min(min(s, nw), min(min(ne, sw), se)));
    float hi1 = max(max(max(y, w), max(e, n)), max(max(s, nw), max(max(ne, sw), se)));
    float lo = min(lo1, min(min(min(w2, e2), min(n2, s2)), min(min(nw2, ne2), min(sw2, se2))));
    float hi = max(hi1, max(max(max(w2, e2), max(n2, s2)), max(max(nw2, ne2), max(sw2, se2))));
    float spread = hi - lo;
    float gate = smoothstep(0.035, 0.16, spread) * amount;
    if (gate <= 0.0) return half4(base);

    float axes = w + e + n + s;
    float diag = nw + ne + sw + se;
    float blurFine = (4.0 * y + 2.0 * axes + diag) / 16.0;
    float blurWide = (4.0 * y + 2.0 * axes + diag + 2.0 * (w2 + e2 + n2 + s2) + nw2 + ne2 + sw2 + se2) / 28.0;
    float fineGate = smoothstep(0.03, 0.12, hi1 - lo1);

    float lap = blurWide - y;
    float side = lap > 0.0 ? lo : hi;
    float conf = clamp(abs(lap) / (spread * 0.2 + 0.0001), 0.0, 1.0);
    float shocked = y + (side - y) * 0.7 * conf * gate;
    float want = shocked + (0.7 * fineGate * (y - blurFine) + 1.3 * (y - blurWide)) * gate;
    want = clamp(want, lo - 0.10 * spread, hi + 0.02 * spread);

    float d = want - y;
    d = clamp(d, -min(rgb.r, min(rgb.g, rgb.b)), 1.0 - max(rgb.r, max(rgb.g, rgb.b)));
    return half4((rgb + d) * a, a);
}
"""

/**
 * CPU equivalent of [MANGA_RESTORE_SHADER] for Android 12 and below (no runtime shaders). It runs once at
 * source resolution (fine ring 1 px, coarse ring 2 px) inside the image pipeline, so the page is decoded already restored.
 */
internal class MangaRestoreTransformation(private val amount: Float = BASE_AMOUNT) : Transformation {
    override val cacheKey = "manga-restore-v3-$amount"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 5 || h < 5) return input
        val src = IntArray(w * h)
        val bitmap = if (input.config == Bitmap.Config.ARGB_8888) input else input.copy(Bitmap.Config.ARGB_8888, false)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h) { i -> lumaOf(src[i]) }
        val out = IntArray(w * h)
        for (row in 0 until h) {
            val up = (row - 1).coerceAtLeast(0) * w
            val mid = row * w
            val down = (row + 1).coerceAtMost(h - 1) * w
            val up2 = (row - 2).coerceAtLeast(0) * w
            val down2 = (row + 2).coerceAtMost(h - 1) * w
            for (col in 0 until w) {
                val left = (col - 1).coerceAtLeast(0)
                val right = (col + 1).coerceAtMost(w - 1)
                val left2 = (col - 2).coerceAtLeast(0)
                val right2 = (col + 2).coerceAtMost(w - 1)
                val c = y[mid + col]
                val wv = y[mid + left]; val ev = y[mid + right]; val nv = y[up + col]; val sv = y[down + col]
                val nwv = y[up + left]; val nev = y[up + right]; val swv = y[down + left]; val sev = y[down + right]
                val w2 = y[mid + left2]; val e2 = y[mid + right2]; val n2 = y[up2 + col]; val s2 = y[down2 + col]
                val nw2 = y[up2 + left2]; val ne2 = y[up2 + right2]; val sw2 = y[down2 + left2]; val se2 = y[down2 + right2]
                val lo1 = minOf(minOf(c, wv, ev), minOf(nv, sv, nwv), minOf(nev, swv, sev))
                val hi1 = maxOf(maxOf(c, wv, ev), maxOf(nv, sv, nwv), maxOf(nev, swv, sev))
                val lo = minOf(lo1, minOf(w2, e2, n2), minOf(s2, nw2, ne2), minOf(sw2, se2))
                val hi = maxOf(hi1, maxOf(w2, e2, n2), maxOf(s2, nw2, ne2), maxOf(sw2, se2))
                val spread = hi - lo
                val gate = smoothstep(0.035f, 0.16f, spread) * amount
                val pixel = src[mid + col]
                if (gate <= 0f) { out[mid + col] = pixel; continue }
                val axes = wv + ev + nv + sv
                val diag = nwv + nev + swv + sev
                val blurFine = (4f * c + 2f * axes + diag) / 16f
                val blurWide = (4f * c + 2f * axes + diag + 2f * (w2 + e2 + n2 + s2) + nw2 + ne2 + sw2 + se2) / 28f
                val fineGate = smoothstep(0.03f, 0.12f, hi1 - lo1)
                val lap = blurWide - c
                val side = if (lap > 0f) lo else hi
                val conf = (abs(lap) / (spread * 0.2f + 1e-4f)).coerceIn(0f, 1f)
                val shocked = c + (side - c) * 0.7f * conf * gate
                val target = (shocked + (0.7f * fineGate * (c - blurFine) + 1.3f * (c - blurWide)) * gate)
                    .coerceIn(lo - 0.10f * spread, hi + 0.02f * spread)
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

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lumaOf(p: Int) = (0.299f * (p ushr 16 and 255) + 0.587f * (p ushr 8 and 255) + 0.114f * (p and 255)) / 255f

    override fun equals(other: Any?) = other is MangaRestoreTransformation && other.amount == amount
    override fun hashCode() = amount.hashCode()
}
