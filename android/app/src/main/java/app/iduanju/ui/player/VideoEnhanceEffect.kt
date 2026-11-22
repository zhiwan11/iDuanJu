package app.iduanju.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.*
import android.opengl.GLES20
import android.os.*
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.*
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import java.lang.ref.WeakReference

/** Live SDR detail reconstruction (CAS + DoG + edge tightening) and perceptual tone mapping. */
@UnstableApi
class VideoEnhanceEffect(@Volatile var level: Int, @Volatile var sdrToHdr: Boolean = false) : GlEffect {
    @Volatile internal var sourceIsNativeHdr = false
    @Volatile private var program: EnhanceShaderProgram? = null
    private var fallbackView: WeakReference<TextureView>? = null
    val framesProcessed: Long get() = program?.framesProcessed ?: 0L

    @Synchronized
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShaderProgram(useHdr, this).also { program = it }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    @Synchronized
    private fun releaseProgram(released: EnhanceShaderProgram) {
        if (program === released) program = null
    }

    @RequiresApi(31)
    internal fun setFallback(view: TextureView?, renderEffect: RenderEffect?) {
        val previous = fallbackView?.get()
        if (previous != null && previous !== view) previous.setRenderEffect(null)
        view?.setRenderEffect(renderEffect)
        fallbackView = if (view != null && renderEffect != null) WeakReference(view) else null
    }

    private class EnhanceShaderProgram(
        private val useHdr: Boolean,
        private val owner: VideoEnhanceEffect,
    ) : BaseGlShaderProgram(useHdr, 1) {
        @Volatile var framesProcessed = 0L
            private set
        private val texelSize = FloatArray(2)
        private val glProgram = try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texelSize[0] = 1f / inputWidth
            texelSize[1] = 1f / inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val nativeHdr = useHdr || owner.sourceIsNativeHdr
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.setFloatsUniform("uTexelSize", texelSize)
                glProgram.setFloatUniform("uGain", if (nativeHdr) 0f else sharpenGain(owner.level))
                glProgram.setFloatUniform("uItm", if (!nativeHdr && owner.sdrToHdr) 1f else 0f)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                if (++framesProcessed == 1L && Build.VERSION.SDK_INT >= 31) {
                    mainHandler.post { if (owner.framesProcessed != 0L) owner.setFallback(null, null) }
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            try {
                super.release()
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            } finally {
                owner.releaseProgram(this)
            }
        }
    }
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
private fun sharpenGain(level: Int): Float = when {
    level >= 2 -> 1.80f
    level == 1 -> 1.05f
    else -> 0f
}

private const val VERTEX_SHADER = """
    #version 100
    attribute vec4 aFramePosition;
    varying vec2 vTexSamplingCoord;
    void main() { gl_Position = aFramePosition; vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5; }
"""

private const val ENHANCE_UNIFORMS = """
    uniform float2 uTexelSize;
    uniform float uGain;
    uniform float uItm;
"""

private const val ENHANCE_BODY = """
    const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

    float3 enhance(float2 uv, float2 pixel, float3 e) {
        float2 st = uTexelSize;
        float3 a = sampleRgb(uv + float2(-st.x, -st.y));
        float3 b = sampleRgb(uv + float2( 0.0,  -st.y));
        float3 c = sampleRgb(uv + float2( st.x, -st.y));
        float3 d = sampleRgb(uv + float2(-st.x,  0.0 ));
        float3 f = sampleRgb(uv + float2( st.x,  0.0 ));
        float3 g = sampleRgb(uv + float2(-st.x,  st.y));
        float3 h = sampleRgb(uv + float2( 0.0,   st.y));
        float3 i = sampleRgb(uv + float2( st.x,  st.y));
        float3 axial = b + d + f + h;
        float3 diag = a + c + g + i;

        float yA = dot(a, LUMA), yB = dot(b, LUMA), yC = dot(c, LUMA);
        float yD = dot(d, LUMA), yE = dot(e, LUMA), yF = dot(f, LUMA);
        float yG = dot(g, LUMA), yH = dot(h, LUMA), yI = dot(i, LUMA);
        float mnCross = min(yE, min(min(yB, yH), min(yD, yF)));
        float mxCross = max(yE, max(max(yB, yH), max(yD, yF)));
        float mnY = min(mnCross, min(min(yA, yC), min(yG, yI)));
        float mxY = max(mxCross, max(max(yA, yC), max(yG, yI)));
        float contrast = mxY - mnY;

        float flatBand = 1.0 - smoothstep(0.004, 0.016, contrast);
        float dither = fract(52.9829189 * fract(dot(floor(pixel), float2(0.06711056, 0.00583715))));
        float3 rgb = e + (dither - 0.5) * (flatBand / 255.0);

        if (uGain > 0.0) {
            float noiseGate = smoothstep(0.008, 0.036, contrast);
            float midY = 0.5 * (mnCross + mxCross);
            float steepDelta = (yE >= midY ? (mxCross - yE) : -(yE - mnCross))
                * smoothstep(0.03, 0.25, mxCross - mnCross);
            float steepAmt = clamp(0.22 * uGain, 0.0, 0.40);

            float avgCross = (yB + yD + yF + yH) * 0.25;
            float avgBlur = (4.0 * yE + 2.0 * (yB + yD + yF + yH) + (yA + yC + yG + yI)) * 0.0625;
            float rawDetail = ((yE - avgCross) * 0.55 + (yE - avgBlur) * 0.95) * uGain * noiseGate;
            float softDetail = rawDetail / (1.0 + 1.25 * abs(rawDetail));

            float maxAllow = mix(0.030, 0.055, clamp(uGain - 1.0, 0.0, 1.0));
            float allow = min(contrast * (0.26 + 0.10 * uGain), maxAllow);
            float yTarget = clamp(yE + steepDelta * steepAmt + softDetail,
                max(0.0, mnY - allow), min(1.0, mxY + allow));
            float clipGuard = smoothstep(0.0, 0.05, yE) * smoothstep(1.0, 0.94, yE);
            rgb += (yTarget - yE) * mix(0.35, 1.0, clipGuard);
        }

        if (uItm > 0.5) {
            float y = clamp(dot(rgb, LUMA), 0.0, 1.0);
            float toe = mix(0.62, 1.0, smoothstep(0.0, 0.18, y));
            float shoulder = smoothstep(0.22, 0.55, y) * (1.0 - smoothstep(0.65, 1.0, y));
            float3 blur3x3 = (4.0 * e + 2.0 * axial + diag) * 0.0625;
            float3 recovery = (e - blur3x3) * (0.45 * smoothstep(0.22, 0.75, y));
            float vibrance = 1.0 + 0.22 * smoothstep(0.12, 0.40, y);
            float saturation = mix(vibrance, 0.80, smoothstep(0.88, 1.0, y));
            rgb = y * (toe + 0.22 * shoulder) + (rgb - y) * saturation + recovery;
        }
        return clamp(rgb, 0.0, 1.0);
    }
"""

private const val FRAGMENT_SHADER = """
    #version 100
    precision highp float;
    #define float2 vec2
    #define float3 vec3
    uniform sampler2D uTexSampler;
    varying vec2 vTexSamplingCoord;
    $ENHANCE_UNIFORMS
    vec3 sampleRgb(vec2 uv) { return texture2D(uTexSampler, uv).rgb; }
    $ENHANCE_BODY
    void main() {
        vec4 center = texture2D(uTexSampler, vTexSamplingCoord);
        gl_FragColor = center;
        if (uGain > 0.0 || uItm > 0.5) {
            gl_FragColor.rgb = enhance(vTexSamplingCoord, gl_FragCoord.xy, center.rgb);
        }
    }
"""

private const val TEXTURE_SHADER = """
    uniform shader video;
    $ENHANCE_UNIFORMS
    float3 sampleRgb(float2 xy) { return float3(video.eval(xy).rgb); }
    $ENHANCE_BODY
    half4 main(float2 xy) {
        half4 center = video.eval(xy);
        if (center.a <= 0.0) return center;
        half3 rgb = half3(enhance(xy, xy, float3(center.rgb)));
        return half4(min(rgb, half3(center.a)), center.a);
    }
"""

@RequiresApi(33)
private fun textureEffect(level: Int, hdr: Boolean): RenderEffect? = runCatching {
    val shader = RuntimeShader(TEXTURE_SHADER).apply {
        setFloatUniform("uTexelSize", 1.35f, 1.35f)
        setFloatUniform("uGain", sharpenGain(level))
        setFloatUniform("uItm", if (hdr) 1f else 0f)
    }
    RenderEffect.createRuntimeShaderEffect(shader, "video")
}.onFailure { Log.w("IDJ", "Could not create TextureView enhancement shader", it) }.getOrNull()

@RequiresApi(31)
private fun colorFallbackEffect(level: Int, hdr: Boolean): RenderEffect {
    val contrast = 1f + (if (level >= 2) 0.12f else if (level == 1) 0.07f else 0f) + (if (hdr) 0.14f else 0f)
    val offset = (1f - contrast) * 127.5f
    val sat = 1f + (if (level >= 2) 0.10f else if (level == 1) 0.05f else 0f) + (if (hdr) 0.22f else 0f)
    val cm = ColorMatrix().apply {
        setSaturation(sat)
        postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, offset, 0f, contrast, 0f, 0f, offset, 0f, 0f, contrast, 0f, offset, 0f, 0f, 0f, 1f, 0f)))
    }
    return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
}

@OptIn(UnstableApi::class)
fun syncWindowAndFallback(activity: Activity?, textureView: TextureView?, effect: VideoEnhanceEffect, isNativeHdr: Boolean) {
    effect.sourceIsNativeHdr = isNativeHdr
    val hdr = effect.sdrToHdr && !isNativeHdr
    activity?.window?.colorMode =
        if (hdr || isNativeHdr) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT
    if (Build.VERSION.SDK_INT >= 31) {
        val level = effect.level
        val active = textureView != null && !isNativeHdr && effect.framesProcessed == 0L &&
            (level > 0 || hdr) && textureView.width > 0 && textureView.height > 0
        val fallback = if (!active) null
        else if (Build.VERSION.SDK_INT >= 33) textureEffect(level, hdr)
        else colorFallbackEffect(level, hdr)
        effect.setFallback(textureView, fallback)
    }
}

@OptIn(UnstableApi::class)
fun clearWindowAndFallback(effect: VideoEnhanceEffect) {
    if (Build.VERSION.SDK_INT >= 31) effect.setFallback(null, null)
}

typealias CasSharpenEffect = VideoEnhanceEffect

object PlayerPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("idj_player_prefs", Context.MODE_PRIVATE)
    fun getSpeed(ctx: Context): Float = prefs(ctx).getFloat("playback_speed", 1f).coerceIn(0.5f, 3f)
    fun setSpeed(ctx: Context, speed: Float) = prefs(ctx).edit().putFloat("playback_speed", speed).apply()
    fun getEnhanceLevel(ctx: Context): Int = prefs(ctx).getInt("enhance_level", 0).coerceIn(0, 2)
    fun setEnhanceLevel(ctx: Context, level: Int) = prefs(ctx).edit().putInt("enhance_level", level.coerceIn(0, 2)).apply()
    fun getSdrToHdr(ctx: Context): Boolean = prefs(ctx).getBoolean("sdr_to_hdr", false)
    fun setSdrToHdr(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("sdr_to_hdr", on).apply()
    fun getFillMode(ctx: Context): Boolean = prefs(ctx).getBoolean("fill_mode", false)
    fun setFillMode(ctx: Context, fill: Boolean) = prefs(ctx).edit().putBoolean("fill_mode", fill).apply()
    fun getAmbientLevel(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains("ambient_level")) return p.getInt("ambient_level", 2).coerceIn(0, 4)
        return if (p.getBoolean("ambient_mode", true)) 2 else 0
    }
    fun setAmbientLevel(ctx: Context, level: Int) {
        val clamped = level.coerceIn(0, 4)
        prefs(ctx).edit().putInt("ambient_level", clamped).putBoolean("ambient_mode", clamped > 0).apply()
    }
    fun getAmbientMode(ctx: Context): Boolean = getAmbientLevel(ctx) > 0
    fun setAmbientMode(ctx: Context, on: Boolean) = setAmbientLevel(ctx, if (on) 2 else 0)
}

@OptIn(UnstableApi::class)
fun installVideoEnhance(player: ExoPlayer, effect: VideoEnhanceEffect?) {
    runCatching { player.setVideoEffects(listOfNotNull(effect)) }
        .onFailure { Log.w("IDJ", "setVideoEffects failed", it) }
}
