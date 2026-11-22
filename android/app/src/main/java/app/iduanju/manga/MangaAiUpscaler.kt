package app.iduanju.manga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 漫画 AI 超分「增强」: hybrid neural + Anime4K restoration pipeline.
 *
 * Why the previous implementation looked invisible / hung on real devices:
 * 1. It ran 4× Real-ESRGAN ONNX on CPU up to 1280×1920 (~48 tiles = 20–78 s per page), so users flipped pages
 *    long before inference ever finished.
 * 2. It applied 100% source back-projection (`residual = src - avg4x4(sr)`), pulling the neural output straight back
 *    toward the blurry/compressed original and erasing most of the sharpening.
 *
 * Now:
 * - **Low-res scans** (≤ 576×864, at most 6 tiles of 288×288): run full native-res 4× Real-ESRGAN ONNX in ~0.8–2.2 s
 *   with a gentle 20% anchor (preserving 80% of neural detail), followed by Anime4K Line Darken + clean levels.
 * - **Medium & HD pages** (everything larger, including 600×900, 1200×1800, and webtoon strips): run a fast
 *   multi-stage CPU Anime4K Restore (2×/1.5× edge-directed upscale for medium scans, native-res for HD) in
 *   ~80–200 ms: Edge-Preserving Bilateral Denoise → Anime4K DoG Deblur → Anime4K Darken HQ → Sobel Edge Thinning &
 *   clean paper/ink levels.
 */
object MangaAiUpscaler {
    private const val SCALE = 4
    private const val ONNX_MAX_SHORT = 576
    private const val ONNX_MAX_LONG = 864
    private const val ONNX_MAX_PIXELS = ONNX_MAX_SHORT * ONNX_MAX_LONG
    private const val MAX_OUTPUT_PIXELS = 6_000_000
    private const val MAX_OUTPUT_SIDE = 8192
    /** Divisible by 3 so tile edges (×4) always land on k×k box edges for k = 2, 3, 4. */
    private const val TILE = 288
    private const val PAD = 12
    private const val PADDED_TILE = TILE + 2 * PAD
    private const val BACK_PROJ_WEIGHT = 0.20f

    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 6).coerceIn(48L shl 20, 160L shl 20).toInt()
    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inferenceLock = Mutex()
    /** True while a page is being processed (Compose-observable): preloaded neighbours wait until idle. */
    var busy by mutableStateOf(false)
        private set
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    /** Only touched under [inferenceLock]. */
    private var session: OrtSession? = null

    fun getCached(key: String): Bitmap? = cache.get(key)

    internal sealed class Plan {
        data class Neural(val workWidth: Int, val workHeight: Int, val box: Int) : Plan() {
            val outWidth get() = ceil(workWidth * SCALE / box.toDouble()).toInt()
            val outHeight get() = ceil(workHeight * SCALE / box.toDouble()).toInt()
        }
        data class Restore(val outWidth: Int, val outHeight: Int) : Plan()
    }

    internal fun plan(srcWidth: Int, srcHeight: Int, viewWidth: Int, viewHeight: Int, fillWidth: Boolean): Plan? {
        if (srcWidth < 8 || srcHeight < 8) return null
        val pixels = srcWidth.toDouble() * srcHeight

        // Low-res scans: Real-ESRGAN 4× ONNX (at most 2×3 = 6 tiles -> ~0.8–2.2 s on phone)
        if (pixels <= ONNX_MAX_PIXELS && min(srcWidth, srcHeight) <= ONNX_MAX_SHORT && max(srcWidth, srcHeight) <= ONNX_MAX_LONG) {
            val preferredBox = if (pixels <= 360.0 * 540.0) 1 else 2
            val box = (preferredBox..4).firstOrNull { k ->
                val ow = ceil(srcWidth * SCALE / k.toDouble()); val oh = ceil(srcHeight * SCALE / k.toDouble())
                ow * oh <= MAX_OUTPUT_PIXELS && max(ow, oh) <= MAX_OUTPUT_SIDE
            } ?: return null
            return Plan.Neural(srcWidth, srcHeight, box)
        }

        // Medium scans: 1.5×–2× Anime4K Edge-Directed Upscale + 4-stage Anime4K Restore
        if (pixels <= 950_000.0 && min(srcWidth, srcHeight) <= 840 && max(srcWidth, srcHeight) <= 1260) {
            val target = if (pixels <= 600_000.0) 2.0 else 1.5
            val cap = min(target, sqrt(4_200_000.0 / pixels))
            val ow = (srcWidth * cap).roundToInt().coerceIn(srcWidth, MAX_OUTPUT_SIDE)
            val oh = (srcHeight * cap).roundToInt().coerceIn(srcHeight, MAX_OUTPUT_SIDE)
            return Plan.Restore(ow, oh)
        }

        // High-res pages & webtoon strips: native-res 4-stage Anime4K Restore (never below screen size)
        val fit = when {
            viewWidth <= 0 -> 1.0
            fillWidth || viewHeight <= 0 -> viewWidth.toDouble() / srcWidth
            else -> min(viewWidth.toDouble() / srcWidth, viewHeight.toDouble() / srcHeight)
        }
        val floor = min(1.0, fit)
        val scale = if (pixels <= MAX_OUTPUT_PIXELS) 1.0 else max(floor, sqrt(MAX_OUTPUT_PIXELS / pixels))
        val w = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (srcHeight * scale).roundToInt().coerceAtLeast(1)
        if (w.toLong() * h > MAX_OUTPUT_PIXELS * 13L / 10 || max(w, h) > MAX_OUTPUT_SIDE) return null
        return Plan.Restore(w, h)
    }

    /** Hybrid AI / Anime4K enhancement for a software [source] bitmap; null if skipped / failed. */
    suspend fun upscale(context: Context, key: String, source: Bitmap, viewWidth: Int, viewHeight: Int, fillWidth: Boolean): Bitmap? {
        getCached(key)?.let { return it }
        val plan = plan(source.width, source.height, viewWidth, viewHeight, fillWidth) ?: return null
        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                getCached(key) ?: try {
                    busy = true
                    val out = when (plan) {
                        is Plan.Neural -> inferNeural(getSession(context), source, plan)
                        is Plan.Restore -> restoreAnime4K(source, plan.outWidth, plan.outHeight)
                    }
                    out.also { cache.put(key, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.w("IDJ", "manga AI upscale failed", e)
                    null
                } finally {
                    busy = false
                }
            }
        }
    }

    // Plain ORT CPU (MLAS) kernels: measured ~2.7× faster for this model than the XNNPACK EP with dynamic tile shapes.
    private fun getSession(context: Context): OrtSession = session ?: OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val model = context.assets.open("models/realesr_anime_v3_x4.onnx").use { it.readBytes() }
        environment.createSession(model, options).also { session = it }
    }

    /** Area-correct resize to [width]×[height]; returns a software ARGB_8888 bitmap (may be [source] itself). */
    private fun resizeSoftware(source: Bitmap, width: Int, height: Int): Bitmap {
        var current = if (source.config == Bitmap.Config.ARGB_8888) source else source.copy(Bitmap.Config.ARGB_8888, false)
        if (current.width == width && current.height == height) return current
        while (current.width / 2 >= width && current.height / 2 >= height) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        val last = Bitmap.createScaledBitmap(current, width, height, true)
        if (current !== source && current !== last) current.recycle()
        return last
    }

    /** B/W manga: < 0.5 % of sampled pixels carry colour ⇒ keep output neutral grey (no tint / chroma noise). */
    private fun isGrey(bitmap: Bitmap): Boolean {
        val step = max(1, sqrt(bitmap.width.toDouble() * bitmap.height / 40_000).toInt())
        val row = IntArray(bitmap.width)
        var total = 0; var colour = 0
        for (y in 0 until bitmap.height step step) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width step step) {
                val p = row[x]
                val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
                if (max(abs(r - g), max(abs(g - b), abs(r - b))) > 24) colour++
                total++
            }
        }
        return colour * 200 <= total
    }

    private suspend fun inferNeural(session: OrtSession, source: Bitmap, plan: Plan.Neural): Bitmap {
        val work = resizeSoftware(source, plan.workWidth, plan.workHeight)
        try {
            val k = plan.box
            val outWidth = plan.outWidth
            val outHeight = plan.outHeight
            val grey = isGrey(work)
            val outPixels = IntArray(outWidth * outHeight)
            val tilePixels = IntArray(PADDED_TILE * PADDED_TILE)
            val src = FloatArray(3 * PADDED_TILE * PADDED_TILE)
            val residual = FloatArray(3 * PADDED_TILE * PADDED_TILE)
            val sr = FloatArray(3 * PADDED_TILE * PADDED_TILE * SCALE * SCALE)
            val input = floatBuffer(src.size)
            val output = floatBuffer(sr.size)
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()
            val inv = 1f / 255f
            for (top in 0 until work.height step TILE) {
                for (left in 0 until work.width step TILE) {
                    currentCoroutineContext().ensureActive()
                    val right = min(left + TILE, work.width)
                    val bottom = min(top + TILE, work.height)
                    val x0 = max(0, left - PAD)
                    val y0 = max(0, top - PAD)
                    val width = min(right + PAD, work.width) - x0
                    val height = min(bottom + PAD, work.height) - y0
                    val plane = width * height

                    work.getPixels(tilePixels, 0, width, x0, y0, width, height)
                    for (i in 0 until plane) {
                        val p = tilePixels[i]
                        src[i] = (p ushr 16 and 255) * inv
                        src[plane + i] = (p ushr 8 and 255) * inv
                        src[2 * plane + i] = (p and 255) * inv
                    }
                    input.clear(); input.put(src, 0, 3 * plane); input.flip()

                    val ow = width * SCALE
                    val oh = height * SCALE
                    val outPlane = ow * oh
                    output.clear(); output.limit(3 * outPlane)
                    tensor(input, width, height).use { inputTensor ->
                        tensor(output, ow, oh).use { outputTensor ->
                            session.run(mapOf(inputName to inputTensor), mapOf(outputName to outputTensor)).close()
                        }
                    }
                    output.rewind(); output.get(sr, 0, 3 * outPlane)

                    // Gentle back-projection residual (20% anchor) so fine text stays intact without erasing neural sharpness
                    for (c in 0 until 3) {
                        val so = c * plane; val oo = c * outPlane
                        for (y in 0 until height) for (x in 0 until width) {
                            var sum = 0f
                            val base = oo + y * SCALE * ow + x * SCALE
                            for (dy in 0 until SCALE) { val r = base + dy * ow; sum += sr[r] + sr[r + 1] + sr[r + 2] + sr[r + 3] }
                            residual[so + y * width + x] = (src[so + y * width + x] - sum / 16f) * BACK_PROJ_WEIGHT
                        }
                    }

                    val cropX = (left - x0) * SCALE
                    val cropY = (top - y0) * SCALE
                    val coreW = (right - left) * SCALE
                    val coreH = (bottom - top) * SCALE
                    val fx0 = left * SCALE / k
                    val fy0 = top * SCALE / k
                    val fw = (coreW + k - 1) / k
                    val fh = (coreH + k - 1) / k
                    for (fy in 0 until fh) {
                        val oy = fy0 + fy
                        if (oy >= outHeight) break
                        for (fx in 0 until fw) {
                            val ox = fx0 + fx
                            if (ox >= outWidth) break
                            var rs = 0f; var gs = 0f; var bs = 0f; var n = 0
                            for (sy in fy * k until min(fy * k + k, coreH)) {
                                val ty = cropY + sy
                                val v = (ty + 0.5f) / SCALE - 0.5f
                                val vf = floor(v)
                                val ya = vf.toInt().coerceIn(0, height - 1); val yb = (vf.toInt() + 1).coerceIn(0, height - 1)
                                val wy = (v - vf).coerceIn(0f, 1f)
                                for (sx in fx * k until min(fx * k + k, coreW)) {
                                    val tx = cropX + sx
                                    val u = (tx + 0.5f) / SCALE - 0.5f
                                    val uf = floor(u)
                                    val xa = uf.toInt().coerceIn(0, width - 1); val xb = (uf.toInt() + 1).coerceIn(0, width - 1)
                                    val wx = (u - uf).coerceIn(0f, 1f)
                                    val i00 = ya * width + xa; val i01 = ya * width + xb; val i10 = yb * width + xa; val i11 = yb * width + xb
                                    val w00 = (1 - wx) * (1 - wy); val w01 = wx * (1 - wy); val w10 = (1 - wx) * wy; val w11 = wx * wy
                                    val o = ty * ow + tx
                                    rs += sr[o] + residual[i00] * w00 + residual[i01] * w01 + residual[i10] * w10 + residual[i11] * w11
                                    val g = plane
                                    gs += sr[outPlane + o] + residual[g + i00] * w00 + residual[g + i01] * w01 + residual[g + i10] * w10 + residual[g + i11] * w11
                                    val b = 2 * plane
                                    bs += sr[2 * outPlane + o] + residual[b + i00] * w00 + residual[b + i01] * w01 + residual[b + i10] * w10 + residual[b + i11] * w11
                                    n++
                                }
                            }
                            if (n == 0) continue
                            var r = rs / n; var g = gs / n; var b = bs / n
                            if (grey) { val l = 0.299f * r + 0.587f * g + 0.114f * b; r = l; g = l; b = l }
                            outPixels[oy * outWidth + ox] = 0xff000000.toInt() or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
                        }
                    }
                }
            }
            // Finish neural output with Anime4K Line Darken + clean paper/ink levels
            currentCoroutineContext().ensureActive()
            applyDarkenAndLevels(outPixels, outWidth, outHeight, grey, darkenStrength = 0.70f)
            return Bitmap.createBitmap(outPixels, outWidth, outHeight, Bitmap.Config.ARGB_8888).apply { setHasMipMap(true) }
        } finally {
            if (work !== source) work.recycle()
        }
    }

    /**
     * 4-stage Anime4K Restore in ~80–200 ms:
     * 1. Edge-preserving bilateral denoise (cleans JPEG compression artifacts without touching lines)
     * 2. Anime4K DoG Deblur (5×5 separable Gaussian + 0.55 power curve + 3×3 minmax clamp)
     * 3. Anime4K Darken HQ (separable Gaussian difference + smoothed negative line map)
     * 4. Sobel edge-directed thinning + clean paper/ink levels
     */
    private suspend fun restoreAnime4K(source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val work = resizeSoftware(source, outWidth, outHeight)
        try {
            val grey = isGrey(work)
            val n = outWidth * outHeight
            val pixels = IntArray(n)
            work.getPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
            val lumaOrig = FloatArray(n)
            val luma = FloatArray(n)
            val bufA = FloatArray(n)
            val bufB = FloatArray(n)

            val inv = 1f / 255f
            for (i in 0 until n) {
                val p = pixels[i]
                val l = (0.299f * (p ushr 16 and 255) + 0.587f * (p ushr 8 and 255) + 0.114f * (p and 255)) * inv
                lumaOrig[i] = l
                luma[i] = l
            }

            // Stage 1: Fast 3×3 edge-preserving bilateral denoise
            currentCoroutineContext().ensureActive()
            bilateralDenoise3x3(luma, bufA, outWidth, outHeight, sigmaRange = 0.065f)
            System.arraycopy(bufA, 0, luma, 0, n)

            // Stage 2: Anime4K DoG Deblur
            currentCoroutineContext().ensureActive()
            gaussian5x5(luma, bufA, bufB, outWidth, outHeight)
            applyDoGDeblur(luma, bufA, bufB, outWidth, outHeight, strength = 1.05f)
            System.arraycopy(bufB, 0, luma, 0, n)

            // Stage 3: Anime4K Darken HQ
            currentCoroutineContext().ensureActive()
            applyLineDarken(luma, bufA, bufB, outWidth, outHeight, strength = 1.25f)

            // Stage 4: Sobel edge-directed thinning + clean paper/ink levels + writeback
            currentCoroutineContext().ensureActive()
            applyEdgeRefineAndWriteback(luma, lumaOrig, pixels, outWidth, outHeight, grey, refineStrength = 0.28f)
            return Bitmap.createBitmap(pixels, outWidth, outHeight, Bitmap.Config.ARGB_8888).apply { setHasMipMap(true) }
        } finally {
            if (work !== source) work.recycle()
        }
    }

    /** Lightweight Line Darken + paper/ink levels finish for the neural ONNX output. */
    private fun applyDarkenAndLevels(pixels: IntArray, w: Int, h: Int, grey: Boolean, darkenStrength: Float) {
        val n = w * h
        val lumaOrig = FloatArray(n)
        val luma = FloatArray(n)
        val bufA = FloatArray(n)
        val bufB = FloatArray(n)
        val inv = 1f / 255f
        for (i in 0 until n) {
            val p = pixels[i]
            val l = (0.299f * (p ushr 16 and 255) + 0.587f * (p ushr 8 and 255) + 0.114f * (p and 255)) * inv
            lumaOrig[i] = l
            luma[i] = l
        }
        applyLineDarken(luma, bufA, bufB, w, h, strength = darkenStrength)
        writePixelsWithLevels(luma, lumaOrig, pixels, w, h, grey, black = 0.035f, white = 0.960f)
    }

    private fun bilateralDenoise3x3(src: FloatArray, dst: FloatArray, w: Int, h: Int, sigmaRange: Float) {
        val invSigma2 = 1f / (sigmaRange * sigmaRange)
        for (y in 0 until h) {
            val ym = max(0, y - 1); val yp = min(h - 1, y + 1)
            val rowOff = y * w; val rowM = ym * w; val rowP = yp * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                val c = src[rowOff + x]
                var sum = c; var wSum = 1f
                var s: Float; var d: Float; var wt: Float
                s = src[rowM + xm]; d = s - c; wt = 0.45f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowM + x];  d = s - c; wt = 0.70f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowM + xp]; d = s - c; wt = 0.45f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowOff + xm]; d = s - c; wt = 0.70f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowOff + xp]; d = s - c; wt = 0.70f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowP + xm]; d = s - c; wt = 0.45f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowP + x];  d = s - c; wt = 0.70f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                s = src[rowP + xp]; d = s - c; wt = 0.45f / (1f + d * d * invSigma2); sum += s * wt; wSum += wt
                dst[rowOff + x] = sum / wSum
            }
        }
    }

    /** Separable 5-tap Anime4K Gaussian blur (0.06136, 0.24477, 0.38774, 0.24477, 0.06136). */
    private fun gaussian5x5(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int) {
        val w0 = 0.38774f; val w1 = 0.24477f; val w2 = 0.06136f
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val xm2 = max(0, x - 2); val xm1 = max(0, x - 1)
                val xp1 = min(w - 1, x + 1); val xp2 = min(w - 1, x + 2)
                tmp[row + x] = (src[row + xm2] + src[row + xp2]) * w2 + (src[row + xm1] + src[row + xp1]) * w1 + src[row + x] * w0
            }
        }
        for (y in 0 until h) {
            val ym2 = max(0, y - 2) * w; val ym1 = max(0, y - 1) * w
            val y0 = y * w
            val yp1 = min(h - 1, y + 1) * w; val yp2 = min(h - 1, y + 2) * w
            for (x in 0 until w) {
                dst[y0 + x] = (tmp[ym2 + x] + tmp[yp2 + x]) * w2 + (tmp[ym1 + x] + tmp[yp1 + x]) * w1 + tmp[y0 + x] * w0
            }
        }
    }

    /** Separable 3-tap blur (0.25, 0.5, 0.25) used to smooth the negative line map in Anime4K_Darken_HQ. */
    private fun gaussian3x3(src: FloatArray, dst: FloatArray, tmp: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                tmp[row + x] = 0.25f * src[row + xm] + 0.5f * src[row + x] + 0.25f * src[row + xp]
            }
        }
        for (y in 0 until h) {
            val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                dst[y0 + x] = 0.25f * tmp[ym + x] + 0.5f * tmp[y0 + x] + 0.25f * tmp[yp + x]
            }
        }
    }

    private fun applyDoGDeblur(src: FloatArray, gauss: FloatArray, dst: FloatArray, w: Int, h: Int, strength: Float) {
        val noiseT = 0.008f
        val blurT = 0.14f
        val tRange = blurT - noiseT
        for (y in 0 until h) {
            val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                val idx = y0 + x
                val mid = src[idx]
                val c = (mid - gauss[idx]) * strength
                val ac = abs(c)
                val boosted = if (ac > noiseT) {
                    if (ac < blurT) {
                        val t = ((ac - noiseT) / tRange).coerceIn(0f, 1f)
                        (t.pow(0.55f) * tRange + noiseT) * (if (c >= 0f) 1f else -1f)
                    } else c
                } else 0f

                val mn = min(
                    min(min(src[ym + xm], src[ym + x]), min(src[ym + xp], src[y0 + xm])),
                    min(min(mid, src[y0 + xp]), min(src[yp + xm], min(src[yp + x], src[yp + xp]))),
                )
                val mx = max(
                    max(max(src[ym + xm], src[ym + x]), max(src[ym + xp], src[y0 + xm])),
                    max(max(mid, src[y0 + xp]), max(src[yp + xm], max(src[yp + x], src[yp + xp]))),
                )
                dst[idx] = (mid + boosted).coerceIn(mn, mx)
            }
        }
    }

    private fun applyLineDarken(luma: FloatArray, bufA: FloatArray, bufB: FloatArray, w: Int, h: Int, strength: Float) {
        gaussian5x5(luma, bufA, bufB, w, h)
        val n = w * h
        for (i in 0 until n) bufB[i] = min(luma[i] - bufA[i], 0f)
        gaussian3x3(bufB, bufA, bufB, w, h)
        for (i in 0 until n) {
            val neg = -bufA[i]
            val gate = smoothstep(0.015f, 0.06f, neg)
            luma[i] = (luma[i] - neg * strength * gate).coerceIn(0f, 1f)
        }
    }

    private fun applyEdgeRefineAndWriteback(
        luma: FloatArray, lumaOrig: FloatArray, pixels: IntArray, w: Int, h: Int, grey: Boolean, refineStrength: Float,
    ) {
        val black = 0.045f; val white = 0.950f
        val span = white - black
        for (y in 0 until h) {
            val ym = max(0, y - 1) * w; val y0 = y * w; val yp = min(h - 1, y + 1) * w
            for (x in 0 until w) {
                val xm = max(0, x - 1); val xp = min(w - 1, x + 1)
                val idx = y0 + x
                val a0 = luma[ym + xm]; val a1 = luma[ym + x]; val a2 = luma[ym + xp]
                val a3 = luma[y0 + xm]; val mid = luma[idx]; val a5 = luma[y0 + xp]
                val a6 = luma[yp + xm]; val a7 = luma[yp + x]; val a8 = luma[yp + xp]

                val mn = min(mid, min(min(min(a0, a1), min(a2, a3)), min(min(a5, a6), min(a7, a8))))
                val mx = max(mid, max(max(max(a0, a1), max(a2, a3)), max(max(a5, a6), max(a7, a8))))
                val gx = (a2 + 2f * a5 + a8) - (a0 + 2f * a3 + a6)
                val gy = (a6 + 2f * a7 + a8) - (a0 + 2f * a1 + a2)
                val grad = sqrt(gx * gx + gy * gy) * 0.25f
                val gate = smoothstep(0.14f, 0.52f, grad)
                val nearest = if (mid - mn < mx - mid) mn else mx
                val refined = mid + (nearest - mid) * refineStrength * gate
                val newL = ((refined - black) / span).coerceIn(0f, 1f)
                pixels[idx] = reconstructPixel(pixels[idx], lumaOrig[idx], newL, grey)
            }
        }
    }

    private fun writePixelsWithLevels(
        luma: FloatArray, lumaOrig: FloatArray, pixels: IntArray, w: Int, h: Int, grey: Boolean, black: Float, white: Float,
    ) {
        val span = white - black
        val n = w * h
        for (i in 0 until n) {
            val newL = ((luma[i] - black) / span).coerceIn(0f, 1f)
            pixels[i] = reconstructPixel(pixels[i], lumaOrig[i], newL, grey)
        }
    }

    private fun reconstructPixel(argb: Int, oldL: Float, newL: Float, grey: Boolean): Int {
        if (grey) {
            val v = channel(newL)
            return 0xff000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val r0 = (argb ushr 16 and 255) / 255f
        val g0 = (argb ushr 8 and 255) / 255f
        val b0 = (argb and 255) / 255f
        val delta = newL - oldL
        val r: Float; val g: Float; val b: Float
        if (delta < 0f && oldL > 0.01f) {
            val ratio = newL / oldL
            r = r0 * ratio; g = g0 * ratio; b = b0 * ratio
        } else {
            r = r0 + delta; g = g0 + delta; b = b0 + delta
        }
        return 0xff000000.toInt() or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun tensor(buffer: FloatBuffer, width: Int, height: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, height.toLong(), width.toLong()))

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
