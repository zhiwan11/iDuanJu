package app.iduanju.ui.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SenPlayer-grade (mpv + libplacebo style) single-pass GPU video enhancer for Media3.
 *
 * One persistent [GlEffect] is installed before `player.prepare()`; [level] and [sdrToHdr] are
 * `@Volatile` and read on every frame, so toggling them takes effect on the very next frame with
 * no pipeline rebuild and no black frames.
 *
 * Pipeline (all in one fragment shader, each stage gated by uniforms):
 * - **Stage A · Deband** (`deband=yes`) — active when `level > 0 || sdrToHdr`.
 *   4 samples at a per-pixel random direction/distance (2.5..4.5 texels); if they all sit within
 *   the banding threshold of the centre, blend toward their mean and add ±0.5/255 dither.
 * - **Stage B · AI Super-Resolution** (`scale=ewa_lanczossharp` / `ewa_lanczos4sharpest`,
 *   `sigmoid-upscaling=yes`, `antiring`) — active when `level > 0`.
 *   The 3×3 neighbourhood is linearised and sigmoidised (centre 0.75, slope 6.5), filtered with
 *   real EWA Lanczos (jinc · jinc-window) weights evaluated at the tap radii, the negative-lobe
 *   response is applied with a CAS-style local-contrast adaptive weight, clamped to the
 *   neighbourhood range (anti-ringing) and un-sigmoidised.
 *   Media3 renders effects at source resolution (the TextureView does the final upscale), so the
 *   EWA kernel is used as a same-resolution reconstruction/sharpening filter.
 * - **Stage C · SDR → HDR** (`inverse-tone-mapping`, `hdr-contrast-recovery=0.30`,
 *   `tone-mapping-desaturate=0.5`, `gamut-mapping-mode=desaturate`) — active when `sdrToHdr`.
 *   Linear-light luminance gets a shadow toe + BT.2390-style Hermite highlight expansion,
 *   local log-luminance detail is recovered in highlights, midtone chroma is expanded ×1.12,
 *   speculars are desaturated and out-of-range colours are luma-preservingly gamut mapped.
 *
 * Native HDR streams (`useHdr == true`) and the all-off state are exact single-read passthrough.
 *
 * Levels: `0 = 关闭`, `1 = 标准 (ewa_lanczossharp)`, `2 = 强效 (ewa_lanczos4sharpest)`.
 */
@UnstableApi
class VideoEnhanceEffect(
    @Volatile var level: Int,
    @Volatile var sdrToHdr: Boolean = false,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EnhanceShaderProgram(useHdr, this)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    /** Per-level libplacebo-equivalent scaler preset, with EWA tap weights precomputed. */
    private class SrPreset(
        radius: Double,
        blur: Double,
        /** Tap distance in texels (the 3×3 kernel is sampled at `r * tapScale`). */
        val tapScale: Float,
        val gain: Float,
        val antiring: Float,
        val debandThreshold: Float,
    ) {
        val wAxial: Float = ewaLanczos(1.0 * tapScale, radius, blur).toFloat()
        val wDiag: Float = ewaLanczos(SQRT2 * tapScale, radius, blur).toFloat()
        val wNorm: Float = 1f / (1f + 4f * wAxial + 4f * wDiag)
    }

    private class EnhanceShaderProgram(
        private val useHdr: Boolean,
        private val owner: VideoEnhanceEffect,
    ) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

        private val glProgram: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1

        init {
            try {
                glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
                glProgram.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(1)
            this.inputHeight = inputHeight.coerceAtLeast(1)
            return Size(this.inputWidth, this.inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                // Native HDR10 / HLG / Dolby Vision: the SDR-domain math below would clip, so bypass.
                val lvl = if (useHdr) 0 else owner.level.coerceIn(0, 2)
                val itm = !useHdr && owner.sdrToHdr
                val active = lvl > 0 || itm
                val preset = if (lvl >= 2) PRESET_STRONG else PRESET_STANDARD

                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform("uTexelSize", floatArrayOf(1.0f / inputWidth, 1.0f / inputHeight))
                glProgram.setFloatUniform("uActive", if (active) 1f else 0f)

                // Stage A · deband
                glProgram.setFloatUniform("uDebandThreshold", if (active) preset.debandThreshold else 0f)

                // Stage B · EWA Lanczos super-resolution in sigmoid space
                glProgram.setFloatUniform("uSrGain", if (lvl > 0) preset.gain else 0f)
                glProgram.setFloatUniform("uTapScale", preset.tapScale)
                glProgram.setFloatUniform("uWAxial", preset.wAxial)
                glProgram.setFloatUniform("uWDiag", preset.wDiag)
                glProgram.setFloatUniform("uWNorm", preset.wNorm)
                glProgram.setFloatUniform("uAntiring", preset.antiring)
                glProgram.setFloatsUniform("uSigmoid", SIGMOID_PARAMS)

                // Stage C · inverse tone mapping
                glProgram.setFloatUniform("uItm", if (itm) 1f else 0f)
                glProgram.setFloatsUniform("uItmCurve", ITM_CURVE)
                glProgram.setFloatsUniform("uItmColor", ITM_COLOR)

                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
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

        /**
         * Inverse tone mapping curve: (knee, shoulder slope at peak, shadow toe depth, hdr-contrast-recovery).
         * Knee 0.35 linear, shoulder 0.35 lifts the 0.35..1.0 range by up to ~+0.07 linear.
         */
        private val ITM_CURVE = floatArrayOf(0.35f, 0.35f, 0.12f, 0.30f)

        /** (midtone chroma expansion, tone-mapping-desaturate, specular start, unused) */
        private val ITM_COLOR = floatArrayOf(1.12f, 0.5f, 0.88f, 0f)

        /** 标准 ≈ `ewa_lanczossharp`: crisp and natural. */
        private val PRESET_STANDARD = SrPreset(
            radius = 3.2383154841662362,
            blur = 0.9812505837223707,
            tapScale = 1.25f,
            gain = 0.90f,
            antiring = 0.85f,
            debandThreshold = 0.022f,
        )

        /** 强效 ≈ `ewa_lanczos4sharpest`: wider support, smaller blur → deeper negative lobes. */
        private val PRESET_STRONG = SrPreset(
            radius = 4.2678535886059700,
            blur = 0.8845120932605005,
            tapScale = 1.25f,
            gain = 1.15f,
            antiring = 0.85f,
            debandThreshold = 0.028f,
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

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uActive;
            // Stage A
            uniform float uDebandThreshold;
            // Stage B
            uniform float uSrGain;
            uniform float uTapScale;
            uniform float uWAxial;
            uniform float uWDiag;
            uniform float uWNorm;
            uniform float uAntiring;
            uniform vec4 uSigmoid;   // center, slope, offset, scale
            // Stage C
            uniform float uItm;
            uniform vec4 uItmCurve;  // knee, shoulder slope, toe depth, contrast recovery
            uniform vec4 uItmColor;  // chroma expansion, desaturate, specular start, -
            varying vec2 vTexSamplingCoord;

            const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

            float hash12(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 toLinear(vec3 c) { return pow(max(c, vec3(0.0)), vec3(2.2)); }
            vec3 toGamma(vec3 c) { return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2)); }

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

            void main() {
                vec4 cSample = texture2D(uTexSampler, vTexSamplingCoord);
                if (uActive < 0.5) {
                    gl_FragColor = cSample;
                    return;
                }
                vec2 uv = vTexSamplingCoord;
                vec3 e0 = cSample.rgb;
                vec3 e = e0;

                // ---------------- Stage A · Deband ----------------
                float debandW = 0.0;
                if (uDebandThreshold > 0.0) {
                    float h1 = hash12(uv);
                    float h2 = hash12(uv + vec2(0.4231, 0.1793));
                    float h3 = hash12(uv + vec2(0.7719, 0.5381));
                    float dist = mix(2.5, 4.5, h1);
                    float ang = h2 * 6.2831853;
                    vec2 dir = vec2(cos(ang), sin(ang)) * dist;   // in texels
                    vec2 dirA = dir * uTexelSize;
                    vec2 dirB = vec2(-dir.y, dir.x) * uTexelSize; // perpendicular pair
                    vec3 o1 = texture2D(uTexSampler, uv + dirA).rgb;
                    vec3 o2 = texture2D(uTexSampler, uv - dirA).rgb;
                    vec3 o3 = texture2D(uTexSampler, uv + dirB).rgb;
                    vec3 o4 = texture2D(uTexSampler, uv - dirB).rgb;
                    vec3 dmax = max(max(abs(o1 - e0), abs(o2 - e0)), max(abs(o3 - e0), abs(o4 - e0)));
                    float diff = max(max(dmax.r, dmax.g), dmax.b);
                    debandW = 1.0 - smoothstep(uDebandThreshold * 0.4, uDebandThreshold, diff);
                    vec3 avg = (o1 + o2 + o3 + o4) * 0.25;
                    e = mix(e0, avg, debandW) + vec3((h3 - 0.5) / 255.0) * debandW;
                }

                // 3x3 neighbourhood (shared by Stage B and Stage C local luminance)
                vec2 st = uTexelSize * uTapScale;
                vec3 a = texture2D(uTexSampler, uv + vec2(-st.x, -st.y)).rgb;
                vec3 b = texture2D(uTexSampler, uv + vec2(0.0, -st.y)).rgb;
                vec3 c = texture2D(uTexSampler, uv + vec2(st.x, -st.y)).rgb;
                vec3 d = texture2D(uTexSampler, uv + vec2(-st.x, 0.0)).rgb;
                vec3 f = texture2D(uTexSampler, uv + vec2(st.x, 0.0)).rgb;
                vec3 g = texture2D(uTexSampler, uv + vec2(-st.x, st.y)).rgb;
                vec3 h = texture2D(uTexSampler, uv + vec2(0.0, st.y)).rgb;
                vec3 i = texture2D(uTexSampler, uv + vec2(st.x, st.y)).rgb;

                vec3 lin = toLinear(e);

                // ---------------- Stage B · EWA Lanczos SR (sigmoid space) ----------------
                if (uSrGain > 0.0) {
                    vec3 sa = sigG(a); vec3 sb = sigG(b); vec3 sc = sigG(c);
                    vec3 sd = sigG(d); vec3 se = sigmoidize(lin); vec3 sf = sigG(f);
                    vec3 sg = sigG(g); vec3 sh = sigG(h); vec3 si = sigG(i);

                    // EWA reconstruction: radial weights 1 (centre), w(1) axial, w(sqrt2) diagonal
                    vec3 ewa = (se + uWAxial * (sb + sd + sf + sh) + uWDiag * (sa + sc + sg + si)) * uWNorm;

                    // Local-contrast adaptive weight (CAS-style) on the gamma neighbourhood
                    vec3 mnCross = min(min(min(d, e), min(f, b)), h);
                    vec3 mxCross = max(max(max(d, e), max(f, b)), h);
                    vec3 mnBox = min(mnCross, min(min(a, c), min(g, i)));
                    vec3 mxBox = max(mxCross, max(max(a, c), max(g, i)));
                    vec3 mnRGB = (mnCross + mnBox) * 0.5;
                    vec3 mxRGB = (mxCross + mxBox) * 0.5;
                    vec3 ampRGB = sqrt(clamp(min(mnRGB, 1.0 - mxRGB) / max(mxRGB, vec3(0.0001)), 0.0, 1.0));

                    // Don't sharpen what deband just flattened
                    float gain = uSrGain * (1.0 - 0.75 * debandW);
                    vec3 sr = se + (ewa - se) * (gain * (0.35 + 0.65 * ampRGB));

                    // Anti-ringing: clamp to the 3x3 range (sigmoid is monotonic) with a small margin
                    vec3 sMn = min(min(min(sa, sb), min(sc, sd)), min(min(se, sf), min(min(sg, sh), si)));
                    vec3 sMx = max(max(max(sa, sb), max(sc, sd)), max(max(se, sf), max(max(sg, sh), si)));
                    vec3 margin = (sMx - sMn) * (1.0 - uAntiring) * 0.5;
                    sr = clamp(sr, sMn - margin, sMx + margin);

                    lin = clamp(unsigmoidize(sr), 0.0, 1.0);
                }

                // ---------------- Stage C · SDR -> HDR (inverse tone mapping) ----------------
                if (uItm > 0.5) {
                    float Y = max(dot(lin, LUMA), 1e-5);
                    vec3 gauss = (e0 * 4.0 + (b + d + f + h) * 2.0 + (a + c + g + i)) * 0.0625;
                    float Yl = max(dot(toLinear(gauss), LUMA), 1e-5);

                    float knee = uItmCurve.x;
                    // Shadow toe: deepen the black floor up to toe-depth, fading out by 0.18
                    float Yn = Y * mix(1.0 - uItmCurve.z, 1.0, smoothstep(0.0, 0.18, Y));
                    // BT.2390-style Hermite expansion above the knee (unit slope in, shoulder slope at peak)
                    if (Y > knee) {
                        float span = 1.0 - knee;
                        float t = clamp((Y - knee) / span, 0.0, 1.0);
                        float t2 = t * t;
                        float t3 = t2 * t;
                        float m0 = span;
                        float m1 = span * uItmCurve.y;
                        Yn = (2.0 * t3 - 3.0 * t2 + 1.0) * knee + (t3 - 2.0 * t2 + t) * m0
                            + (-2.0 * t3 + 3.0 * t2) + (t3 - t2) * m1;
                    }
                    // hdr-contrast-recovery: re-inject local log-luminance detail in highlights
                    float logDetail = clamp(log2(Y) - log2(Yl), -1.0, 1.0);
                    float hiW = smoothstep(knee, knee + 0.30, Y);
                    Yn *= exp2(logDetail * uItmCurve.w * hiW);
                    Yn = clamp(Yn, 0.0, 1.0);

                    vec3 col = lin * (Yn / Y);
                    // Midtone chroma expansion + tone-mapping-desaturate on speculars
                    float midW = smoothstep(0.02, 0.18, Yn) * (1.0 - smoothstep(0.60, uItmColor.z, Yn));
                    float sat = 1.0 + (uItmColor.x - 1.0) * midW;
                    float desat = uItmColor.y * smoothstep(uItmColor.z, 1.0, Yn);
                    col = vec3(Yn) + (col - vec3(Yn)) * sat * (1.0 - desat);
                    // gamut-mapping-mode=desaturate: pull out-of-range colours toward luma
                    float cMax = max(max(col.r, col.g), col.b);
                    float cMin = min(min(col.r, col.g), col.b);
                    float k = 1.0;
                    if (cMax > 1.0) k = min(k, (1.0 - Yn) / max(cMax - Yn, 1e-5));
                    if (cMin < 0.0) k = min(k, Yn / max(Yn - cMin, 1e-5));
                    lin = vec3(Yn) + (col - vec3(Yn)) * clamp(k, 0.0, 1.0);
                }

                gl_FragColor = vec4(clamp(toGamma(lin), 0.0, 1.0), cSample.a);
            }
        """
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
