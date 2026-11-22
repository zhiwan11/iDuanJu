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

/**
 * Lightweight GPU real-time video enhancer using AMD FidelityFX CAS
 * (Contrast Adaptive Sharpening) via Media3 GlEffect.
 *
 * Installed once before `player.prepare()` and controlled live via `@Volatile var level`
 * so switching between Off (0), Standard (1), and Strong (2) happens on the very next
 * video frame with zero pipeline rebuild and zero black frames.
 *
 * Levels:
 * - 0: Off (`uStrength = 0.0`, exact single-read passthrough)
 * - 1: Standard CAS sharpening (`uStrength = 0.68`, sharpness = 0.62)
 * - 2: Strong CAS sharpening + micro-contrast (`uStrength = 1.0`, sharpness = 0.96, contrast = 1.075)
 */
@UnstableApi
class CasSharpenEffect(@Volatile var level: Int) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CasSharpenShaderProgram(useHdr, this)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false

    private class CasSharpenShaderProgram(
        private val useHdr: Boolean,
        private val owner: CasSharpenEffect,
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
                // HDR streams are passed through unchanged because the SDR CAS shader clamps to [0, 1].
                val curLevel = if (useHdr) 0 else owner.level
                val strong = curLevel >= 2
                val strength = when {
                    curLevel <= 0 -> 0.0f
                    strong -> 1.0f
                    else -> 0.68f
                }
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1.0f / inputWidth, 1.0f / inputHeight),
                )
                val sharpness = if (strong) 0.96f else 0.62f
                val detailBoost = if (strong) 0.58f else 0.28f
                val contrast = if (strong) 1.075f else 1.035f
                val saturation = if (strong) 1.09f else 1.04f
                val radius = if (strong) 1.35f else 1.12f
                glProgram.setFloatUniform("uStrength", strength)
                glProgram.setFloatUniform("uSharpness", sharpness)
                glProgram.setFloatUniform("uDetailBoost", detailBoost)
                glProgram.setFloatUniform("uContrast", contrast)
                glProgram.setFloatUniform("uSaturation", saturation)
                glProgram.setFloatUniform("uRadius", radius)
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

        // 9-tap 3x3 AMD FidelityFX Contrast Adaptive Sharpening + local high-frequency detail recovery + vibrance
        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uStrength;
            uniform float uSharpness;
            uniform float uDetailBoost;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uRadius;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec4 cSample = texture2D(uTexSampler, vTexSamplingCoord);
                if (uStrength <= 0.001) {
                    gl_FragColor = cSample;
                    return;
                }
                vec3 e = cSample.rgb;
                vec2 step = uTexelSize * uRadius;

                vec3 a = texture2D(uTexSampler, vTexSamplingCoord + vec2(-step.x, -step.y)).rgb;
                vec3 b = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -step.y)).rgb;
                vec3 c = texture2D(uTexSampler, vTexSamplingCoord + vec2(step.x, -step.y)).rgb;
                vec3 d = texture2D(uTexSampler, vTexSamplingCoord + vec2(-step.x, 0.0)).rgb;
                vec3 f = texture2D(uTexSampler, vTexSamplingCoord + vec2(step.x, 0.0)).rgb;
                vec3 g = texture2D(uTexSampler, vTexSamplingCoord + vec2(-step.x, step.y)).rgb;
                vec3 h = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, step.y)).rgb;
                vec3 i = texture2D(uTexSampler, vTexSamplingCoord + vec2(step.x, step.y)).rgb;

                // Soft 3x3 neighborhood min/max (cross blended with full 3x3 box)
                vec3 mnCross = min(min(min(d, e), min(f, b)), h);
                vec3 mxCross = max(max(max(d, e), max(f, b)), h);
                vec3 mnBox = min(mnCross, min(min(a, c), min(g, i)));
                vec3 mxBox = max(mxCross, max(max(a, c), max(g, i)));
                vec3 mnRGB = (mnCross + mnBox) * 0.5;
                vec3 mxRGB = (mxCross + mxBox) * 0.5;

                vec3 rcpMRGB = 1.0 / max(mxRGB, vec3(0.0001));
                vec3 ampRGB = clamp(min(mnRGB, 1.0 - mxRGB) * rcpMRGB, 0.0, 1.0);
                ampRGB = sqrt(ampRGB);

                float peak = -1.0 / mix(8.0, 4.2, clamp(uSharpness, 0.0, 1.0));
                vec3 wRGB = ampRGB * peak;
                vec3 rcpWeightRGB = 1.0 / (1.0 + 4.0 * wRGB);

                vec3 casColor = clamp(((b + d + f + h) * wRGB + e) * rcpWeightRGB, 0.0, 1.0);

                // Local high-frequency detail recovery clamped to neighborhood bounds to avoid haloing
                vec3 blur3x3 = (b + d + f + h) * 0.15 + (a + c + g + i) * 0.05 + e * 0.20;
                vec3 highFreq = e - blur3x3;
                vec3 minBound = max(mnBox - vec3(0.04), vec3(0.0));
                vec3 maxBound = min(mxBox + vec3(0.04), vec3(1.0));
                vec3 sharpened = clamp(casColor + highFreq * (uDetailBoost * ampRGB), minBound, maxBound);

                // Micro-contrast around midpoint + subtle luma-preserving saturation boost
                vec3 contrasted = clamp((sharpened - 0.5) * uContrast + 0.5, 0.0, 1.0);
                float luma = dot(contrasted, vec3(0.299, 0.587, 0.114));
                vec3 enhanced = clamp(mix(vec3(luma), contrasted, uSaturation), 0.0, 1.0);
                vec3 outColor = mix(e, enhanced, clamp(uStrength, 0.0, 1.0));
                gl_FragColor = vec4(outColor, cSample.a);
            }
        """
    }
}

/** Persistent player preferences (playback speed, enhance level, fill mode, ambient mode). */
object PlayerPrefs {
    private const val PREF_NAME = "idj_player_prefs"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_ENHANCE = "enhance_level"
    private const val KEY_FILL = "fill_mode"
    private const val KEY_AMBIENT = "ambient_mode"

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
    effect: CasSharpenEffect?,
) {
    runCatching {
        val effects: List<androidx.media3.common.Effect> =
            if (effect != null) listOf(effect) else emptyList()
        player.setVideoEffects(effects)
    }.onFailure {
        android.util.Log.w("IDJ", "setVideoEffects failed", it)
    }
}
