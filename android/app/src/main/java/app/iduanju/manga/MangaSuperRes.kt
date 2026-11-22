package app.iduanju.manga

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer

/** Standard mode: one GPU pass over the fitted image, with a one-rendered-pixel sampling radius. */
@Composable
internal fun Modifier.mangaSuperRes(enabled: Boolean): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < 33) return this
    val effect = remember {
        runCatching {
            RenderEffect.createRuntimeShaderEffect(RuntimeShader(MANGA_RESTORE_SHADER), "img")
                .asComposeRenderEffect()
        }.onFailure { Log.w("IDJ", "Manga edge restoration shader unavailable", it) }.getOrNull()
    } ?: return this
    return graphicsLayer { renderEffect = effect }
}

/** Local contrast enhancement without extrema overshoot or independent RGB clipping. */
private const val MANGA_RESTORE_SHADER = """
uniform shader img;
const float3 LUMA = float3(0.299, 0.587, 0.114);

float lumaAt(float2 pos, float mid, float alpha) {
    float4 neighbor = img.eval(pos);
    return neighbor.a >= alpha - 0.002 && neighbor.a > 0.004
        ? dot(neighbor.rgb, LUMA) / neighbor.a : mid;
}

half4 main(float2 pos) {
    float4 base = img.eval(pos);
    if (base.a < 0.004) return half4(base);
    float3 rgb = base.rgb / base.a;
    float mid = dot(rgb, LUMA);
    float west = lumaAt(pos + float2(-1, 0), mid, base.a);
    float east = lumaAt(pos + float2(1, 0), mid, base.a);
    float north = lumaAt(pos + float2(0, -1), mid, base.a);
    float south = lumaAt(pos + float2(0, 1), mid, base.a);
    float nw = lumaAt(pos + float2(-1, -1), mid, base.a);
    float ne = lumaAt(pos + float2(1, -1), mid, base.a);
    float sw = lumaAt(pos + float2(-1, 1), mid, base.a);
    float se = lumaAt(pos + float2(1, 1), mid, base.a);

    float low = min(mid, min(min(west, east), min(north, south)));
    float high = max(mid, max(max(west, east), max(north, south)));
    float blur = (4.0 * mid + 2.0 * (west + east + north + south) + nw + ne + sw + se) / 16.0;
    float detail = mid - blur;
    float gate = smoothstep(0.025, 0.12, high - low);
    float delta = sign(detail) * max(abs(detail) - 0.006, 0.0) * gate;
    delta = clamp(delta * (detail < 0.0 ? 1.8 : 0.95), low - mid, high - mid);
    delta = clamp(delta, -min(rgb.r, min(rgb.g, rgb.b)), 1.0 - max(rgb.r, max(rgb.g, rgb.b)));
    return half4((rgb + delta) * base.a, base.a);
}
"""
