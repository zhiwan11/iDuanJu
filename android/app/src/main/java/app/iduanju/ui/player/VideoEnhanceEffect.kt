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
 * Levels:
 * - 0: Off (no GlEffect attached, zero GPU overhead)
 * - 1: Standard CAS sharpening (sharpness = 0.55)
 * - 2: Strong CAS sharpening + subtle micro-contrast (sharpness = 0.92, contrast = 1.04)
 */
@UnstableApi
class CasSharpenEffect(private val level: Int) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CasSharpenShaderProgram(useHdr, level)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = level <= 0

    private class CasSharpenShaderProgram(
        useHdr: Boolean,
        private val level: Int,
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
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
                glProgram.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1.0f / inputWidth, 1.0f / inputHeight),
                )
                val sharpness = if (level >= 2) 0.92f else 0.55f
                val contrast = if (level >= 2) 1.04f else 1.015f
                glProgram.setFloatUniform("uSharpness", sharpness)
                glProgram.setFloatUniform("uContrast", contrast)
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
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        // Contrast Adaptive Sharpening (5-tap cross filter with local dynamic-range weighting)
        private const val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uSharpness;
            uniform float uContrast;
            varying vec2 vTexSamplingCoord;

            void main() {
                vec4 cSample = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 e = cSample.rgb;
                vec3 b = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, -uTexelSize.y)).rgb;
                vec3 d = texture2D(uTexSampler, vTexSamplingCoord + vec2(-uTexelSize.x, 0.0)).rgb;
                vec3 f = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb;
                vec3 h = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb;

                vec3 mnRGB = min(min(min(d, e), min(f, b)), h);
                vec3 mxRGB = max(max(max(d, e), max(f, b)), h);

                vec3 rcpMRGB = 1.0 / max(mxRGB, vec3(0.0001));
                vec3 ampRGB = clamp(min(mnRGB, 1.0 - mxRGB) * rcpMRGB, 0.0, 1.0);
                ampRGB = sqrt(ampRGB);

                float peak = -1.0 / mix(8.0, 5.0, clamp(uSharpness, 0.0, 1.0));
                vec3 wRGB = ampRGB * peak;
                vec3 rcpWeightRGB = 1.0 / (1.0 + 4.0 * wRGB);

                vec3 sharpened = clamp(((b + d + f + h) * wRGB + e) * rcpWeightRGB, 0.0, 1.0);
                // Subtle micro-contrast around midpoint without clipping
                vec3 outColor = clamp((sharpened - 0.5) * uContrast + 0.5, 0.0, 1.0);
                gl_FragColor = vec4(outColor, cSample.a);
            }
        """
    }
}

/** Persistent player preferences (playback speed, enhance level, fill mode). */
object PlayerPrefs {
    private const val PREF_NAME = "idj_player_prefs"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_ENHANCE = "enhance_level"
    private const val KEY_FILL = "fill_mode"

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
}

@androidx.annotation.OptIn(UnstableApi::class)
fun applyVideoEnhance(player: androidx.media3.exoplayer.ExoPlayer, level: Int) {
    runCatching {
        val effects: List<androidx.media3.common.Effect> =
            if (level > 0) listOf(CasSharpenEffect(level)) else emptyList()
        player.setVideoEffects(effects)
    }.onFailure {
        android.util.Log.w("IDJ", "setVideoEffects failed", it)
    }
}
