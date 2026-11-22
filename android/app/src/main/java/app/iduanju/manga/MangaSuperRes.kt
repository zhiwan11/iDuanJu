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
 * 漫画超分「标准」: instant API 33+ AGSL pass — contrast-adaptive line-art / text sharpening with screentone
 * anti-ringing, applied as a RenderEffect (zero latency, no extra bitmaps). No-op below Android 13 or at [level] 0.
 * 「增强」 (level 2) is the neural [MangaAiUpscaler]; the reader shows this pass only as its instant preview.
 */
@Composable
fun Modifier.mangaSuperRes(level: Int): Modifier {
    if (Build.VERSION.SDK_INT < 33 || level <= 0) return this
    var size by remember { mutableStateOf(IntSize.Zero) }
    val effect = remember(size) {
        if (size.width < 1 || size.height < 1) null
        else runCatching { // AGSL compilation can fail on broken drivers: fall back to the plain page
            val shader = RuntimeShader(MANGA_SR_SHADER)
            shader.setFloatUniform("uSize", size.width.toFloat(), size.height.toFloat())
            shader.setFloatUniform("uGain", 1.45f)
            RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
        }.getOrNull()
    }
    return onSizeChanged { size = it }.graphicsLayer { renderEffect = effect }
}

private const val MANGA_SR_SHADER = """
uniform shader img;
uniform float2 uSize;
uniform float uGain;

const half3 LUMA = half3(0.2126, 0.7152, 0.0722);

half luma(half4 sampleColor, half centerLuma) {
    return dot(sampleColor.rgb, LUMA) + (1.0 - sampleColor.a) * centerLuma;
}

half4 main(float2 pos) {
    float2 lower = float2(0.5);
    float2 upper = uSize - 0.5;
    float2 center = clamp(pos, lower, upper);
    float2 low = max(center - 1.0, lower);
    float2 high = min(center + 1.0, upper);
    half4 base = img.eval(center);
    if (base.a == 0.0) return base;
    half mid = dot(base.rgb, LUMA) / base.a;
    half tl = luma(img.eval(low), mid);
    half tc = luma(img.eval(float2(center.x, low.y)), mid);
    half tr = luma(img.eval(float2(high.x, low.y)), mid);
    half ml = luma(img.eval(float2(low.x, center.y)), mid);
    half mr = luma(img.eval(float2(high.x, center.y)), mid);
    half bl = luma(img.eval(float2(low.x, high.y)), mid);
    half bc = luma(img.eval(float2(center.x, high.y)), mid);
    half br = luma(img.eval(high), mid);

    half mnBox = min(mid, min(min(min(tl, tr), min(bl, br)), min(min(tc, bc), min(ml, mr))));
    half mxBox = max(mid, max(max(max(tl, tr), max(bl, br)), max(max(tc, bc), max(ml, mr))));
    half span = mxBox - mnBox;
    half blur3x3 = (tl + tr + bl + br + 2.0 * (tc + ml + mr + bc) + 4.0 * mid) / 16.0;
    half headroom = max(0.0, min(mnBox, 1.0 - mxBox));
    half cas = mix(0.65, 1.0, sqrt(clamp(headroom / max(mxBox, 0.001), 0.0, 1.0)));
    half edge = max(abs(ml - mr), abs(tc - bc));
    half coherence = mix(0.22, 1.0, smoothstep(0.05, 0.65, edge / max(span, 0.001)));
    half antiring = 0.85;
    half margin = (1.0 - antiring) * span * 0.2;
    half sharpened = mid + (mid - blur3x3) * half(uGain) * cas * coherence * smoothstep(0.02, 0.22, span);
    half delta = clamp(sharpened, mnBox - margin, mxBox + margin) - mid;
    return half4(clamp(base.rgb + delta * base.a, 0.0, base.a), base.a);
}
"""
