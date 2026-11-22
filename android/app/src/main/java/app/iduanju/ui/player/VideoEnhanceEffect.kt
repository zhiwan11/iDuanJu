package app.iduanju.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Install once before `player.prepare()`. The renderer reads live settings on each frame.
 * Media3 and the API 33+ fallback share the deband, EWA detail reconstruction and SDR tone curve.
 * SR is a same-resolution GPU detail filter, not neural upscaling; SDR enhancement does not change
 * Media3's output transfer function or signal HDR metadata. Native HDR is left unmodified.
 */
@UnstableApi
class VideoEnhanceEffect(
    level: Int,
    sdrToHdr: Boolean = false,
) : GlEffect {

    @Volatile var level: Int = level
        set(value) {
            if (field == value) return
            field = value
            requestUiSync()
        }

    @Volatile var sdrToHdr: Boolean = sdrToHdr
        set(value) {
            if (field == value) return
            field = value
            requestUiSync()
        }

    @Volatile var framesProcessed: Long = 0L
        private set

    private var currentProgram: EnhanceShaderProgram? = null
    @Volatile private var programUsesHdr = false
    @Volatile private var sourceIsNativeHdr = false
    @Volatile private var uiBinding: EnhanceUiBinding? = null
    private val syncPending = AtomicBoolean()

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShaderProgram(useHdr, this).also { setProgram(it, useHdr) }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    @Synchronized
    private fun setProgram(program: EnhanceShaderProgram?, useHdr: Boolean) {
        currentProgram = program
        programUsesHdr = useHdr
        framesProcessed = 0L
        requestUiSync()
    }

    @Synchronized
    private fun recordFrame(program: EnhanceShaderProgram) {
        if (currentProgram === program && ++framesProcessed == 1L) requestUiSync()
    }

    @Synchronized
    private fun releaseProgram(program: EnhanceShaderProgram) {
        if (currentProgram === program) setProgram(null, false)
    }

    private fun requestUiSync() {
        if (uiBinding != null && syncPending.compareAndSet(false, true)) {
            enhanceMainHandler.post {
                syncPending.set(false)
                uiBinding?.sync()
            }
        }
    }

    internal fun syncUi(activity: Activity?, textureView: TextureView?, isNativeHdr: Boolean) = onEnhanceMain {
        sourceIsNativeHdr = isNativeHdr
        val binding = uiBinding
        if (binding != null && binding.matches(activity, textureView)) {
            binding.sync()
        } else {
            binding?.close()
            uiBinding = null
            if (activity != null || textureView != null) {
                uiBinding = EnhanceUiBinding(this, activity, textureView).also { it.sync() }
            }
        }
    }

    internal fun clearUi() = onEnhanceMain {
        uiBinding?.close()
        uiBinding = null
        sourceIsNativeHdr = false
    }

    internal fun isNativeHdr(): Boolean = sourceIsNativeHdr || programUsesHdr

    private class SrPreset(
        radius: Double,
        blur: Double,
        tapScale: Float,
        gain: Float,
        nearGain: Float,
        farGain: Float,
        val debandThreshold: Float,
    ) {
        private val wAxial = ewaLanczos(tapScale.toDouble(), radius, blur).toFloat()
        private val wDiag = ewaLanczos(SQRT2 * tapScale, radius, blur).toFloat()
        val kernel = floatArrayOf(tapScale, wAxial, wDiag, 1f / (1f + 4f * wAxial + 4f * wDiag))
        val sr = floatArrayOf(gain, nearGain, farGain, 0.82f)
    }

    private class EnhanceShaderProgram(
        private val useHdr: Boolean,
        private val owner: VideoEnhanceEffect,
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private val texelSize = FloatArray(2)

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
                glProgram.setFloatsUniform("uSigmoid", SIGMOID_PARAMS)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(1)
            this.inputHeight = inputHeight.coerceAtLeast(1)
            texelSize[0] = 1f / this.inputWidth
            texelSize[1] = 1f / this.inputHeight
            return Size(this.inputWidth, this.inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Native HDR10 / HLG / Dolby Vision: the SDR-domain math below would clip, so bypass.
                val nativeHdr = useHdr || owner.sourceIsNativeHdr
                val lvl = if (nativeHdr) 0 else owner.level.coerceIn(0, 2)
                val itm = !nativeHdr && owner.sdrToHdr
                val active = lvl > 0 || itm
                val preset = if (lvl >= 2) PRESET_STRONG else PRESET_STANDARD

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform("uTexelSize", texelSize)
                glProgram.setFloatUniform("uActive", if (active) 1f else 0f)

                // Stage A · deband
                glProgram.setFloatUniform("uDebandThreshold", if (active) preset.debandThreshold else 0f)

                // Stage B · EWA Lanczos super-resolution in sigmoid space
                glProgram.setFloatsUniform("uSr", if (lvl > 0) preset.sr else SR_OFF)
                glProgram.setFloatsUniform("uKernel", preset.kernel)

                // Stage C · inverse tone mapping
                glProgram.setFloatUniform("uItm", if (itm) 1f else 0f)

                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
                owner.recordFrame(this)
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

    companion object {
        private const val SQRT2 = 1.4142135623730951

        /** First zero of jinc, used to stretch the jinc window to the kernel radius (libplacebo). */
        private const val JINC_ZERO = 1.2196698912665045

        // libplacebo `sigmoid_center` / `sigmoid_slope` defaults.
        private const val SIGMOID_CENTER = 0.75f
        private const val SIGMOID_SLOPE = 6.5f
        private val SIGMOID_OFFSET = (1.0 / (1.0 + exp(SIGMOID_SLOPE * SIGMOID_CENTER.toDouble()))).toFloat()
        private val SIGMOID_SCALE =
            (1.0 / (1.0 + exp(SIGMOID_SLOPE * (SIGMOID_CENTER - 1.0))) - SIGMOID_OFFSET).toFloat()

        /** (center, slope, offset, scale) */
        private val SIGMOID_PARAMS = floatArrayOf(SIGMOID_CENTER, SIGMOID_SLOPE, SIGMOID_OFFSET, SIGMOID_SCALE)

        private val SR_OFF = FloatArray(4)

        /** 标准 ≈ `ewa_lanczossharp`: crisp and natural. */
        private val PRESET_STANDARD = SrPreset(
            radius = 3.2383154841662362,
            blur = 0.9812505837223707,
            tapScale = 1.25f,
            gain = 1.55f,
            nearGain = 0.38f,
            farGain = 0.17f,
            debandThreshold = 0.022f,
        )

        /** 强效 ≈ `ewa_lanczos4sharpest`: wider support, smaller blur → deeper negative lobes. */
        private val PRESET_STRONG = SrPreset(
            radius = 4.2678535886059700,
            blur = 0.8845120932605005,
            tapScale = 1.25f,
            gain = 2.05f,
            nearGain = 0.62f,
            farGain = 0.28f,
            debandThreshold = 0.022f,
        )

        /** EWA Lanczos: `jinc(r / blur) * jinc(r * JINC_ZERO / radius)` for `r < radius`. */
        private fun ewaLanczos(r: Double, radius: Double, blur: Double): Double =
            if (r >= radius) 0.0 else jinc(r / blur) * jinc(r * JINC_ZERO / radius)

        /** `jinc(x) = 2·J1(πx) / (πx)`, normalised so `jinc(0) = 1`. */
        private fun jinc(x: Double): Double {
            if (abs(x) < 1e-8) return 1.0
            val px = PI * x
            return 2.0 * besselJ1(px) / px
        }

        /** Bessel function of the first kind, order 1 (Numerical Recipes rational approximation). */
        private fun besselJ1(x: Double): Double {
            val ax = abs(x)
            return if (ax < 8.0) {
                val y = x * x
                val n = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1 +
                    y * (-2972611.439 + y * (15704.48260 + y * (-30.16036606))))))
                val d = 144725228442.0 + y * (2300535178.0 + y * (18583304.74 +
                    y * (99447.43394 + y * (376.9991397 + y))))
                n / d
            } else {
                val z = 8.0 / ax
                val y = z * z
                val xx = ax - 2.356194491
                val p = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 +
                    y * (0.2457520174e-5 + y * (-0.240337019e-6))))
                val q = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 +
                    y * (-0.88228987e-6 + y * 0.105787412e-6)))
                val ans = sqrt(0.636619772 / ax) * (cos(xx) * p - z * sin(xx) * q)
                if (x < 0.0) -ans else ans
            }
        }

        private const val VERTEX_SHADER = """
            #version 100
            precision highp float;
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        private const val ENHANCE_UNIFORMS = """
            uniform vec2 uTexelSize;
            uniform float uActive;
            uniform float uDebandThreshold;
            uniform vec4 uSr;
            uniform vec4 uKernel;
            uniform vec4 uSigmoid;
            uniform float uItm;
        """

        private const val COLOR_MATH = """
            vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
            vec3 toGamma(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }
        """

        private const val ENHANCE_BODY = """
            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            float hash12(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            // libplacebo pl_shader_sigmoidize / unsigmoidize (operating on linear light)
            vec3 sigmoidize(vec3 lin) {
                vec3 v = clamp(lin, 0.0, 1.0) * uSigmoid.w + uSigmoid.z;
                return uSigmoid.x - log(1.0 / v - 1.0) / uSigmoid.y;
            }
            vec3 unsigmoidize(vec3 s) {
                vec3 v = 1.0 / (1.0 + exp(uSigmoid.y * (uSigmoid.x - clamp(s, 0.0, 1.0))));
                return (v - uSigmoid.z) / uSigmoid.w;
            }
            vec3 sigG(vec3 gammaRgb) { return sigmoidize(toLinear(gammaRgb)); }

            float expandLuma(float y) {
                float toe = mix(0.53, 1.0, smoothstep(0.0, 0.16, y));
                if (y <= 0.62) return y * (toe + 0.30 * smoothstep(0.18, 0.255, y));
                float t = (y - 0.62) / 0.38;
                float t2 = t * t;
                float t3 = t2 * t;
                return (2.0 * t3 - 3.0 * t2 + 1.0) * 0.806
                    + (t3 - 2.0 * t2 + t) * 0.494
                    + (-2.0 * t3 + 3.0 * t2) + (t3 - t2) * 0.0608;
            }

            vec3 enhance(vec2 uv, vec3 e0) {
                vec3 e = e0;

                // ---------------- Stage A · Deband ----------------
                float debandW = 0.0;
                if (uDebandThreshold > 0.0) {
                    vec2 seed = uv / (uTexelSize * 4096.0);
                    float h1 = hash12(seed);
                    float h2 = hash12(seed + vec2(0.4231, 0.1793));
                    float h3 = hash12(seed + vec2(0.7719, 0.5381));
                    float dist = mix(2.5, 4.5, h1);
                    float ang = h2 * 6.2831853;
                    vec2 dir = vec2(cos(ang), sin(ang)) * dist;   // in texels
                    vec2 dirA = dir * uTexelSize;
                    vec2 dirB = vec2(-dir.y, dir.x) * uTexelSize; // perpendicular pair
                    vec3 o1 = sampleRgb(uv + dirA);
                    vec3 o2 = sampleRgb(uv - dirA);
                    vec3 o3 = sampleRgb(uv + dirB);
                    vec3 o4 = sampleRgb(uv - dirB);
                    vec3 dmax = max(max(abs(o1 - e0), abs(o2 - e0)), max(abs(o3 - e0), abs(o4 - e0)));
                    float diff = max(max(dmax.r, dmax.g), dmax.b);
                    debandW = 1.0 - smoothstep(uDebandThreshold * 0.4, uDebandThreshold, diff);
                    vec3 avg = (o1 + o2 + o3 + o4) * 0.25;
                    e = mix(e0, avg, debandW) + vec3((h3 - 0.5) / 255.0) * debandW;
                }

                // 3x3 neighbourhood (shared by Stage B and Stage C local luminance)
                vec2 st = uTexelSize * uKernel.x;
                vec3 tl = sampleRgb(uv + vec2(-st.x, -st.y));
                vec3 tc = sampleRgb(uv + vec2(0.0, -st.y));
                vec3 tr = sampleRgb(uv + vec2(st.x, -st.y));
                vec3 ml = sampleRgb(uv + vec2(-st.x, 0.0));
                vec3 mr = sampleRgb(uv + vec2(st.x, 0.0));
                vec3 bl = sampleRgb(uv + vec2(-st.x, st.y));
                vec3 bc = sampleRgb(uv + vec2(0.0, st.y));
                vec3 br = sampleRgb(uv + vec2(st.x, st.y));

                vec3 lin = toLinear(e);

                // ---------------- Stage B · EWA Lanczos SR (sigmoid space) ----------------
                if (uSr.x > 0.0) {
                    vec3 center = sigmoidize(lin);
                    vec3 axial = sigG(tc) + sigG(ml) + sigG(mr) + sigG(bc);
                    vec3 diag = sigG(tl) + sigG(tr) + sigG(bl) + sigG(br);
                    vec3 ewa = (center + uKernel.y * axial + uKernel.z * diag) * uKernel.w;
                    vec3 nearLeft = sampleRgb(uv - vec2(uTexelSize.x, 0.0));
                    vec3 nearRight = sampleRgb(uv + vec2(uTexelSize.x, 0.0));
                    vec3 nearTop = sampleRgb(uv - vec2(0.0, uTexelSize.y));
                    vec3 nearBottom = sampleRgb(uv + vec2(0.0, uTexelSize.y));
                    vec3 nearMean = (sigG(nearLeft) + sigG(nearRight) + sigG(nearTop) + sigG(nearBottom)) * 0.25;
                    vec3 nearMin = min(min(nearLeft, nearRight), min(nearTop, nearBottom));
                    vec3 nearMax = max(max(nearLeft, nearRight), max(nearTop, nearBottom));
                    vec3 lo = min(min(nearMin, e), min(min(tl, tr), min(bl, br)));
                    vec3 hi = max(max(nearMax, e), max(max(tl, tr), max(bl, br)));
                    float edge = max(max(hi.r - lo.r, hi.g - lo.g), hi.b - lo.b);
                    float hardEdge = smoothstep(0.23, 0.65, edge);
                    float weight = (1.0 - 0.75 * debandW) * (1.0 - 0.60 * hardEdge);
                    vec3 detail = uSr.x * (ewa - center) + uSr.y * (center - nearMean)
                        + uSr.z * (nearMean - diag * 0.25);
                    vec3 margin = vec3((1.0 - uSr.w) * min(edge, 0.035) * (1.0 - hardEdge));
                    vec3 sr = clamp(center + weight * detail, sigG(lo) - margin, sigG(hi) + margin);
                    lin = clamp(unsigmoidize(sr), 0.0, 1.0);
                }

                // ---------------- Stage C · SDR -> HDR (inverse tone mapping) ----------------
                if (uItm > 0.5) {
                    float y = clamp(dot(lin, LUMA), 0.0, 1.0);
                    float baseY = dot(toLinear(e0), LUMA);
                    vec3 axial = toLinear(tc) + toLinear(ml) + toLinear(mr) + toLinear(bc);
                    vec3 diag = toLinear(tl) + toLinear(tr) + toLinear(bl) + toLinear(br);
                    float localY = (4.0 * baseY + dot(2.0 * axial + diag, LUMA)) * 0.0625;
                    float logDetail = log2(max(baseY, 1e-5)) - log2(max(localY, 1e-5));
                    float contrast = 0.65 * smoothstep(0.13, 0.44, y)
                        * (1.0 - smoothstep(0.33, 0.92, abs(logDetail)));
                    float mappedY = expandLuma(y);
                    float delta = mappedY * (exp2(clamp(logDetail, -0.25, 0.25) * contrast) - 1.0);
                    float room = delta > 0.0 ? 1.0 - mappedY : mappedY;
                    mappedY += delta * room / max(room + abs(delta), 1e-5);

                    vec3 rgb = toGamma(lin);
                    float warm = smoothstep(0.025, 0.10, rgb.r - rgb.g)
                        * smoothstep(0.015, 0.07, rgb.g - rgb.b)
                        * (1.0 - smoothstep(0.26, 0.46, rgb.r - rgb.b));
                    float vibrance = 1.0 + 0.26 * smoothstep(0.08, 0.30, y) * (1.0 - 0.80 * warm);
                    float specular = smoothstep(0.85, 1.0, y);
                    vec3 col = vec3(mappedY) + (lin - vec3(y)) * (mappedY / max(y, 1e-5))
                        * vibrance * (1.0 - 0.36 * specular);
                    float cMax = max(max(col.r, col.g), col.b);
                    float cMin = min(min(col.r, col.g), col.b);
                    float gamut = min(1.0, (1.0 - mappedY) / max(cMax - mappedY, 1e-5));
                    gamut = min(gamut, mappedY / max(mappedY - cMin, 1e-5));
                    lin = vec3(mappedY) + (col - vec3(mappedY)) * clamp(gamut, 0.0, 1.0);
                }

                return clamp(toGamma(lin), 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            $ENHANCE_UNIFORMS
            $COLOR_MATH
            vec3 sampleRgb(vec2 uv) { return texture2D(uTexSampler, uv).rgb; }
            $ENHANCE_BODY
            void main() {
                vec4 sampleColor = texture2D(uTexSampler, vTexSamplingCoord);
                gl_FragColor = uActive < 0.5 ? sampleColor
                    : vec4(enhance(vTexSamplingCoord, sampleColor.rgb), sampleColor.a);
            }
        """

        private const val TEXTURE_SHADER = """
            uniform shader video;
            uniform float2 uSize;
            $ENHANCE_UNIFORMS
            $COLOR_MATH
            vec3 asGamma(vec4 color) {
                return toGamma(toLinearSrgb(half3(color.rgb / max(color.a, 1e-5))));
            }
            vec3 sampleRgb(vec2 xy) {
                return asGamma(video.eval(clamp(xy, vec2(0.5), uSize - vec2(0.5))));
            }
            $ENHANCE_BODY
            half4 main(float2 xy) {
                half4 sampleColor = video.eval(xy);
                if (uActive < 0.5 || sampleColor.a <= 0.0) return sampleColor;
                vec3 rgb = enhance(xy, asGamma(sampleColor));
                return half4(fromLinearSrgb(half3(toLinear(rgb))) * sampleColor.a, sampleColor.a);
            }
        """

        @RequiresApi(33)
        internal fun textureEffect(level: Int, sdrToHdr: Boolean, width: Float, height: Float): RenderEffect {
            val preset = if (level >= 2) PRESET_STRONG else PRESET_STANDARD
            val shader = RuntimeShader(TEXTURE_SHADER).apply {
                setFloatUniform("uSize", width, height)
                setFloatUniform("uTexelSize", 1f, 1f)
                setFloatUniform("uActive", 1f)
                setFloatUniform("uDebandThreshold", preset.debandThreshold)
                setFloatUniform("uSr", if (level > 0) preset.sr else SR_OFF)
                setFloatUniform("uKernel", preset.kernel)
                setFloatUniform("uSigmoid", SIGMOID_PARAMS)
                setFloatUniform("uItm", if (sdrToHdr) 1f else 0f)
            }
            return RenderEffect.createRuntimeShaderEffect(shader, "video")
        }
    }
}

private val enhanceMainHandler by lazy { Handler(Looper.getMainLooper()) }

private fun onEnhanceMain(action: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) action() else enhanceMainHandler.post { action() }
}

private val boostedWindows = WeakHashMap<Window, Float>()

/** Request wide-gamut composition and a window-local brightness override, not an HDR output mode. */
fun applyHdrWindowBoost(activity: Activity?, enabled: Boolean) = onEnhanceMain {
    if (activity == null || activity.isDestroyed) return@onEnhanceMain
    val window = activity.window
    val mode = if (enabled) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT
    if (window.colorMode != mode) window.colorMode = mode
    setHdrBrightness(activity, enabled)
}

private fun setHdrBrightness(activity: Activity, enabled: Boolean) {
    val window = activity.window
    val attrs = window.attributes
    val target = if (enabled) {
        val existing = boostedWindows[window]
        if (existing != null && attrs.screenBrightness == existing) return
        val baseline = if (attrs.screenBrightness >= 0f) attrs.screenBrightness else {
            runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }.getOrDefault(0.5f)
        }
        (baseline + 0.18f).coerceIn(0.35f, 1.0f).also { boostedWindows[window] = it }
    } else {
        boostedWindows.remove(window)
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (attrs.screenBrightness != target) {
        attrs.screenBrightness = target
        window.attributes = attrs
    }
}

/** Build the API 33+ fallback for an SDR TextureView. Returns null when disabled or unavailable. */
@androidx.annotation.OptIn(UnstableApi::class)
fun buildTextureRenderEffect(level: Int, sdrToHdr: Boolean, width: Float, height: Float): RenderEffect? {
    if (Build.VERSION.SDK_INT < 33 || (level <= 0 && !sdrToHdr) ||
        !width.isFinite() || !height.isFinite() || width < 1f || height < 1f
    ) return null
    return runCatching { VideoEnhanceEffect.textureEffect(level.coerceIn(0, 2), sdrToHdr, width, height) }
        .onFailure { Log.w("IDJ", "Could not create TextureView enhancement shader", it) }
        .getOrNull()
}

/**
 * Bind the playback window and video view once available, and resync when the view or HDR state
 * changes. Setting the same effect's level or SDR flag then updates this binding automatically.
 * Call [clearWindowAndFallback] on disposal. The view's RenderEffect slot belongs to this binding.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun syncWindowAndFallback(activity: Activity?, textureView: TextureView?, effect: VideoEnhanceEffect, isNativeHdr: Boolean) {
    effect.syncUi(activity, textureView, isNativeHdr)
}

@androidx.annotation.OptIn(UnstableApi::class)
fun clearWindowAndFallback(effect: VideoEnhanceEffect) {
    effect.clearUi()
}

@androidx.annotation.OptIn(UnstableApi::class)
private class EnhanceUiBinding(
    private val effect: VideoEnhanceEffect,
    activity: Activity?,
    view: TextureView?,
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private val activityRef = WeakReference(activity)
    private val viewRef = WeakReference(view)
    private var cachedFallback: FallbackKey? = null
    private var fallbackApplied = false
    private var ownsBrightness = false

    private data class FallbackKey(val level: Int, val hdr: Boolean, val width: Int, val height: Int)

    init {
        view?.addOnAttachStateChangeListener(this)
        view?.addOnLayoutChangeListener(this)
    }

    fun matches(activity: Activity?, view: TextureView?) = activityRef.get() === activity && viewRef.get() === view

    fun sync() {
        val view = viewRef.get()
        if (view != null && !view.isAttachedToWindow) return
        val nativeHdr = effect.isNativeHdr()
        val hdr = effect.sdrToHdr && !nativeHdr
        activityRef.get()?.takeUnless { it.isDestroyed }?.let { activity ->
            if (hdr) {
                applyHdrWindowBoost(activity, true)
                ownsBrightness = true
            } else {
                if (ownsBrightness) setHdrBrightness(activity, false)
                ownsBrightness = false
                val mode = if (nativeHdr) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT
                if (activity.window.colorMode != mode) activity.window.colorMode = mode
            }
        }
        if (Build.VERSION.SDK_INT < 33 || view == null) return
        val level = effect.level.coerceIn(0, 2)
        val desired = if (!nativeHdr && effect.framesProcessed == 0L && (level > 0 || hdr) &&
            view.isHardwareAccelerated && view.width > 0 && view.height > 0
        ) FallbackKey(level, hdr, view.width, view.height) else null
        if (desired == cachedFallback) return
        cachedFallback = desired
        val renderEffect = desired?.let { buildTextureRenderEffect(it.level, it.hdr, it.width.toFloat(), it.height.toFloat()) }
        if (renderEffect != null || fallbackApplied) view.setRenderEffect(renderEffect)
        fallbackApplied = renderEffect != null
    }

    private fun clearFallback(view: View) {
        if (Build.VERSION.SDK_INT >= 33 && fallbackApplied) view.setRenderEffect(null)
        cachedFallback = null
        fallbackApplied = false
    }

    override fun onViewAttachedToWindow(view: View) = sync()

    override fun onViewDetachedFromWindow(view: View) {
        clearFallback(view)
        resetWindow()
    }

    override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) = sync()

    private fun resetWindow() {
        activityRef.get()?.takeUnless { it.isDestroyed }?.let { activity ->
            if (ownsBrightness) applyHdrWindowBoost(activity, false)
            else activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
        ownsBrightness = false
    }

    fun close() {
        viewRef.get()?.let { view ->
            view.removeOnAttachStateChangeListener(this)
            view.removeOnLayoutChangeListener(this)
            clearFallback(view)
        }
        resetWindow()
    }
}

/** Old name kept so existing call sites / references stay valid. */
typealias CasSharpenEffect = VideoEnhanceEffect

/** Persistent player preferences (playback speed, enhance level, fill mode, ambient mode, SDR→HDR). */
object PlayerPrefs {
    private const val PREF_NAME = "idj_player_prefs"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_ENHANCE = "enhance_level"
    private const val KEY_FILL = "fill_mode"
    private const val KEY_AMBIENT = "ambient_mode"
    private const val KEY_SDR_TO_HDR = "sdr_to_hdr"

    fun getSpeed(ctx: Context): Float =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 3f)

    fun setSpeed(ctx: Context, speed: Float) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun getEnhanceLevel(ctx: Context): Int =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_ENHANCE, 0).coerceIn(0, 2)

    fun setEnhanceLevel(ctx: Context, level: Int) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_ENHANCE, level.coerceIn(0, 2)).apply()
    }

    fun getSdrToHdr(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SDR_TO_HDR, false)

    fun setSdrToHdr(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SDR_TO_HDR, on).apply()
    }

    fun getFillMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_FILL, false)

    fun setFillMode(ctx: Context, fill: Boolean) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_FILL, fill).apply()
    }

    fun getAmbientMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AMBIENT, true)

    fun setAmbientMode(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AMBIENT, on).apply()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
fun installVideoEnhance(
    player: androidx.media3.exoplayer.ExoPlayer,
    effect: VideoEnhanceEffect?,
) {
    runCatching {
        val effects: List<androidx.media3.common.Effect> =
            if (effect != null) listOf(effect) else emptyList()
        player.setVideoEffects(effects)
    }.onFailure {
        android.util.Log.w("IDJ", "setVideoEffects failed", it)
    }
}
