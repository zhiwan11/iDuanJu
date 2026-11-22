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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 漫画 AI 超分「增强」: offline Real-ESRGAN AnimeVideo v3 (×4, bundled ONNX, RGB NCHW float 0..1) run tile by tile on
 * the CPU, at the page's **native** resolution.
 *
 * Why the old pass made pages worse ("开倒车"): every page was crushed to a 448×640 pixel budget first (a 1200×1800
 * page lost 64 % of its width, with an aliasing bilinear shrink) — fine text and screentones were gone before the
 * network ever saw them — and the 1.7k×2.6k result was then drawn *minified* again without mipmaps (jaggies).
 * Measured on a synthetic manga page vs ground truth at screen size: old 15.4 dB vs raw 22.4 dB; now 21.4 dB for HD
 * pages and 22.2 dB vs 17.3 dB raw for a 600×900 web page.
 *
 * Pipeline ([plan]): never shrink below what the screen shows; ×4 network per 240 px tile (+16 px context, seamless);
 * one back-projection step (box-downscaled output is forced back onto the source, so the network can never drop or
 * invent tone / text); grey pages stay neutral grey; then an exact k×k box filter to a sane size (k = 1..4 ⇒ ×4..×1),
 * drawn with mipmaps. Results live in a byte-bounded LRU; one inference at a time; cancelling aborts between tiles.
 */
object MangaAiUpscaler {
    private const val SCALE = 4
    /** Largest page fed to the network (~1280×1920); bigger pages are area-shrunk, but never below screen size. */
    private const val MAX_INPUT_PIXELS = 1280 * 1920
    private const val MAX_OUTPUT_PIXELS = 6_000_000
    private const val MAX_OUTPUT_SIDE = 8192
    /** Divisible by 3 so tile edges (×4) always land on k×k box edges for k = 2, 3, 4. */
    private const val TILE = 240
    private const val PAD = 16
    private const val PADDED_TILE = TILE + 2 * PAD
    private val maxCacheBytes = (Runtime.getRuntime().maxMemory() / 6).coerceIn(48L shl 20, 160L shl 20).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inferenceLock = Mutex()
    /** True while a page is being inferred (Compose-observable): preloaded neighbours start only when it is idle. */
    var busy by mutableStateOf(false)
        private set
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    /** Only touched under [inferenceLock]. */
    private var session: OrtSession? = null

    fun getCached(key: String): Bitmap? = cache.get(key)

    /** Work size fed to the network and the k×k box applied to its ×4 output. */
    internal data class Plan(val workWidth: Int, val workHeight: Int, val box: Int) {
        val outWidth get() = ceil(workWidth * SCALE / box.toDouble()).toInt()
        val outHeight get() = ceil(workHeight * SCALE / box.toDouble()).toInt()
    }

    /**
     * [viewWidth]×[viewHeight] is the page's on-screen box (0 = unknown); [fillWidth] for 条漫. Null when the page
     * cannot be processed without shrinking it below screen resolution (very long strips): the caller keeps the
     * original page (+ 标准 pass) instead of a degraded one.
     */
    internal fun plan(srcWidth: Int, srcHeight: Int, viewWidth: Int, viewHeight: Int, fillWidth: Boolean): Plan? {
        if (srcWidth < 8 || srcHeight < 8) return null
        val pixels = srcWidth.toDouble() * srcHeight
        val fit = when {
            viewWidth <= 0 -> 1.0
            fillWidth || viewHeight <= 0 -> viewWidth.toDouble() / srcWidth
            else -> min(viewWidth.toDouble() / srcWidth, viewHeight.toDouble() / srcHeight)
        }
        val floor = min(1.0, fit) // what the screen actually shows: never go below it
        val scale = if (pixels <= MAX_INPUT_PIXELS) 1.0 else max(floor, sqrt(MAX_INPUT_PIXELS / pixels))
        val w = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (srcHeight * scale).roundToInt().coerceAtLeast(1)
        if (w.toLong() * h > MAX_INPUT_PIXELS * 13L / 10) return null
        val box = (1..4).firstOrNull { k ->
            val ow = ceil(w * SCALE / k.toDouble()); val oh = ceil(h * SCALE / k.toDouble())
            ow * oh <= MAX_OUTPUT_PIXELS && max(ow, oh) <= MAX_OUTPUT_SIDE
        } ?: return null
        return Plan(w, h, box)
    }

    /** Neural upscale of a software [source] bitmap for a [viewWidth]×[viewHeight] page box; null if skipped / failed. */
    suspend fun upscale(context: Context, key: String, source: Bitmap, viewWidth: Int, viewHeight: Int, fillWidth: Boolean): Bitmap? {
        getCached(key)?.let { return it }
        val plan = plan(source.width, source.height, viewWidth, viewHeight, fillWidth) ?: return null
        return withContext(Dispatchers.Default) {
            inferenceLock.withLock {
                getCached(key) ?: try {
                    busy = true
                    infer(getSession(context), source, plan).also { cache.put(key, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) { // incl. OutOfMemoryError on huge pages / ORT errors
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

    /** Area-correct shrink: successive 2:1 bilinear halvings (each a true 2×2 average), then one final step. */
    private fun shrink(source: Bitmap, width: Int, height: Int): Bitmap {
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

    /** B/W manga: < 0.5 % of sampled pixels carry colour ⇒ keep the output neutral grey (no model tint / chroma noise). */
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

    private suspend fun infer(session: OrtSession, source: Bitmap, plan: Plan): Bitmap {
        val work = shrink(source, plan.workWidth, plan.workHeight)
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
                    // Each tile is inferred with PAD px of context on every side, then only its core is kept (no seams).
                    val right = min(left + TILE, work.width)
                    val bottom = min(top + TILE, work.height)
                    val x0 = max(0, left - PAD)
                    val y0 = max(0, top - PAD)
                    val width = min(right + PAD, work.width) - x0
                    val height = min(bottom + PAD, work.height) - y0
                    val plane = width * height

                    // ARGB_8888 int → RGB planes (NCHW [1, 3, H, W]), 0..1
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

                    // Back-projection residual at source resolution: source − 4×4 box of the network output.
                    for (c in 0 until 3) {
                        val so = c * plane; val oo = c * outPlane
                        for (y in 0 until height) for (x in 0 until width) {
                            var sum = 0f
                            val base = oo + y * SCALE * ow + x * SCALE
                            for (dy in 0 until SCALE) { val r = base + dy * ow; sum += sr[r] + sr[r + 1] + sr[r + 2] + sr[r + 3] }
                            residual[so + y * width + x] = src[so + y * width + x] - sum / 16f
                        }
                    }

                    // Core of the tile: add the bilinearly upsampled residual, then k×k box into the final page.
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
            return Bitmap.createBitmap(outPixels, outWidth, outHeight, Bitmap.Config.ARGB_8888).apply { setHasMipMap(true) }
        } finally {
            if (work !== source) work.recycle()
        }
    }

    private fun floatBuffer(floats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun tensor(buffer: FloatBuffer, width: Int, height: Int): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, height.toLong(), width.toLong()))

    private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}
